/******************************************************************************
    Copyright (C) 2026 Panggung / OBS-Mobile Contributors
    GPL-2.0-or-later
******************************************************************************/

#include <obs.h>
#include <util/threading.h>
#include <util/platform.h>
#include <util/dstr.h>
#include <string.h>
#include <stdlib.h>

struct android_source_slot {
	pthread_mutex_t lock;
	obs_source_t *source;
	bool color_matrix_ready;
	float color_matrix[16];
	float color_range_min[3];
	float color_range_max[3];
};

static struct android_source_slot g_camera = {PTHREAD_MUTEX_INITIALIZER, NULL, false};
static struct android_source_slot g_screen = {PTHREAD_MUTEX_INITIALIZER, NULL, false};
static struct android_source_slot g_webview = {PTHREAD_MUTEX_INITIALIZER, NULL, false};
static struct android_source_slot g_audio_in = {PTHREAD_MUTEX_INITIALIZER, NULL, false};
static struct android_source_slot g_audio_out = {PTHREAD_MUTEX_INITIALIZER, NULL, false};

static void ensure_color_matrix(struct android_source_slot *slot)
{
	if (!slot->color_matrix_ready) {
		video_format_get_parameters_for_format(
			VIDEO_CS_601, VIDEO_RANGE_FULL, VIDEO_FORMAT_I420,
			slot->color_matrix, slot->color_range_min, slot->color_range_max);
		slot->color_matrix_ready = true;
	}
}

/* ---- android_camera ---- */
static const char *cam_get_name(void *unused) { UNUSED_PARAMETER(unused); return "Android Camera"; }
static void *cam_create(obs_data_t *settings, obs_source_t *source)
{
	UNUSED_PARAMETER(settings);
	pthread_mutex_lock(&g_camera.lock);
	g_camera.source = source;
	ensure_color_matrix(&g_camera);
	pthread_mutex_unlock(&g_camera.lock);
	return &g_camera;
}
static void cam_destroy(void *data)
{
	struct android_source_slot *slot = (struct android_source_slot *)data;
	pthread_mutex_lock(&slot->lock);
	slot->source = NULL;
	pthread_mutex_unlock(&slot->lock);
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
	.output_flags = OBS_SOURCE_ASYNC_VIDEO | OBS_SOURCE_DO_NOT_DUPLICATE,
	.get_name = cam_get_name,
	.create = cam_create,
	.destroy = cam_destroy,
	.get_properties = cam_properties,
};

/* ---- android_screen ---- */
static const char *scr_get_name(void *unused) { UNUSED_PARAMETER(unused); return "Android Screen Capture"; }
static void *scr_create(obs_data_t *settings, obs_source_t *source)
{
	UNUSED_PARAMETER(settings);
	pthread_mutex_lock(&g_screen.lock);
	g_screen.source = source;
	pthread_mutex_unlock(&g_screen.lock);
	return &g_screen;
}
static void scr_destroy(void *data)
{
	struct android_source_slot *slot = (struct android_source_slot *)data;
	pthread_mutex_lock(&slot->lock);
	slot->source = NULL;
	pthread_mutex_unlock(&slot->lock);
}
static struct obs_source_info android_screen_info = {
	.id = "android_screen",
	.type = OBS_SOURCE_TYPE_INPUT,
	.output_flags = OBS_SOURCE_ASYNC_VIDEO | OBS_SOURCE_DO_NOT_DUPLICATE,
	.get_name = scr_get_name,
	.create = scr_create,
	.destroy = scr_destroy,
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

/* ---- Exported push APIs for Rust / JNI ---- */

EXPORT void panggung_push_camera_i420(const uint8_t *y, const uint8_t *u, const uint8_t *v,
				      uint32_t y_stride, uint32_t u_stride, uint32_t v_stride,
				      uint32_t width, uint32_t height, uint64_t timestamp_ns)
{
	UNUSED_PARAMETER(timestamp_ns);
	pthread_mutex_lock(&g_camera.lock);
	if (g_camera.source) {
		struct obs_source_frame frame = {0};
		frame.data[0] = (uint8_t *)y;
		frame.data[1] = (uint8_t *)u;
		frame.data[2] = (uint8_t *)v;
		frame.linesize[0] = y_stride;
		frame.linesize[1] = u_stride;
		frame.linesize[2] = v_stride;
		frame.width = width;
		frame.height = height;
		frame.format = VIDEO_FORMAT_I420;
		frame.timestamp = os_gettime_ns();
		memcpy(frame.color_matrix, g_camera.color_matrix, sizeof(frame.color_matrix));
		memcpy(frame.color_range_min, g_camera.color_range_min, sizeof(frame.color_range_min));
		memcpy(frame.color_range_max, g_camera.color_range_max, sizeof(frame.color_range_max));
		obs_source_output_video(g_camera.source, &frame);
		static uint64_t cam_frames = 0;
		if ((++cam_frames) == 1 || (cam_frames % 180) == 0)
			blog(LOG_INFO, "[android-camera] pushed frame #%llu (%ux%u)", (unsigned long long)cam_frames, width, height);
	}
	pthread_mutex_unlock(&g_camera.lock);
}

EXPORT void panggung_push_screen_rgba(const uint8_t *rgba, uint32_t stride,
				      uint32_t width, uint32_t height, uint64_t timestamp_ns)
{
	UNUSED_PARAMETER(timestamp_ns);
	pthread_mutex_lock(&g_screen.lock);
	if (g_screen.source) {
		struct obs_source_frame frame = {0};
		frame.data[0] = (uint8_t *)rgba;
		frame.linesize[0] = stride;
		frame.width = width;
		frame.height = height;
		frame.format = VIDEO_FORMAT_RGBA;
		frame.timestamp = os_gettime_ns();
		obs_source_output_video(g_screen.source, &frame);
	}
	pthread_mutex_unlock(&g_screen.lock);
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

/* ---- Generic obs_properties -> JSON serializer (powers Auto-UI for ALL sources/filters/plugins) ---- */

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
	blog(LOG_INFO, "[android-plugins] Registered android_camera, android_screen, android_webview, android_audio_input, android_audio_output, mediacodec_h264/hevc");
}
