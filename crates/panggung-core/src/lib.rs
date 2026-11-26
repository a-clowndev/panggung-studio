mod crash_handler;
mod lua_engine;
mod obs_parity;
mod studio_ops;
mod web_remote;
mod ws_server;

use std::ffi::{CStr, CString};
use std::ptr;
use std::sync::Mutex;

use jni::JNIEnv;
use jni::objects::{JByteBuffer, JClass, JObject, JString};
use jni::sys::{jboolean, jfloat, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use log::{error, info};
use obs::{ObsContext, ObsData, sys};
use serde::Serialize;

#[link(name = "android")]
unsafe extern "C" {
    fn ANativeWindow_fromSurface(env: *mut jni::sys::JNIEnv, surface: jni::sys::jobject) -> *mut libc::c_void;
    fn ANativeWindow_release(window: *mut libc::c_void);
}

const OBS_BOUNDS_SCALE_INNER: i32 = 2;
const OBS_TRANSITION_MODE_AUTO: i32 = 0;
const OBS_TRANSITION_MODE_MANUAL: i32 = 1;

static AUDIO_LEVELS: Mutex<Option<std::collections::HashMap<String, (f32, f32)>>> = Mutex::new(None);

unsafe extern "C" fn volmeter_cb(
    param: *mut libc::c_void,
    magnitude: *const libc::c_float,
    peak: *const libc::c_float,
    _input_peak: *const libc::c_float,
) {
    if param.is_null() || magnitude.is_null() || peak.is_null() {
        return;
    }
    let name = unsafe { &*(param as *const String) };
    let mag = unsafe { *magnitude }.clamp(-60.0, 0.0);
    let pk = unsafe { *peak }.clamp(-60.0, 0.0);
    if let Ok(mut g) = AUDIO_LEVELS.try_lock() {
        g.get_or_insert_with(std::collections::HashMap::new)
            .insert(name.clone(), (mag, pk));
    }
}

fn attach_volmeter_to_source(src: *mut sys::obs_source_t, name: &str) {
    unsafe {
        let vm = sys::obs_volmeter_create(0); // OBS_FADER_CUBIC
        if vm.is_null() {
            return;
        }
        let boxed = Box::into_raw(Box::new(name.to_string()));
        sys::obs_volmeter_add_callback(vm, Some(volmeter_cb), boxed as *mut libc::c_void);
        if !sys::obs_volmeter_attach_source(vm, src) {
            sys::obs_volmeter_destroy(vm);
            let _ = Box::from_raw(boxed);
        }
    }
}

struct OutputBundle {
    output: *mut sys::obs_output_t,
    v_enc: *mut sys::obs_encoder_t,
    a_enc: *mut sys::obs_encoder_t,
    service: *mut sys::obs_service_t,
}

impl OutputBundle {
    unsafe fn stop_and_release(self) {
        unsafe {
            if !self.output.is_null() {
                if sys::obs_output_active(self.output) {
                    sys::obs_output_stop(self.output);
                    // Wait for async libobs output/encoder threads to finish draining before release
                    for _ in 0..100 {
                        if !sys::obs_output_active(self.output) {
                            break;
                        }
                        std::thread::sleep(std::time::Duration::from_millis(20));
                    }
                }
                sys::obs_output_release(self.output);
            }
            if !self.v_enc.is_null() { sys::obs_encoder_release(self.v_enc); }
            if !self.a_enc.is_null() { sys::obs_encoder_release(self.a_enc); }
            if !self.service.is_null() { sys::obs_service_release(self.service); }
        }
    }
}

struct SceneEntry {
    name: String,
    scene: *mut sys::obs_scene_t,
    sources: Vec<String>,
}

struct AppState {
    ctx: ObsContext,
    display: *mut sys::obs_display_t,
    native_window: *mut libc::c_void,
    scenes: Vec<SceneEntry>,
    active_scene_idx: usize,
    preview_scene_idx: usize,
    studio_mode: bool,
    transition: *mut sys::obs_source_t,
    transition_id: String,
    transition_ms: u32,
    base_width: u32,
    base_height: u32,
    fps: u32,
    config_dir: String,
    last_record_path: String,
    video_encoder_id: String,
    recording: Option<OutputBundle>,
    streaming: Option<OutputBundle>,
    replay: Option<OutputBundle>,
    multistreams: Vec<OutputBundle>,
    custom_output: Option<OutputBundle>,
    projector_display: *mut sys::obs_display_t,
    projector_window: *mut libc::c_void,
    vertical_view: *mut sys::obs_view_t,
    last_crash_report: Option<String>,
}

unsafe impl Send for AppState {}

static STATE: Mutex<Option<AppState>> = Mutex::new(None);

unsafe extern "C" fn preview_draw_cb(_param: *mut libc::c_void, cx: u32, cy: u32) {
    let mut ovi: sys::obs_video_info = unsafe { std::mem::zeroed() };
    let (base_w, base_h) = if unsafe { sys::obs_get_video_info(&mut ovi) } && ovi.base_width > 0 && ovi.base_height > 0 {
        (ovi.base_width, ovi.base_height)
    } else {
        (1280u32, 720u32)
    };
    if cx == 0 || cy == 0 {
        return;
    }

    let base_aspect = base_w as f32 / base_h as f32;
    let disp_aspect = cx as f32 / cy as f32;
    let (vw, vh) = if disp_aspect > base_aspect {
        ((cy as f32 * base_aspect) as i32, cy as i32)
    } else {
        (cx as i32, (cx as f32 / base_aspect) as i32)
    };
    let vx = (cx as i32 - vw) / 2;
    let vy = (cy as i32 - vh) / 2;

    unsafe {
        sys::gs_viewport_push();
        sys::gs_projection_push();
        sys::gs_set_viewport(vx, vy, vw, vh);
        sys::gs_ortho(0.0, base_w as f32, 0.0, base_h as f32, -100.0, 100.0);
        sys::gs_reset_blend_state();
        sys::obs_render_main_texture();
        sys::gs_projection_pop();
        sys::gs_viewport_pop();
    }
}

fn add_source_to_scene(
    scene: *mut sys::obs_scene_t,
    id: &str,
    name: &str,
    settings_json: Option<&str>,
    base_w: u32,
    base_h: u32,
) -> bool {
    let Ok(c_id) = CString::new(id) else { return false; };
    let Ok(c_name) = CString::new(name) else { return false; };
    unsafe {
        let settings = match settings_json {
            Some(j) if !j.is_empty() => {
                if let Ok(cj) = CString::new(j) {
                    sys::obs_data_create_from_json(cj.as_ptr())
                } else {
                    sys::obs_data_create()
                }
            }
            _ => sys::obs_data_create(),
        };
        let src = sys::obs_source_create(c_id.as_ptr(), c_name.as_ptr(), settings, ptr::null_mut());
        sys::obs_data_release(settings);
        if src.is_null() {
            error!("obs_source_create failed for id={id}, name={name}");
            return false;
        }
        let item = sys::obs_scene_add(scene, src);
        if !item.is_null() && !id.contains("audio") {
            let center = sys::vec2 { x: base_w as f32 * 0.5, y: base_h as f32 * 0.5 };
            let bounds = sys::vec2 { x: base_w as f32, y: base_h as f32 };
            sys::obs_sceneitem_set_alignment(item, 0); // OBS_ALIGN_CENTER
            sys::obs_sceneitem_set_bounds_alignment(item, 0); // OBS_ALIGN_CENTER
            sys::obs_sceneitem_set_pos(item, &center);
            sys::obs_sceneitem_set_bounds_type(item, OBS_BOUNDS_SCALE_INNER);
            sys::obs_sceneitem_set_bounds(item, &bounds);
        }
        if id.contains("audio") || id == "ffmpeg_source" || id == "android_webview" {
            attach_volmeter_to_source(src, name);
        }
        sys::obs_source_release(src);
        !item.is_null()
    }
}

fn switch_program_scene(state: &mut AppState, idx: usize) -> bool {
    if idx >= state.scenes.len() { return false; }
    state.active_scene_idx = idx;
    let target_src = unsafe { sys::obs_scene_get_source(state.scenes[idx].scene) };
    unsafe {
        if !state.transition.is_null() {
            sys::obs_transition_start(state.transition, OBS_TRANSITION_MODE_AUTO, state.transition_ms, target_src);
        } else {
            sys::obs_set_output_source(0, target_src);
        }
    }
    true
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    locale: JString,
    data_dir: JString,
    module_dir: JString,
    config_dir: JString,
    width: jint,
    height: jint,
    fps: jint,
) -> jboolean {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("PanggungCore"),
    );
    unsafe { sys::obs_android_install_logcat_handler(); }

    let locale: String = env.get_string(&locale).map(|s| s.into()).unwrap_or_else(|_| "en-US".into());
    let Ok(data_dir): Result<String, _> = env.get_string(&data_dir).map(|s| s.into()) else { return JNI_FALSE; };
    let Ok(module_dir): Result<String, _> = env.get_string(&module_dir).map(|s| s.into()) else { return JNI_FALSE; };
    let Ok(config_dir): Result<String, _> = env.get_string(&config_dir).map(|s| s.into()) else { return JNI_FALSE; };

    let last_crash_report = crash_handler::read_and_clear_last_crash(&config_dir);
    crash_handler::install_crash_handler(&config_dir);

    info!("Initializing libobs {} ({}x{}@{}fps)", ObsContext::version_string(), width, height, fps);

    match ObsContext::init(&locale, &data_dir, &module_dir, &config_dir) {
        Ok(ctx) => {
            if let Err(e) = ctx.reset_video(width as u32, height as u32, fps as u32, 1) {
                error!("reset_video failed: {e}");
                return JNI_FALSE;
            }
            if let Err(e) = ctx.reset_audio(48000) {
                error!("reset_audio failed: {e}");
                return JNI_FALSE;
            }
            ctx.load_all_modules();
            unsafe { sys::panggung_register_android_plugins(); }
            let pack_so = format!("{module_dir}/libobs-mod-panggung-3p-pack.so");
            let _ = ws_load_plugin_so(&pack_so, "");
            info!("Loaded input types: {:?}", ctx.list_input_types());

            let bw = width as u32;
            let bh = height as u32;
            let scene_name = "Main Scene".to_string();
            let c_scene = CString::new(scene_name.as_str()).unwrap();
            let (scene, transition) = unsafe {
                let sc = sys::obs_scene_create(c_scene.as_ptr());
                let t_id = CString::new("fade_transition").unwrap();
                let t_name = CString::new("Fade").unwrap();
                let tr = sys::obs_source_create(t_id.as_ptr(), t_name.as_ptr(), ptr::null_mut(), ptr::null_mut());
                if !tr.is_null() {
                    sys::obs_transition_set(tr, sys::obs_scene_get_source(sc));
                    sys::obs_set_output_source(0, tr);
                } else {
                    sys::obs_set_output_source(0, sys::obs_scene_get_source(sc));
                }
                (sc, tr)
            };

            let mut sources = Vec::new();
            let bg_json = format!(r#"{{"color":4293616421,"width":{bw},"height":{bh}}}"#);
            if add_source_to_scene(scene, "color_source", "Background", Some(&bg_json), bw, bh) {
                sources.push("Background".to_string());
            }
            if add_source_to_scene(scene, "android_audio_input", "Microphone", None, bw, bh) {
                sources.push("Microphone".to_string());
            }

            *STATE.lock().unwrap() = Some(AppState {
                ctx,
                display: ptr::null_mut(),
                native_window: ptr::null_mut(),
                scenes: vec![SceneEntry { name: scene_name, scene, sources }],
                active_scene_idx: 0,
                preview_scene_idx: 0,
                studio_mode: false,
                transition,
                transition_id: "fade_transition".into(),
                transition_ms: 300,
                base_width: bw,
                base_height: bh,
                fps: fps as u32,
                config_dir,
                last_record_path: String::new(),
                video_encoder_id: "android_mediacodec_h264".into(),
                recording: None,
                streaming: None,
                replay: None,
                multistreams: Vec::new(),
                custom_output: None,
                projector_display: ptr::null_mut(),
                projector_window: ptr::null_mut(),
                vertical_view: ptr::null_mut(),
                last_crash_report,
            });
            ws_server::spawn_obs_websocket_server(4455);
            web_remote::spawn_web_remote_server(8080);
            JNI_TRUE
        }
        Err(e) => {
            error!("obs_startup failed: {e}");
            JNI_FALSE
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeAttachSurface(
    env: JNIEnv,
    _class: JClass,
    surface: JObject,
    width: jint,
    height: jint,
) -> jboolean {
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return JNI_FALSE; };
    unsafe {
        if !state.display.is_null() {
            sys::obs_display_destroy(state.display);
            state.display = ptr::null_mut();
        }
        if !state.native_window.is_null() {
            ANativeWindow_release(state.native_window);
            state.native_window = ptr::null_mut();
        }
        let win = ANativeWindow_fromSurface(env.get_raw(), surface.as_raw());
        if win.is_null() { return JNI_FALSE; }
        state.native_window = win;

        let init = sys::gs_init_data {
            window: sys::gs_window { id: 0, display: win },
            cx: width as u32,
            cy: height as u32,
            num_backbuffers: 2,
            format: sys::gs_color_format::GS_RGBA,
            zsformat: sys::gs_zstencil_format::GS_ZS_NONE,
            adapter: 0,
        };
        let disp = sys::obs_display_create(&init, 0xFF18181B);
        if disp.is_null() { return JNI_FALSE; }
        state.display = disp;
        let dims = Box::into_raw(Box::new((state.base_width, state.base_height)));
        sys::obs_display_add_draw_callback(disp, Some(preview_draw_cb), dims as *mut libc::c_void);
    }
    JNI_TRUE
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeResizeSurface(
    _env: JNIEnv,
    _class: JClass,
    width: jint,
    height: jint,
) {
    let guard = STATE.lock().unwrap();
    if let Some(state) = guard.as_ref() {
        if !state.display.is_null() {
            unsafe { sys::obs_display_resize(state.display, width as u32, height as u32) };
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeDetachSurface(
    _env: JNIEnv,
    _class: JClass,
) {
    let mut guard = STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        unsafe {
            if !state.display.is_null() {
                sys::obs_display_destroy(state.display);
                state.display = ptr::null_mut();
            }
            if !state.native_window.is_null() {
                ANativeWindow_release(state.native_window);
                state.native_window = ptr::null_mut();
            }
        }
    }
}

/* ---- DirectByteBuffer frame & audio pushers ---- */

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativePushCameraI420(
    env: JNIEnv, _class: JClass,
    y_buf: JByteBuffer, u_buf: JByteBuffer, v_buf: JByteBuffer,
    y_stride: jint, u_stride: jint, v_stride: jint,
    width: jint, height: jint, timestamp_ns: jlong,
) {
    let (Ok(yp), Ok(up), Ok(vp)) = (
        env.get_direct_buffer_address(&y_buf),
        env.get_direct_buffer_address(&u_buf),
        env.get_direct_buffer_address(&v_buf),
    ) else { return; };
    unsafe {
        sys::panggung_push_camera_i420(
            yp, up, vp, y_stride as u32, u_stride as u32, v_stride as u32,
            width as u32, height as u32, timestamp_ns as u64,
        );
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativePushScreenRgba(
    env: JNIEnv, _class: JClass, rgba_buf: JByteBuffer,
    stride: jint, width: jint, height: jint, timestamp_ns: jlong,
) {
    let Ok(ptr) = env.get_direct_buffer_address(&rgba_buf) else { return; };
    unsafe { sys::panggung_push_screen_rgba(ptr, stride as u32, width as u32, height as u32, timestamp_ns as u64); }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativePushWebViewRgba(
    env: JNIEnv, _class: JClass, rgba_buf: JByteBuffer,
    stride: jint, width: jint, height: jint,
) {
    let Ok(ptr) = env.get_direct_buffer_address(&rgba_buf) else { return; };
    unsafe { sys::panggung_push_webview_rgba(ptr, stride as u32, width as u32, height as u32); }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativePushAudioInputS16(
    env: JNIEnv, _class: JClass, pcm_buf: JByteBuffer,
    frames: jint, sample_rate: jint, channels: jint, timestamp_ns: jlong,
) {
    let Ok(ptr) = env.get_direct_buffer_address(&pcm_buf) else { return; };
    unsafe { sys::panggung_push_audio_input_s16(ptr, frames as u32, sample_rate as u32, channels as u32, timestamp_ns as u64); }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativePushAudioOutputS16(
    env: JNIEnv, _class: JClass, pcm_buf: JByteBuffer,
    frames: jint, sample_rate: jint, channels: jint, timestamp_ns: jlong,
) {
    let Ok(ptr) = env.get_direct_buffer_address(&pcm_buf) else { return; };
    unsafe { sys::panggung_push_audio_output_s16(ptr, frames as u32, sample_rate as u32, channels as u32, timestamp_ns as u64); }
}

/* ---- Scene, Transition, Studio Mode, Source, Filter, Properties ---- */

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeCreateScene(
    mut env: JNIEnv, _class: JClass, name: JString,
) -> jboolean {
    let Ok(name): Result<String, _> = env.get_string(&name).map(|s| s.into()) else { return JNI_FALSE; };
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return JNI_FALSE; };
    let Ok(c_name) = CString::new(name.as_str()) else { return JNI_FALSE; };
    let sc = unsafe { sys::obs_scene_create(c_name.as_ptr()) };
    if sc.is_null() { return JNI_FALSE; }
    state.scenes.push(SceneEntry { name, scene: sc, sources: Vec::new() });
    JNI_TRUE
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeSwitchScene(
    _env: JNIEnv, _class: JClass, idx: jint,
) -> jboolean {
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return JNI_FALSE; };
    let i = idx as usize;
    if i >= state.scenes.len() { return JNI_FALSE; }
    if state.studio_mode {
        state.preview_scene_idx = i;
        JNI_TRUE
    } else {
        if switch_program_scene(state, i) { JNI_TRUE } else { JNI_FALSE }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeSetStudioMode(
    _env: JNIEnv, _class: JClass, enabled: jboolean,
) {
    let mut guard = STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        state.studio_mode = enabled != JNI_FALSE;
        if state.studio_mode {
            state.preview_scene_idx = state.active_scene_idx;
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeTransitionToProgram(
    _env: JNIEnv, _class: JClass,
) -> jboolean {
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return JNI_FALSE; };
    let target = state.preview_scene_idx;
    if switch_program_scene(state, target) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeSetTransition(
    mut env: JNIEnv, _class: JClass, transition_id: JString, duration_ms: jint,
) -> jboolean {
    let Ok(tid): Result<String, _> = env.get_string(&transition_id).map(|s| s.into()) else { return JNI_FALSE; };
    if ws_set_transition(&tid, duration_ms.max(50) as u32) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeAddSource(
    mut env: JNIEnv, _class: JClass, source_id: JString, name: JString, settings_json: JString,
) -> jboolean {
    let Ok(id): Result<String, _> = env.get_string(&source_id).map(|s| s.into()) else { return JNI_FALSE; };
    let Ok(name): Result<String, _> = env.get_string(&name).map(|s| s.into()) else { return JNI_FALSE; };
    let json: String = env.get_string(&settings_json).map(|s| s.into()).unwrap_or_default();

    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return JNI_FALSE; };
    let bw = state.base_width;
    let bh = state.base_height;
    let idx = if state.studio_mode { state.preview_scene_idx } else { state.active_scene_idx };
    let sc = &mut state.scenes[idx];
    let ok = add_source_to_scene(sc.scene, &id, &name, if json.is_empty() { None } else { Some(&json) }, bw, bh);
    if ok {
        sc.sources.push(name);
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeSetSourceVisible(
    mut env: JNIEnv, _class: JClass, name: JString, visible: jboolean,
) {
    let Ok(name): Result<String, _> = env.get_string(&name).map(|s| s.into()) else { return; };
    let Ok(c_name) = CString::new(name) else { return; };
    let guard = STATE.lock().unwrap();
    let Some(state) = guard.as_ref() else { return; };
    let idx = if state.studio_mode { state.preview_scene_idx } else { state.active_scene_idx };
    let sc = state.scenes[idx].scene;
    unsafe {
        let item = sys::obs_scene_find_source(sc, c_name.as_ptr());
        if !item.is_null() {
            sys::obs_sceneitem_set_visible(item, visible != JNI_FALSE);
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeSetSourceTransform(
    mut env: JNIEnv, _class: JClass, name: JString,
    x: jfloat, y: jfloat, scale_x: jfloat, scale_y: jfloat, rot_deg: jfloat,
) {
    let Ok(name): Result<String, _> = env.get_string(&name).map(|s| s.into()) else { return; };
    let Ok(c_name) = CString::new(name) else { return; };
    let guard = STATE.lock().unwrap();
    let Some(state) = guard.as_ref() else { return; };
    let idx = if state.studio_mode { state.preview_scene_idx } else { state.active_scene_idx };
    let sc = state.scenes[idx].scene;
    unsafe {
        let item = sys::obs_scene_find_source(sc, c_name.as_ptr());
        if !item.is_null() {
            let pos = sys::vec2 { x, y };
            let scale = sys::vec2 { x: scale_x, y: scale_y };
            let bounds = sys::vec2 {
                x: (state.base_width as f32 * scale_x.abs()).max(32.0),
                y: (state.base_height as f32 * scale_y.abs()).max(32.0),
            };
            sys::obs_sceneitem_set_alignment(item, 0); // Center pivot for smooth rotation
            sys::obs_sceneitem_set_bounds_alignment(item, 0);
            sys::obs_sceneitem_set_pos(item, &pos);
            sys::obs_sceneitem_set_scale(item, &scale);
            sys::obs_sceneitem_set_bounds_type(item, OBS_BOUNDS_SCALE_INNER);
            sys::obs_sceneitem_set_bounds(item, &bounds);
            sys::obs_sceneitem_set_rot(item, rot_deg);
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeRemoveSource(
    mut env: JNIEnv, _class: JClass, name: JString,
) {
    let Ok(name): Result<String, _> = env.get_string(&name).map(|s| s.into()) else { return; };
    let Ok(c_name) = CString::new(name.as_str()) else { return; };
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return; };
    let idx = if state.studio_mode { state.preview_scene_idx } else { state.active_scene_idx };
    let sc = &mut state.scenes[idx];
    unsafe {
        let item = sys::obs_scene_find_source(sc.scene, c_name.as_ptr());
        if !item.is_null() { sys::obs_sceneitem_remove(item); }
    }
    sc.sources.retain(|s| s != &name);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeSetSourceVolume(
    mut env: JNIEnv, _class: JClass, name: JString, volume: jfloat,
) {
    let Ok(name): Result<String, _> = env.get_string(&name).map(|s| s.into()) else { return; };
    ws_set_volume(&name, volume);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeSetSourceMuted(
    mut env: JNIEnv, _class: JClass, name: JString, muted: jboolean,
) {
    let Ok(name): Result<String, _> = env.get_string(&name).map(|s| s.into()) else { return; };
    ws_set_muted(&name, muted != JNI_FALSE);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeGetSourcePropertiesJson(
    mut env: JNIEnv, _class: JClass, name: JString,
) -> jstring {
    let Ok(name): Result<String, _> = env.get_string(&name).map(|s| s.into()) else {
        return env.new_string("{}").map(|s| s.into_raw()).unwrap_or(ptr::null_mut());
    };
    let json = ws_get_source_properties_json(&name);
    env.new_string(json).map(|s| s.into_raw()).unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeUpdateSourceSettings(
    mut env: JNIEnv, _class: JClass, name: JString, settings_json: JString,
) -> jboolean {
    let Ok(name): Result<String, _> = env.get_string(&name).map(|s| s.into()) else { return JNI_FALSE; };
    let Ok(json): Result<String, _> = env.get_string(&settings_json).map(|s| s.into()) else { return JNI_FALSE; };
    if ws_update_source_settings(&name, &json) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeListFiltersJson(
    mut env: JNIEnv, _class: JClass, source_name: JString,
) -> jstring {
    let Ok(name): Result<String, _> = env.get_string(&source_name).map(|s| s.into()) else {
        return env.new_string("[]").map(|s| s.into_raw()).unwrap_or(ptr::null_mut());
    };
    let json = ws_list_filters_json(&name);
    env.new_string(json).map(|s| s.into_raw()).unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeAddFilter(
    mut env: JNIEnv, _class: JClass, source_name: JString, filter_id: JString, filter_name: JString, settings_json: JString,
) -> jboolean {
    let Ok(sname): Result<String, _> = env.get_string(&source_name).map(|s| s.into()) else { return JNI_FALSE; };
    let Ok(fid): Result<String, _> = env.get_string(&filter_id).map(|s| s.into()) else { return JNI_FALSE; };
    let Ok(fname): Result<String, _> = env.get_string(&filter_name).map(|s| s.into()) else { return JNI_FALSE; };
    let json: String = env.get_string(&settings_json).map(|s| s.into()).unwrap_or_default();
    if ws_add_filter(&sname, &fid, &fname, &json) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeRemoveFilter(
    mut env: JNIEnv, _class: JClass, source_name: JString, filter_name: JString,
) -> jboolean {
    let Ok(sname): Result<String, _> = env.get_string(&source_name).map(|s| s.into()) else { return JNI_FALSE; };
    let Ok(fname): Result<String, _> = env.get_string(&filter_name).map(|s| s.into()) else { return JNI_FALSE; };
    if ws_remove_filter(&sname, &fname) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeSetFilterEnabled(
    mut env: JNIEnv, _class: JClass, source_name: JString, filter_name: JString, enabled: jboolean,
) {
    let Ok(sname): Result<String, _> = env.get_string(&source_name).map(|s| s.into()) else { return; };
    let Ok(fname): Result<String, _> = env.get_string(&filter_name).map(|s| s.into()) else { return; };
    if let (Ok(cs), Ok(cf)) = (CString::new(sname), CString::new(fname)) {
        unsafe {
            let src = sys::obs_get_source_by_name(cs.as_ptr());
            if !src.is_null() {
                let flt = sys::obs_source_get_filter_by_name(src, cf.as_ptr());
                if !flt.is_null() {
                    sys::obs_source_set_enabled(flt, enabled != JNI_FALSE);
                    sys::obs_source_release(flt);
                }
                sys::obs_source_release(src);
            }
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeSetVideoEncoder(
    mut env: JNIEnv, _class: JClass, encoder_id: JString,
) {
    if let Ok(eid) = env.get_string(&encoder_id) {
        let mut guard = STATE.lock().unwrap();
        if let Some(state) = guard.as_mut() {
            state.video_encoder_id = eid.into();
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeResetVideo(
    _env: JNIEnv, _class: JClass, width: jint, height: jint, fps: jint,
) -> jboolean {
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return JNI_FALSE; };
    if state.ctx.reset_video(width as u32, height as u32, fps as u32, 1).is_ok() {
        state.base_width = width as u32;
        state.base_height = height as u32;
        state.fps = fps as u32;
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeSaveSceneCollection(
    mut env: JNIEnv, _class: JClass, path: JString,
) -> jboolean {
    let Ok(path): Result<String, _> = env.get_string(&path).map(|s| s.into()) else { return JNI_FALSE; };
    let snap = ws_get_snapshot();
    match serde_json::to_string_pretty(&snap) {
        Ok(s) => if std::fs::write(&path, s).is_ok() { JNI_TRUE } else { JNI_FALSE },
        Err(_) => JNI_FALSE,
    }
}

/* ---- Snapshot & WebSocket internal helpers ---- */

#[derive(Serialize)]
pub(crate) struct SourceSummary {
    pub name: String,
    pub id: String,
    pub visible: bool,
    pub locked: bool,
    pub volume: f32,
    pub muted: bool,
    pub magnitude_db: f32,
    pub peak_db: f32,
}

#[derive(Serialize)]
pub(crate) struct StudioSnapshot {
    pub scenes: Vec<String>,
    pub active_scene_idx: usize,
    pub preview_scene_idx: usize,
    pub studio_mode: bool,
    pub transition_id: String,
    pub transition_ms: u32,
    pub video_encoder_id: String,
    pub base_width: u32,
    pub base_height: u32,
    pub sources: Vec<SourceSummary>,
    pub fps: f64,
    pub frame_time_ms: f64,
    pub total_frames: u32,
    pub lagged_frames: u32,
    pub dropped_frames: i32,
    pub output_bytes: u64,
    pub recording: bool,
    pub streaming: bool,
    pub replay_active: bool,
}

pub(crate) fn ws_get_snapshot() -> StudioSnapshot {
    let guard = STATE.lock().unwrap();
    let Some(state) = guard.as_ref() else {
        return StudioSnapshot {
            scenes: vec![], active_scene_idx: 0, preview_scene_idx: 0, studio_mode: false,
            transition_id: "".into(), transition_ms: 300, video_encoder_id: "".into(),
            base_width: 1280, base_height: 720, sources: vec![],
            fps: 0.0, frame_time_ms: 0.0, total_frames: 0, lagged_frames: 0,
            dropped_frames: 0, output_bytes: 0,
            recording: false, streaming: false, replay_active: false,
        };
    };
    let idx = if state.studio_mode { state.preview_scene_idx } else { state.active_scene_idx };
    let sc = &state.scenes[idx];
    let levels = AUDIO_LEVELS.lock().ok().and_then(|g| g.clone());
    let mut sources = Vec::new();
    for sname in &sc.sources {
        if let Ok(c_name) = CString::new(sname.as_str()) {
            unsafe {
                let item = sys::obs_scene_find_source(sc.scene, c_name.as_ptr());
                if !item.is_null() {
                    let src = sys::obs_sceneitem_get_source(item);
                    let id_ptr = sys::obs_source_get_id(src);
                    let id = if id_ptr.is_null() { "".into() } else { CStr::from_ptr(id_ptr).to_string_lossy().into_owned() };
                    let (mag, pk) = levels.as_ref().and_then(|m| m.get(sname).copied()).unwrap_or((-60.0, -60.0));
                    sources.push(SourceSummary {
                        name: sname.clone(),
                        id,
                        visible: sys::obs_sceneitem_visible(item),
                        locked: sys::obs_sceneitem_locked(item),
                        volume: sys::obs_source_get_volume(src),
                        muted: sys::obs_source_muted(src),
                        magnitude_db: mag,
                        peak_db: pk,
                    });
                }
            }
        }
    }
    let (dropped_frames, output_bytes) = if let Some(st) = &state.streaming {
        unsafe { (sys::obs_output_get_frames_dropped(st.output), sys::obs_output_get_total_bytes(st.output)) }
    } else if let Some(rc) = &state.recording {
        unsafe { (sys::obs_output_get_frames_dropped(rc.output), sys::obs_output_get_total_bytes(rc.output)) }
    } else {
        (0, 0)
    };
    StudioSnapshot {
        scenes: state.scenes.iter().map(|s| s.name.clone()).collect(),
        active_scene_idx: state.active_scene_idx,
        preview_scene_idx: state.preview_scene_idx,
        studio_mode: state.studio_mode,
        transition_id: state.transition_id.clone(),
        transition_ms: state.transition_ms,
        video_encoder_id: state.video_encoder_id.clone(),
        base_width: state.base_width,
        base_height: state.base_height,
        sources,
        fps: unsafe { sys::obs_get_active_fps() },
        frame_time_ms: unsafe { sys::obs_get_average_frame_time_ns() as f64 / 1_000_000.0 },
        total_frames: unsafe { sys::obs_get_total_frames() },
        lagged_frames: unsafe { sys::obs_get_lagged_frames() },
        dropped_frames,
        output_bytes,
        recording: state.recording.as_ref().map(|r| unsafe { sys::obs_output_active(r.output) }).unwrap_or(false),
        streaming: state.streaming.as_ref().map(|s| unsafe { sys::obs_output_active(s.output) }).unwrap_or(false),
        replay_active: state.replay.as_ref().map(|r| unsafe { sys::obs_output_active(r.output) }).unwrap_or(false),
    }
}

pub(crate) fn ws_switch_scene_by_name(name: &str) -> bool {
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return false; };
    if let Some(i) = state.scenes.iter().position(|s| s.name == name) {
        return switch_program_scene(state, i);
    }
    false
}

pub(crate) fn ws_set_transition(transition_id: &str, duration_ms: u32) -> bool {
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return false; };
    state.transition_ms = duration_ms;
    if state.transition_id == transition_id && !state.transition.is_null() {
        return true;
    }
    let Ok(c_id) = CString::new(transition_id) else { return false; };
    let Ok(c_name) = CString::new(transition_id) else { return false; };
    unsafe {
        let tr = sys::obs_source_create(c_id.as_ptr(), c_name.as_ptr(), ptr::null_mut(), ptr::null_mut());
        if tr.is_null() { return false; }
        let cur_scene = sys::obs_scene_get_source(state.scenes[state.active_scene_idx].scene);
        sys::obs_transition_set(tr, cur_scene);
        sys::obs_set_output_source(0, tr);
        if !state.transition.is_null() {
            sys::obs_source_release(state.transition);
        }
        state.transition = tr;
        state.transition_id = transition_id.to_string();
    }
    true
}

pub(crate) fn ws_set_studio_mode(enabled: bool) {
    let mut guard = STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        state.studio_mode = enabled;
        if enabled { state.preview_scene_idx = state.active_scene_idx; }
    }
}

pub(crate) fn ws_trigger_studio_transition() -> bool {
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return false; };
    let target = state.preview_scene_idx;
    switch_program_scene(state, target)
}

pub(crate) fn ws_set_muted(name: &str, muted: bool) {
    if let Ok(c_name) = CString::new(name) {
        unsafe {
            let src = sys::obs_get_source_by_name(c_name.as_ptr());
            if !src.is_null() {
                sys::obs_source_set_muted(src, muted);
                sys::obs_source_release(src);
            }
        }
    }
}

pub(crate) fn ws_set_volume(name: &str, volume: f32) {
    if let Ok(c_name) = CString::new(name) {
        unsafe {
            let src = sys::obs_get_source_by_name(c_name.as_ptr());
            if !src.is_null() {
                sys::obs_source_set_volume(src, volume);
                sys::obs_source_release(src);
            }
        }
    }
}

pub(crate) fn ws_get_source_properties_json(name: &str) -> String {
    let Ok(c_name) = CString::new(name) else { return "{}".into(); };
    unsafe {
        let p = sys::panggung_serialize_source_properties(c_name.as_ptr());
        if p.is_null() { return "{}".into(); }
        let s = CStr::from_ptr(p).to_string_lossy().into_owned();
        sys::panggung_free_string(p);
        s
    }
}

pub(crate) fn ws_update_source_settings(name: &str, json: &str) -> bool {
    let (Ok(c_name), Ok(c_json)) = (CString::new(name), CString::new(json)) else { return false; };
    unsafe {
        let src = sys::obs_get_source_by_name(c_name.as_ptr());
        if src.is_null() { return false; }
        let data = sys::obs_data_create_from_json(c_json.as_ptr());
        if data.is_null() {
            sys::obs_source_release(src);
            return false;
        }
        sys::obs_source_update(src, data);
        sys::obs_data_release(data);
        sys::obs_source_release(src);
        true
    }
}

pub(crate) fn ws_list_filters_json(source_name: &str) -> String {
    let Ok(c_name) = CString::new(source_name) else { return "[]".into(); };
    unsafe {
        let p = sys::panggung_list_source_filters_json(c_name.as_ptr());
        if p.is_null() { return "[]".into(); }
        let s = CStr::from_ptr(p).to_string_lossy().into_owned();
        sys::panggung_free_string(p);
        s
    }
}

pub(crate) fn ws_add_filter(source_name: &str, filter_id: &str, filter_name: &str, settings_json: &str) -> bool {
    let (Ok(cs), Ok(cfid), Ok(cfn)) = (CString::new(source_name), CString::new(filter_id), CString::new(filter_name)) else { return false; };
    unsafe {
        let src = sys::obs_get_source_by_name(cs.as_ptr());
        if src.is_null() { return false; }
        let data = if !settings_json.is_empty() {
            CString::new(settings_json).ok().map(|cj| sys::obs_data_create_from_json(cj.as_ptr())).unwrap_or(ptr::null_mut())
        } else {
            sys::obs_data_create()
        };
        let flt = sys::obs_source_create(cfid.as_ptr(), cfn.as_ptr(), data, ptr::null_mut());
        if !data.is_null() { sys::obs_data_release(data); }
        if flt.is_null() {
            sys::obs_source_release(src);
            return false;
        }
        sys::obs_source_filter_add(src, flt);
        sys::obs_source_release(flt);
        sys::obs_source_release(src);
        true
    }
}

pub(crate) fn ws_remove_filter(source_name: &str, filter_name: &str) -> bool {
    let (Ok(cs), Ok(cfn)) = (CString::new(source_name), CString::new(filter_name)) else { return false; };
    unsafe {
        let src = sys::obs_get_source_by_name(cs.as_ptr());
        if src.is_null() { return false; }
        let flt = sys::obs_source_get_filter_by_name(src, cfn.as_ptr());
        let ok = if !flt.is_null() {
            sys::obs_source_filter_remove(src, flt);
            sys::obs_source_release(flt);
            true
        } else {
            false
        };
        sys::obs_source_release(src);
        ok
    }
}

pub(crate) fn ws_start_record() -> bool {
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return false; };
    let ts = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let ext_dir = "/sdcard/Android/data/io.panggung.obs/files/Movies";
    let dir = if std::fs::create_dir_all(ext_dir).is_ok() {
        ext_dir
    } else {
        state.config_dir.as_str()
    };
    let path = format!("{dir}/ws_rec_{ts}.mp4");
    start_recording_locked(state, &path, 4500)
}

pub(crate) fn ws_stop_record() -> String {
    let mut guard = STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        if let Some(rec) = state.recording.take() {
            unsafe { rec.stop_and_release(); }
            return state.last_record_path.clone();
        }
    }
    String::new()
}

pub(crate) fn ws_create_scene(name: &str) -> bool {
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return false; };
    if state.scenes.iter().any(|s| s.name == name) { return false; }
    let Ok(c_name) = CString::new(name) else { return false; };
    let scene = unsafe { sys::obs_scene_create(c_name.as_ptr()) };
    if scene.is_null() { return false; }
    state.scenes.push(SceneEntry { name: name.to_string(), scene, sources: vec![] });
    true
}

pub(crate) fn ws_set_video_encoder(enc_id: &str) -> bool {
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return false; };
    state.video_encoder_id = enc_id.to_string();
    true
}

pub(crate) fn ws_pause_record(pause: bool) -> bool {
    let guard = STATE.lock().unwrap();
    let Some(state) = guard.as_ref() else { return false; };
    if let Some(rec) = &state.recording {
        unsafe { sys::obs_output_pause(rec.output, pause) }
    } else {
        false
    }
}

pub(crate) fn ws_start_replay_buffer(dir: &str, max_sec: i64, bitrate_kbps: i64) -> bool {
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return false; };
    if state.replay.is_some() { return true; }
    let enc_id = state.video_encoder_id.clone();
    let save_dir = if dir.is_empty() { state.config_dir.clone() } else { dir.to_string() };
    unsafe {
        let Some((v_enc, a_enc)) = create_av_encoders(&enc_id, bitrate_kbps) else { return false; };
        let Ok(mut out_data) = ObsData::new() else { return false; };
        let _ = out_data.set_string("directory", &save_dir);
        let _ = out_data.set_string("format", "Replay_%CCYY-%MM-%DD_%hh-%mm-%ss");
        let _ = out_data.set_string("extension", "mp4");
        let _ = out_data.set_int("max_time_sec", max_sec.clamp(5, 300));
        let _ = out_data.set_int("max_size_mb", 256);
        let out_id = CString::new("replay_buffer").unwrap();
        let out_name = CString::new("Replay Buffer").unwrap();
        let output = sys::obs_output_create(out_id.as_ptr(), out_name.as_ptr(), out_data.as_ptr(), ptr::null_mut());
        if output.is_null() {
            sys::obs_encoder_release(v_enc);
            sys::obs_encoder_release(a_enc);
            return false;
        }
        sys::obs_output_set_video_encoder(output, v_enc);
        sys::obs_output_set_audio_encoder(output, a_enc, 0);
        if !sys::obs_output_start(output) {
            sys::obs_output_release(output);
            sys::obs_encoder_release(v_enc);
            sys::obs_encoder_release(a_enc);
            return false;
        }
        info!("Replay Buffer started ({max_sec}s) -> {save_dir}");
        state.replay = Some(OutputBundle { output, v_enc, a_enc, service: ptr::null_mut() });
        true
    }
}

pub(crate) fn ws_save_replay_buffer() -> bool {
    let guard = STATE.lock().unwrap();
    let Some(state) = guard.as_ref() else { return false; };
    let Some(rp) = &state.replay else { return false; };
    unsafe {
        let ph = sys::obs_output_get_proc_handler(rp.output);
        if ph.is_null() { return false; }
        let save = CString::new("save").unwrap();
        sys::proc_handler_call(ph, save.as_ptr(), ptr::null_mut())
    }
}

pub(crate) fn ws_stop_replay_buffer() {
    let mut guard = STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        if let Some(rp) = state.replay.take() {
            unsafe { rp.stop_and_release(); }
            info!("Replay Buffer stopped");
        }
    }
}

pub(crate) fn ws_set_audio_advanced(name: &str, sync_offset_ns: Option<i64>, balance: Option<f32>, mixers: Option<u32>, monitor_type: Option<i32>) -> bool {
    let Ok(c_name) = CString::new(name) else { return false; };
    unsafe {
        let src = sys::obs_get_source_by_name(c_name.as_ptr());
        if src.is_null() { return false; }
        if let Some(off) = sync_offset_ns { sys::obs_source_set_sync_offset(src, off); }
        if let Some(bal) = balance { sys::obs_source_set_balance_value(src, bal.clamp(0.0, 1.0)); }
        if let Some(mx) = mixers { sys::obs_source_set_audio_mixers(src, mx); }
        if let Some(mt) = monitor_type { sys::obs_source_set_monitoring_type(src, mt); }
        sys::obs_source_release(src);
        true
    }
}

pub(crate) fn ws_get_audio_advanced_json(name: &str) -> String {
    let Ok(c_name) = CString::new(name) else { return "{}".into(); };
    unsafe {
        let src = sys::obs_get_source_by_name(c_name.as_ptr());
        if src.is_null() { return "{}".into(); }
        let off = sys::obs_source_get_sync_offset(src);
        let bal = sys::obs_source_get_balance_value(src);
        let mx = sys::obs_source_get_audio_mixers(src);
        let mt = sys::obs_source_get_monitoring_type(src);
        sys::obs_source_release(src);
        serde_json::json!({
            "sync_offset_ms": off / 1_000_000,
            "balance": bal,
            "mixers": mx,
            "monitor_type": mt
        }).to_string()
    }
}

fn start_recording_locked(state: &mut AppState, path: &str, bitrate_kbps: i64) -> bool {
    if state.recording.is_some() { return true; }
    let enc_id = state.video_encoder_id.clone();
    unsafe {
        let Some((v_enc, a_enc)) = create_av_encoders(&enc_id, bitrate_kbps) else { return false; };
        let Ok(mut out_data) = ObsData::new() else { return false; };
        let _ = out_data.set_string("path", path);
        let out_id = CString::new("mp4_output").unwrap();
        let out_name = CString::new("Local Recording").unwrap();
        let output = sys::obs_output_create(out_id.as_ptr(), out_name.as_ptr(), out_data.as_ptr(), ptr::null_mut());
        if output.is_null() {
            sys::obs_encoder_release(v_enc);
            sys::obs_encoder_release(a_enc);
            return false;
        }
        sys::obs_output_set_video_encoder(output, v_enc);
        sys::obs_output_set_audio_encoder(output, a_enc, 0);
        if !sys::obs_output_start(output) {
            sys::obs_output_release(output);
            sys::obs_encoder_release(v_enc);
            sys::obs_encoder_release(a_enc);
            return false;
        }
        info!("Recording started ({enc_id}) -> {path}");
        state.last_record_path = path.to_string();
        state.recording = Some(OutputBundle { output, v_enc, a_enc, service: ptr::null_mut() });
    }
    true
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativePauseRecording(
    _env: JNIEnv, _class: JClass, paused: jboolean,
) -> jboolean {
    if ws_pause_record(paused != JNI_FALSE) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeStartReplayBuffer(
    mut env: JNIEnv, _class: JClass, dir: JString, max_sec: jint, bitrate_kbps: jint,
) -> jboolean {
    let dir: String = env.get_string(&dir).map(|s| s.into()).unwrap_or_default();
    if ws_start_replay_buffer(&dir, max_sec as i64, bitrate_kbps as i64) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeSaveReplayBuffer(
    _env: JNIEnv, _class: JClass,
) -> jboolean {
    if ws_save_replay_buffer() { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeStopReplayBuffer(
    _env: JNIEnv, _class: JClass,
) {
    ws_stop_replay_buffer();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeSetAudioAdvanced(
    mut env: JNIEnv, _class: JClass, name: JString, sync_ms: jint, balance: jfloat, mixers: jint, monitor_type: jint,
) -> jboolean {
    let Ok(name): Result<String, _> = env.get_string(&name).map(|s| s.into()) else { return JNI_FALSE; };
    if ws_set_audio_advanced(&name, Some(sync_ms as i64 * 1_000_000), Some(balance), Some(mixers as u32), Some(monitor_type)) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeGetAudioAdvancedJson(
    mut env: JNIEnv, _class: JClass, name: JString,
) -> jstring {
    let name: String = env.get_string(&name).map(|s| s.into()).unwrap_or_default();
    let json = ws_get_audio_advanced_json(&name);
    env.new_string(json).map(|s| s.into_raw()).unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeLoadSceneCollection(
    mut env: JNIEnv, _class: JClass, path: JString,
) -> jboolean {
    let Ok(path): Result<String, _> = env.get_string(&path).map(|s| s.into()) else { return JNI_FALSE; };
    let Ok(raw) = std::fs::read_to_string(&path) else { return JNI_FALSE; };
    let Ok(val) = serde_json::from_str::<serde_json::Value>(&raw) else { return JNI_FALSE; };
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return JNI_FALSE; };
    if let Some(arr) = val.get("scenes").and_then(|s| s.as_array()) {
        for sc in arr {
            if let Some(sname) = sc.as_str() {
                if !state.scenes.iter().any(|e| e.name == sname) {
                    if let Ok(c) = CString::new(sname) {
                        let ptr = unsafe { sys::obs_scene_create(c.as_ptr()) };
                        if !ptr.is_null() {
                            state.scenes.push(SceneEntry { name: sname.to_string(), scene: ptr, sources: vec![] });
                        }
                    }
                }
            }
        }
        return JNI_TRUE;
    }
    JNI_FALSE
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeAttachProjectorSurface(
    env: JNIEnv, _class: JClass, surface: JObject, width: jint, height: jint,
) -> jboolean {
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return JNI_FALSE; };
    unsafe {
        if !state.projector_display.is_null() {
            sys::obs_display_destroy(state.projector_display);
            state.projector_display = ptr::null_mut();
        }
        if !state.projector_window.is_null() {
            ANativeWindow_release(state.projector_window);
            state.projector_window = ptr::null_mut();
        }
        let win = ANativeWindow_fromSurface(env.get_raw(), surface.as_raw());
        if win.is_null() { return JNI_FALSE; }
        let init = sys::gs_init_data {
            window: sys::gs_window { id: 0, display: win },
            cx: width.max(1) as u32,
            cy: height.max(1) as u32,
            num_backbuffers: 2,
            format: sys::gs_color_format::GS_RGBA,
            zsformat: sys::gs_zstencil_format::GS_ZS_NONE,
            adapter: 0,
        };
        let disp = sys::obs_display_create(&init, 0xFF000000);
        if disp.is_null() {
            ANativeWindow_release(win);
            return JNI_FALSE;
        }
        let dims = Box::into_raw(Box::new((state.base_width, state.base_height)));
        sys::obs_display_add_draw_callback(disp, Some(preview_draw_cb), dims as *mut libc::c_void);
        state.projector_window = win;
        state.projector_display = disp;
    }
    JNI_TRUE
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeDetachProjectorSurface(
    _env: JNIEnv, _class: JClass,
) {
    let mut guard = STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        unsafe {
            if !state.projector_display.is_null() {
                sys::obs_display_destroy(state.projector_display);
                state.projector_display = ptr::null_mut();
            }
            if !state.projector_window.is_null() {
                ANativeWindow_release(state.projector_window);
                state.projector_window = ptr::null_mut();
            }
        }
    }
}

pub(crate) fn ws_remux_to_mp4(src: &str, dst: &str) -> bool {
    let (Ok(cs), Ok(cd)) = (CString::new(src), CString::new(dst)) else { return false; };
    unsafe {
        let mut job: *mut libc::c_void = ptr::null_mut();
        if !sys::media_remux_job_create(&mut job, cs.as_ptr(), cd.as_ptr()) || job.is_null() {
            return false;
        }
        let ok = sys::media_remux_job_process(job, ptr::null_mut(), ptr::null_mut());
        sys::media_remux_job_destroy(job);
        ok
    }
}

pub(crate) fn ws_start_multistream(url: &str, key: &str, bitrate_kbps: i64) -> bool {
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return false; };
    let enc_id = state.video_encoder_id.clone();
    unsafe {
        let Some((v_enc, a_enc)) = create_av_encoders(&enc_id, bitrate_kbps) else { return false; };
        let Ok(mut svc_data) = ObsData::new() else { return false; };
        let _ = svc_data.set_string("server", url);
        let _ = svc_data.set_string("key", key);
        let svc_id = CString::new("rtmp_custom").unwrap();
        let svc_name = CString::new(format!("Multistream #{}", state.multistreams.len() + 1)).unwrap();
        let service = sys::obs_service_create(svc_id.as_ptr(), svc_name.as_ptr(), svc_data.as_ptr(), ptr::null_mut());
        if service.is_null() {
            sys::obs_encoder_release(v_enc);
            sys::obs_encoder_release(a_enc);
            return false;
        }
        let out_id = CString::new("rtmp_output").unwrap();
        let output = sys::obs_output_create(out_id.as_ptr(), svc_name.as_ptr(), ptr::null_mut(), ptr::null_mut());
        if output.is_null() {
            sys::obs_service_release(service);
            sys::obs_encoder_release(v_enc);
            sys::obs_encoder_release(a_enc);
            return false;
        }
        sys::obs_output_set_service(output, service);
        sys::obs_output_set_video_encoder(output, v_enc);
        sys::obs_output_set_audio_encoder(output, a_enc, 0);
        if !sys::obs_output_start(output) {
            sys::obs_output_release(output);
            sys::obs_service_release(service);
            sys::obs_encoder_release(v_enc);
            sys::obs_encoder_release(a_enc);
            return false;
        }
        state.multistreams.push(OutputBundle { output, v_enc, a_enc, service });
        true
    }
}

pub(crate) fn ws_stop_all_multistreams() {
    let mut guard = STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        for ms in state.multistreams.drain(..) {
            unsafe { ms.stop_and_release(); }
        }
    }
}

pub(crate) fn ws_start_custom_output(output_id: &str, url_or_path: &str, bitrate_kbps: i64) -> bool {
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return false; };
    if let Some(old) = state.custom_output.take() {
        unsafe { old.stop_and_release(); }
    }
    let enc_id = state.video_encoder_id.clone();
    unsafe {
        let Some((v_enc, a_enc)) = create_av_encoders(&enc_id, bitrate_kbps) else { return false; };
        let Ok(mut out_data) = ObsData::new() else { return false; };
        if !url_or_path.is_empty() {
            let _ = out_data.set_string("path", url_or_path);
            let _ = out_data.set_string("url", url_or_path);
        }
        let Ok(oid) = CString::new(output_id) else { return false; };
        let oname = CString::new("Custom Output").unwrap();
        let output = sys::obs_output_create(oid.as_ptr(), oname.as_ptr(), out_data.as_ptr(), ptr::null_mut());
        if output.is_null() {
            sys::obs_encoder_release(v_enc);
            sys::obs_encoder_release(a_enc);
            return false;
        }
        sys::obs_output_set_video_encoder(output, v_enc);
        sys::obs_output_set_audio_encoder(output, a_enc, 0);
        if !sys::obs_output_start(output) {
            sys::obs_output_release(output);
            sys::obs_encoder_release(v_enc);
            sys::obs_encoder_release(a_enc);
            return false;
        }
        state.custom_output = Some(OutputBundle { output, v_enc, a_enc, service: ptr::null_mut() });
        true
    }
}

pub(crate) fn ws_stop_custom_output() {
    let mut guard = STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        if let Some(co) = state.custom_output.take() {
            unsafe { co.stop_and_release(); }
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeRemuxToMp4(
    mut env: JNIEnv, _class: JClass, src: JString, dst: JString,
) -> jboolean {
    let (Ok(s), Ok(d)) = (
        env.get_string(&src).map(|x| String::from(x)),
        env.get_string(&dst).map(|x| String::from(x)),
    ) else { return JNI_FALSE; };
    if ws_remux_to_mp4(&s, &d) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeStartMultistream(
    mut env: JNIEnv, _class: JClass, url: JString, key: JString, bitrate_kbps: jint,
) -> jboolean {
    let (Ok(u), Ok(k)) = (
        env.get_string(&url).map(|x| String::from(x)),
        env.get_string(&key).map(|x| String::from(x)),
    ) else { return JNI_FALSE; };
    if ws_start_multistream(&u, &k, bitrate_kbps as i64) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeStopMultistreams(_env: JNIEnv, _class: JClass) {
    ws_stop_all_multistreams();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeStartCustomOutput(
    mut env: JNIEnv, _class: JClass, output_id: JString, url: JString, bitrate_kbps: jint,
) -> jboolean {
    let (Ok(oid), Ok(u)) = (
        env.get_string(&output_id).map(|x| String::from(x)),
        env.get_string(&url).map(|x| String::from(x)),
    ) else { return JNI_FALSE; };
    if ws_start_custom_output(&oid, &u, bitrate_kbps as i64) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeStopCustomOutput(_env: JNIEnv, _class: JClass) {
    ws_stop_custom_output();
}

pub(crate) fn ws_load_plugin_so(so_path: &str, data_path: &str) -> bool {
    let (Ok(c_so), Ok(c_data)) = (CString::new(so_path), CString::new(data_path)) else { return false; };
    unsafe {
        let mut module: *mut sys::obs_module_t = ptr::null_mut();
        let res = sys::obs_open_module(&mut module, c_so.as_ptr(), c_data.as_ptr());
        if res != 0 || module.is_null() {
            error!("obs_open_module failed ({res}) for {so_path}");
            return false;
        }
        if !sys::obs_init_module(module) {
            error!("obs_init_module failed for {so_path}");
            return false;
        }
        sys::obs_post_load_modules();
        info!("Dynamically loaded .pgplugin module: {so_path}");
        true
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeLoadPluginSo(
    mut env: JNIEnv, _class: JClass, so_path: JString, data_path: JString,
) -> jboolean {
    let (Ok(sp), Ok(dp)) = (
        env.get_string(&so_path).map(|s| String::from(s)),
        env.get_string(&data_path).map(|s| String::from(s)),
    ) else { return JNI_FALSE; };
    if ws_load_plugin_so(&sp, &dp) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeRunLuaScript(
    mut env: JNIEnv, _class: JClass, label: JString, code: JString,
) -> jstring {
    let label: String = env.get_string(&label).map(|s| s.into()).unwrap_or_else(|_| "inline.lua".into());
    let code: String = env.get_string(&code).map(|s| s.into()).unwrap_or_default();
    let res = match lua_engine::run_lua_code(&label, &code) {
        Ok(desc) => serde_json::json!({ "ok": true, "description": desc }).to_string(),
        Err(e) => serde_json::json!({ "ok": false, "error": e }).to_string(),
    };
    env.new_string(res).map(|s| s.into_raw()).unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeListScriptsJson(
    env: JNIEnv, _class: JClass,
) -> jstring {
    env.new_string(lua_engine::list_scripts_json()).map(|s| s.into_raw()).unwrap_or(ptr::null_mut())
}

pub(crate) fn ws_toggle_dual_canvas(enabled: bool) -> bool {
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return false; };
    unsafe {
        if enabled {
            if !state.vertical_view.is_null() { return true; }
            let view = sys::obs_view_create();
            if view.is_null() { return false; }
            let cur_scene = sys::obs_scene_get_source(state.scenes[state.active_scene_idx].scene);
            sys::obs_view_set_source(view, 0, cur_scene);
            let mut ovi: sys::obs_video_info = std::mem::zeroed();
            if sys::obs_get_video_info(&mut ovi) {
                ovi.base_width = 720;
                ovi.base_height = 1280;
                ovi.output_width = 720;
                ovi.output_height = 1280;
                let _ = sys::obs_view_add2(view, &mut ovi);
            }
            state.vertical_view = view;
            info!("Dual Canvas (720x1280 vertical obs_view_t) enabled");
            true
        } else {
            if !state.vertical_view.is_null() {
                sys::obs_view_remove(state.vertical_view);
                sys::obs_view_destroy(state.vertical_view);
                state.vertical_view = ptr::null_mut();
                info!("Dual Canvas disabled");
            }
            true
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeToggleDualCanvas(
    _env: JNIEnv, _class: JClass, enabled: jboolean,
) -> jboolean {
    if ws_toggle_dual_canvas(enabled != JNI_FALSE) { JNI_TRUE } else { JNI_FALSE }
}

pub(crate) fn ws_reset_video_ex(
    width: u32,
    height: u32,
    fps: u32,
    color_fmt: i32,
    color_space: i32,
    color_range: i32,
    scale_type: i32,
) -> bool {
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return false; };
    unsafe {
        let mut ovi: sys::obs_video_info = std::mem::zeroed();
        if !sys::obs_get_video_info(&mut ovi) {
            return false;
        }
        ovi.base_width = width;
        ovi.base_height = height;
        ovi.output_width = width;
        ovi.output_height = height;
        ovi.fps_num = fps.max(1);
        ovi.fps_den = 1;
        ovi.output_format = match color_fmt {
            1 => sys::video_format::VIDEO_FORMAT_I420,
            7 => sys::video_format::VIDEO_FORMAT_BGRA,
            10 => sys::video_format::VIDEO_FORMAT_I444,
            17 => sys::video_format::VIDEO_FORMAT_I010,
            18 => sys::video_format::VIDEO_FORMAT_P010,
            _ => sys::video_format::VIDEO_FORMAT_NV12,
        };
        ovi.colorspace = match color_space {
            1 => sys::video_colorspace::VIDEO_CS_601,
            3 => sys::video_colorspace::VIDEO_CS_SRGB,
            4 => sys::video_colorspace::VIDEO_CS_2100_PQ,
            5 => sys::video_colorspace::VIDEO_CS_2100_HLG,
            _ => sys::video_colorspace::VIDEO_CS_709,
        };
        ovi.range = if color_range == 2 {
            sys::video_range_type::VIDEO_RANGE_FULL
        } else {
            sys::video_range_type::VIDEO_RANGE_PARTIAL
        };
        ovi.scale_type = match scale_type {
            3 => sys::obs_scale_type::OBS_SCALE_BILINEAR,
            4 => sys::obs_scale_type::OBS_SCALE_LANCZOS,
            5 => sys::obs_scale_type::OBS_SCALE_AREA,
            _ => sys::obs_scale_type::OBS_SCALE_BICUBIC,
        };
        let mut res = sys::obs_reset_video(&mut ovi);
        if res != sys::OBS_VIDEO_SUCCESS && ovi.output_format != sys::video_format::VIDEO_FORMAT_NV12 {
            // Safe fallback to NV12 if GPU/GLES rejects exotic output format, preserving colorspace (e.g. Rec.2100 PQ)
            ovi.output_format = sys::video_format::VIDEO_FORMAT_NV12;
            res = sys::obs_reset_video(&mut ovi);
        }
        if res == sys::OBS_VIDEO_SUCCESS {
            state.base_width = width;
            state.base_height = height;
            state.fps = fps;
            true
        } else {
            false
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeResetVideoEx(
    _env: JNIEnv, _class: JClass, w: jint, h: jint, fps: jint, fmt: jint, space: jint, range: jint, scale: jint,
) -> jboolean {
    if ws_reset_video_ex(w as u32, h as u32, fps as u32, fmt, space, range, scale) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativePushSourceRgba(
    mut env: JNIEnv,
    _class: JClass,
    source_name: JString,
    rgba_buf: JByteBuffer,
    stride: jint,
    width: jint,
    height: jint,
) -> jboolean {
    let Ok(name): Result<String, _> = env.get_string(&source_name).map(|s| s.into()) else { return JNI_FALSE; };
    let Ok(c_name) = CString::new(name) else { return JNI_FALSE; };
    if let Ok(ptr) = env.get_direct_buffer_address(&rgba_buf) {
        let ok = unsafe { sys::panggung_push_source_rgba(c_name.as_ptr(), ptr, stride as u32, width as u32, height as u32) };
        if ok { return JNI_TRUE; }
    }
    JNI_FALSE
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeGetLastCrashLog(
    env: JNIEnv, _class: JClass,
) -> jstring {
    let guard = STATE.lock().unwrap();
    let txt = guard.as_ref().and_then(|s| s.last_crash_report.clone()).unwrap_or_default();
    env.new_string(txt).map(|s| s.into_raw()).unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeGetSnapshotJson(
    env: JNIEnv, _class: JClass,
) -> jstring {
    let snap = ws_get_snapshot();
    let json = serde_json::to_string(&snap).unwrap_or_else(|_| "{}".into());
    env.new_string(json).map(|s| s.into_raw()).unwrap_or(ptr::null_mut())
}

/* ---- Hardware/Software Recording (MP4), Replay Buffer & RTMP Streaming ---- */

unsafe fn create_av_encoders(encoder_id: &str, bitrate_kbps: i64) -> Option<(*mut sys::obs_encoder_t, *mut sys::obs_encoder_t)> {
    let mut v_data = ObsData::new().ok()?;
    let _ = v_data.set_int("bitrate", bitrate_kbps);
    let _ = v_data.set_int("keyint_sec", 2);
    let v_id = CString::new(encoder_id).ok()?;
    let v_name = CString::new("Video Encoder").unwrap();
    let v_enc = unsafe { sys::obs_video_encoder_create(v_id.as_ptr(), v_name.as_ptr(), v_data.as_ptr(), ptr::null_mut()) };
    if v_enc.is_null() {
        error!("Failed to create video encoder {encoder_id}");
        return None;
    }
    unsafe { sys::obs_encoder_set_video(v_enc, sys::obs_get_video()); }

    let mut a_data = ObsData::new().ok()?;
    let _ = a_data.set_int("bitrate", 128);
    let a_id = CString::new("ffmpeg_aac").unwrap();
    let a_name = CString::new("FFmpeg AAC").unwrap();
    let a_enc = unsafe { sys::obs_audio_encoder_create(a_id.as_ptr(), a_name.as_ptr(), a_data.as_ptr(), 0, ptr::null_mut()) };
    if a_enc.is_null() {
        unsafe { sys::obs_encoder_release(v_enc); }
        return None;
    }
    unsafe { sys::obs_encoder_set_audio(a_enc, sys::obs_get_audio()); }
    Some((v_enc, a_enc))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeStartRecording(
    mut env: JNIEnv, _class: JClass, path: JString, bitrate_kbps: jint,
) -> jboolean {
    let Ok(path): Result<String, _> = env.get_string(&path).map(|s| s.into()) else { return JNI_FALSE; };
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return JNI_FALSE; };
    if start_recording_locked(state, &path, bitrate_kbps as i64) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeStopRecording(_env: JNIEnv, _class: JClass) {
    let _ = ws_stop_record();
    info!("Recording stopped");
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeStartStreaming(
    mut env: JNIEnv, _class: JClass, rtmp_url: JString, stream_key: JString, bitrate_kbps: jint,
) -> jboolean {
    let Ok(url): Result<String, _> = env.get_string(&rtmp_url).map(|s| s.into()) else { return JNI_FALSE; };
    let Ok(key): Result<String, _> = env.get_string(&stream_key).map(|s| s.into()) else { return JNI_FALSE; };
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return JNI_FALSE; };
    if state.streaming.is_some() { return JNI_TRUE; }

    let enc_id = state.video_encoder_id.clone();
    unsafe {
        let Some((v_enc, a_enc)) = create_av_encoders(&enc_id, bitrate_kbps as i64) else { return JNI_FALSE; };
        let Ok(mut svc_data) = ObsData::new() else { return JNI_FALSE; };
        let _ = svc_data.set_string("server", &url);
        let _ = svc_data.set_string("key", &key);
        let svc_id = CString::new("rtmp_custom").unwrap();
        let svc_name = CString::new("Custom RTMP").unwrap();
        let service = sys::obs_service_create(svc_id.as_ptr(), svc_name.as_ptr(), svc_data.as_ptr(), ptr::null_mut());
        if service.is_null() {
            sys::obs_encoder_release(v_enc);
            sys::obs_encoder_release(a_enc);
            return JNI_FALSE;
        }
        let out_id = CString::new("rtmp_output").unwrap();
        let out_name = CString::new("RTMP Stream").unwrap();
        let output = sys::obs_output_create(out_id.as_ptr(), out_name.as_ptr(), ptr::null_mut(), ptr::null_mut());
        if output.is_null() {
            sys::obs_service_release(service);
            sys::obs_encoder_release(v_enc);
            sys::obs_encoder_release(a_enc);
            return JNI_FALSE;
        }
        sys::obs_output_set_service(output, service);
        sys::obs_output_set_video_encoder(output, v_enc);
        sys::obs_output_set_audio_encoder(output, a_enc, 0);
        if !sys::obs_output_start(output) {
            sys::obs_output_release(output);
            sys::obs_service_release(service);
            sys::obs_encoder_release(v_enc);
            sys::obs_encoder_release(a_enc);
            return JNI_FALSE;
        }
        info!("RTMP stream started ({enc_id}) -> {url}");
        state.streaming = Some(OutputBundle { output, v_enc, a_enc, service });
    }
    JNI_TRUE
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeStopStreaming(_env: JNIEnv, _class: JClass) {
    let mut guard = STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        if let Some(st) = state.streaming.take() {
            unsafe { st.stop_and_release(); }
            info!("RTMP stream stopped");
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeGetVersion(env: JNIEnv, _class: JClass) -> jstring {
    env.new_string(ObsContext::version_string()).map(|s| s.into_raw()).unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeGetLoadedInputsJson(env: JNIEnv, _class: JClass) -> jstring {
    let guard = STATE.lock().unwrap();
    let list = guard.as_ref().map(|s| s.ctx.list_input_types()).unwrap_or_default();
    let json = serde_json::to_string(&list).unwrap_or_else(|_| "[]".into());
    env.new_string(json).map(|s| s.into_raw()).unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeShutdown(_env: JNIEnv, _class: JClass) {
    let mut guard = STATE.lock().unwrap();
    if let Some(mut state) = guard.take() {
        if let Some(rec) = state.recording.take() { unsafe { rec.stop_and_release(); } }
        if let Some(st) = state.streaming.take() { unsafe { st.stop_and_release(); } }
        if let Some(rp) = state.replay.take() { unsafe { rp.stop_and_release(); } }
        unsafe {
            if !state.display.is_null() { sys::obs_display_destroy(state.display); }
            if !state.native_window.is_null() { ANativeWindow_release(state.native_window); }
            sys::obs_set_output_source(0, ptr::null_mut());
            if !state.transition.is_null() { sys::obs_source_release(state.transition); }
            for sc in state.scenes { sys::obs_scene_release(sc.scene); }
        }
        drop(state.ctx);
    }
    info!("libobs shut down cleanly");
}
