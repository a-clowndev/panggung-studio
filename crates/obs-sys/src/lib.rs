#![allow(non_camel_case_types, non_snake_case, non_upper_case_globals, dead_code)]

use libc::{c_char, c_double, c_float, c_int, c_void};

// Opaque libobs types
#[repr(C)] pub struct obs_source_t { _private: [u8; 0] }
#[repr(C)] pub struct obs_scene_t { _private: [u8; 0] }
#[repr(C)] pub struct obs_sceneitem_t { _private: [u8; 0] }
#[repr(C)] pub struct obs_data_t { _private: [u8; 0] }
#[repr(C)] pub struct obs_data_array_t { _private: [u8; 0] }
#[repr(C)] pub struct obs_properties_t { _private: [u8; 0] }
#[repr(C)] pub struct obs_property_t { _private: [u8; 0] }
#[repr(C)] pub struct obs_output_t { _private: [u8; 0] }
#[repr(C)] pub struct obs_encoder_t { _private: [u8; 0] }
#[repr(C)] pub struct obs_service_t { _private: [u8; 0] }
#[repr(C)] pub struct obs_display_t { _private: [u8; 0] }
#[repr(C)] pub struct obs_view_t { _private: [u8; 0] }
#[repr(C)] pub struct obs_module_t { _private: [u8; 0] }
#[repr(C)] pub struct config_t { _private: [u8; 0] }

// Enums
pub const LOG_ERROR: c_int = 100;
pub const LOG_WARNING: c_int = 200;
pub const LOG_INFO: c_int = 300;
pub const LOG_DEBUG: c_int = 400;

pub const OBS_VIDEO_SUCCESS: c_int = 0;
pub const OBS_VIDEO_FAIL: c_int = -1;
pub const OBS_VIDEO_NOT_SUPPORTED: c_int = -2;
pub const OBS_VIDEO_INVALID_PARAM: c_int = -3;
pub const OBS_VIDEO_CURRENTLY_ACTIVE: c_int = -4;
pub const OBS_VIDEO_MODULE_NOT_FOUND: c_int = -5;

#[repr(C)]
#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub enum video_format {
    VIDEO_FORMAT_NONE = 0,
    VIDEO_FORMAT_I420,
    VIDEO_FORMAT_NV12,
    VIDEO_FORMAT_YVYU,
    VIDEO_FORMAT_YUY2,
    VIDEO_FORMAT_UYVY,
    VIDEO_FORMAT_RGBA,
    VIDEO_FORMAT_BGRA,
    VIDEO_FORMAT_BGRX,
    VIDEO_FORMAT_Y800,
    VIDEO_FORMAT_I444,
    VIDEO_FORMAT_BGR3,
    VIDEO_FORMAT_I422,
    VIDEO_FORMAT_I40A,
    VIDEO_FORMAT_I42A,
    VIDEO_FORMAT_YUVA,
    VIDEO_FORMAT_AYUV,
    VIDEO_FORMAT_I010,
    VIDEO_FORMAT_P010,
    VIDEO_FORMAT_I210,
    VIDEO_FORMAT_I412,
    VIDEO_FORMAT_YA2L,
    VIDEO_FORMAT_P216,
    VIDEO_FORMAT_P416,
    VIDEO_FORMAT_V210,
    VIDEO_FORMAT_R10L,
}

#[repr(C)]
#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub enum video_colorspace {
    VIDEO_CS_DEFAULT = 0,
    VIDEO_CS_601,
    VIDEO_CS_709,
    VIDEO_CS_SRGB,
    VIDEO_CS_2100_PQ,
    VIDEO_CS_2100_HLG,
}

#[repr(C)]
#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub enum video_range_type {
    VIDEO_RANGE_DEFAULT = 0,
    VIDEO_RANGE_PARTIAL,
    VIDEO_RANGE_FULL,
}

#[repr(C)]
#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub enum obs_scale_type {
    OBS_SCALE_DISABLE = 0,
    OBS_SCALE_POINT,
    OBS_SCALE_BICUBIC,
    OBS_SCALE_BILINEAR,
    OBS_SCALE_LANCZOS,
    OBS_SCALE_AREA,
}

#[repr(C)]
#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub enum speaker_layout {
    SPEAKERS_UNKNOWN = 0,
    SPEAKERS_MONO,
    SPEAKERS_STEREO,
    SPEAKERS_2POINT1,
    SPEAKERS_4POINT0,
    SPEAKERS_4POINT1,
    SPEAKERS_5POINT1,
    SPEAKERS_7POINT1 = 8,
}

#[repr(C)]
#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub enum gs_color_format {
    GS_UNKNOWN = 0,
    GS_A8,
    GS_R8,
    GS_RGBA,
    GS_BGRX,
    GS_BGRA,
    GS_R10G10B10A2,
    GS_RGBA16,
    GS_R16,
    GS_RGBA16F,
    GS_RGBA32F,
    GS_RG16F,
    GS_RG32F,
    GS_R8G8,
    GS_R16F,
    GS_R32F,
    GS_DXT1,
    GS_DXT3,
    GS_DXT5,
    GS_R8G8_UNORM,
    GS_RGBA_UNORM,
    GS_BGRX_UNORM,
    GS_BGRA_UNORM,
    GS_RG16,
}

#[repr(C)]
#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub enum gs_zstencil_format {
    GS_ZS_NONE = 0,
    GS_Z16,
    GS_Z24_S8,
    GS_Z32F,
    GS_Z32F_S8X24,
}

#[repr(C)]
#[derive(Copy, Clone)]
pub struct gs_window {
    pub id: u32,
    pub display: *mut c_void,
}

#[repr(C)]
#[derive(Copy, Clone)]
pub struct gs_init_data {
    pub window: gs_window,
    pub cx: u32,
    pub cy: u32,
    pub num_backbuffers: u32,
    pub format: gs_color_format,
    pub zsformat: gs_zstencil_format,
    pub adapter: u32,
}

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct obs_video_info {
    pub graphics_module: *const c_char,
    pub fps_num: u32,
    pub fps_den: u32,
    pub base_width: u32,
    pub base_height: u32,
    pub output_width: u32,
    pub output_height: u32,
    pub output_format: video_format,
    pub adapter: u32,
    pub gpu_conversion: bool,
    pub colorspace: video_colorspace,
    pub range: video_range_type,
    pub scale_type: obs_scale_type,
}

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct obs_audio_info {
    pub samples_per_sec: u32,
    pub speakers: speaker_layout,
}

#[repr(C)]
#[derive(Debug, Copy, Clone)]
pub struct vec2 {
    pub x: c_float,
    pub y: c_float,
}

pub type draw_callback_t = Option<unsafe extern "C" fn(param: *mut c_void, cx: u32, cy: u32)>;
pub type log_handler_t = Option<unsafe extern "C" fn(lvl: c_int, msg: *const c_char, args: *mut c_void, p: *mut c_void)>;

#[link(name = "obs")]
unsafe extern "C" {
    // Logging
    pub fn obs_android_install_logcat_handler();
    // Android-specific path hooks (defined in libobs/obs-android.c)
    pub fn obs_android_set_data_path(path: *const c_char);
    pub fn obs_android_set_module_path(path: *const c_char);

    // Core lifecycle
    pub fn obs_startup(
        locale: *const c_char,
        module_config_path: *const c_char,
        store: *mut c_void,
    ) -> bool;
    pub fn obs_shutdown();
    pub fn obs_initialized() -> bool;
    pub fn obs_get_version() -> u32;
    pub fn obs_get_version_string() -> *const c_char;

    pub fn obs_reset_video(ovi: *mut obs_video_info) -> c_int;
    pub fn obs_reset_audio(oai: *const obs_audio_info) -> bool;
    pub fn obs_get_video_info(ovi: *mut obs_video_info) -> bool;

    // Modules
    pub fn obs_add_module_path(bin: *const c_char, data: *const c_char);
    pub fn obs_open_module(module: *mut *mut obs_module_t, path: *const c_char, data_path: *const c_char) -> c_int;
    pub fn obs_init_module(module: *mut obs_module_t) -> bool;
    pub fn obs_get_module_name(module: *const obs_module_t) -> *const c_char;
    pub fn obs_get_module_binary_path(module: *const obs_module_t) -> *const c_char;
    pub fn obs_load_core_modules() -> bool;

    // Multi-Canvas (obs_view_t)
    pub fn obs_view_create() -> *mut obs_view_t;
    pub fn obs_view_destroy(view: *mut obs_view_t);
    pub fn obs_view_set_source(view: *mut obs_view_t, channel: u32, source: *mut obs_source_t);
    pub fn obs_view_add2(view: *mut obs_view_t, ovi: *mut obs_video_info) -> *mut c_void;
    pub fn obs_view_remove(view: *mut obs_view_t);
    pub fn obs_view_render(view: *mut obs_view_t);

    // Real-time Audio Volume Meter (obs_volmeter_t)
    pub fn obs_volmeter_create(fader_type: c_int) -> *mut c_void;
    pub fn obs_volmeter_destroy(volmeter: *mut c_void);
    pub fn obs_volmeter_attach_source(volmeter: *mut c_void, source: *mut obs_source_t) -> bool;
    pub fn obs_volmeter_detach_source(volmeter: *mut c_void);
    pub fn obs_volmeter_set_update_interval(volmeter: *mut c_void, ms: u32);
    pub fn obs_volmeter_add_callback(
        volmeter: *mut c_void,
        callback: Option<unsafe extern "C" fn(param: *mut c_void, magnitude: *const c_float, peak: *const c_float, input_peak: *const c_float)>,
        param: *mut c_void,
    );
    pub fn obs_load_all_modules();
    pub fn obs_post_load_modules();
    pub fn obs_log_loaded_modules();

    // Channels / Output sources
    pub fn obs_set_output_source(channel: u32, source: *mut obs_source_t);
    pub fn obs_get_output_source(channel: u32) -> *mut obs_source_t;

    // Scenes
    pub fn obs_scene_create(name: *const c_char) -> *mut obs_scene_t;
    pub fn obs_scene_release(scene: *mut obs_scene_t);
    pub fn obs_scene_get_source(scene: *const obs_scene_t) -> *mut obs_source_t;
    pub fn obs_scene_add(scene: *mut obs_scene_t, source: *mut obs_source_t) -> *mut obs_sceneitem_t;
    pub fn obs_scene_find_source(scene: *mut obs_scene_t, name: *const c_char) -> *mut obs_sceneitem_t;
    pub fn obs_sceneitem_remove(item: *mut obs_sceneitem_t);
    pub fn obs_sceneitem_set_pos(item: *mut obs_sceneitem_t, pos: *const vec2);
    pub fn obs_sceneitem_get_pos(item: *const obs_sceneitem_t, pos: *mut vec2);
    pub fn obs_sceneitem_set_scale(item: *mut obs_sceneitem_t, scale: *const vec2);
    pub fn obs_sceneitem_get_scale(item: *const obs_sceneitem_t, scale: *mut vec2);
    pub fn obs_sceneitem_get_rot(item: *const obs_sceneitem_t) -> c_float;
    pub fn obs_sceneitem_set_bounds_type(item: *mut obs_sceneitem_t, bounds_type: c_int);
    pub fn obs_sceneitem_get_bounds_type(item: *const obs_sceneitem_t) -> c_int;
    pub fn obs_sceneitem_set_bounds(item: *mut obs_sceneitem_t, bounds: *const vec2);
    pub fn obs_sceneitem_get_bounds(item: *const obs_sceneitem_t, bounds: *mut vec2);
    pub fn obs_sceneitem_set_alignment(item: *mut obs_sceneitem_t, alignment: u32);
    pub fn obs_sceneitem_get_alignment(item: *const obs_sceneitem_t) -> u32;
    pub fn obs_sceneitem_set_bounds_alignment(item: *mut obs_sceneitem_t, alignment: u32);
    pub fn obs_sceneitem_get_bounds_alignment(item: *const obs_sceneitem_t) -> u32;
    pub fn obs_source_media_play_pause(source: *mut obs_source_t, pause: bool);
    pub fn obs_source_media_restart(source: *mut obs_source_t);
    pub fn obs_source_set_name(source: *mut obs_source_t, name: *const c_char);
    pub fn obs_output_get_total_bytes(output: *const obs_output_t) -> u64;
    pub fn obs_output_get_frames_dropped(output: *const obs_output_t) -> c_int;
    pub fn obs_output_get_total_frames(output: *const obs_output_t) -> c_int;
    pub fn obs_output_get_congestion(output: *const obs_output_t) -> c_float;
    pub fn obs_sceneitem_set_visible(item: *mut obs_sceneitem_t, visible: bool) -> bool;
    pub fn obs_sceneitem_visible(item: *const obs_sceneitem_t) -> bool;
    pub fn obs_sceneitem_get_source(item: *const obs_sceneitem_t) -> *mut obs_source_t;
    pub fn obs_get_source_by_name(name: *const c_char) -> *mut obs_source_t;

    // Sources
    pub fn obs_source_create(
        id: *const c_char,
        name: *const c_char,
        settings: *mut obs_data_t,
        hotkey_data: *mut obs_data_t,
    ) -> *mut obs_source_t;
    pub fn obs_source_release(source: *mut obs_source_t);
    pub fn obs_source_get_name(source: *const obs_source_t) -> *const c_char;
    pub fn obs_source_get_id(source: *const obs_source_t) -> *const c_char;
    pub fn obs_source_update(source: *mut obs_source_t, settings: *mut obs_data_t);
    pub fn obs_source_get_settings(source: *const obs_source_t) -> *mut obs_data_t;
    pub fn obs_source_properties(source: *const obs_source_t) -> *mut obs_properties_t;
    pub fn obs_source_filter_add(source: *mut obs_source_t, filter: *mut obs_source_t);
    pub fn obs_source_filter_remove(source: *mut obs_source_t, filter: *mut obs_source_t);
    pub fn obs_source_get_filter_by_name(source: *mut obs_source_t, name: *const c_char) -> *mut obs_source_t;
    pub fn obs_source_set_enabled(source: *mut obs_source_t, enabled: bool);
    pub fn obs_source_enabled(source: *const obs_source_t) -> bool;
    pub fn obs_source_set_volume(source: *mut obs_source_t, volume: c_float);
    pub fn obs_source_get_volume(source: *const obs_source_t) -> c_float;
    pub fn obs_source_set_muted(source: *mut obs_source_t, muted: bool);
    pub fn obs_source_muted(source: *const obs_source_t) -> bool;
    pub fn obs_source_set_sync_offset(source: *mut obs_source_t, offset: i64);
    pub fn obs_source_get_sync_offset(source: *const obs_source_t) -> i64;
    pub fn obs_source_set_balance_value(source: *mut obs_source_t, balance: c_float);
    pub fn obs_source_get_balance_value(source: *const obs_source_t) -> c_float;
    pub fn obs_source_set_audio_mixers(source: *mut obs_source_t, mixers: u32);
    pub fn obs_source_get_audio_mixers(source: *const obs_source_t) -> u32;
    pub fn obs_source_set_monitoring_type(source: *mut obs_source_t, typ: c_int);
    pub fn obs_source_get_monitoring_type(source: *const obs_source_t) -> c_int;
    pub fn obs_sceneitem_set_locked(item: *mut obs_sceneitem_t, lock: bool) -> bool;
    pub fn obs_sceneitem_locked(item: *const obs_sceneitem_t) -> bool;
    pub fn obs_sceneitem_set_order(item: *mut obs_sceneitem_t, movement: c_int);

    // Transitions
    pub fn obs_transition_set(transition: *mut obs_source_t, source: *mut obs_source_t);
    pub fn obs_transition_clear(transition: *mut obs_source_t);
    pub fn obs_transition_start(
        transition: *mut obs_source_t,
        mode: c_int,
        duration_ms: u32,
        dest: *mut obs_source_t,
    ) -> bool;
    pub fn obs_transition_set_manual_time(transition: *mut obs_source_t, t: c_float);
    pub fn obs_sceneitem_set_rot(item: *mut obs_sceneitem_t, rot_deg: c_float);
    pub fn obs_save_source(source: *mut obs_source_t) -> *mut obs_data_t;
    pub fn obs_load_source(data: *mut obs_data_t) -> *mut obs_source_t;

    // Data
    pub fn obs_data_create() -> *mut obs_data_t;
    pub fn obs_data_create_from_json(json_string: *const c_char) -> *mut obs_data_t;
    pub fn obs_data_get_json(data: *mut obs_data_t) -> *const c_char;
    pub fn obs_data_release(data: *mut obs_data_t);
    pub fn obs_data_set_string(data: *mut obs_data_t, name: *const c_char, val: *const c_char);
    pub fn obs_data_set_int(data: *mut obs_data_t, name: *const c_char, val: i64);
    pub fn obs_data_set_double(data: *mut obs_data_t, name: *const c_char, val: c_double);
    pub fn obs_data_set_bool(data: *mut obs_data_t, name: *const c_char, val: bool);

    // Display (preview / projector)
    pub fn obs_display_create(graphics_data: *const gs_init_data, background_color: u32) -> *mut obs_display_t;
    pub fn obs_display_destroy(display: *mut obs_display_t);
    pub fn obs_display_resize(display: *mut obs_display_t, cx: u32, cy: u32);
    pub fn obs_display_add_draw_callback(display: *mut obs_display_t, draw: draw_callback_t, param: *mut c_void);
    pub fn obs_display_remove_draw_callback(display: *mut obs_display_t, draw: draw_callback_t, param: *mut c_void);
    pub fn obs_render_main_texture();

    // Outputs, Encoders, Services
    pub fn obs_output_create(
        id: *const c_char,
        name: *const c_char,
        settings: *mut obs_data_t,
        hotkey_data: *mut obs_data_t,
    ) -> *mut obs_output_t;
    pub fn obs_output_release(output: *mut obs_output_t);
    pub fn obs_output_start(output: *mut obs_output_t) -> bool;
    pub fn obs_output_stop(output: *mut obs_output_t);
    pub fn obs_output_active(output: *const obs_output_t) -> bool;
    pub fn obs_output_pause(output: *mut obs_output_t, pause: bool) -> bool;
    pub fn obs_output_paused(output: *const obs_output_t) -> bool;
    pub fn obs_output_get_proc_handler(output: *const obs_output_t) -> *mut c_void;
    pub fn proc_handler_call(handler: *mut c_void, name: *const c_char, params: *mut c_void) -> bool;
    pub fn obs_output_set_video_encoder(output: *mut obs_output_t, encoder: *mut obs_encoder_t);
    pub fn obs_output_set_audio_encoder(output: *mut obs_output_t, encoder: *mut obs_encoder_t, idx: usize);
    pub fn obs_output_set_service(output: *mut obs_output_t, service: *mut obs_service_t);

    pub fn obs_video_encoder_create(
        id: *const c_char,
        name: *const c_char,
        settings: *mut obs_data_t,
        hotkey_data: *mut obs_data_t,
    ) -> *mut obs_encoder_t;
    pub fn obs_audio_encoder_create(
        id: *const c_char,
        name: *const c_char,
        settings: *mut obs_data_t,
        mixer_idx: usize,
        hotkey_data: *mut obs_data_t,
    ) -> *mut obs_encoder_t;
    pub fn obs_encoder_release(encoder: *mut obs_encoder_t);
    pub fn obs_encoder_get_settings(encoder: *const obs_encoder_t) -> *mut obs_data_t;
    pub fn obs_encoder_update(encoder: *mut obs_encoder_t, settings: *mut obs_data_t);
    pub fn obs_encoder_set_video(encoder: *mut obs_encoder_t, video: *mut c_void);
    pub fn obs_encoder_set_audio(encoder: *mut obs_encoder_t, audio: *mut c_void);
    pub fn obs_get_video() -> *mut c_void;
    pub fn obs_get_audio() -> *mut c_void;

    pub fn obs_service_create(
        id: *const c_char,
        name: *const c_char,
        settings: *mut obs_data_t,
        hotkey_data: *mut obs_data_t,
    ) -> *mut obs_service_t;
    pub fn obs_service_release(service: *mut obs_service_t);

    // Stats
    pub fn obs_get_active_fps() -> c_double;
    pub fn obs_get_average_frame_time_ns() -> u64;
    pub fn obs_get_total_frames() -> u32;
    pub fn obs_get_lagged_frames() -> u32;

    // Media Remux (MKV/FLV/TS -> MP4)
    pub fn media_remux_job_create(job: *mut *mut c_void, in_filename: *const c_char, out_filename: *const c_char) -> bool;
    pub fn media_remux_job_process(job: *mut c_void, callback: *mut c_void, data: *mut c_void) -> bool;
    pub fn media_remux_job_destroy(job: *mut c_void);

    // Enumerators
    pub fn obs_enum_input_types(idx: usize, id: *mut *const c_char) -> bool;
    pub fn obs_enum_filter_types(idx: usize, id: *mut *const c_char) -> bool;
    pub fn obs_enum_transition_types(idx: usize, id: *mut *const c_char) -> bool;
    pub fn obs_enum_output_types(idx: usize, id: *mut *const c_char) -> bool;
    pub fn obs_enum_encoder_types(idx: usize, id: *mut *const c_char) -> bool;
    pub fn obs_enum_service_types(idx: usize, id: *mut *const c_char) -> bool;

    // Graphics helpers for draw callback
    pub fn gs_viewport_push();
    pub fn gs_viewport_pop();
    pub fn gs_projection_push();
    pub fn gs_projection_pop();
    pub fn gs_ortho(left: c_float, right: c_float, top: c_float, bottom: c_float, znear: c_float, zfar: c_float);
    pub fn gs_set_viewport(x: c_int, y: c_int, width: c_int, height: c_int);
    pub fn gs_reset_blend_state();
}

#[repr(C)]
#[derive(Copy, Clone, Default)]
pub struct obs_sceneitem_crop {
    pub left: c_int,
    pub top: c_int,
    pub right: c_int,
    pub bottom: c_int,
}

#[link(name = "obs")]
unsafe extern "C" {
    pub fn obs_sceneitem_set_crop(item: *mut obs_sceneitem_t, crop: *const obs_sceneitem_crop);
    pub fn obs_sceneitem_get_crop(item: *const obs_sceneitem_t, crop: *mut obs_sceneitem_crop);
    pub fn obs_source_get_width(source: *const obs_source_t) -> u32;
    pub fn obs_source_get_height(source: *const obs_source_t) -> u32;
}

#[link(name = "obs-android-plugins")]
unsafe extern "C" {
    pub fn panggung_register_android_plugins();
    pub fn panggung_push_camera_ahb(
        source_name: *const c_char,
        ahb_ptr: *mut c_void,
        width: u32,
        height: u32,
        timestamp_ns: u64,
    ) -> bool;
    pub fn panggung_push_screen_ahb(
        ahb_ptr: *mut c_void,
        width: u32,
        height: u32,
        timestamp_ns: u64,
    ) -> bool;
    pub fn panggung_push_camera_i420(
        y: *const u8,
        u: *const u8,
        v: *const u8,
        y_stride: u32,
        u_stride: u32,
        v_stride: u32,
        width: u32,
        height: u32,
        timestamp_ns: u64,
    );
    pub fn panggung_push_screen_rgba(
        rgba: *const u8,
        stride: u32,
        width: u32,
        height: u32,
        timestamp_ns: u64,
    );
    pub fn panggung_push_webview_rgba(
        rgba: *const u8,
        stride: u32,
        width: u32,
        height: u32,
    );
    pub fn panggung_push_source_rgba(
        source_name: *const c_char,
        rgba: *const u8,
        stride: u32,
        width: u32,
        height: u32,
    ) -> bool;
    pub fn panggung_serialize_source_properties(source_name: *const c_char) -> *mut c_char;
    pub fn panggung_list_source_filters_json(source_name: *const c_char) -> *mut c_char;
    pub fn panggung_free_string(s: *mut c_char);
    pub fn panggung_push_audio_input_s16(
        pcm: *const u8,
        frames: u32,
        sample_rate: u32,
        channels: u32,
        timestamp_ns: u64,
    );
    pub fn panggung_push_audio_output_s16(
        pcm: *const u8,
        frames: u32,
        sample_rate: u32,
        channels: u32,
        timestamp_ns: u64,
    );
}
