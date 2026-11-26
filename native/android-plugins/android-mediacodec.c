/******************************************************************************
    Copyright (C) 2026 Panggung / OBS-Mobile Contributors
    GPL-2.0-or-later
******************************************************************************/

#include <obs.h>
#include <util/darray.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <string.h>
#include <stdlib.h>

#define COLOR_FormatYUV420SemiPlanar 21
#define COLOR_FormatYUVP010 54
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

static uint64_t g_mc_session_gen = 0;

struct mc_encoder {
	obs_encoder_t *encoder;
	AMediaCodec *codec;
	const char *mime;
	uint64_t session_gen;
	bool is_hdr10;
	bool use_p010;
	uint32_t width;
	uint32_t height;
	uint32_t fps_num;
	uint32_t fps_den;
	int32_t bitrate_bps;
	int32_t keyint_sec;

	DARRAY(uint8_t) extra_data;
	DARRAY(uint8_t) packet_buf;
};

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
	enc->encoder = encoder;
	enc->mime = mime;
	enc->is_hdr10 = hdr10;
	enc->session_gen = ++g_mc_session_gen;
	enc->width = obs_encoder_get_width(encoder);
	enc->height = obs_encoder_get_height(encoder);
	enc->fps_num = voi->fps_num ? voi->fps_num : 30;
	enc->fps_den = voi->fps_den ? voi->fps_den : 1;
	int64_t kbps = obs_data_get_int(settings, "bitrate");
	if (kbps <= 0) kbps = 4500;
	enc->bitrate_bps = (int32_t)(kbps * 1000);
	int64_t keyint = obs_data_get_int(settings, "keyint_sec");
	if (keyint <= 0) keyint = 2;
	enc->keyint_sec = (int32_t)keyint;

	/* Probe 10-bit P010 (for HDR10) + CBR + color metadata first; fall back cleanly to NV12/VBR.
	 * Recreate AMediaCodec per attempt because a failed configure() puts MediaCodec in Error state. */
	bool want_hdr = hdr10 || voi->colorspace == VIDEO_CS_2100_PQ || voi->colorspace == VIDEO_CS_2100_HLG;
	int rc_modes[3] = { BITRATE_MODE_CBR, BITRATE_MODE_CBR, BITRATE_MODE_VBR };
	int cf_modes[3] = {
		want_hdr ? COLOR_FormatYUVP010 : COLOR_FormatYUV420SemiPlanar,
		COLOR_FormatYUV420SemiPlanar,
		COLOR_FormatYUV420SemiPlanar
	};
	media_status_t st = AMEDIA_ERROR_UNKNOWN;
	enc->codec = NULL;
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

	if (!enc->codec || st != AMEDIA_OK) {
		blog(LOG_ERROR, "[mediacodec] AMediaCodec_configure failed (%d) for %s %ux%u", st, mime, enc->width, enc->height);
		bfree(enc);
		return NULL;
	}

	st = AMediaCodec_start(enc->codec);
	if (st != AMEDIA_OK) {
		blog(LOG_ERROR, "[mediacodec] AMediaCodec_start failed (%d)", st);
		AMediaCodec_delete(enc->codec);
		bfree(enc);
		return NULL;
	}

	blog(LOG_INFO, "[mediacodec] Started %s gen=%llu %ux%u @ %d kbps (hdr10=%d)",
	     mime, (unsigned long long)enc->session_gen, enc->width, enc->height, enc->bitrate_bps / 1000, hdr10 ? 1 : 0);
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
	if (enc->codec) {
		AMediaCodec_stop(enc->codec);
		AMediaCodec_delete(enc->codec);
	}
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

	/* 1. Feed NV12 input buffer */
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
				/* Copy Y plane (NV12 8-bit or P010 10-bit) */
				for (uint32_t row = 0; row < h; row++) {
					memcpy(buf + (size_t)row * row_bytes,
					       frame->data[0] + (size_t)row * frame->linesize[0], row_bytes);
				}
				/* Copy UV plane (interleaved) */
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

	/* 2. Drain output buffer (loop past CODEC_CONFIG / FORMAT_CHANGED) */
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
