mod crash_handler;
mod lua_engine;
mod obs_parity;
mod studio_ops;
mod web_remote;
mod tls_config;
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

static VOLMETERS: Mutex<Vec<(usize, usize)>> = Mutex::new(Vec::new());

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
        } else if let Ok(mut list) = VOLMETERS.lock() {
            list.push((vm as usize, boxed as usize));
        }
    }
}

fn destroy_all_volmeters() {
    let items: Vec<(usize, usize)> = VOLMETERS
        .lock()
        .map(|mut v| std::mem::take(&mut *v))
        .unwrap_or_default();
    for (vm_ptr, boxed_ptr) in items {
        unsafe {
            let vm = vm_ptr as *mut libc::c_void;
            sys::obs_volmeter_detach_source(vm);
            sys::obs_volmeter_destroy(vm);
            let _ = Box::from_raw(boxed_ptr as *mut String);
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

/// Adds a source to `scene`, reusing an existing global OBS source if one with the same
/// name and `id` already exists (OBS Desktop "Add Existing" semantics). Always returns the
/// exact actual OBS source name (`obs_source_get_name`) so `sc.sources` never desyncs when
/// libobs auto-suffixes duplicate names (e.g. "Color Source 2").
fn add_source_to_scene(
    scene: *mut sys::obs_scene_t,
    id: &str,
    name: &str,
    settings_json: Option<&str>,
    base_w: u32,
    base_h: u32,
) -> Option<String> {
    let Ok(c_id) = CString::new(id) else { return None; };
    let Ok(c_name) = CString::new(name) else { return None; };
    unsafe {
        // 1. Check if this scene already contains a source with this name
        let existing_item = sys::obs_scene_find_source(scene, c_name.as_ptr());
        if !existing_item.is_null() {
            return Some(name.to_string());
        }

        // 2. Reuse existing global OBS source if name + id match (critical for shared Camera / Screen / Mic across scenes)
        let mut reused = false;
        let existing_global = sys::obs_get_source_by_name(c_name.as_ptr());
        let src = if !existing_global.is_null() {
            let ex_id_ptr = sys::obs_source_get_id(existing_global);
            let ex_id = if ex_id_ptr.is_null() { "" } else { CStr::from_ptr(ex_id_ptr).to_str().unwrap_or("") };
            if ex_id == id {
                reused = true;
                existing_global
            } else {
                sys::obs_source_release(existing_global);
                ptr::null_mut()
            }
        } else {
            ptr::null_mut()
        };

        let src = if !src.is_null() {
            src
        } else {
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
            let created = sys::obs_source_create(c_id.as_ptr(), c_name.as_ptr(), settings, ptr::null_mut());
            sys::obs_data_release(settings);
            created
        };

        if src.is_null() {
            error!("obs_source_create failed for id={id}, name={name}");
            return None;
        }

        let actual_name = {
            let np = sys::obs_source_get_name(src);
            if np.is_null() { name.to_string() } else { CStr::from_ptr(np).to_string_lossy().into_owned() }
        };

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
        if !reused && (id.contains("audio") || id == "ffmpeg_source" || id == "android_webview") {
            attach_volmeter_to_source(src, &actual_name);
        }
        sys::obs_source_release(src);
        if item.is_null() { None } else { Some(actual_name) }
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
    tls_config::set_config_dir(&config_dir);

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
            if let Some(n) = add_source_to_scene(scene, "color_source", "Background", Some(&bg_json), bw, bh) {
                sources.push(n);
            }
            if let Some(n) = add_source_to_scene(scene, "android_audio_input", "Microphone", None, bw, bh) {
                sources.push(n);
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
            // SECURITY: Remote WebSocket (4455) & Web Remote (8080) are disabled by default.
            // Users explicitly opt in via Remote Control dialog with mandatory SHA-256 auth.
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

#[link(name = "android")]
unsafe extern "C" {
    fn AHardwareBuffer_fromHardwareBuffer(
        env: *mut jni::sys::JNIEnv,
        hardware_buffer_obj: jni::sys::jobject,
    ) -> *mut libc::c_void;
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativePushCameraHardwareBuffer(
    mut env: JNIEnv, _class: JClass,
    source_name: JString, hw_buf: JObject,
    width: jint, height: jint, frame_seq: jlong,
) -> jlong {
    let name: String = env.get_string(&source_name).map(|s| s.into()).unwrap_or_default();
    let Ok(c_name) = CString::new(name) else { return 0; };
    let ahb = unsafe { AHardwareBuffer_fromHardwareBuffer(env.get_raw(), hw_buf.as_raw()) };
    if ahb.is_null() {
        return 0;
    }
    unsafe {
        sys::panggung_push_camera_ahb(c_name.as_ptr(), ahb, width.max(0) as u32, height.max(0) as u32, frame_seq.max(0) as u64) as jlong
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativePushScreenHardwareBuffer(
    env: JNIEnv, _class: JClass,
    hw_buf: JObject, width: jint, height: jint, frame_seq: jlong,
) -> jlong {
    let ahb = unsafe { AHardwareBuffer_fromHardwareBuffer(env.get_raw(), hw_buf.as_raw()) };
    if ahb.is_null() {
        return 0;
    }
    unsafe {
        sys::panggung_push_screen_ahb(ahb, width.max(0) as u32, height.max(0) as u32, frame_seq.max(0) as u64) as jlong
    }
}

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
    if let Some(actual_name) = add_source_to_scene(sc.scene, &id, &name, if json.is_empty() { None } else { Some(&json) }, bw, bh) {
        if !sc.sources.contains(&actual_name) {
            sc.sources.push(actual_name);
        }
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

fn save_scene_collection_locked(state: &AppState, path: &str) -> bool {
    let mut scenes_json = Vec::with_capacity(state.scenes.len());
    for sc in &state.scenes {
        let mut items_json = Vec::new();
        for sname in &sc.sources {
            let Ok(c_name) = CString::new(sname.as_str()) else { continue; };
            unsafe {
                let item = sys::obs_scene_find_source(sc.scene, c_name.as_ptr());
                if item.is_null() { continue; }
                let src = sys::obs_sceneitem_get_source(item);
                if src.is_null() { continue; }

                let id_ptr = sys::obs_source_get_id(src);
                let id_str = if id_ptr.is_null() { String::new() } else { CStr::from_ptr(id_ptr).to_string_lossy().into_owned() };

                let settings_data = sys::obs_source_get_settings(src);
                let settings_val: serde_json::Value = if !settings_data.is_null() {
                    let jp = sys::obs_data_get_json(settings_data);
                    let v = if !jp.is_null() {
                        serde_json::from_str(&CStr::from_ptr(jp).to_string_lossy()).unwrap_or(serde_json::json!({}))
                    } else {
                        serde_json::json!({})
                    };
                    sys::obs_data_release(settings_data);
                    v
                } else {
                    serde_json::json!({})
                };

                let mut pos = sys::vec2 { x: 0.0, y: 0.0 };
                let mut scale = sys::vec2 { x: 1.0, y: 1.0 };
                let mut bounds = sys::vec2 { x: 0.0, y: 0.0 };
                let mut crop = sys::obs_sceneitem_crop::default();
                sys::obs_sceneitem_get_pos(item, &mut pos);
                sys::obs_sceneitem_get_scale(item, &mut scale);
                sys::obs_sceneitem_get_bounds(item, &mut bounds);
                sys::obs_sceneitem_get_crop(item, &mut crop);
                let rot = sys::obs_sceneitem_get_rot(item);
                let bounds_type = sys::obs_sceneitem_get_bounds_type(item);
                let alignment = sys::obs_sceneitem_get_alignment(item);
                let bounds_alignment = sys::obs_sceneitem_get_bounds_alignment(item);
                let visible = sys::obs_sceneitem_visible(item);
                let locked = sys::obs_sceneitem_locked(item);

                let volume = sys::obs_source_get_volume(src);
                let muted = sys::obs_source_muted(src);
                let sync_offset = sys::obs_source_get_sync_offset(src);
                let balance = sys::obs_source_get_balance_value(src);
                let audio_mixers = sys::obs_source_get_audio_mixers(src);
                let monitoring_type = sys::obs_source_get_monitoring_type(src);

                // Enumerate attached filters + their settings
                let flt_raw = ws_list_filters_json(sname);
                let flt_arr: Vec<serde_json::Value> = serde_json::from_str(&flt_raw).unwrap_or_default();
                let mut filters_out = Vec::new();
                for f in flt_arr {
                    let fname = f.get("name").and_then(|x| x.as_str()).unwrap_or("");
                    let fid = f.get("id").and_then(|x| x.as_str()).unwrap_or("");
                    let fenabled = f.get("enabled").and_then(|x| x.as_bool()).unwrap_or(true);
                    let fsettings = if let Ok(cfn) = CString::new(fname) {
                        let fptr = sys::obs_source_get_filter_by_name(src, cfn.as_ptr());
                        if !fptr.is_null() {
                            let fd = sys::obs_source_get_settings(fptr);
                            let fv = if !fd.is_null() {
                                let jp = sys::obs_data_get_json(fd);
                                let parsed = if !jp.is_null() {
                                    serde_json::from_str(&CStr::from_ptr(jp).to_string_lossy()).unwrap_or(serde_json::json!({}))
                                } else {
                                    serde_json::json!({})
                                };
                                sys::obs_data_release(fd);
                                parsed
                            } else {
                                serde_json::json!({})
                            };
                            sys::obs_source_release(fptr);
                            fv
                        } else {
                            serde_json::json!({})
                        }
                    } else {
                        serde_json::json!({})
                    };
                    filters_out.push(serde_json::json!({
                        "id": fid,
                        "name": fname,
                        "enabled": fenabled,
                        "settings": fsettings
                    }));
                }

                items_json.push(serde_json::json!({
                    "name": sname,
                    "id": id_str,
                    "settings": settings_val,
                    "visible": visible,
                    "locked": locked,
                    "pos_x": pos.x,
                    "pos_y": pos.y,
                    "scale_x": scale.x,
                    "scale_y": scale.y,
                    "rot": rot,
                    "bounds_type": bounds_type,
                    "bounds_x": bounds.x,
                    "bounds_y": bounds.y,
                    "alignment": alignment,
                    "bounds_alignment": bounds_alignment,
                    "crop_left": crop.left,
                    "crop_top": crop.top,
                    "crop_right": crop.right,
                    "crop_bottom": crop.bottom,
                    "volume": volume,
                    "muted": muted,
                    "sync_offset_ns": sync_offset,
                    "balance": balance,
                    "audio_mixers": audio_mixers,
                    "monitoring_type": monitoring_type,
                    "filters": filters_out
                }));
            }
        }
        scenes_json.push(serde_json::json!({
            "name": sc.name,
            "items": items_json
        }));
    }

    let doc = serde_json::json!({
        "version": 2,
        "active_scene_idx": state.active_scene_idx,
        "base_width": state.base_width,
        "base_height": state.base_height,
        "fps": state.fps,
        "transition_id": state.transition_id,
        "transition_ms": state.transition_ms,
        "video_encoder_id": state.video_encoder_id,
        "scenes": scenes_json
    });

    match serde_json::to_string_pretty(&doc) {
        Ok(s) => {
            // Atomic write + .bak rotation: write to .tmp, fsync, backup last valid collection, then POSIX rename
            let tmp_path = format!("{path}.tmp");
            let bak_path = format!("{path}.bak");
            let ok = (|| -> std::io::Result<()> {
                use std::io::Write;
                let mut f = std::fs::File::create(&tmp_path)?;
                f.write_all(s.as_bytes())?;
                f.sync_all()?;
                if std::path::Path::new(&path).exists() {
                    let _ = std::fs::copy(&path, &bak_path);
                }
                std::fs::rename(&tmp_path, &path)?;
                Ok(())
            })()
            .is_ok();
            if ok { true } else { let _ = std::fs::remove_file(&tmp_path); false }
        }
        Err(_) => false,
    }
}

pub(crate) fn auto_save_scene_collection_locked(state: &AppState) {
    if let Some(parent) = std::path::Path::new(&state.config_dir).parent() {
        let p = parent.join("scene_collection.json");
        if let Some(s) = p.to_str() {
            let _ = save_scene_collection_locked(state, s);
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeSaveSceneCollection(
    mut env: JNIEnv, _class: JClass, path: JString,
) -> jboolean {
    let Ok(path): Result<String, _> = env.get_string(&path).map(|s| s.into()) else { return JNI_FALSE; };
    let guard = STATE.lock().unwrap();
    let Some(state) = guard.as_ref() else { return JNI_FALSE; };
    if save_scene_collection_locked(state, &path) { JNI_TRUE } else { JNI_FALSE }
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
    pub congestion: f32,
    pub recording: bool,
    pub streaming: bool,
    pub want_streaming: bool,
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
            dropped_frames: 0, output_bytes: 0, congestion: 0.0,
            recording: false, streaming: false, want_streaming: false, replay_active: false,
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
    let (dropped_frames, output_bytes, congestion) = if let Some(st) = &state.streaming {
        unsafe {
            (
                sys::obs_output_get_frames_dropped(st.output),
                sys::obs_output_get_total_bytes(st.output),
                sys::obs_output_get_congestion(st.output),
            )
        }
    } else if let Some(rc) = &state.recording {
        unsafe { (sys::obs_output_get_frames_dropped(rc.output), sys::obs_output_get_total_bytes(rc.output), 0.0) }
    } else {
        (0, 0, 0.0)
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
        congestion,
        recording: state.recording.as_ref().map(|r| unsafe { sys::obs_output_active(r.output) }).unwrap_or(false),
        streaming: state.streaming.as_ref().map(|s| unsafe { sys::obs_output_active(s.output) }).unwrap_or(false),
        want_streaming: WANT_STREAMING.load(std::sync::atomic::Ordering::SeqCst),
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
    auto_save_scene_collection_locked(state);
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

fn is_valid_scene_collection(v: &serde_json::Value) -> bool {
    let Some(arr) = v.get("scenes").and_then(|s| s.as_array()) else {
        return false;
    };
    arr.iter().any(|sc| {
        let name = sc.as_str().or_else(|| sc.get("name").and_then(|n| n.as_str())).unwrap_or("");
        !name.trim().is_empty()
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeLoadSceneCollection(
    mut env: JNIEnv, _class: JClass, path: JString,
) -> jboolean {
    let Ok(path): Result<String, _> = env.get_string(&path).map(|s| s.into()) else { return JNI_FALSE; };
    let val = match std::fs::read_to_string(&path)
        .ok()
        .and_then(|r| serde_json::from_str::<serde_json::Value>(&r).ok())
        .filter(is_valid_scene_collection)
    {
        Some(v) => v,
        None => {
            // Corrupt or schema-invalid primary JSON (e.g. `{}` or empty scenes) -> quarantine to .corrupt & recover from .bak
            let bak_path = format!("{path}.bak");
            if std::path::Path::new(&path).exists() {
                let _ = std::fs::rename(&path, format!("{path}.corrupt"));
                log::warn!("Scene collection '{path}' was invalid/corrupt — quarantined and attempting .bak recovery");
            }
            match std::fs::read_to_string(&bak_path)
                .ok()
                .and_then(|r| serde_json::from_str::<serde_json::Value>(&r).ok())
                .filter(is_valid_scene_collection)
            {
                Some(v) => {
                    let _ = std::fs::copy(&bak_path, &path);
                    info!("Recovered valid scene collection cleanly from '{bak_path}'");
                    v
                }
                None => return JNI_FALSE,
            }
        }
    };
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return JNI_FALSE; };

    // Restore saved video resolution/FPS & encoder if present in v2 collection
    if let (Some(w), Some(h), Some(f)) = (
        val.get("base_width").and_then(|x| x.as_u64()),
        val.get("base_height").and_then(|x| x.as_u64()),
        val.get("fps").and_then(|x| x.as_u64()),
    ) {
        let (wu, hu, fu) = (w as u32, h as u32, f as u32);
        if wu >= 320 && hu >= 320 && fu >= 15 && (wu != state.base_width || hu != state.base_height || fu != state.fps) {
            if state.ctx.reset_video(wu, hu, fu, 1).is_ok() {
                state.base_width = wu;
                state.base_height = hu;
                state.fps = fu;
            }
        }
    }
    if let Some(enc) = val.get("video_encoder_id").and_then(|x| x.as_str()) {
        if !enc.is_empty() {
            state.video_encoder_id = enc.to_string();
        }
    }
    if let Some(tid) = val.get("transition_id").and_then(|x| x.as_str()) {
        if !tid.is_empty() {
            state.transition_id = tid.to_string();
        }
    }
    if let Some(tms) = val.get("transition_ms").and_then(|x| x.as_u64()) {
        state.transition_ms = tms as u32;
    }

    let bw = state.base_width;
    let bh = state.base_height;

    if let Some(arr) = val.get("scenes").and_then(|s| s.as_array()) {
        for sc in arr {
            // Support both v2 full object format and legacy v1 string format
            let sname = sc.as_str()
                .or_else(|| sc.get("name").and_then(|n| n.as_str()))
                .unwrap_or("");
            if sname.is_empty() { continue; }

            let sc_idx = if let Some(existing) = state.scenes.iter().position(|e| e.name == sname) {
                existing
            } else {
                let Ok(c) = CString::new(sname) else { continue; };
                let ptr = unsafe { sys::obs_scene_create(c.as_ptr()) };
                if ptr.is_null() { continue; }
                state.scenes.push(SceneEntry { name: sname.to_string(), scene: ptr, sources: vec![] });
                state.scenes.len() - 1
            };

            let scene_ptr = state.scenes[sc_idx].scene;
            if let Some(items) = sc.get("items").and_then(|x| x.as_array()) {
                for it in items {
                    let item_name = it.get("name").and_then(|x| x.as_str()).unwrap_or("");
                    let item_id = it.get("id").and_then(|x| x.as_str()).unwrap_or("");
                    if item_name.is_empty() || item_id.is_empty() { continue; }
                    let settings_str = it.get("settings").map(|v| v.to_string()).unwrap_or_else(|| "{}".into());

                    // Create or reuse source in scene if not already present
                    if !state.scenes[sc_idx].sources.iter().any(|s| s == item_name) {
                        if let Some(actual_name) = add_source_to_scene(scene_ptr, item_id, item_name, Some(&settings_str), bw, bh) {
                            if !state.scenes[sc_idx].sources.contains(&actual_name) {
                                state.scenes[sc_idx].sources.push(actual_name);
                            }
                        }
                    }

                    // Restore full transform, visibility, lock, audio, and filters
                    let Ok(c_item_name) = CString::new(item_name) else { continue; };
                    unsafe {
                        let item = sys::obs_scene_find_source(scene_ptr, c_item_name.as_ptr());
                        if item.is_null() { continue; }

                        if let Some(a) = it.get("alignment").and_then(|x| x.as_u64()) {
                            sys::obs_sceneitem_set_alignment(item, a as u32);
                        }
                        if let Some(ba) = it.get("bounds_alignment").and_then(|x| x.as_u64()) {
                            sys::obs_sceneitem_set_bounds_alignment(item, ba as u32);
                        }
                        if let Some(bt) = it.get("bounds_type").and_then(|x| x.as_i64()) {
                            sys::obs_sceneitem_set_bounds_type(item, bt as i32);
                        }
                        if let (Some(bx), Some(by)) = (
                            it.get("bounds_x").and_then(|x| x.as_f64()),
                            it.get("bounds_y").and_then(|x| x.as_f64()),
                        ) {
                            let b = sys::vec2 { x: bx as f32, y: by as f32 };
                            sys::obs_sceneitem_set_bounds(item, &b);
                        }
                        if let (Some(px), Some(py)) = (
                            it.get("pos_x").and_then(|x| x.as_f64()),
                            it.get("pos_y").and_then(|x| x.as_f64()),
                        ) {
                            let p = sys::vec2 { x: px as f32, y: py as f32 };
                            sys::obs_sceneitem_set_pos(item, &p);
                        }
                        if let (Some(sx), Some(sy)) = (
                            it.get("scale_x").and_then(|x| x.as_f64()),
                            it.get("scale_y").and_then(|x| x.as_f64()),
                        ) {
                            let scv = sys::vec2 { x: sx as f32, y: sy as f32 };
                            sys::obs_sceneitem_set_scale(item, &scv);
                        }
                        if let Some(rot) = it.get("rot").and_then(|x| x.as_f64()) {
                            sys::obs_sceneitem_set_rot(item, rot as f32);
                        }
                        if let Some(vis) = it.get("visible").and_then(|x| x.as_bool()) {
                            sys::obs_sceneitem_set_visible(item, vis);
                        }
                        if let Some(lck) = it.get("locked").and_then(|x| x.as_bool()) {
                            sys::obs_sceneitem_set_locked(item, lck);
                        }
                        if it.get("crop_left").is_some() || it.get("crop_top").is_some()
                            || it.get("crop_right").is_some() || it.get("crop_bottom").is_some()
                        {
                            let crop = sys::obs_sceneitem_crop {
                                left: it.get("crop_left").and_then(|x| x.as_i64()).unwrap_or(0).max(0) as libc::c_int,
                                top: it.get("crop_top").and_then(|x| x.as_i64()).unwrap_or(0).max(0) as libc::c_int,
                                right: it.get("crop_right").and_then(|x| x.as_i64()).unwrap_or(0).max(0) as libc::c_int,
                                bottom: it.get("crop_bottom").and_then(|x| x.as_i64()).unwrap_or(0).max(0) as libc::c_int,
                            };
                            sys::obs_sceneitem_set_crop(item, &crop);
                        }

                        let src = sys::obs_sceneitem_get_source(item);
                        if !src.is_null() {
                            // Always apply saved source settings even when source already existed
                            if !settings_str.is_empty() && settings_str != "{}" {
                                if let Ok(cj) = CString::new(settings_str.as_str()) {
                                    let sd = sys::obs_data_create_from_json(cj.as_ptr());
                                    if !sd.is_null() {
                                        sys::obs_source_update(src, sd);
                                        sys::obs_data_release(sd);
                                    }
                                }
                            }
                            if let Some(vol) = it.get("volume").and_then(|x| x.as_f64()) {
                                sys::obs_source_set_volume(src, vol as f32);
                            }
                            if let Some(muted) = it.get("muted").and_then(|x| x.as_bool()) {
                                sys::obs_source_set_muted(src, muted);
                            }
                            if let Some(sync_ns) = it.get("sync_offset_ns").and_then(|x| x.as_i64()) {
                                sys::obs_source_set_sync_offset(src, sync_ns);
                            }
                            if let Some(bal) = it.get("balance").and_then(|x| x.as_f64()) {
                                sys::obs_source_set_balance_value(src, bal as f32);
                            }
                            if let Some(mx) = it.get("audio_mixers").and_then(|x| x.as_u64()) {
                                sys::obs_source_set_audio_mixers(src, mx as u32);
                            }
                            if let Some(mt) = it.get("monitoring_type").and_then(|x| x.as_i64()) {
                                sys::obs_source_set_monitoring_type(src, mt as i32);
                            }

                            if let Some(filters) = it.get("filters").and_then(|x| x.as_array()) {
                                for flt in filters {
                                    let fid = flt.get("id").and_then(|x| x.as_str()).unwrap_or("");
                                    let fname = flt.get("name").and_then(|x| x.as_str()).unwrap_or("");
                                    let fenabled = flt.get("enabled").and_then(|x| x.as_bool()).unwrap_or(true);
                                    let fset = flt.get("settings").map(|v| v.to_string()).unwrap_or_default();
                                    if fid.is_empty() || fname.is_empty() { continue; }
                                    if let (Ok(cfid), Ok(cfn)) = (CString::new(fid), CString::new(fname)) {
                                        let existing_flt = sys::obs_source_get_filter_by_name(src, cfn.as_ptr());
                                        if !existing_flt.is_null() {
                                            if !fset.is_empty() && fset != "{}" {
                                                if let Ok(cj) = CString::new(fset.as_str()) {
                                                    let fd = sys::obs_data_create_from_json(cj.as_ptr());
                                                    if !fd.is_null() {
                                                        sys::obs_source_update(existing_flt, fd);
                                                        sys::obs_data_release(fd);
                                                    }
                                                }
                                            }
                                            sys::obs_source_set_enabled(existing_flt, fenabled);
                                            sys::obs_source_release(existing_flt);
                                        } else {
                                            let data = if !fset.is_empty() {
                                                CString::new(fset).ok().map(|cj| sys::obs_data_create_from_json(cj.as_ptr())).unwrap_or(ptr::null_mut())
                                            } else {
                                                sys::obs_data_create()
                                            };
                                            let fptr = sys::obs_source_create(cfid.as_ptr(), cfn.as_ptr(), data, ptr::null_mut());
                                            if !data.is_null() { sys::obs_data_release(data); }
                                            if !fptr.is_null() {
                                                sys::obs_source_set_enabled(fptr, fenabled);
                                                sys::obs_source_filter_add(src, fptr);
                                                sys::obs_source_release(fptr);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if let Some(active_idx) = val.get("active_scene_idx").and_then(|x| x.as_u64()) {
            let idx = active_idx as usize;
            if idx < state.scenes.len() {
                let _ = switch_program_scene(state, idx);
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

static ENCODER_INSTANCE_SEQ: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);

unsafe fn create_av_encoders(encoder_id: &str, bitrate_kbps: i64) -> Option<(*mut sys::obs_encoder_t, *mut sys::obs_encoder_t)> {
    let seq = ENCODER_INSTANCE_SEQ.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    let mut v_data = ObsData::new().ok()?;
    let _ = v_data.set_int("bitrate", bitrate_kbps);
    let _ = v_data.set_int("keyint_sec", 2);
    let v_id = CString::new(encoder_id).ok()?;
    let v_name = CString::new(format!("Video Encoder #{seq}")).unwrap();
    let v_enc = unsafe { sys::obs_video_encoder_create(v_id.as_ptr(), v_name.as_ptr(), v_data.as_ptr(), ptr::null_mut()) };
    if v_enc.is_null() {
        error!("Failed to create video encoder {encoder_id}");
        return None;
    }
    unsafe { sys::obs_encoder_set_video(v_enc, sys::obs_get_video()); }

    let mut a_data = ObsData::new().ok()?;
    let _ = a_data.set_int("bitrate", 128);
    let a_id = CString::new("ffmpeg_aac").unwrap();
    let a_name = CString::new(format!("FFmpeg AAC #{seq}")).unwrap();
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

static WS_STREAM_CFG: Mutex<Option<(String, String, i64)>> = Mutex::new(None);
static WANT_STREAMING: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

fn redact_url_for_log(url: &str) -> String {
    // Strip both userinfo (user:pass@) and query parameters (?passphrase=...&streamid=...)
    let (base, has_q) = match url.find('?') {
        Some(idx) => (&url[..idx], true),
        None => (url, false),
    };
    let clean_base = if let Some(scheme_pos) = base.find("://") {
        let after_scheme = &base[scheme_pos + 3..];
        if let Some(at_pos) = after_scheme.find('@') {
            format!("{}://[redacted]@{}", &base[..scheme_pos], &after_scheme[at_pos + 1..])
        } else {
            base.to_string()
        }
    } else {
        base.to_string()
    };
    if has_q { format!("{clean_base}?[redacted]") } else { clean_base }
}

pub(crate) fn ws_set_stream_service_settings(server: &str, key: &str, bitrate_kbps: i64) {
    if let Ok(mut g) = WS_STREAM_CFG.lock() {
        *g = Some((server.trim().to_string(), key.trim().to_string(), bitrate_kbps.clamp(600, 50_000)));
    }
}

pub(crate) fn ws_get_stream_service_settings() -> (String, i64) {
    WS_STREAM_CFG
        .lock()
        .ok()
        .and_then(|g| g.as_ref().map(|(s, _, b)| (s.clone(), *b)))
        .unwrap_or_else(|| (String::new(), 4500))
}

pub(crate) fn ws_start_stream() -> bool {
    let cfg = WS_STREAM_CFG.lock().ok().and_then(|g| g.clone());
    let Some((url, key, br)) = cfg else { return false; };
    if url.is_empty() { return false; }
    start_streaming_internal(&url, &key, br)
}

fn stop_streaming_internal(clear_intent: bool) -> bool {
    // Acquire STATE mutex FIRST so WANT_STREAMING transitions are strictly serialized against start_streaming_internal()
    let mut guard = STATE.lock().unwrap();
    if clear_intent {
        WANT_STREAMING.store(false, std::sync::atomic::Ordering::SeqCst);
    }
    if let Some(state) = guard.as_mut() {
        if let Some(st) = state.streaming.take() {
            unsafe { st.stop_and_release(); }
            return true;
        }
    }
    false
}

pub(crate) fn ws_stop_stream() -> bool {
    stop_streaming_internal(true)
}

pub(crate) fn ws_get_stream_status() -> (bool, u64, i32, f32) {
    let guard = STATE.lock().unwrap();
    let Some(state) = guard.as_ref() else { return (false, 0, 0, 0.0); };
    if let Some(st) = &state.streaming {
        unsafe {
            let active = sys::obs_output_active(st.output);
            let bytes = sys::obs_output_get_total_bytes(st.output);
            let dropped = sys::obs_output_get_frames_dropped(st.output);
            let congestion = sys::obs_output_get_congestion(st.output);
            (active, bytes, dropped, congestion)
        }
    } else {
        (false, 0, 0, 0.0)
    }
}

fn start_streaming_internal(url: &str, key: &str, bitrate_kbps: i64) -> bool {
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return false; };
    if let Some(existing) = &state.streaming {
        let still_active = unsafe { sys::obs_output_active(existing.output) };
        if still_active {
            return true;
        }
        // Clean up dropped/disconnected output before reconnecting
        if let Some(dead) = state.streaming.take() {
            unsafe { dead.stop_and_release(); }
        }
    }

    let enc_id = state.video_encoder_id.clone();
    let lower_url = url.trim().to_ascii_lowercase();
    let is_srt_or_udp = lower_url.starts_with("srt://") || lower_url.starts_with("udp://");
    let safe_url = redact_url_for_log(url.trim());
    if is_srt_or_udp {
        // Strict codec compatibility gate for MPEG-TS/SRT: only H.264 & HEVC supported (reject AV1/VP9)
        let enc_lower = enc_id.to_ascii_lowercase();
        let is_supported_srt_codec = enc_lower.contains("h264") || enc_lower.contains("264")
            || enc_lower.contains("hevc") || enc_lower.contains("265");
        if !is_supported_srt_codec {
            log::warn!("SRT/UDP rejected unsupported video encoder '{}' (only H.264/HEVC supported)", enc_id);
            return false;
        }
    }
    unsafe {
        let Some((v_enc, a_enc)) = create_av_encoders(&enc_id, bitrate_kbps) else { return false; };
        if is_srt_or_udp {
            // First-class in-process Hardware-Encoded SRT / UDP MPEG-TS streaming via `panggung_srt_output`
            // Uses OBS's selected hardware MediaCodec H.264/HEVC (or x264) + AAC encoders,
            // supports live ABR bitrate updates via obs_encoder_update() AND real congestion metrics!
            let mut full_url = if !key.trim().is_empty() && !url.contains("streamid=") {
                let sep = if url.contains('?') { "&" } else { "?" };
                format!("{url}{sep}streamid={}", key.trim())
            } else {
                url.to_string()
            };
            if lower_url.starts_with("srt://") && !full_url.contains("connect_timeout=") {
                let sep = if full_url.contains('?') { "&" } else { "?" };
                full_url.push_str(&format!("{sep}connect_timeout=3000"));
            }
            let Ok(mut out_data) = ObsData::new() else {
                sys::obs_encoder_release(v_enc);
                sys::obs_encoder_release(a_enc);
                return false;
            };
            let _ = out_data.set_string("url", &full_url);
            let out_id = CString::new("panggung_srt_output").unwrap();
            let out_name = CString::new("SRT Stream").unwrap();
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
            WANT_STREAMING.store(true, std::sync::atomic::Ordering::SeqCst);
            info!("SRT/MPEG-TS in-process stream started ({enc_id}) -> {safe_url}");
            state.streaming = Some(OutputBundle { output, v_enc, a_enc, service: ptr::null_mut() });
            return true;
        }
        let Ok(mut svc_data) = ObsData::new() else {
            sys::obs_encoder_release(v_enc);
            sys::obs_encoder_release(a_enc);
            return false;
        };
        let _ = svc_data.set_string("server", url);
        let _ = svc_data.set_string("key", key);
        let svc_id = CString::new("rtmp_custom").unwrap();
        let svc_name = CString::new("Custom RTMP").unwrap();
        let service = sys::obs_service_create(svc_id.as_ptr(), svc_name.as_ptr(), svc_data.as_ptr(), ptr::null_mut());
        if service.is_null() {
            sys::obs_encoder_release(v_enc);
            sys::obs_encoder_release(a_enc);
            return false;
        }
        let out_id = CString::new("rtmp_output").unwrap();
        let out_name = CString::new("RTMP Stream").unwrap();
        let output = sys::obs_output_create(out_id.as_ptr(), out_name.as_ptr(), ptr::null_mut(), ptr::null_mut());
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
        WANT_STREAMING.store(true, std::sync::atomic::Ordering::SeqCst);
        info!("RTMP/RTMPS stream started ({enc_id}) -> {safe_url}");
        state.streaming = Some(OutputBundle { output, v_enc, a_enc, service });
    }
    true
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeStartStreaming(
    mut env: JNIEnv, _class: JClass, rtmp_url: JString, stream_key: JString, bitrate_kbps: jint,
) -> jboolean {
    let Ok(url): Result<String, _> = env.get_string(&rtmp_url).map(|s| s.into()) else { return JNI_FALSE; };
    let Ok(key): Result<String, _> = env.get_string(&stream_key).map(|s| s.into()) else { return JNI_FALSE; };
    ws_set_stream_service_settings(&url, &key, bitrate_kbps as i64);
    if start_streaming_internal(&url, &key, bitrate_kbps as i64) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeStopStreaming(_env: JNIEnv, _class: JClass) {
    if stop_streaming_internal(true) {
        info!("Stream stopped cleanly");
    }
}

/// Stops & releases active stream output during network-handoff reconnect WITHOUT clearing WANT_STREAMING intent.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeStopStreamingForReconnect(_env: JNIEnv, _class: JClass) {
    if stop_streaming_internal(false) {
        info!("Stream output stopped for network reconnect (intent preserved)");
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
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeSetRemoteControl(
    mut env: JNIEnv,
    _class: JClass,
    enabled: jboolean,
    lan_access: jboolean,
    auth_token: JString,
) -> jboolean {
    if enabled == JNI_FALSE {
        ws_server::stop_obs_websocket_server();
        web_remote::stop_web_remote_server();
        return JNI_TRUE;
    }
    let Ok(token): Result<String, _> = env.get_string(&auth_token).map(|s| s.into()) else {
        return JNI_FALSE;
    };
    let lan = lan_access != JNI_FALSE;
    let ok_ws = ws_server::start_obs_websocket_server(4455, lan, &token);
    let ok_web = web_remote::start_web_remote_server(8080, lan, &token);
    if ok_ws && ok_web {
        JNI_TRUE
    } else {
        ws_server::stop_obs_websocket_server();
        web_remote::stop_web_remote_server();
        JNI_FALSE
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeGetRemoteStatusJson(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let json = serde_json::json!({
        "enabled": ws_server::is_running(),
        "lanAccess": ws_server::is_lan_enabled(),
        "tlsEnabled": ws_server::is_lan_enabled(),
        "tlsFingerprint": tls_config::current_fingerprint(),
        "token": ws_server::current_auth_token(),
        "wsPort": 4455,
        "webPort": 8080,
    })
    .to_string();
    env.new_string(json).map(|s| s.into_raw()).unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeRenameSource(
    mut env: JNIEnv,
    _class: JClass,
    old_name: JString,
    new_name: JString,
) -> jboolean {
    let Ok(old_s): Result<String, _> = env.get_string(&old_name).map(|s| s.into()) else { return JNI_FALSE; };
    let Ok(new_s): Result<String, _> = env.get_string(&new_name).map(|s| s.into()) else { return JNI_FALSE; };
    if old_s.is_empty() || new_s.is_empty() || old_s == new_s { return JNI_TRUE; }
    let Ok(c_old) = CString::new(old_s.as_str()) else { return JNI_FALSE; };
    let Ok(c_new) = CString::new(new_s.as_str()) else { return JNI_FALSE; };
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return JNI_FALSE; };
    unsafe {
        let src = sys::obs_get_source_by_name(c_old.as_ptr());
        if src.is_null() { return JNI_FALSE; }
        sys::obs_source_set_name(src, c_new.as_ptr());
        let actual_ptr = sys::obs_source_get_name(src);
        let actual_name = if actual_ptr.is_null() {
            new_s.clone()
        } else {
            CStr::from_ptr(actual_ptr).to_string_lossy().into_owned()
        };
        sys::obs_source_release(src);
        for sc in state.scenes.iter_mut() {
            for s in sc.sources.iter_mut() {
                if *s == old_s {
                    *s = actual_name.clone();
                }
            }
        }
    }
    JNI_TRUE
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeShutdown(_env: JNIEnv, _class: JClass) {
    WANT_STREAMING.store(false, std::sync::atomic::Ordering::SeqCst);
    ws_server::stop_obs_websocket_server();
    web_remote::stop_web_remote_server();
    let mut guard = STATE.lock().unwrap();
    if let Some(mut state) = guard.take() {
        // 1. Stop all active outputs first
        if let Some(rec) = state.recording.take() { unsafe { rec.stop_and_release(); } }
        if let Some(st) = state.streaming.take() { unsafe { st.stop_and_release(); } }
        if let Some(rp) = state.replay.take() { unsafe { rp.stop_and_release(); } }
        for ms in state.multistreams.drain(..) { unsafe { ms.stop_and_release(); } }
        if let Some(co) = state.custom_output.take() { unsafe { co.stop_and_release(); } }

        // 2. Detach & destroy all volmeters before releasing sources
        destroy_all_volmeters();

        unsafe {
            // 3. Destroy displays & dual-canvas views
            if !state.display.is_null() {
                sys::obs_display_destroy(state.display);
                state.display = ptr::null_mut();
            }
            if !state.native_window.is_null() {
                ANativeWindow_release(state.native_window);
                state.native_window = ptr::null_mut();
            }
            if !state.projector_display.is_null() {
                sys::obs_display_destroy(state.projector_display);
                state.projector_display = ptr::null_mut();
            }
            if !state.projector_window.is_null() {
                ANativeWindow_release(state.projector_window);
                state.projector_window = ptr::null_mut();
            }
            if !state.vertical_view.is_null() {
                sys::obs_view_remove(state.vertical_view);
                sys::obs_view_destroy(state.vertical_view);
                state.vertical_view = ptr::null_mut();
            }

            // 4. Clear all OBS output channels & transition references
            for ch in 0..64u32 {
                sys::obs_set_output_source(ch, ptr::null_mut());
            }
            if !state.transition.is_null() {
                sys::obs_transition_clear(state.transition);
                sys::obs_source_release(state.transition);
                state.transition = ptr::null_mut();
            }

            // 5. Release all scenes while libobs modules (e.g. text-freetype2) are still loaded
            for sc in state.scenes.drain(..) {
                sys::obs_scene_release(sc.scene);
            }
        }

        // 6. Flush deferred source destruction on OBS video thread BEFORE obs_shutdown() unloads modules (prevents FT_Done_Face after FT_Done_FreeType)
        std::thread::sleep(std::time::Duration::from_millis(120));
        drop(state.ctx);
    }
    info!("libobs shut down cleanly");
}
