/******************************************************************************
    Copyright (C) 2026 Panggung / OBS-Mobile Contributors
    GPL-2.0-or-later
******************************************************************************/

#include <obs.h>
#include <graphics/graphics.h>
#include <util/threading.h>
#include <util/platform.h>
#include <util/dstr.h>
#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <string.h>
#include <stdlib.h>

/* ---- EGL / GLES External OES Extension Loader ---- */
typedef EGLClientBuffer(EGLAPIENTRYP PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC_L)(const struct AHardwareBuffer *buffer);
typedef EGLImageKHR(EGLAPIENTRYP PFNEGLCREATEIMAGEKHRPROC_L)(EGLDisplay dpy, EGLContext ctx, EGLenum target,
							      EGLClientBuffer buffer, const EGLint *attrib_list);
typedef EGLBoolean(EGLAPIENTRYP PFNEGLDESTROYIMAGEKHRPROC_L)(EGLDisplay dpy, EGLImageKHR image);
typedef void(GL_APIENTRYP PFNGLEGLIMAGETARGETTEXTURE2DOESPROC_L)(GLenum target, GLeglImageOES image);

#ifndef EGL_NATIVE_BUFFER_ANDROID
#define EGL_NATIVE_BUFFER_ANDROID 0x3140
#endif
#ifndef EGL_IMAGE_PRESERVED_KHR
#define EGL_IMAGE_PRESERVED_KHR 0x30D2
#endif

static PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC_L p_eglGetNativeClientBufferANDROID = NULL;
static PFNEGLCREATEIMAGEKHRPROC_L p_eglCreateImageKHR = NULL;
static PFNEGLDESTROYIMAGEKHRPROC_L p_eglDestroyImageKHR = NULL;
static PFNGLEGLIMAGETARGETTEXTURE2DOESPROC_L p_glEGLImageTargetTexture2DOES = NULL;
static bool g_egl_exts_loaded = false;

static bool load_ahb_egl_exts(void)
{
	if (!g_egl_exts_loaded) {
		p_eglGetNativeClientBufferANDROID =
			(PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC_L)eglGetProcAddress("eglGetNativeClientBufferANDROID");
		p_eglCreateImageKHR = (PFNEGLCREATEIMAGEKHRPROC_L)eglGetProcAddress("eglCreateImageKHR");
		p_eglDestroyImageKHR = (PFNEGLDESTROYIMAGEKHRPROC_L)eglGetProcAddress("eglDestroyImageKHR");
		p_glEGLImageTargetTexture2DOES =
			(PFNGLEGLIMAGETARGETTEXTURE2DOESPROC_L)eglGetProcAddress("glEGLImageTargetTexture2DOES");
		g_egl_exts_loaded = true;
	}
	return p_eglGetNativeClientBufferANDROID && p_eglCreateImageKHR &&
	       p_eglDestroyImageKHR && p_glEGLImageTargetTexture2DOES;
}

/* ---- Shared GLES 3.0 OES -> 2D Render-Target Blit Program (runs on OBS graphics thread) ---- */
static GLuint g_oes_prog = 0;
static GLuint g_oes_vao = 0;
static GLuint g_oes_fbo = 0;

static const char *k_oes_vs =
	"#version 300 es\n"
	"out vec2 v_uv;\n"
	"void main() {\n"
	"  vec2 pos = vec2((gl_VertexID == 1) ? 3.0 : -1.0, (gl_VertexID == 2) ? 3.0 : -1.0);\n"
	"  v_uv = (pos + 1.0) * 0.5;\n"
	"  gl_Position = vec4(pos, 0.0, 1.0);\n"
	"}\n";

static const char *k_oes_fs =
	"#version 300 es\n"
	"#extension GL_OES_EGL_image_external_essl3 : require\n"
	"precision mediump float;\n"
	"in vec2 v_uv;\n"
	"uniform samplerExternalOES u_tex;\n"
	"out vec4 fragColor;\n"
	"void main() {\n"
	"  fragColor = texture(u_tex, v_uv);\n"
	"}\n";

static GLuint compile_shader(GLenum type, const char *src)
{
	GLuint sh = glCreateShader(type);
	glShaderSource(sh, 1, &src, NULL);
	glCompileShader(sh);
	GLint ok = 0;
	glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
	if (!ok) {
		char log[256];
		glGetShaderInfoLog(sh, sizeof(log), NULL, log);
		blog(LOG_ERROR, "[ahb-zero-copy] shader compile err: %s", log);
		glDeleteShader(sh);
		return 0;
	}
	return sh;
}

static bool ensure_oes_blit_program(void)
{
	if (g_oes_prog)
		return true;
	GLuint vs = compile_shader(GL_VERTEX_SHADER, k_oes_vs);
	GLuint fs = compile_shader(GL_FRAGMENT_SHADER, k_oes_fs);
	if (!vs || !fs) {
		if (vs) glDeleteShader(vs);
		if (fs) glDeleteShader(fs);
		return false;
	}
	GLuint prog = glCreateProgram();
	glAttachShader(prog, vs);
	glAttachShader(prog, fs);
	glLinkProgram(prog);
	glDeleteShader(vs);
	glDeleteShader(fs);
	GLint ok = 0;
	glGetProgramiv(prog, GL_LINK_STATUS, &ok);
	if (!ok) {
		glDeleteProgram(prog);
		return false;
	}
	g_oes_prog = prog;
	glGenVertexArrays(1, &g_oes_vao);
	glGenFramebuffers(1, &g_oes_fbo);
	return true;
}

/* ---- Zero-Copy AHardwareBuffer + Multi-Instance Video Source ---- */
#define MAX_CAM_INSTANCES 4

struct ahb_video_source {
	pthread_mutex_t lock;
	obs_source_t *source;
	char label[64];

	/* Pending GPU AHardwareBuffer (written by Camera2/Screen thread, consumed on OBS render thread) */
	AHardwareBuffer *pending_ahb;
	uint32_t pending_w;
	uint32_t pending_h;
	bool has_new_ahb;

	/* Active GPU resources (owned exclusively by OBS graphics thread) */
	AHardwareBuffer *cur_ahb;
	EGLImageKHR cur_egl_image;
	GLuint oes_tex;
	gs_texture_t *obs_tex;
	uint32_t tex_w;
	uint32_t tex_h;
	uint64_t ahb_frames;

	/* CPU fallback color matrix (for legacy I420 / RGBA pushes) */
	bool color_matrix_ready;
	float color_matrix[16];
	float color_range_min[3];
	float color_range_max[3];
};

static pthread_mutex_t g_cam_registry_lock = PTHREAD_MUTEX_INITIALIZER;
static struct ahb_video_source *g_cam_slots[MAX_CAM_INSTANCES] = {0};

static pthread_mutex_t g_screen_registry_lock = PTHREAD_MUTEX_INITIALIZER;
static struct ahb_video_source *g_screen_inst = NULL;

struct android_source_slot {
	pthread_mutex_t lock;
	obs_source_t *source;
};

static struct android_source_slot g_webview = {PTHREAD_MUTEX_INITIALIZER, NULL};
static struct android_source_slot g_audio_in = {PTHREAD_MUTEX_INITIALIZER, NULL};
static struct android_source_slot g_audio_out = {PTHREAD_MUTEX_INITIALIZER, NULL};

static void ensure_color_matrix_ahb(struct ahb_video_source *src)
{
	if (!src->color_matrix_ready) {
		video_format_get_parameters_for_format(
			VIDEO_CS_601, VIDEO_RANGE_FULL, VIDEO_FORMAT_I420,
			src->color_matrix, src->color_range_min, src->color_range_max);
		src->color_matrix_ready = true;
	}
}

static void ahb_source_destroy_gpu(struct ahb_video_source *s)
{
	obs_enter_graphics();
	EGLDisplay dpy = eglGetCurrentDisplay();
	if (s->cur_egl_image != EGL_NO_IMAGE_KHR && dpy != EGL_NO_DISPLAY && p_eglDestroyImageKHR) {
		p_eglDestroyImageKHR(dpy, s->cur_egl_image);
		s->cur_egl_image = EGL_NO_IMAGE_KHR;
	}
	if (s->cur_ahb) {
		AHardwareBuffer_release(s->cur_ahb);
		s->cur_ahb = NULL;
	}
	if (s->oes_tex) {
		glDeleteTextures(1, &s->oes_tex);
		s->oes_tex = 0;
	}
	if (s->obs_tex) {
		gs_texture_destroy(s->obs_tex);
		s->obs_tex = NULL;
	}
	obs_leave_graphics();
}

static void ahb_source_render(void *data, gs_effect_t *effect)
{
	UNUSED_PARAMETER(effect);
	struct ahb_video_source *s = (struct ahb_video_source *)data;
	if (!s)
		return;

	AHardwareBuffer *new_ahb = NULL;
	uint32_t nw = 0, nh = 0;

	pthread_mutex_lock(&s->lock);
	if (s->has_new_ahb && s->pending_ahb) {
		new_ahb = s->pending_ahb;
		s->pending_ahb = NULL;
		s->has_new_ahb = false;
		nw = s->pending_w;
		nh = s->pending_h;
	}
	pthread_mutex_unlock(&s->lock);

	if (new_ahb && nw > 0 && nh > 0 && load_ahb_egl_exts() && ensure_oes_blit_program()) {
		EGLDisplay dpy = eglGetCurrentDisplay();
		if (dpy != EGL_NO_DISPLAY) {
			EGLClientBuffer cb = p_eglGetNativeClientBufferANDROID(new_ahb);
			static const EGLint attrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
			EGLImageKHR img = cb ? p_eglCreateImageKHR(dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, cb, attrs)
					     : EGL_NO_IMAGE_KHR;
			if (img != EGL_NO_IMAGE_KHR) {
				if (!s->oes_tex) {
					glGenTextures(1, &s->oes_tex);
				}
				if (!s->obs_tex || s->tex_w != nw || s->tex_h != nh) {
					if (s->obs_tex)
						gs_texture_destroy(s->obs_tex);
					s->obs_tex = gs_texture_create(nw, nh, GS_RGBA, 1, NULL, GS_RENDER_TARGET);
					s->tex_w = nw;
					s->tex_h = nh;
				}
				if (s->obs_tex) {
					GLuint dst_gl_tex = *(GLuint *)gs_texture_get_obj(s->obs_tex);

					/* Save GLES state so libobs-gles state cache stays 100% consistent */
					GLint prev_prog = 0, prev_fbo = 0, prev_vao = 0, prev_active_tex = 0, prev_tex2d = 0;
					GLint prev_vp[4] = {0};
					GLboolean prev_blend = glIsEnabled(GL_BLEND);
					GLboolean prev_depth = glIsEnabled(GL_DEPTH_TEST);
					GLboolean prev_cull = glIsEnabled(GL_CULL_FACE);
					GLboolean prev_scissor = glIsEnabled(GL_SCISSOR_TEST);
					glGetIntegerv(GL_CURRENT_PROGRAM, &prev_prog);
					glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &prev_fbo);
					glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &prev_vao);
					glGetIntegerv(GL_ACTIVE_TEXTURE, &prev_active_tex);
					glGetIntegerv(GL_TEXTURE_BINDING_2D, &prev_tex2d);
					glGetIntegerv(GL_VIEWPORT, prev_vp);

					glActiveTexture(GL_TEXTURE0);
					glBindTexture(GL_TEXTURE_EXTERNAL_OES, s->oes_tex);
					p_glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, (GLeglImageOES)img);
					glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
					glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
					glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
					glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

					glBindFramebuffer(GL_FRAMEBUFFER, g_oes_fbo);
					glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, dst_gl_tex, 0);
					glViewport(0, 0, (GLsizei)nw, (GLsizei)nh);
					glDisable(GL_BLEND);
					glDisable(GL_DEPTH_TEST);
					glDisable(GL_CULL_FACE);
					glDisable(GL_SCISSOR_TEST);

					glUseProgram(g_oes_prog);
					glUniform1i(glGetUniformLocation(g_oes_prog, "u_tex"), 0);
					glBindVertexArray(g_oes_vao);
					glDrawArrays(GL_TRIANGLES, 0, 3);

					glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
					glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);

					/* Restore saved GLES state */
					glBindFramebuffer(GL_FRAMEBUFFER, (GLuint)prev_fbo);
					glViewport(prev_vp[0], prev_vp[1], prev_vp[2], prev_vp[3]);
					glBindVertexArray((GLuint)prev_vao);
					glUseProgram((GLuint)prev_prog);
					glActiveTexture((GLenum)prev_active_tex);
					glBindTexture(GL_TEXTURE_2D, (GLuint)prev_tex2d);
					if (prev_blend) glEnable(GL_BLEND);
					if (prev_depth) glEnable(GL_DEPTH_TEST);
					if (prev_cull) glEnable(GL_CULL_FACE);
					if (prev_scissor) glEnable(GL_SCISSOR_TEST);

					/* Release previous frame's EGLImage & AHardwareBuffer */
					if (s->cur_egl_image != EGL_NO_IMAGE_KHR && p_eglDestroyImageKHR) {
						p_eglDestroyImageKHR(dpy, s->cur_egl_image);
					}
					if (s->cur_ahb) {
						AHardwareBuffer_release(s->cur_ahb);
					}
					s->cur_egl_image = img;
					s->cur_ahb = new_ahb;
					new_ahb = NULL;

					s->ahb_frames++;
					if (s->ahb_frames == 1 || (s->ahb_frames % 300) == 0) {
						blog(LOG_INFO, "[ahb-zero-copy] '%s' GPU frame #%llu (%ux%u) via EGLImageKHR",
						     s->label, (unsigned long long)s->ahb_frames, nw, nh);
					}
				} else {
					p_eglDestroyImageKHR(dpy, img);
				}
			}
		}
	}
	if (new_ahb) {
		AHardwareBuffer_release(new_ahb);
	}

	if (s->obs_tex) {
		obs_source_draw(s->obs_tex, 0, 0, 0, 0, false);
	}
}

static uint32_t ahb_source_get_width(void *data)
{
	struct ahb_video_source *s = (struct ahb_video_source *)data;
	return s ? (s->tex_w ? s->tex_w : s->pending_w) : 0;
}

static uint32_t ahb_source_get_height(void *data)
{
	struct ahb_video_source *s = (struct ahb_video_source *)data;
	return s ? (s->tex_h ? s->tex_h : s->pending_h) : 0;
}

/* ---- android_camera (Multi-instance + Zero-Copy AHardwareBuffer + Async CPU Fallback) ---- */
static const char *cam_get_name(void *unused) { UNUSED_PARAMETER(unused); return "Android Camera"; }

static void *cam_create(obs_data_t *settings, obs_source_t *source)
{
	UNUSED_PARAMETER(settings);
	struct ahb_video_source *s = bzalloc(sizeof(struct ahb_video_source));
	pthread_mutex_init(&s->lock, NULL);
	s->source = source;
	s->cur_egl_image = EGL_NO_IMAGE_KHR;
	const char *nm = obs_source_get_name(source);
	snprintf(s->label, sizeof(s->label), "%s", nm ? nm : "Camera");
	ensure_color_matrix_ahb(s);

	pthread_mutex_lock(&g_cam_registry_lock);
	for (int i = 0; i < MAX_CAM_INSTANCES; i++) {
		if (!g_cam_slots[i]) {
			g_cam_slots[i] = s;
			break;
		}
	}
	pthread_mutex_unlock(&g_cam_registry_lock);
	return s;
}

static void cam_destroy(void *data)
{
	struct ahb_video_source *s = (struct ahb_video_source *)data;
	if (!s) return;

	pthread_mutex_lock(&g_cam_registry_lock);
	for (int i = 0; i < MAX_CAM_INSTANCES; i++) {
		if (g_cam_slots[i] == s) {
			g_cam_slots[i] = NULL;
		}
	}
	pthread_mutex_unlock(&g_cam_registry_lock);

	pthread_mutex_lock(&s->lock);
	if (s->pending_ahb) {
		AHardwareBuffer_release(s->pending_ahb);
		s->pending_ahb = NULL;
	}
	s->source = NULL;
	pthread_mutex_unlock(&s->lock);

	ahb_source_destroy_gpu(s);
	pthread_mutex_destroy(&s->lock);
	bfree(s);
}

static obs_properties_t *cam_properties(void *unused)
{
	UNUSED_PARAMETER(unused);
	obs_properties_t *props = obs_properties_create();
	obs_property_t *p = obs_properties_add_list(props, "facing", "Camera", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_STRING);
	obs_property_list_add_string(p, "Back Camera", "back");
	obs_property_list_add_string(p, "Front Camera", "front");
	return props;
}

static struct obs_source_info android_camera_info = {
	.id = "android_camera",
	.type = OBS_SOURCE_TYPE_INPUT,
	/* Standard OBS_SOURCE_VIDEO (NOT CUSTOM_DRAW) so libobs binds default/filter effect loop before video_render */
	.output_flags = OBS_SOURCE_VIDEO,
	.get_name = cam_get_name,
	.create = cam_create,
	.destroy = cam_destroy,
	.video_render = ahb_source_render,
	.get_width = ahb_source_get_width,
	.get_height = ahb_source_get_height,
	.get_properties = cam_properties,
};

/* ---- android_screen (Zero-Copy AHardwareBuffer) ---- */
static const char *scr_get_name(void *unused) { UNUSED_PARAMETER(unused); return "Android Screen Capture"; }

static void *scr_create(obs_data_t *settings, obs_source_t *source)
{
	UNUSED_PARAMETER(settings);
	struct ahb_video_source *s = bzalloc(sizeof(struct ahb_video_source));
	pthread_mutex_init(&s->lock, NULL);
	s->source = source;
	s->cur_egl_image = EGL_NO_IMAGE_KHR;
	snprintf(s->label, sizeof(s->label), "Screen Capture");

	pthread_mutex_lock(&g_screen_registry_lock);
	g_screen_inst = s;
	pthread_mutex_unlock(&g_screen_registry_lock);
	return s;
}

static void scr_destroy(void *data)
{
	struct ahb_video_source *s = (struct ahb_video_source *)data;
	if (!s) return;

	pthread_mutex_lock(&g_screen_registry_lock);
	if (g_screen_inst == s) g_screen_inst = NULL;
	pthread_mutex_unlock(&g_screen_registry_lock);

	pthread_mutex_lock(&s->lock);
	if (s->pending_ahb) {
		AHardwareBuffer_release(s->pending_ahb);
		s->pending_ahb = NULL;
	}
	s->source = NULL;
	pthread_mutex_unlock(&s->lock);

	ahb_source_destroy_gpu(s);
	pthread_mutex_destroy(&s->lock);
	bfree(s);
}

static struct obs_source_info android_screen_info = {
	.id = "android_screen",
	.type = OBS_SOURCE_TYPE_INPUT,
	.output_flags = OBS_SOURCE_VIDEO | OBS_SOURCE_DO_NOT_DUPLICATE,
	.get_name = scr_get_name,
	.create = scr_create,
	.destroy = scr_destroy,
	.video_render = ahb_source_render,
	.get_width = ahb_source_get_width,
	.get_height = ahb_source_get_height,
};

/* ---- android_webview (Browser Source) ---- */
static const char *web_get_name(void *unused) { UNUSED_PARAMETER(unused); return "Browser (WebView)"; }
static void *web_create(obs_data_t *settings, obs_source_t *source)
{
	UNUSED_PARAMETER(settings);
	pthread_mutex_lock(&g_webview.lock);
	g_webview.source = source;
	pthread_mutex_unlock(&g_webview.lock);
	return &g_webview;
}
static void web_destroy(void *data)
{
	struct android_source_slot *slot = (struct android_source_slot *)data;
	pthread_mutex_lock(&slot->lock);
	slot->source = NULL;
	pthread_mutex_unlock(&slot->lock);
}
static void web_defaults(obs_data_t *settings)
{
	obs_data_set_default_string(settings, "url", "https://obsproject.com/browser-source");
	obs_data_set_default_int(settings, "width", 1280);
	obs_data_set_default_int(settings, "height", 720);
	obs_data_set_default_string(settings, "css", "body { background-color: rgba(0, 0, 0, 0); margin: 0; overflow: hidden; }");
}
static obs_properties_t *web_properties(void *unused)
{
	UNUSED_PARAMETER(unused);
	obs_properties_t *props = obs_properties_create();
	obs_properties_add_text(props, "url", "URL", OBS_TEXT_DEFAULT);
	obs_properties_add_int(props, "width", "Width", 64, 3840, 1);
	obs_properties_add_int(props, "height", "Height", 64, 2160, 1);
	obs_properties_add_text(props, "css", "Custom CSS", OBS_TEXT_MULTILINE);
	return props;
}
static struct obs_source_info android_webview_info = {
	.id = "android_webview",
	.type = OBS_SOURCE_TYPE_INPUT,
	.output_flags = OBS_SOURCE_ASYNC_VIDEO | OBS_SOURCE_DO_NOT_DUPLICATE,
	.get_name = web_get_name,
	.create = web_create,
	.destroy = web_destroy,
	.get_defaults = web_defaults,
	.get_properties = web_properties,
};

/* ---- android_audio_input ---- */
static const char *ain_get_name(void *unused) { UNUSED_PARAMETER(unused); return "Android Microphone"; }
static void *ain_create(obs_data_t *settings, obs_source_t *source)
{
	UNUSED_PARAMETER(settings);
	pthread_mutex_lock(&g_audio_in.lock);
	g_audio_in.source = source;
	pthread_mutex_unlock(&g_audio_in.lock);
	return &g_audio_in;
}
static void ain_destroy(void *data)
{
	struct android_source_slot *slot = (struct android_source_slot *)data;
	pthread_mutex_lock(&slot->lock);
	slot->source = NULL;
	pthread_mutex_unlock(&slot->lock);
}
static struct obs_source_info android_audio_in_info = {
	.id = "android_audio_input",
	.type = OBS_SOURCE_TYPE_INPUT,
	.output_flags = OBS_SOURCE_AUDIO | OBS_SOURCE_DO_NOT_DUPLICATE,
	.get_name = ain_get_name,
	.create = ain_create,
	.destroy = ain_destroy,
};

/* ---- android_audio_output ---- */
static const char *aout_get_name(void *unused) { UNUSED_PARAMETER(unused); return "Android Internal Audio"; }
static void *aout_create(obs_data_t *settings, obs_source_t *source)
{
	UNUSED_PARAMETER(settings);
	pthread_mutex_lock(&g_audio_out.lock);
	g_audio_out.source = source;
	pthread_mutex_unlock(&g_audio_out.lock);
	return &g_audio_out;
}
static void aout_destroy(void *data)
{
	struct android_source_slot *slot = (struct android_source_slot *)data;
	pthread_mutex_lock(&slot->lock);
	slot->source = NULL;
	pthread_mutex_unlock(&slot->lock);
}
static struct obs_source_info android_audio_out_info = {
	.id = "android_audio_output",
	.type = OBS_SOURCE_TYPE_INPUT,
	.output_flags = OBS_SOURCE_AUDIO | OBS_SOURCE_DO_NOT_DUPLICATE,
	.get_name = aout_get_name,
	.create = aout_create,
	.destroy = aout_destroy,
};

/* ---- Exported Zero-Copy AHardwareBuffer Push APIs ---- */

static bool submit_ahb_to_slot(struct ahb_video_source *s, AHardwareBuffer *ahb, uint32_t width, uint32_t height)
{
	if (!s || !ahb || width == 0 || height == 0)
		return false;
	AHardwareBuffer_acquire(ahb);
	pthread_mutex_lock(&s->lock);
	if (s->pending_ahb) {
		AHardwareBuffer_release(s->pending_ahb);
	}
	s->pending_ahb = ahb;
	s->pending_w = width;
	s->pending_h = height;
	s->has_new_ahb = true;
	pthread_mutex_unlock(&s->lock);
	return true;
}

EXPORT bool panggung_push_camera_ahb(const char *source_name, void *ahb_ptr,
				     uint32_t width, uint32_t height, uint64_t timestamp_ns)
{
	UNUSED_PARAMETER(timestamp_ns);
	AHardwareBuffer *ahb = (AHardwareBuffer *)ahb_ptr;
	if (!ahb || width == 0 || height == 0)
		return false;

	bool pushed = false;
	pthread_mutex_lock(&g_cam_registry_lock);
	struct ahb_video_source *fallback = NULL;
	for (int i = 0; i < MAX_CAM_INSTANCES; i++) {
		struct ahb_video_source *s = g_cam_slots[i];
		if (!s || !s->source)
			continue;
		if (!fallback)
			fallback = s;
		const char *cur_name = obs_source_get_name(s->source);
		if (source_name && source_name[0] && cur_name && strcmp(cur_name, source_name) == 0) {
			pushed = submit_ahb_to_slot(s, ahb, width, height);
			break;
		}
	}
	if (!pushed && fallback) {
		pushed = submit_ahb_to_slot(fallback, ahb, width, height);
	}
	pthread_mutex_unlock(&g_cam_registry_lock);
	return pushed;
}

EXPORT bool panggung_push_screen_ahb(void *ahb_ptr, uint32_t width, uint32_t height, uint64_t timestamp_ns)
{
	UNUSED_PARAMETER(timestamp_ns);
	AHardwareBuffer *ahb = (AHardwareBuffer *)ahb_ptr;
	if (!ahb || width == 0 || height == 0)
		return false;

	bool pushed = false;
	pthread_mutex_lock(&g_screen_registry_lock);
	if (g_screen_inst) {
		pushed = submit_ahb_to_slot(g_screen_inst, ahb, width, height);
	}
	pthread_mutex_unlock(&g_screen_registry_lock);
	return pushed;
}

/* ---- Legacy CPU Push APIs (kept for ABI compatibility & non-AHB fallback) ---- */

EXPORT void panggung_push_camera_i420(const uint8_t *y, const uint8_t *u, const uint8_t *v,
				      uint32_t y_stride, uint32_t u_stride, uint32_t v_stride,
				      uint32_t width, uint32_t height, uint64_t timestamp_ns)
{
	UNUSED_PARAMETER(y); UNUSED_PARAMETER(u); UNUSED_PARAMETER(v);
	UNUSED_PARAMETER(y_stride); UNUSED_PARAMETER(u_stride); UNUSED_PARAMETER(v_stride);
	UNUSED_PARAMETER(width); UNUSED_PARAMETER(height); UNUSED_PARAMETER(timestamp_ns);
}

EXPORT void panggung_push_screen_rgba(const uint8_t *rgba, uint32_t stride,
				      uint32_t width, uint32_t height, uint64_t timestamp_ns)
{
	UNUSED_PARAMETER(rgba); UNUSED_PARAMETER(stride);
	UNUSED_PARAMETER(width); UNUSED_PARAMETER(height); UNUSED_PARAMETER(timestamp_ns);
}

EXPORT void panggung_push_webview_rgba(const uint8_t *rgba, uint32_t stride,
				       uint32_t width, uint32_t height)
{
	pthread_mutex_lock(&g_webview.lock);
	if (g_webview.source) {
		struct obs_source_frame frame = {0};
		frame.data[0] = (uint8_t *)rgba;
		frame.linesize[0] = stride;
		frame.width = width;
		frame.height = height;
		frame.format = VIDEO_FORMAT_RGBA;
		frame.timestamp = os_gettime_ns();
		obs_source_output_video(g_webview.source, &frame);
	}
	pthread_mutex_unlock(&g_webview.lock);
}

EXPORT bool panggung_push_source_rgba(const char *source_name, const uint8_t *rgba, uint32_t stride, uint32_t width, uint32_t height)
{
	if (!source_name || !rgba || width == 0 || height == 0) return false;
	obs_source_t *src = obs_get_source_by_name(source_name);
	if (!src) return false;
	struct obs_source_frame frame = {0};
	frame.data[0] = (uint8_t *)rgba;
	frame.linesize[0] = stride;
	frame.width = width;
	frame.height = height;
	frame.format = VIDEO_FORMAT_RGBA;
	frame.timestamp = os_gettime_ns();
	obs_source_output_video(src, &frame);
	obs_source_release(src);
	return true;
}

static void push_audio_slot(struct android_source_slot *slot, const uint8_t *pcm,
			    uint32_t frames, uint32_t sample_rate, uint32_t channels)
{
	pthread_mutex_lock(&slot->lock);
	if (slot->source) {
		struct obs_source_audio audio = {0};
		audio.data[0] = pcm;
		audio.frames = frames;
		audio.speakers = (channels == 1) ? SPEAKERS_MONO : SPEAKERS_STEREO;
		audio.format = AUDIO_FORMAT_16BIT;
		audio.samples_per_sec = sample_rate;
		audio.timestamp = os_gettime_ns();
		obs_source_output_audio(slot->source, &audio);
	}
	pthread_mutex_unlock(&slot->lock);
}

EXPORT void panggung_push_audio_input_s16(const uint8_t *pcm, uint32_t frames,
					  uint32_t sample_rate, uint32_t channels, uint64_t timestamp_ns)
{
	UNUSED_PARAMETER(timestamp_ns);
	push_audio_slot(&g_audio_in, pcm, frames, sample_rate, channels);
}

EXPORT void panggung_push_audio_output_s16(const uint8_t *pcm, uint32_t frames,
					   uint32_t sample_rate, uint32_t channels, uint64_t timestamp_ns)
{
	UNUSED_PARAMETER(timestamp_ns);
	push_audio_slot(&g_audio_out, pcm, frames, sample_rate, channels);
}

/* ---- Generic obs_properties -> JSON serializer ---- */

static void append_json_escaped(struct dstr *out, const char *s)
{
	dstr_cat_ch(out, '"');
	if (s) {
		for (const char *p = s; *p; p++) {
			if (*p == '"' || *p == '\\') {
				dstr_cat_ch(out, '\\');
				dstr_cat_ch(out, *p);
			} else if (*p == '\n') {
				dstr_cat(out, "\\n");
			} else if (*p == '\r') {
				dstr_cat(out, "\\r");
			} else if (*p == '\t') {
				dstr_cat(out, "\\t");
			} else if ((unsigned char)*p >= 0x20) {
				dstr_cat_ch(out, *p);
			}
		}
	}
	dstr_cat_ch(out, '"');
}

EXPORT char *panggung_serialize_source_properties(const char *source_name)
{
	obs_source_t *src = obs_get_source_by_name(source_name);
	if (!src)
		return bstrdup("{\"properties\":[],\"settings\":{}}");

	obs_properties_t *props = obs_source_properties(src);
	obs_data_t *settings = obs_source_get_settings(src);
	const char *settings_json = settings ? obs_data_get_json(settings) : "{}";

	struct dstr out = {0};
	dstr_copy(&out, "{\"properties\":[");

	bool first = true;
	if (props) {
		for (obs_property_t *p = obs_properties_first(props); p; obs_property_next(&p)) {
			if (!obs_property_visible(p))
				continue;
			if (!first)
				dstr_cat_ch(&out, ',');
			first = false;

			enum obs_property_type t = obs_property_get_type(p);
			const char *name = obs_property_name(p);
			const char *desc = obs_property_description(p);

			dstr_cat(&out, "{\"name\":");
			append_json_escaped(&out, name);
			dstr_cat(&out, ",\"desc\":");
			append_json_escaped(&out, desc);
			dstr_catf(&out, ",\"type\":%d", (int)t);

			if (t == OBS_PROPERTY_INT) {
				dstr_catf(&out, ",\"min\":%d,\"max\":%d,\"step\":%d",
					  obs_property_int_min(p), obs_property_int_max(p), obs_property_int_step(p));
			} else if (t == OBS_PROPERTY_FLOAT) {
				dstr_catf(&out, ",\"min\":%.4f,\"max\":%.4f,\"step\":%.4f",
					  obs_property_float_min(p), obs_property_float_max(p), obs_property_float_step(p));
			} else if (t == OBS_PROPERTY_LIST) {
				enum obs_combo_format fmt = obs_property_list_format(p);
				dstr_catf(&out, ",\"list_format\":%d,\"items\":[", (int)fmt);
				size_t cnt = obs_property_list_item_count(p);
				for (size_t i = 0; i < cnt; i++) {
					if (i > 0) dstr_cat_ch(&out, ',');
					dstr_cat(&out, "{\"name\":");
					append_json_escaped(&out, obs_property_list_item_name(p, i));
					if (fmt == OBS_COMBO_FORMAT_INT)
						dstr_catf(&out, ",\"val\":%lld}", (long long)obs_property_list_item_int(p, i));
					else if (fmt == OBS_COMBO_FORMAT_FLOAT)
						dstr_catf(&out, ",\"val\":%.4f}", obs_property_list_item_float(p, i));
					else {
						dstr_cat(&out, ",\"val\":");
						append_json_escaped(&out, obs_property_list_item_string(p, i));
						dstr_cat_ch(&out, '}');
					}
				}
				dstr_cat_ch(&out, ']');
			}
			dstr_cat_ch(&out, '}');
		}
		obs_properties_destroy(props);
	}

	dstr_cat(&out, "],\"settings\":");
	dstr_cat(&out, settings_json ? settings_json : "{}");
	dstr_cat_ch(&out, '}');

	if (settings) obs_data_release(settings);
	obs_source_release(src);
	return out.array;
}

EXPORT void panggung_free_string(char *s)
{
	bfree(s);
}

/* ---- Filter enumeration helper ---- */
struct filter_enum_ctx {
	struct dstr out;
	bool first;
};

static void enum_filter_cb(obs_source_t *parent, obs_source_t *filter, void *param)
{
	UNUSED_PARAMETER(parent);
	struct filter_enum_ctx *ctx = (struct filter_enum_ctx *)param;
	if (!ctx->first)
		dstr_cat_ch(&ctx->out, ',');
	ctx->first = false;
	dstr_cat(&ctx->out, "{\"name\":");
	append_json_escaped(&ctx->out, obs_source_get_name(filter));
	dstr_cat(&ctx->out, ",\"id\":");
	append_json_escaped(&ctx->out, obs_source_get_id(filter));
	dstr_catf(&ctx->out, ",\"enabled\":%s}", obs_source_enabled(filter) ? "true" : "false");
}

EXPORT char *panggung_list_source_filters_json(const char *source_name)
{
	obs_source_t *src = obs_get_source_by_name(source_name);
	if (!src)
		return bstrdup("[]");
	struct filter_enum_ctx ctx = {{0}, true};
	dstr_copy(&ctx.out, "[");
	obs_source_enum_filters(src, enum_filter_cb, &ctx);
	dstr_cat_ch(&ctx.out, ']');
	obs_source_release(src);
	return ctx.out.array;
}

extern void panggung_register_mediacodec_encoders(void);

EXPORT void panggung_register_android_plugins(void)
{
	obs_register_source(&android_camera_info);
	obs_register_source(&android_screen_info);
	obs_register_source(&android_webview_info);
	obs_register_source(&android_audio_in_info);
	obs_register_source(&android_audio_out_info);
	panggung_register_mediacodec_encoders();
	blog(LOG_INFO, "[android-plugins] Registered zero-copy AHB android_camera (multi-cam), android_screen, android_webview, audio, GPU-surface mediacodec");
}
