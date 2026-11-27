/******************************************************************************
    Copyright (C) 2026 Panggung / OBS-Mobile Contributors
    GPL-2.0-or-later
******************************************************************************/

#include <obs.h>
#include <graphics/graphics.h>
#include <util/darray.h>
#include <util/platform.h>
#include <util/threading.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <android/native_window.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <string.h>
#include <stdlib.h>

#define COLOR_FormatYUV420SemiPlanar 21
#define COLOR_FormatYUVP010 54
#define COLOR_FormatSurface 0x7F000789
#define BITRATE_MODE_CQ 0
#define BITRATE_MODE_VBR 1
#define BITRATE_MODE_CBR 2
#define COLOR_RANGE_FULL 1
#define COLOR_RANGE_LIMITED 2
#define COLOR_STANDARD_BT709 1
#define COLOR_STANDARD_BT2020 6
#define COLOR_TRANSFER_SDR_VIDEO 3
#define COLOR_TRANSFER_ST2084 6
#define COLOR_TRANSFER_HLG 7

extern void obs_android_gpu_surface_encoder_delta(int delta);

typedef EGLBoolean(EGLAPIENTRYP PFNEGLPRESENTATIONTIMEANDROIDPROC_L)(EGLDisplay dpy, EGLSurface surface, EGLnsecsANDROID time);
static PFNEGLPRESENTATIONTIMEANDROIDPROC_L p_eglPresentationTimeANDROID = NULL;

static uint64_t g_mc_session_gen = 0;

/* Shared GLES 3.0 2D-texture -> MediaCodec ANativeWindow surface blit shader */
static GLuint g_mc_blit_prog = 0;
static GLuint g_mc_blit_vao = 0;

static const char *k_mc_blit_vs =
	"#version 300 es\n"
	"out vec2 v_uv;\n"
	"void main() {\n"
	"  vec2 pos = vec2((gl_VertexID == 1) ? 3.0 : -1.0, (gl_VertexID == 2) ? 3.0 : -1.0);\n"
	"  v_uv = vec2((pos.x + 1.0) * 0.5, 1.0 - (pos.y + 1.0) * 0.5);\n"
	"  gl_Position = vec4(pos, 0.0, 1.0);\n"
	"}\n";

static const char *k_mc_blit_fs =
	"#version 300 es\n"
	"precision mediump float;\n"
	"in vec2 v_uv;\n"
	"uniform sampler2D u_tex;\n"
	"out vec4 fragColor;\n"
	"void main() {\n"
	"  fragColor = texture(u_tex, v_uv);\n"
	"}\n";

static bool ensure_mc_blit_program(void)
{
	if (g_mc_blit_prog)
		return true;
	GLuint vs = glCreateShader(GL_VERTEX_SHADER);
	glShaderSource(vs, 1, &k_mc_blit_vs, NULL);
	glCompileShader(vs);
	GLint ok1 = 0;
	glGetShaderiv(vs, GL_COMPILE_STATUS, &ok1);

	GLuint fs = glCreateShader(GL_FRAGMENT_SHADER);
	glShaderSource(fs, 1, &k_mc_blit_fs, NULL);
	glCompileShader(fs);
	GLint ok2 = 0;
	glGetShaderiv(fs, GL_COMPILE_STATUS, &ok2);

	if (!ok1 || !ok2) {
		glDeleteShader(vs);
		glDeleteShader(fs);
		return false;
	}
	GLuint prog = glCreateProgram();
	glAttachShader(prog, vs);
	glAttachShader(prog, fs);
	glLinkProgram(prog);
	glDeleteShader(vs);
	glDeleteShader(fs);
	GLint linked = 0;
	glGetProgramiv(prog, GL_LINK_STATUS, &linked);
	if (!linked) {
		glDeleteProgram(prog);
		return false;
	}
	g_mc_blit_prog = prog;
	glGenVertexArrays(1, &g_mc_blit_vao);
	return true;
}

struct mc_encoder {
	obs_encoder_t *encoder;
	AMediaCodec *codec;
	const char *mime;
	uint64_t session_gen;
	bool is_hdr10;
	bool use_p010;
	bool use_surface;
	uint32_t width;
	uint32_t height;
	uint32_t fps_num;
	uint32_t fps_den;
	int32_t bitrate_bps;
	int32_t keyint_sec;

	/* GPU-to-MediaCodec Input Surface state (owned by OBS graphics thread) */
	pthread_mutex_t surface_lock;
	ANativeWindow *input_window;
	EGLDisplay egl_display;
	EGLSurface egl_surface;
	bool surface_failed;
	bool destroying;
	volatile long encode_credit; /* Backpressure gate: prevents BufferQueue deadlock on PauseRecord / StopRecord */
	uint64_t surface_frames_pushed;
	uint64_t start_video_ns;
	uint64_t last_active_ns;
	uint64_t paused_accum_ns;
	bool was_paused;

	DARRAY(uint8_t) extra_data;
	DARRAY(uint8_t) packet_buf;
};

static void mc_on_main_rendered(void *param)
{
	struct mc_encoder *enc = (struct mc_encoder *)param;
	if (!enc || !enc->use_surface)
		return;

	pthread_mutex_lock(&enc->surface_lock);
	if (enc->destroying || enc->surface_failed || !enc->input_window) {
		pthread_mutex_unlock(&enc->surface_lock);
		return;
	}
	/* Backpressure gate: only blit into MediaCodec ANativeWindow when mc_encode() is actively draining outputs.
	 * Guarantees eglSwapBuffers() NEVER blocks on BufferQueue during PauseRecord or StopRecord! */
	if (os_atomic_load_long(&enc->encode_credit) <= 0) {
		if (!enc->was_paused && enc->start_video_ns > 0) {
			enc->was_paused = true;
			enc->last_active_ns = obs_get_video_frame_time();
		}
		pthread_mutex_unlock(&enc->surface_lock);
		return;
	}
	os_atomic_dec_long(&enc->encode_credit);

	EGLDisplay dpy = eglGetCurrentDisplay();
	EGLContext ctx = eglGetCurrentContext();
	if (dpy == EGL_NO_DISPLAY || ctx == EGL_NO_CONTEXT) {
		pthread_mutex_unlock(&enc->surface_lock);
		return;
	}

	if (enc->egl_surface == EGL_NO_SURFACE) {
		if (!p_eglPresentationTimeANDROID) {
			p_eglPresentationTimeANDROID =
				(PFNEGLPRESENTATIONTIMEANDROIDPROC_L)eglGetProcAddress("eglPresentationTimeANDROID");
		}
		EGLint cfg_id = 0;
		EGLConfig cfg = NULL;
		EGLint num_cfg = 0;
		if (eglQueryContext(dpy, ctx, EGL_CONFIG_ID, &cfg_id)) {
			EGLint attrs[] = {EGL_CONFIG_ID, cfg_id, EGL_NONE};
			eglChooseConfig(dpy, attrs, &cfg, 1, &num_cfg);
		}
		if (num_cfg < 1) {
			static const EGLint fallback_attrs[] = {
				EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
				EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
				EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
				EGL_NONE
			};
			eglChooseConfig(dpy, fallback_attrs, &cfg, 1, &num_cfg);
		}
		if (num_cfg >= 1) {
			enc->egl_surface = eglCreateWindowSurface(dpy, cfg, enc->input_window, NULL);
			enc->egl_display = dpy;
		}
		if (enc->egl_surface == EGL_NO_SURFACE) {
			blog(LOG_ERROR, "[mediacodec-gpu] eglCreateWindowSurface failed (0x%x)", eglGetError());
			enc->surface_failed = true;
			pthread_mutex_unlock(&enc->surface_lock);
			return;
		}
	}

	gs_texture_t *main_tex = obs_get_main_texture();
	if (!main_tex || !ensure_mc_blit_program()) {
		pthread_mutex_unlock(&enc->surface_lock);
		return;
	}
	GLuint tex_id = *(GLuint *)gs_texture_get_obj(main_tex);
	if (!tex_id) {
		pthread_mutex_unlock(&enc->surface_lock);
		return;
	}

	EGLSurface prev_draw = eglGetCurrentSurface(EGL_DRAW);
	EGLSurface prev_read = eglGetCurrentSurface(EGL_READ);

	/* Save GLES state */
	GLint prev_prog = 0, prev_fbo = 0, prev_vao = 0, prev_active = 0, prev_tex2d = 0;
	GLint prev_vp[4] = {0};
	GLboolean prev_blend = glIsEnabled(GL_BLEND);
	GLboolean prev_depth = glIsEnabled(GL_DEPTH_TEST);
	GLboolean prev_cull = glIsEnabled(GL_CULL_FACE);
	GLboolean prev_scissor = glIsEnabled(GL_SCISSOR_TEST);
	glGetIntegerv(GL_CURRENT_PROGRAM, &prev_prog);
	glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &prev_fbo);
	glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &prev_vao);
	glGetIntegerv(GL_ACTIVE_TEXTURE, &prev_active);
	glGetIntegerv(GL_TEXTURE_BINDING_2D, &prev_tex2d);
	glGetIntegerv(GL_VIEWPORT, prev_vp);

	if (eglMakeCurrent(dpy, enc->egl_surface, enc->egl_surface, ctx)) {
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		glViewport(0, 0, (GLsizei)enc->width, (GLsizei)enc->height);
		glDisable(GL_BLEND);
		glDisable(GL_DEPTH_TEST);
		glDisable(GL_CULL_FACE);
		glDisable(GL_SCISSOR_TEST);

		glUseProgram(g_mc_blit_prog);
		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, tex_id);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glUniform1i(glGetUniformLocation(g_mc_blit_prog, "u_tex"), 0);
		glBindVertexArray(g_mc_blit_vao);
		glDrawArrays(GL_TRIANGLES, 0, 3);

		/* Use OBS's real monotonic video clock (not frame counter) so thermal drops/pauses never drift A/V! */
		uint64_t now_vns = obs_get_video_frame_time();
		if (now_vns == 0) now_vns = os_gettime_ns();
		if (enc->start_video_ns == 0) {
			enc->start_video_ns = now_vns;
		}
		if (enc->was_paused) {
			if (now_vns > enc->last_active_ns) {
				enc->paused_accum_ns += (now_vns - enc->last_active_ns);
			}
			enc->was_paused = false;
		}
		uint64_t elapsed_ns = (now_vns > enc->start_video_ns + enc->paused_accum_ns)
					      ? (now_vns - enc->start_video_ns - enc->paused_accum_ns)
					      : 0ULL;
		if (p_eglPresentationTimeANDROID) {
			p_eglPresentationTimeANDROID(dpy, enc->egl_surface, (EGLnsecsANDROID)elapsed_ns);
		}
		eglSwapBuffers(dpy, enc->egl_surface);
		enc->surface_frames_pushed++;
		if (enc->surface_frames_pushed == 1 || (enc->surface_frames_pushed % 300) == 0) {
			blog(LOG_INFO, "[mediacodec-gpu] Zero-copy GPU Surface frame #%llu (%ux%u, %s)",
			     (unsigned long long)enc->surface_frames_pushed, enc->width, enc->height, enc->mime);
		}

		/* Restore OBS EGL surface */
		eglMakeCurrent(dpy, prev_draw, prev_read, ctx);
	}

	/* Restore GLES state */
	glBindFramebuffer(GL_FRAMEBUFFER, (GLuint)prev_fbo);
	glViewport(prev_vp[0], prev_vp[1], prev_vp[2], prev_vp[3]);
	glBindVertexArray((GLuint)prev_vao);
	glUseProgram((GLuint)prev_prog);
	glActiveTexture((GLenum)prev_active);
	glBindTexture(GL_TEXTURE_2D, (GLuint)prev_tex2d);
	if (prev_blend) glEnable(GL_BLEND);
	if (prev_depth) glEnable(GL_DEPTH_TEST);
	if (prev_cull) glEnable(GL_CULL_FACE);
	if (prev_scissor) glEnable(GL_SCISSOR_TEST);

	pthread_mutex_unlock(&enc->surface_lock);
}

static const char *mc_h264_name(void *unused) { UNUSED_PARAMETER(unused); return "Android MediaCodec H.264"; }
static const char *mc_hevc_name(void *unused) { UNUSED_PARAMETER(unused); return "Android MediaCodec HEVC"; }
static const char *mc_hevc_hdr_name(void *unused) { UNUSED_PARAMETER(unused); return "Android MediaCodec HEVC Main10 HDR"; }
static const char *mc_av1_name(void *unused) { UNUSED_PARAMETER(unused); return "Android MediaCodec AV1"; }
static const char *mc_vp9_name(void *unused) { UNUSED_PARAMETER(unused); return "Android MediaCodec VP9"; }

static void mc_defaults(obs_data_t *settings)
{
	obs_data_set_default_int(settings, "bitrate", 4500);
	obs_data_set_default_int(settings, "keyint_sec", 2);
}

static obs_properties_t *mc_properties(void *unused)
{
	UNUSED_PARAMETER(unused);
	obs_properties_t *props = obs_properties_create();
	obs_properties_add_int(props, "bitrate", "Bitrate (Kbps)", 500, 50000, 100);
	obs_properties_add_int(props, "keyint_sec", "Keyframe Interval (s)", 1, 10, 1);
	return props;
}

static void *mc_create_common(obs_data_t *settings, obs_encoder_t *encoder, const char *mime, bool hdr10)
{
	video_t *video = obs_encoder_video(encoder);
	const struct video_output_info *voi = video_output_get_info(video);
	if (!voi) {
		blog(LOG_ERROR, "[mediacodec] No video output info");
		return NULL;
	}

	struct mc_encoder *enc = bzalloc(sizeof(struct mc_encoder));
	pthread_mutex_init(&enc->surface_lock, NULL);
	enc->encoder = encoder;
	enc->mime = mime;
	enc->is_hdr10 = hdr10;
	enc->session_gen = ++g_mc_session_gen;
	enc->width = obs_encoder_get_width(encoder);
	enc->height = obs_encoder_get_height(encoder);
	enc->fps_num = voi->fps_num ? voi->fps_num : 30;
	enc->fps_den = voi->fps_den ? voi->fps_den : 1;
	enc->egl_display = EGL_NO_DISPLAY;
	enc->egl_surface = EGL_NO_SURFACE;
	int64_t kbps = obs_data_get_int(settings, "bitrate");
	if (kbps <= 0) kbps = 4500;
	enc->bitrate_bps = (int32_t)(kbps * 1000);
	int64_t keyint = obs_data_get_int(settings, "keyint_sec");
	if (keyint <= 0) keyint = 2;
	enc->keyint_sec = (int32_t)keyint;

	bool want_hdr = hdr10 || voi->colorspace == VIDEO_CS_2100_PQ || voi->colorspace == VIDEO_CS_2100_HLG;
	bool try_gpu_surface = !want_hdr && (strcmp(mime, "video/avc") == 0 || strcmp(mime, "video/hevc") == 0);

	/* Attempt 0: TRUE Zero-Copy GPU Surface Encoding (COLOR_FormatSurface + AMediaCodec_createInputSurface) for SDR H.264/HEVC.
	 * Attempts 1-3: P010 (for HDR10) + NV12 CBR/VBR fallback. */
	media_status_t st = AMEDIA_ERROR_UNKNOWN;
	enc->codec = NULL;

	if (try_gpu_surface) {
		AMediaCodec *cand = AMediaCodec_createEncoderByType(mime);
		if (cand) {
			AMediaFormat *fmt = AMediaFormat_new();
			AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, mime);
			AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, (int32_t)enc->width);
			AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, (int32_t)enc->height);
			AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_COLOR_FORMAT, COLOR_FormatSurface);
			AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_BIT_RATE, enc->bitrate_bps);
			AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_FRAME_RATE, (int32_t)(enc->fps_num / enc->fps_den));
			AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, enc->keyint_sec);
			AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_BITRATE_MODE, BITRATE_MODE_CBR);
			AMediaFormat_setInt32(fmt, "color-range", voi->range == VIDEO_RANGE_FULL ? COLOR_RANGE_FULL : COLOR_RANGE_LIMITED);
			AMediaFormat_setInt32(fmt, "color-standard", COLOR_STANDARD_BT709);
			AMediaFormat_setInt32(fmt, "color-transfer", COLOR_TRANSFER_SDR_VIDEO);
			st = AMediaCodec_configure(cand, fmt, NULL, NULL, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
			AMediaFormat_delete(fmt);
			if (st == AMEDIA_OK) {
				ANativeWindow *win = NULL;
				st = AMediaCodec_createInputSurface(cand, &win);
				if (st == AMEDIA_OK && win != NULL) {
					enc->codec = cand;
					enc->input_window = win;
					enc->use_surface = true;
				} else {
					if (win) ANativeWindow_release(win);
					AMediaCodec_delete(cand);
				}
			} else {
				AMediaCodec_delete(cand);
			}
		}
	}

	if (!enc->codec) {
		int rc_modes[3] = { BITRATE_MODE_CBR, BITRATE_MODE_CBR, BITRATE_MODE_VBR };
		int cf_modes[3] = {
			want_hdr ? COLOR_FormatYUVP010 : COLOR_FormatYUV420SemiPlanar,
			COLOR_FormatYUV420SemiPlanar,
			COLOR_FormatYUV420SemiPlanar
		};
		for (int attempt = 0; attempt < 3; attempt++) {
			if (attempt == 0 && !want_hdr) continue;
			AMediaCodec *cand = AMediaCodec_createEncoderByType(mime);
			if (!cand) break;
			AMediaFormat *fmt = AMediaFormat_new();
			AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, mime);
			AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, (int32_t)enc->width);
			AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, (int32_t)enc->height);
			AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_COLOR_FORMAT, cf_modes[attempt]);
			AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_BIT_RATE, enc->bitrate_bps);
			AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_FRAME_RATE, (int32_t)(enc->fps_num / enc->fps_den));
			AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, enc->keyint_sec);
			AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_BITRATE_MODE, rc_modes[attempt]);
			if (attempt < 2) {
				AMediaFormat_setInt32(fmt, "color-range", voi->range == VIDEO_RANGE_FULL ? COLOR_RANGE_FULL : COLOR_RANGE_LIMITED);
				if (want_hdr) {
					AMediaFormat_setInt32(fmt, "color-standard", COLOR_STANDARD_BT2020);
					AMediaFormat_setInt32(fmt, "color-transfer",
							      voi->colorspace == VIDEO_CS_2100_HLG ? COLOR_TRANSFER_HLG : COLOR_TRANSFER_ST2084);
					if (attempt == 0) {
						AMediaFormat_setInt32(fmt, "profile", 2); /* HEVCProfileMain10 */
					}
				} else {
					AMediaFormat_setInt32(fmt, "color-standard", COLOR_STANDARD_BT709);
					AMediaFormat_setInt32(fmt, "color-transfer", COLOR_TRANSFER_SDR_VIDEO);
				}
			}
			st = AMediaCodec_configure(cand, fmt, NULL, NULL, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
			AMediaFormat_delete(fmt);
			if (st == AMEDIA_OK) {
				enc->codec = cand;
				enc->use_p010 = (cf_modes[attempt] == COLOR_FormatYUVP010);
				break;
			}
			AMediaCodec_delete(cand);
		}
	}

	if (!enc->codec || st != AMEDIA_OK) {
		blog(LOG_ERROR, "[mediacodec] AMediaCodec_configure failed (%d) for %s %ux%u", st, mime, enc->width, enc->height);
		pthread_mutex_destroy(&enc->surface_lock);
		bfree(enc);
		return NULL;
	}

	st = AMediaCodec_start(enc->codec);
	if (st != AMEDIA_OK) {
		blog(LOG_ERROR, "[mediacodec] AMediaCodec_start failed (%d)", st);
		AMediaCodec_delete(enc->codec);
		if (enc->input_window) ANativeWindow_release(enc->input_window);
		pthread_mutex_destroy(&enc->surface_lock);
		bfree(enc);
		return NULL;
	}

	if (enc->use_surface) {
		os_atomic_set_long(&enc->encode_credit, 2);
		obs_android_gpu_surface_encoder_delta(+1);
		obs_add_main_rendered_callback(mc_on_main_rendered, enc);
	}

	blog(LOG_INFO, "[mediacodec] Started %s gen=%llu %ux%u @ %d kbps (mode=%s, hdr10=%d)",
	     mime, (unsigned long long)enc->session_gen, enc->width, enc->height, enc->bitrate_bps / 1000,
	     enc->use_surface ? "GPU-SURFACE" : (enc->use_p010 ? "P010-HDR" : "NV12-BUF"),
	     hdr10 ? 1 : 0);
	return enc;
}

static void *mc_h264_create(obs_data_t *s, obs_encoder_t *e) { return mc_create_common(s, e, "video/avc", false); }
static void *mc_hevc_create(obs_data_t *s, obs_encoder_t *e) { return mc_create_common(s, e, "video/hevc", false); }
static void *mc_hevc_hdr_create(obs_data_t *s, obs_encoder_t *e) { return mc_create_common(s, e, "video/hevc", true); }
static void *mc_av1_create(obs_data_t *s, obs_encoder_t *e) { return mc_create_common(s, e, "video/av01", false); }
static void *mc_vp9_create(obs_data_t *s, obs_encoder_t *e) { return mc_create_common(s, e, "video/x-vnd.on2.vp9", false); }

static void mc_destroy(void *data)
{
	struct mc_encoder *enc = (struct mc_encoder *)data;
	if (!enc) return;

	if (enc->use_surface) {
		pthread_mutex_lock(&enc->surface_lock);
		enc->destroying = true;
		pthread_mutex_unlock(&enc->surface_lock);

		obs_remove_main_rendered_callback(mc_on_main_rendered, enc);
		obs_android_gpu_surface_encoder_delta(-1);

		pthread_mutex_lock(&enc->surface_lock);
		EGLSurface surf = enc->egl_surface;
		EGLDisplay dpy = enc->egl_display;
		enc->egl_surface = EGL_NO_SURFACE;
		pthread_mutex_unlock(&enc->surface_lock);

		/* Safe without obs_enter_graphics(): surf is never current outside mc_on_main_rendered */
		if (surf != EGL_NO_SURFACE && dpy != EGL_NO_DISPLAY) {
			eglDestroySurface(dpy, surf);
		}
	}

	if (enc->codec) {
		if (enc->use_surface) {
			AMediaCodec_signalEndOfInputStream(enc->codec);
		}
		AMediaCodec_stop(enc->codec);
		AMediaCodec_delete(enc->codec);
	}
	if (enc->input_window) {
		ANativeWindow_release(enc->input_window);
		enc->input_window = NULL;
	}
	pthread_mutex_destroy(&enc->surface_lock);
	da_free(enc->extra_data);
	da_free(enc->packet_buf);
	bfree(enc);
}

static bool mc_update(void *data, obs_data_t *settings)
{
	struct mc_encoder *enc = (struct mc_encoder *)data;
	int64_t kbps = obs_data_get_int(settings, "bitrate");
	if (kbps > 0 && enc->codec) {
		enc->bitrate_bps = (int32_t)(kbps * 1000);
		AMediaFormat *params = AMediaFormat_new();
		AMediaFormat_setInt32(params, "video-bitrate", enc->bitrate_bps);
		AMediaCodec_setParameters(enc->codec, params);
		AMediaFormat_delete(params);
	}
	return true;
}

static void mc_video_info(void *data, struct video_scale_info *info)
{
	struct mc_encoder *enc = (struct mc_encoder *)data;
	info->format = (enc && enc->use_p010) ? VIDEO_FORMAT_P010 : VIDEO_FORMAT_NV12;
}

static bool mc_extra_data(void *data, uint8_t **extra_data, size_t *size)
{
	struct mc_encoder *enc = (struct mc_encoder *)data;
	if (enc->extra_data.num == 0)
		return false;
	*extra_data = enc->extra_data.array;
	*size = enc->extra_data.num;
	return true;
}

static bool mc_encode(void *data, struct encoder_frame *frame, struct encoder_packet *packet, bool *received_packet)
{
	struct mc_encoder *enc = (struct mc_encoder *)data;
	*received_packet = false;

	/* 1. Feed NV12/P010 CPU buffer ONLY when NOT in zero-copy GPU-Surface mode */
	if (enc->use_surface) {
		/* Refresh GPU-surface backpressure credit so mc_on_main_rendered can blit up to 2 frames ahead */
		os_atomic_set_long(&enc->encode_credit, 2);
	} else {
		ssize_t in_idx = AMediaCodec_dequeueInputBuffer(enc->codec, 10000);
		if (in_idx >= 0) {
			size_t buf_size = 0;
			uint8_t *buf = AMediaCodec_getInputBuffer(enc->codec, (size_t)in_idx, &buf_size);
			if (buf) {
				uint32_t w = enc->width;
				uint32_t h = enc->height;
				size_t bpp = enc->use_p010 ? 2 : 1;
				size_t row_bytes = (size_t)w * bpp;
				size_t y_bytes = row_bytes * h;
				size_t uv_bytes = y_bytes / 2;
				if (buf_size >= y_bytes + uv_bytes) {
					for (uint32_t row = 0; row < h; row++) {
						memcpy(buf + (size_t)row * row_bytes,
						       frame->data[0] + (size_t)row * frame->linesize[0], row_bytes);
					}
					uint8_t *uv_dst = buf + y_bytes;
					for (uint32_t row = 0; row < h / 2; row++) {
						memcpy(uv_dst + (size_t)row * row_bytes,
						       frame->data[1] + (size_t)row * frame->linesize[1], row_bytes);
					}
					int64_t pts_us = (frame->pts * 1000000LL * enc->fps_den) / enc->fps_num;
					AMediaCodec_queueInputBuffer(enc->codec, (size_t)in_idx, 0,
								     y_bytes + uv_bytes, (uint64_t)pts_us, 0);
				}
			}
		}
	}

	/* 2. Drain hardware-encoded bitstream (loop past CODEC_CONFIG / FORMAT_CHANGED) */
	for (int tries = 0; tries < 4; tries++) {
		AMediaCodecBufferInfo info;
		ssize_t out_idx = AMediaCodec_dequeueOutputBuffer(enc->codec, &info, tries == 0 ? 10000 : 0);
		if (out_idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
			continue;
		} else if (out_idx < 0) {
			break;
		}

		size_t out_buf_size = 0;
		uint8_t *out_buf = AMediaCodec_getOutputBuffer(enc->codec, (size_t)out_idx, &out_buf_size);
		if (out_buf && info.size > 0) {
			const uint8_t *src = out_buf + info.offset;
			if (info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) {
				da_resize(enc->extra_data, (size_t)info.size);
				memcpy(enc->extra_data.array, src, (size_t)info.size);
				AMediaCodec_releaseOutputBuffer(enc->codec, (size_t)out_idx, false);
				continue;
			}

			bool keyframe = (info.flags & 1 /* BUFFER_FLAG_KEY_FRAME */) != 0;
			da_clear(enc->packet_buf);
			if (keyframe && enc->extra_data.num > 0) {
				da_push_back_array(enc->packet_buf, enc->extra_data.array, enc->extra_data.num);
			}
			da_push_back_array(enc->packet_buf, src, (size_t)info.size);

			int64_t pts_frames = (info.presentationTimeUs * enc->fps_num + 500000LL * enc->fps_den) /
					     (1000000LL * enc->fps_den);
			packet->data = enc->packet_buf.array;
			packet->size = enc->packet_buf.num;
			packet->type = OBS_ENCODER_VIDEO;
			packet->pts = pts_frames;
			packet->dts = pts_frames;
			packet->keyframe = keyframe;
			*received_packet = true;
		}
		AMediaCodec_releaseOutputBuffer(enc->codec, (size_t)out_idx, false);
		if (*received_packet)
			break;
	}

	return true;
}

static struct obs_encoder_info mc_h264_info = {
	.id = "android_mediacodec_h264",
	.type = OBS_ENCODER_VIDEO,
	.codec = "h264",
	.get_name = mc_h264_name,
	.create = mc_h264_create,
	.destroy = mc_destroy,
	.encode = mc_encode,
	.get_defaults = mc_defaults,
	.get_properties = mc_properties,
	.update = mc_update,
	.get_extra_data = mc_extra_data,
	.get_video_info = mc_video_info,
	.caps = OBS_ENCODER_CAP_DYN_BITRATE,
};

static struct obs_encoder_info mc_hevc_info = {
	.id = "android_mediacodec_hevc",
	.type = OBS_ENCODER_VIDEO,
	.codec = "hevc",
	.get_name = mc_hevc_name,
	.create = mc_hevc_create,
	.destroy = mc_destroy,
	.encode = mc_encode,
	.get_defaults = mc_defaults,
	.get_properties = mc_properties,
	.update = mc_update,
	.get_extra_data = mc_extra_data,
	.get_video_info = mc_video_info,
	.caps = OBS_ENCODER_CAP_DYN_BITRATE,
};

static struct obs_encoder_info mc_hevc_hdr_info = {
	.id = "android_mediacodec_hevc_hdr",
	.type = OBS_ENCODER_VIDEO,
	.codec = "hevc",
	.get_name = mc_hevc_hdr_name,
	.create = mc_hevc_hdr_create,
	.destroy = mc_destroy,
	.encode = mc_encode,
	.get_defaults = mc_defaults,
	.get_properties = mc_properties,
	.update = mc_update,
	.get_extra_data = mc_extra_data,
	.get_video_info = mc_video_info,
	.caps = OBS_ENCODER_CAP_DYN_BITRATE,
};

static struct obs_encoder_info mc_av1_info = {
	.id = "android_mediacodec_av1",
	.type = OBS_ENCODER_VIDEO,
	.codec = "av1",
	.get_name = mc_av1_name,
	.create = mc_av1_create,
	.destroy = mc_destroy,
	.encode = mc_encode,
	.get_defaults = mc_defaults,
	.get_properties = mc_properties,
	.update = mc_update,
	.get_extra_data = mc_extra_data,
	.get_video_info = mc_video_info,
	.caps = OBS_ENCODER_CAP_DYN_BITRATE,
};

static struct obs_encoder_info mc_vp9_info = {
	.id = "android_mediacodec_vp9",
	.type = OBS_ENCODER_VIDEO,
	.codec = "vp9",
	.get_name = mc_vp9_name,
	.create = mc_vp9_create,
	.destroy = mc_destroy,
	.encode = mc_encode,
	.get_defaults = mc_defaults,
	.get_properties = mc_properties,
	.update = mc_update,
	.get_extra_data = mc_extra_data,
	.get_video_info = mc_video_info,
	.caps = OBS_ENCODER_CAP_DYN_BITRATE,
};

void panggung_register_mediacodec_encoders(void)
{
	obs_register_encoder(&mc_h264_info);
	obs_register_encoder(&mc_hevc_info);
	obs_register_encoder(&mc_hevc_hdr_info);
	obs_register_encoder(&mc_av1_info);
	obs_register_encoder(&mc_vp9_info);
}
