/******************************************************************************
    Copyright (C) 2026 Panggung / OBS-Mobile Contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 2 of the License, or
    (at your option) any later version.
******************************************************************************/

#include "gl-subsystem.h"
#include <android/native_window.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <EGL/eglext.h>
#include <GLES2/gl2ext.h>

typedef EGLClientBuffer(EGLAPIENTRYP PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC_LOCAL)(const struct AHardwareBuffer *buffer);
typedef EGLImageKHR(EGLAPIENTRYP PFNEGLCREATEIMAGEKHRPROC_LOCAL)(EGLDisplay dpy, EGLContext ctx, EGLenum target,
								  EGLClientBuffer buffer, const EGLint *attrib_list);
typedef EGLBoolean(EGLAPIENTRYP PFNEGLDESTROYIMAGEKHRPROC_LOCAL)(EGLDisplay dpy, EGLImageKHR image);
typedef void(GL_APIENTRYP PFNGLEGLIMAGETARGETTEXTURE2DOESPROC_LOCAL)(GLenum target, GLeglImageOES image);
typedef EGLSyncKHR(EGLAPIENTRYP PFNEGLCREATESYNCKHRPROC_LOCAL)(EGLDisplay dpy, EGLenum type, const EGLint *attrib_list);
typedef EGLBoolean(EGLAPIENTRYP PFNEGLDESTROYSYNCKHRPROC_LOCAL)(EGLDisplay dpy, EGLSyncKHR sync);
typedef EGLint(EGLAPIENTRYP PFNEGLCLIENTWAITSYNCKHRPROC_LOCAL)(EGLDisplay dpy, EGLSyncKHR sync, EGLint flags,
							       EGLTimeKHR timeout);

static PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC_LOCAL p_eglGetNativeClientBufferANDROID = NULL;
static PFNEGLCREATEIMAGEKHRPROC_LOCAL p_eglCreateImageKHR = NULL;
static PFNEGLDESTROYIMAGEKHRPROC_LOCAL p_eglDestroyImageKHR = NULL;
static PFNGLEGLIMAGETARGETTEXTURE2DOESPROC_LOCAL p_glEGLImageTargetTexture2DOES = NULL;
static PFNEGLCREATESYNCKHRPROC_LOCAL p_eglCreateSyncKHR = NULL;
static PFNEGLDESTROYSYNCKHRPROC_LOCAL p_eglDestroySyncKHR = NULL;
static PFNEGLCLIENTWAITSYNCKHRPROC_LOCAL p_eglClientWaitSyncKHR = NULL;

static void gl_android_load_egl_exts(void)
{
	if (!p_eglGetNativeClientBufferANDROID)
		p_eglGetNativeClientBufferANDROID =
			(PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC_LOCAL)eglGetProcAddress("eglGetNativeClientBufferANDROID");
	if (!p_eglCreateImageKHR)
		p_eglCreateImageKHR = (PFNEGLCREATEIMAGEKHRPROC_LOCAL)eglGetProcAddress("eglCreateImageKHR");
	if (!p_eglDestroyImageKHR)
		p_eglDestroyImageKHR = (PFNEGLDESTROYIMAGEKHRPROC_LOCAL)eglGetProcAddress("eglDestroyImageKHR");
	if (!p_glEGLImageTargetTexture2DOES)
		p_glEGLImageTargetTexture2DOES =
			(PFNGLEGLIMAGETARGETTEXTURE2DOESPROC_LOCAL)eglGetProcAddress("glEGLImageTargetTexture2DOES");
	if (!p_eglCreateSyncKHR)
		p_eglCreateSyncKHR = (PFNEGLCREATESYNCKHRPROC_LOCAL)eglGetProcAddress("eglCreateSyncKHR");
	if (!p_eglDestroySyncKHR)
		p_eglDestroySyncKHR = (PFNEGLDESTROYSYNCKHRPROC_LOCAL)eglGetProcAddress("eglDestroySyncKHR");
	if (!p_eglClientWaitSyncKHR)
		p_eglClientWaitSyncKHR = (PFNEGLCLIENTWAITSYNCKHRPROC_LOCAL)eglGetProcAddress("eglClientWaitSyncKHR");
}

struct gl_windowinfo {
	ANativeWindow *window;
	EGLSurface egl_surface;
	uint32_t cx;
	uint32_t cy;
};

struct gl_platform {
	EGLDisplay display;
	EGLConfig config;
	EGLContext context;
	EGLSurface pbuffer;
};

static const EGLint k_config_attribs[] = {
	EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
	EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
	EGL_RED_SIZE, 8,
	EGL_GREEN_SIZE, 8,
	EGL_BLUE_SIZE, 8,
	EGL_ALPHA_SIZE, 8,
	EGL_DEPTH_SIZE, 0,
	EGL_STENCIL_SIZE, 0,
	EGL_NONE
};

static const EGLint k_context_attribs_32[] = {
	EGL_CONTEXT_MAJOR_VERSION, 3,
	EGL_CONTEXT_MINOR_VERSION, 2,
	EGL_NONE
};

static const EGLint k_context_attribs_30[] = {
	EGL_CONTEXT_MAJOR_VERSION, 3,
	EGL_CONTEXT_MINOR_VERSION, 0,
	EGL_NONE
};

static const EGLint k_pbuffer_attribs[] = {
	EGL_WIDTH, 1,
	EGL_HEIGHT, 1,
	EGL_NONE
};

struct gl_platform *gl_platform_create(gs_device_t *device, uint32_t adapter)
{
	UNUSED_PARAMETER(device);
	UNUSED_PARAMETER(adapter);

	struct gl_platform *plat = bzalloc(sizeof(struct gl_platform));
	plat->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
	if (plat->display == EGL_NO_DISPLAY) {
		blog(LOG_ERROR, "libobs-gles: eglGetDisplay failed");
		goto fail;
	}

	EGLint major = 0, minor = 0;
	if (!eglInitialize(plat->display, &major, &minor)) {
		blog(LOG_ERROR, "libobs-gles: eglInitialize failed (0x%x)", eglGetError());
		goto fail;
	}
	blog(LOG_INFO, "libobs-gles: EGL %d.%d initialized", major, minor);

	if (!eglBindAPI(EGL_OPENGL_ES_API)) {
		blog(LOG_ERROR, "libobs-gles: eglBindAPI(EGL_OPENGL_ES_API) failed");
		goto fail;
	}

	EGLint num_configs = 0;
	if (!eglChooseConfig(plat->display, k_config_attribs, &plat->config, 1, &num_configs) || num_configs < 1) {
		blog(LOG_ERROR, "libobs-gles: eglChooseConfig failed (0x%x)", eglGetError());
		goto fail;
	}

	plat->context = eglCreateContext(plat->display, plat->config, EGL_NO_CONTEXT, k_context_attribs_32);
	if (plat->context == EGL_NO_CONTEXT) {
		blog(LOG_WARNING, "libobs-gles: GLES 3.2 context failed, falling back to GLES 3.0");
		plat->context = eglCreateContext(plat->display, plat->config, EGL_NO_CONTEXT, k_context_attribs_30);
	}
	if (plat->context == EGL_NO_CONTEXT) {
		blog(LOG_ERROR, "libobs-gles: eglCreateContext failed (0x%x)", eglGetError());
		goto fail;
	}

	plat->pbuffer = eglCreatePbufferSurface(plat->display, plat->config, k_pbuffer_attribs);
	if (plat->pbuffer == EGL_NO_SURFACE) {
		blog(LOG_ERROR, "libobs-gles: eglCreatePbufferSurface failed (0x%x)", eglGetError());
		goto fail;
	}

	if (!eglMakeCurrent(plat->display, plat->pbuffer, plat->pbuffer, plat->context)) {
		blog(LOG_ERROR, "libobs-gles: eglMakeCurrent failed (0x%x)", eglGetError());
		goto fail;
	}

	gl_android_load_egl_exts();
	blog(LOG_INFO, "libobs-gles: GL Vendor: %s", (const char *)glGetString(GL_VENDOR));
	blog(LOG_INFO, "libobs-gles: GL Renderer: %s", (const char *)glGetString(GL_RENDERER));
	blog(LOG_INFO, "libobs-gles: GL Version: %s", (const char *)glGetString(GL_VERSION));
	blog(LOG_INFO, "libobs-gles: Zero-Copy AHardwareBuffer=%s EGLSync=%s",
	     (p_eglGetNativeClientBufferANDROID && p_eglCreateImageKHR && p_glEGLImageTargetTexture2DOES) ? "YES" : "NO",
	     (p_eglCreateSyncKHR && p_eglClientWaitSyncKHR) ? "YES" : "NO");
	return plat;

fail:
	if (plat) {
		if (plat->display != EGL_NO_DISPLAY) {
			eglMakeCurrent(plat->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
			if (plat->pbuffer != EGL_NO_SURFACE)
				eglDestroySurface(plat->display, plat->pbuffer);
			if (plat->context != EGL_NO_CONTEXT)
				eglDestroyContext(plat->display, plat->context);
			eglTerminate(plat->display);
		}
		bfree(plat);
	}
	return NULL;
}

void gl_platform_destroy(struct gl_platform *plat)
{
	if (!plat)
		return;
	if (plat->display != EGL_NO_DISPLAY) {
		eglMakeCurrent(plat->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
		if (plat->pbuffer != EGL_NO_SURFACE)
			eglDestroySurface(plat->display, plat->pbuffer);
		if (plat->context != EGL_NO_CONTEXT)
			eglDestroyContext(plat->display, plat->context);
		eglTerminate(plat->display);
	}
	bfree(plat);
}

struct gl_windowinfo *gl_windowinfo_create(const struct gs_init_data *info)
{
	struct gl_windowinfo *wi = bzalloc(sizeof(struct gl_windowinfo));
	wi->window = (ANativeWindow *)info->window.display;
	wi->cx = info->cx;
	wi->cy = info->cy;
	if (wi->window)
		ANativeWindow_acquire(wi->window);
	return wi;
}

void gl_windowinfo_destroy(struct gl_windowinfo *info)
{
	if (!info)
		return;
	if (info->window)
		ANativeWindow_release(info->window);
	bfree(info);
}

bool gl_platform_init_swapchain(struct gs_swap_chain *swap)
{
	struct gl_platform *plat = swap->device->plat;
	struct gl_windowinfo *wi = swap->wi;
	if (!wi || !wi->window) {
		blog(LOG_ERROR, "libobs-gles: init_swapchain with null ANativeWindow");
		return false;
	}
	wi->egl_surface = eglCreateWindowSurface(plat->display, plat->config, wi->window, NULL);
	if (wi->egl_surface == EGL_NO_SURFACE) {
		blog(LOG_ERROR, "libobs-gles: eglCreateWindowSurface failed (0x%x)", eglGetError());
		return false;
	}
	return true;
}

void gl_platform_cleanup_swapchain(struct gs_swap_chain *swap)
{
	struct gl_platform *plat = swap->device->plat;
	struct gl_windowinfo *wi = swap->wi;
	if (wi && wi->egl_surface != EGL_NO_SURFACE) {
		if (eglGetCurrentSurface(EGL_DRAW) == wi->egl_surface)
			eglMakeCurrent(plat->display, plat->pbuffer, plat->pbuffer, plat->context);
		eglDestroySurface(plat->display, wi->egl_surface);
		wi->egl_surface = EGL_NO_SURFACE;
	}
}

void device_enter_context(gs_device_t *device)
{
	struct gl_platform *plat = device->plat;
	EGLSurface surf = plat->pbuffer;
	if (device->cur_swap && device->cur_swap->wi && device->cur_swap->wi->egl_surface != EGL_NO_SURFACE)
		surf = device->cur_swap->wi->egl_surface;
	if (!eglMakeCurrent(plat->display, surf, surf, plat->context))
		blog(LOG_ERROR, "libobs-gles: device_enter_context eglMakeCurrent failed (0x%x)", eglGetError());
}

void device_leave_context(gs_device_t *device)
{
	struct gl_platform *plat = device->plat;
	device->cur_render_target = NULL;
	device->cur_zstencil_buffer = NULL;
	device->cur_vertex_buffer = NULL;
	device->cur_index_buffer = NULL;
	device->cur_swap = NULL;
	device->cur_fbo = NULL;
	eglMakeCurrent(plat->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
}

void *device_get_device_obj(gs_device_t *device)
{
	return device->plat->context;
}

void gl_update(gs_device_t *device)
{
	UNUSED_PARAMETER(device);
}

void gl_clear_context(gs_device_t *device)
{
	struct gl_platform *plat = device->plat;
	eglMakeCurrent(plat->display, plat->pbuffer, plat->pbuffer, plat->context);
}

void device_load_swapchain(gs_device_t *device, gs_swapchain_t *swap)
{
	struct gl_platform *plat = device->plat;
	if (device->cur_swap == swap)
		return;
	device->cur_swap = swap;
	EGLSurface surf = plat->pbuffer;
	if (swap && swap->wi && swap->wi->egl_surface != EGL_NO_SURFACE)
		surf = swap->wi->egl_surface;
	if (!eglMakeCurrent(plat->display, surf, surf, plat->context))
		blog(LOG_ERROR, "libobs-gles: device_load_swapchain eglMakeCurrent failed (0x%x)", eglGetError());
}

void device_present(gs_device_t *device)
{
	if (!device->cur_swap || !device->cur_swap->wi || device->cur_swap->wi->egl_surface == EGL_NO_SURFACE)
		return;
	eglSwapBuffers(device->plat->display, device->cur_swap->wi->egl_surface);
}

void gl_getclientsize(const struct gs_swap_chain *swap, uint32_t *width, uint32_t *height)
{
	if (swap && swap->wi && swap->wi->egl_surface != EGL_NO_SURFACE) {
		EGLint w = 0, h = 0;
		eglQuerySurface(swap->device->plat->display, swap->wi->egl_surface, EGL_WIDTH, &w);
		eglQuerySurface(swap->device->plat->display, swap->wi->egl_surface, EGL_HEIGHT, &h);
		*width = (uint32_t)(w > 0 ? w : (EGLint)swap->wi->cx);
		*height = (uint32_t)(h > 0 ? h : (EGLint)swap->wi->cy);
	} else {
		*width = swap && swap->wi ? swap->wi->cx : 0;
		*height = swap && swap->wi ? swap->wi->cy : 0;
	}
}

bool device_enum_adapters(gs_device_t *device, bool (*callback)(void *param, const char *name, uint32_t id), void *param)
{
	UNUSED_PARAMETER(device);
	return callback(param, "Android GLES 3.2 GPU", 0);
}

/* dmabuf / sync stubs (Android uses AHardwareBuffer / EGLImage instead) */
bool device_is_monitor_hdr(gs_device_t *device, void *monitor)
{
	UNUSED_PARAMETER(device);
	UNUSED_PARAMETER(monitor);
	return false;
}

bool device_can_adapter_fast_clear(gs_device_t *device)
{
	UNUSED_PARAMETER(device);
	return false;
}

bool device_is_present_ready(gs_device_t *device)
{
	UNUSED_PARAMETER(device);
	return true;
}

gs_texture_t *device_texture_create_from_dmabuf(gs_device_t *device, unsigned int width, unsigned int height,
						uint32_t drm_format, enum gs_color_format color_format,
						uint32_t n_planes, const int *fds, const uint32_t *strides,
						const uint32_t *offsets, const uint64_t *modifiers)
{
	UNUSED_PARAMETER(device); UNUSED_PARAMETER(width); UNUSED_PARAMETER(height);
	UNUSED_PARAMETER(drm_format); UNUSED_PARAMETER(color_format); UNUSED_PARAMETER(n_planes);
	UNUSED_PARAMETER(fds); UNUSED_PARAMETER(strides); UNUSED_PARAMETER(offsets); UNUSED_PARAMETER(modifiers);
	return NULL;
}

bool device_query_dmabuf_capabilities(gs_device_t *device, enum gs_dmabuf_flags *gs_dmabuf_flags,
				      uint32_t **drm_formats, size_t *n_formats)
{
	UNUSED_PARAMETER(device);
	if (gs_dmabuf_flags) *gs_dmabuf_flags = 0;
	if (drm_formats) *drm_formats = NULL;
	if (n_formats) *n_formats = 0;
	return false;
}

bool device_query_dmabuf_modifiers_for_format(gs_device_t *device, uint32_t drm_format, uint64_t **modifiers,
					      size_t *n_modifiers)
{
	UNUSED_PARAMETER(device); UNUSED_PARAMETER(drm_format);
	if (modifiers) *modifiers = NULL;
	if (n_modifiers) *n_modifiers = 0;
	return false;
}

gs_texture_t *device_texture_create_from_pixmap(gs_device_t *device, uint32_t width, uint32_t height,
						enum gs_color_format color_format, uint32_t target, void *pixmap)
{
	UNUSED_PARAMETER(device); UNUSED_PARAMETER(width); UNUSED_PARAMETER(height);
	UNUSED_PARAMETER(color_format); UNUSED_PARAMETER(target); UNUSED_PARAMETER(pixmap);
	return NULL;
}

/* Zero-Copy AHardwareBuffer -> EGLImageKHR -> GL_TEXTURE_EXTERNAL_OES (T-C01) */
bool panggung_egl_has_zero_copy_ahb(void)
{
	gl_android_load_egl_exts();
	return p_eglGetNativeClientBufferANDROID && p_eglCreateImageKHR &&
	       p_eglDestroyImageKHR && p_glEGLImageTargetTexture2DOES;
}

bool panggung_egl_bind_ahardwarebuffer(gs_device_t *device, AHardwareBuffer *ahb,
				       GLuint tex_id, EGLImageKHR *out_image)
{
	if (!device || !device->plat || !ahb || !out_image)
		return false;
	if (!panggung_egl_has_zero_copy_ahb())
		return false;
	EGLClientBuffer client_buf = p_eglGetNativeClientBufferANDROID(ahb);
	if (!client_buf)
		return false;
	static const EGLint attrs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
	EGLImageKHR img = p_eglCreateImageKHR(device->plat->display, EGL_NO_CONTEXT,
					      EGL_NATIVE_BUFFER_ANDROID, client_buf, attrs);
	if (img == EGL_NO_IMAGE_KHR)
		return false;
	glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex_id);
	p_glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, (GLeglImageOES)img);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	*out_image = img;
	return glGetError() == GL_NO_ERROR;
}

void panggung_egl_release_ahardwarebuffer_image(gs_device_t *device, EGLImageKHR image)
{
	if (device && device->plat && image != EGL_NO_IMAGE_KHR && p_eglDestroyImageKHR) {
		p_eglDestroyImageKHR(device->plat->display, image);
	}
}

bool device_query_sync_capabilities(gs_device_t *device)
{
	UNUSED_PARAMETER(device);
	gl_android_load_egl_exts();
	return p_eglCreateSyncKHR && p_eglDestroySyncKHR && p_eglClientWaitSyncKHR;
}

gs_sync_t *device_sync_create(gs_device_t *device)
{
	if (!device || !device->plat || !p_eglCreateSyncKHR)
		return NULL;
	EGLSyncKHR sync = p_eglCreateSyncKHR(device->plat->display, EGL_SYNC_FENCE_KHR, NULL);
	if (sync == EGL_NO_SYNC_KHR)
		return NULL;
	glFlush();
	return (gs_sync_t *)sync;
}

gs_sync_t *device_sync_create_from_syncobj_timeline_point(gs_device_t *device, int syncobj_fd, uint64_t timeline_point)
{
	UNUSED_PARAMETER(device); UNUSED_PARAMETER(syncobj_fd); UNUSED_PARAMETER(timeline_point);
	return NULL;
}

void device_sync_destroy(gs_device_t *device, gs_sync_t *sync)
{
	if (device && device->plat && sync && p_eglDestroySyncKHR)
		p_eglDestroySyncKHR(device->plat->display, (EGLSyncKHR)sync);
}

bool device_sync_export_syncobj_timeline_point(gs_device_t *device, gs_sync_t *sync, int syncobj_fd,
					       uint64_t timeline_point)
{
	UNUSED_PARAMETER(device); UNUSED_PARAMETER(sync); UNUSED_PARAMETER(syncobj_fd); UNUSED_PARAMETER(timeline_point);
	return false;
}

bool device_sync_signal_syncobj_timeline_point(gs_device_t *device, int syncobj_fd, uint64_t timeline_point)
{
	UNUSED_PARAMETER(device); UNUSED_PARAMETER(syncobj_fd); UNUSED_PARAMETER(timeline_point);
	return false;
}

bool device_sync_wait(gs_device_t *device, gs_sync_t *sync)
{
	if (!device || !device->plat || !sync || !p_eglClientWaitSyncKHR)
		return false;
	EGLint res = p_eglClientWaitSyncKHR(device->plat->display, (EGLSyncKHR)sync,
					    EGL_SYNC_FLUSH_COMMANDS_BIT_KHR, 1000000000ULL);
	return res == EGL_CONDITION_SATISFIED_KHR;
}
