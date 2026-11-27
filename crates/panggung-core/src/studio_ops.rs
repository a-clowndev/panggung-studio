use std::ffi::CString;
use std::ptr;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jfloat, jint, jstring, JNI_FALSE, JNI_TRUE};
use log::info;
use obs::sys;

use crate::{OBS_BOUNDS_SCALE_INNER, OBS_TRANSITION_MODE_AUTO, OBS_TRANSITION_MODE_MANUAL, STATE, SceneEntry};

/// Move source layer order up (0) or down (1) in active/preview scene (T-A03).
pub(crate) fn ws_move_source_order(name: &str, direction: i32) -> bool {
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return false; };
    let idx = if state.studio_mode { state.preview_scene_idx } else { state.active_scene_idx };
    let sc = &mut state.scenes[idx];
    let Ok(c_name) = CString::new(name) else { return false; };
    unsafe {
        let item = sys::obs_scene_find_source(sc.scene, c_name.as_ptr());
        if item.is_null() { return false; }
        sys::obs_sceneitem_set_order(item, direction);
    }
    if let Some(pos) = sc.sources.iter().position(|s| s == name) {
        if direction == 0 && pos > 0 {
            sc.sources.swap(pos, pos - 1);
        } else if direction == 1 && pos + 1 < sc.sources.len() {
            sc.sources.swap(pos, pos + 1);
        }
    }
    true
}

pub(crate) fn ws_set_source_locked(name: &str, locked: bool) -> bool {
    let guard = STATE.lock().unwrap();
    let Some(state) = guard.as_ref() else { return false; };
    let idx = if state.studio_mode { state.preview_scene_idx } else { state.active_scene_idx };
    let sc = &state.scenes[idx];
    let Ok(c_name) = CString::new(name) else { return false; };
    unsafe {
        let item = sys::obs_scene_find_source(sc.scene, c_name.as_ptr());
        if item.is_null() { return false; }
        sys::obs_sceneitem_set_locked(item, locked)
    }
}

pub(crate) fn ws_fit_source_to_screen(name: &str) -> bool {
    let guard = STATE.lock().unwrap();
    let Some(state) = guard.as_ref() else { return false; };
    let idx = if state.studio_mode { state.preview_scene_idx } else { state.active_scene_idx };
    let sc = &state.scenes[idx];
    let Ok(c_name) = CString::new(name) else { return false; };
    unsafe {
        let item = sys::obs_scene_find_source(sc.scene, c_name.as_ptr());
        if item.is_null() { return false; }
        let center = sys::vec2 { x: state.base_width as f32 * 0.5, y: state.base_height as f32 * 0.5 };
        let one = sys::vec2 { x: 1.0, y: 1.0 };
        let bounds = sys::vec2 { x: state.base_width as f32, y: state.base_height as f32 };
        sys::obs_sceneitem_set_alignment(item, 0);
        sys::obs_sceneitem_set_bounds_alignment(item, 0);
        sys::obs_sceneitem_set_pos(item, &center);
        sys::obs_sceneitem_set_rot(item, 0.0);
        sys::obs_sceneitem_set_scale(item, &one);
        sys::obs_sceneitem_set_bounds_type(item, OBS_BOUNDS_SCALE_INNER);
        sys::obs_sceneitem_set_bounds(item, &bounds);
    }
    true
}

pub(crate) fn ws_get_source_transform_json(name: &str) -> String {
    let guard = STATE.lock().unwrap();
    let Some(state) = guard.as_ref() else { return "{}".into(); };
    let idx = if state.studio_mode { state.preview_scene_idx } else { state.active_scene_idx };
    let sc = &state.scenes[idx];
    let Ok(c_name) = CString::new(name) else { return "{}".into(); };
    unsafe {
        let item = sys::obs_scene_find_source(sc.scene, c_name.as_ptr());
        if item.is_null() { return "{}".into(); }
        let mut pos = sys::vec2 { x: 0.0, y: 0.0 };
        let mut scale = sys::vec2 { x: 1.0, y: 1.0 };
        sys::obs_sceneitem_get_pos(item, &mut pos);
        sys::obs_sceneitem_get_scale(item, &mut scale);
        let rot = sys::obs_sceneitem_get_rot(item);
        let locked = sys::obs_sceneitem_locked(item);
        serde_json::json!({
            "x": pos.x, "y": pos.y,
            "scale_x": scale.x, "scale_y": scale.y,
            "rot": rot, "locked": locked,
            "base_w": state.base_width, "base_h": state.base_height
        }).to_string()
    }
}

pub(crate) fn ws_rename_scene(idx: usize, new_name: &str) -> bool {
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return false; };
    if idx >= state.scenes.len() || new_name.is_empty() { return false; }
    let Ok(c_new) = CString::new(new_name) else { return false; };
    unsafe {
        let src = sys::obs_scene_get_source(state.scenes[idx].scene);
        sys::obs_source_set_name(src, c_new.as_ptr());
    }
    state.scenes[idx].name = new_name.to_string();
    true
}

pub(crate) fn ws_delete_scene(idx: usize) -> bool {
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return false; };
    if state.scenes.len() <= 1 || idx >= state.scenes.len() { return false; }
    let removed = state.scenes.remove(idx);
    if state.active_scene_idx >= state.scenes.len() {
        state.active_scene_idx = state.scenes.len() - 1;
    }
    if state.preview_scene_idx >= state.scenes.len() {
        state.preview_scene_idx = state.scenes.len() - 1;
    }
    let next_idx = state.active_scene_idx;
    let _ = crate::switch_program_scene(state, next_idx);
    unsafe { sys::obs_scene_release(removed.scene); }
    true
}

pub(crate) fn ws_duplicate_scene(idx: usize, dup_name: &str) -> bool {
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return false; };
    if idx >= state.scenes.len() { return false; }
    let Ok(c_name) = CString::new(dup_name) else { return false; };
    let new_sc = unsafe { sys::obs_scene_create(c_name.as_ptr()) };
    if new_sc.is_null() { return false; }
    state.scenes.push(SceneEntry { name: dup_name.to_string(), scene: new_sc, sources: vec![] });
    true
}

pub(crate) fn ws_media_play_pause(name: &str, pause: bool) -> bool {
    let Ok(c_name) = CString::new(name) else { return false; };
    unsafe {
        let src = sys::obs_get_source_by_name(c_name.as_ptr());
        if src.is_null() { return false; }
        sys::obs_source_media_play_pause(src, pause);
        sys::obs_source_release(src);
    }
    true
}

pub(crate) fn ws_media_restart(name: &str) -> bool {
    let Ok(c_name) = CString::new(name) else { return false; };
    unsafe {
        let src = sys::obs_get_source_by_name(c_name.as_ptr());
        if src.is_null() { return false; }
        sys::obs_source_media_restart(src);
        sys::obs_source_release(src);
    }
    true
}

/// Studio Mode Manual T-Bar transition control (T-A04).
pub(crate) fn ws_set_studio_tbar(t: f32) -> bool {
    let mut guard = STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else { return false; };
    if state.transition.is_null() { return false; }
    let clamped = t.clamp(0.0, 1.0);
    let target_src = unsafe { sys::obs_scene_get_source(state.scenes[state.preview_scene_idx].scene) };
    unsafe {
        if clamped <= 0.02 {
            sys::obs_transition_start(state.transition, OBS_TRANSITION_MODE_MANUAL, 0, target_src);
        }
        sys::obs_transition_set_manual_time(state.transition, clamped);
    }
    if clamped >= 0.99 {
        state.active_scene_idx = state.preview_scene_idx;
    }
    true
}

/// Fade to Black quick studio transition (T-A04).
pub(crate) fn ws_fade_to_black() -> bool {
    let guard = STATE.lock().unwrap();
    let Some(state) = guard.as_ref() else { return false; };
    if state.transition.is_null() { return false; }
    unsafe {
        sys::obs_transition_start(state.transition, OBS_TRANSITION_MODE_AUTO, state.transition_ms, ptr::null_mut());
    }
    info!("Fade to Black triggered ({} ms)", state.transition_ms);
    true
}

/// Center source on canvas (T-A03).
pub(crate) fn ws_center_source(name: &str) -> bool {
    let guard = STATE.lock().unwrap();
    let Some(state) = guard.as_ref() else { return false; };
    let idx = if state.studio_mode { state.preview_scene_idx } else { state.active_scene_idx };
    let sc = &state.scenes[idx];
    let Ok(c_name) = CString::new(name) else { return false; };
    unsafe {
        let item = sys::obs_scene_find_source(sc.scene, c_name.as_ptr());
        if item.is_null() { return false; }
        let pos = sys::vec2 { x: state.base_width as f32 * 0.1, y: state.base_height as f32 * 0.1 };
        let bounds = sys::vec2 { x: state.base_width as f32 * 0.8, y: state.base_height as f32 * 0.8 };
        sys::obs_sceneitem_set_pos(item, &pos);
        sys::obs_sceneitem_set_bounds_type(item, OBS_BOUNDS_SCALE_INNER);
        sys::obs_sceneitem_set_bounds(item, &bounds);
    }
    true
}

/// Flip source horizontally or vertically (T-A03).
pub(crate) fn ws_flip_source(name: &str, horizontal: bool) -> bool {
    let guard = STATE.lock().unwrap();
    let Some(state) = guard.as_ref() else { return false; };
    let idx = if state.studio_mode { state.preview_scene_idx } else { state.active_scene_idx };
    let sc = &state.scenes[idx];
    let Ok(c_name) = CString::new(name) else { return false; };
    unsafe {
        let item = sys::obs_scene_find_source(sc.scene, c_name.as_ptr());
        if item.is_null() { return false; }
        let mut scale = sys::vec2 { x: 1.0, y: 1.0 };
        sys::obs_sceneitem_get_scale(item, &mut scale);
        if horizontal { scale.x = -scale.x; } else { scale.y = -scale.y; }
        sys::obs_sceneitem_set_scale(item, &scale);
    }
    true
}

/// Reset source transform to default (T-A03).
pub(crate) fn ws_reset_source_transform(name: &str) -> bool {
    ws_fit_source_to_screen(name)
}

/// Dynamic bitrate adjustment via obs_encoder_update without dropping active stream/recording (T-C02 / T-E01).
/// When `include_recording` is false (network congestion ABR), ONLY streaming bitrate is adjusted so local recording quality stays untouched.
pub(crate) fn ws_update_live_bitrate_ex(bitrate_kbps: i64, include_recording: bool) -> bool {
    let guard = STATE.lock().unwrap();
    let Some(state) = guard.as_ref() else { return false; };
    let mut updated = false;
    let Ok(key) = CString::new("bitrate") else { return false; };
    let apply = |bundle: &Option<crate::OutputBundle>| -> bool {
        if let Some(b) = bundle {
            if !b.v_enc.is_null() {
                unsafe {
                    let settings = sys::obs_encoder_get_settings(b.v_enc);
                    if !settings.is_null() {
                        sys::obs_data_set_int(settings, key.as_ptr(), bitrate_kbps.clamp(500, 50000));
                        sys::obs_encoder_update(b.v_enc, settings);
                        sys::obs_data_release(settings);
                        return true;
                    }
                }
            }
        }
        false
    };
    if apply(&state.streaming) { updated = true; }
    if include_recording && apply(&state.recording) { updated = true; }
    if updated {
        info!("Dynamic live bitrate updated to {} kbps (include_recording={})", bitrate_kbps, include_recording);
    }
    updated
}

pub(crate) fn ws_update_live_bitrate(bitrate_kbps: i64) -> bool {
    ws_update_live_bitrate_ex(bitrate_kbps, true)
}

/* ---- JNI Exports for Fase A (T-A03 & T-A04) ---- */

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeMoveSourceOrder(
    mut env: JNIEnv, _class: JClass, name: JString, dir: jint,
) -> jboolean {
    let Ok(n): Result<String, _> = env.get_string(&name).map(|s| s.into()) else { return JNI_FALSE; };
    if ws_move_source_order(&n, dir) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeSetSourceLocked(
    mut env: JNIEnv, _class: JClass, name: JString, locked: jboolean,
) -> jboolean {
    let Ok(n): Result<String, _> = env.get_string(&name).map(|s| s.into()) else { return JNI_FALSE; };
    if ws_set_source_locked(&n, locked != JNI_FALSE) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeFitSourceToScreen(
    mut env: JNIEnv, _class: JClass, name: JString,
) -> jboolean {
    let Ok(n): Result<String, _> = env.get_string(&name).map(|s| s.into()) else { return JNI_FALSE; };
    if ws_fit_source_to_screen(&n) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeGetSourceTransformJson(
    mut env: JNIEnv, _class: JClass, name: JString,
) -> jstring {
    let n: String = env.get_string(&name).map(|s| s.into()).unwrap_or_default();
    env.new_string(ws_get_source_transform_json(&n)).map(|s| s.into_raw()).unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeRenameScene(
    mut env: JNIEnv, _class: JClass, idx: jint, new_name: JString,
) -> jboolean {
    let Ok(n): Result<String, _> = env.get_string(&new_name).map(|s| s.into()) else { return JNI_FALSE; };
    if ws_rename_scene(idx as usize, &n) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeDeleteScene(
    _env: JNIEnv, _class: JClass, idx: jint,
) -> jboolean {
    if ws_delete_scene(idx as usize) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeMediaPlayPause(
    mut env: JNIEnv, _class: JClass, name: JString, pause: jboolean,
) -> jboolean {
    let Ok(n): Result<String, _> = env.get_string(&name).map(|s| s.into()) else { return JNI_FALSE; };
    if ws_media_play_pause(&n, pause != JNI_FALSE) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeMediaRestart(
    mut env: JNIEnv, _class: JClass, name: JString,
) -> jboolean {
    let Ok(n): Result<String, _> = env.get_string(&name).map(|s| s.into()) else { return JNI_FALSE; };
    if ws_media_restart(&n) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeSetStudioTBar(
    _env: JNIEnv, _class: JClass, t: jfloat,
) -> jboolean {
    if ws_set_studio_tbar(t) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeFadeToBlack(
    _env: JNIEnv, _class: JClass,
) -> jboolean {
    if ws_fade_to_black() { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeDuplicateScene(
    mut env: JNIEnv, _class: JClass, idx: jint, dup_name: JString,
) -> jboolean {
    let Ok(n): Result<String, _> = env.get_string(&dup_name).map(|s| s.into()) else { return JNI_FALSE; };
    if ws_duplicate_scene(idx as usize, &n) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeCenterSource(
    mut env: JNIEnv, _class: JClass, name: JString,
) -> jboolean {
    let Ok(n): Result<String, _> = env.get_string(&name).map(|s| s.into()) else { return JNI_FALSE; };
    if ws_center_source(&n) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeFlipSource(
    mut env: JNIEnv, _class: JClass, name: JString, horizontal: jboolean,
) -> jboolean {
    let Ok(n): Result<String, _> = env.get_string(&name).map(|s| s.into()) else { return JNI_FALSE; };
    if ws_flip_source(&n, horizontal != JNI_FALSE) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeResetSourceTransform(
    mut env: JNIEnv, _class: JClass, name: JString,
) -> jboolean {
    let Ok(n): Result<String, _> = env.get_string(&name).map(|s| s.into()) else { return JNI_FALSE; };
    if ws_reset_source_transform(&n) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeUpdateLiveBitrate(
    _env: JNIEnv, _class: JClass, bitrate_kbps: jint,
) -> jboolean {
    if ws_update_live_bitrate(bitrate_kbps as i64) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_panggung_obs_NativeBridge_nativeUpdateStreamBitrate(
    _env: JNIEnv, _class: JClass, bitrate_kbps: jint,
) -> jboolean {
    if ws_update_live_bitrate_ex(bitrate_kbps as i64, false) { JNI_TRUE } else { JNI_FALSE }
}
