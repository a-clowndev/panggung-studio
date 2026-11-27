use std::fs::File;
use std::io::Read;
use std::net::TcpListener;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Mutex;
use std::thread;
use std::time::Duration;

use log::{error, info, warn};
use serde_json::{Value, json};
use tungstenite::protocol::frame::coding::CloseCode;
use tungstenite::protocol::CloseFrame;
use tungstenite::{Message, accept};

use crate::{
    ws_add_filter, ws_create_scene, ws_get_audio_advanced_json, ws_get_snapshot,
    ws_get_source_properties_json, ws_list_filters_json, ws_pause_record, ws_remove_filter,
    ws_remux_to_mp4, ws_save_replay_buffer, ws_set_audio_advanced, ws_set_muted,
    ws_set_studio_mode, ws_set_transition, ws_set_video_encoder, ws_set_volume,
    ws_start_custom_output, ws_start_multistream, ws_start_record, ws_start_replay_buffer,
    ws_stop_all_multistreams, ws_stop_custom_output, ws_stop_record, ws_stop_replay_buffer,
    ws_switch_scene_by_name, ws_trigger_studio_transition, ws_update_source_settings,
};

static WS_RUNNING: AtomicBool = AtomicBool::new(false);
static WS_LAN_ACCESS: AtomicBool = AtomicBool::new(false);
static WS_GENERATION: AtomicU64 = AtomicU64::new(0);
static WS_AUTH_TOKEN: Mutex<String> = Mutex::new(String::new());

pub fn is_running() -> bool {
    WS_RUNNING.load(Ordering::SeqCst)
}

pub fn is_lan_enabled() -> bool {
    WS_LAN_ACCESS.load(Ordering::SeqCst)
}

pub fn current_auth_token() -> String {
    WS_AUTH_TOKEN.lock().unwrap().clone()
}

pub fn stop_obs_websocket_server() {
    WS_RUNNING.store(false, Ordering::SeqCst);
    WS_GENERATION.fetch_add(1, Ordering::SeqCst);
}

/// Start obs-websocket v5 server with mandatory SHA-256 challenge-response authentication.
/// Disabled by default; binds 127.0.0.1 unless `lan_access` is explicitly true.
pub fn start_obs_websocket_server(port: u16, lan_access: bool, auth_token: &str) -> bool {
    if auth_token.len() < 8 {
        error!("Refusing to start obs-websocket without a strong auth token (>= 8 chars)");
        return false;
    }
    stop_obs_websocket_server();
    // Brief yield so previous non-blocking listener releases the port
    thread::sleep(Duration::from_millis(120));

    {
        let mut tok = WS_AUTH_TOKEN.lock().unwrap();
        *tok = auth_token.to_string();
    }
    WS_LAN_ACCESS.store(lan_access, Ordering::SeqCst);
    let generation = WS_GENERATION.fetch_add(1, Ordering::SeqCst) + 1;
    let bind_host = if lan_access { "0.0.0.0" } else { "127.0.0.1" };
    let addr = format!("{bind_host}:{port}");
    let listener = match TcpListener::bind(&addr) {
        Ok(l) => {
            let _ = l.set_nonblocking(true);
            info!("obs-websocket v5 server listening on ws://{addr} (auth=REQUIRED, lan={lan_access})");
            l
        }
        Err(e) => {
            error!("Failed to bind obs-websocket on {addr}: {e}");
            return false;
        }
    };

    WS_RUNNING.store(true, Ordering::SeqCst);
    let expected_token = auth_token.to_string();

    thread::Builder::new()
        .name("obs-ws-v5".into())
        .spawn(move || {
            while WS_RUNNING.load(Ordering::SeqCst) && WS_GENERATION.load(Ordering::SeqCst) == generation {
                match listener.accept() {
                    Ok((stream, _peer)) => {
                        let _ = stream.set_nonblocking(false);
                        let tok = expected_token.clone();
                        thread::spawn(move || handle_client(stream, &tok, generation));
                    }
                    Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(80));
                    }
                    Err(e) => {
                        warn!("obs-websocket accept error: {e}");
                        thread::sleep(Duration::from_millis(100));
                    }
                }
            }
            info!("obs-websocket v5 server stopped");
        })
        .is_ok()
}

fn is_session_valid(expected_gen: u64) -> bool {
    WS_RUNNING.load(Ordering::SeqCst) && WS_GENERATION.load(Ordering::SeqCst) == expected_gen
}

fn handle_client(stream: std::net::TcpStream, password: &str, expected_gen: u64) {
    // Short read timeout so active sessions promptly detect server disable or token rotation
    let _ = stream.set_read_timeout(Some(Duration::from_millis(500)));
    let mut ws = match accept(stream) {
        Ok(w) => w,
        Err(e) => {
            warn!("WS handshake failed: {e}");
            return;
        }
    };

    let (Some(challenge), Some(salt)) = (random_b64(24), random_b64(24)) else {
        error!("CSPRNG (/dev/urandom) unavailable — failing closed");
        let _ = ws.close(Some(CloseFrame {
            code: CloseCode::Error,
            reason: "CSPRNG unavailable".into(),
        }));
        return;
    };
    let expected_auth = compute_obs_ws_auth(password, &salt, &challenge);

    // OpCode 0: Hello (obs-websocket v5.5.0 with mandatory authentication)
    let hello = json!({
        "op": 0,
        "d": {
            "obsStudioVersion": obs::ObsContext::version_string(),
            "obsWebSocketVersion": "5.5.0",
            "rpcVersion": 1,
            "authentication": {
                "challenge": challenge,
                "salt": salt
            }
        }
    });
    if ws.send(Message::Text(hello.to_string().into())).is_err() {
        return;
    }

    let mut identified = false;

    loop {
        if !is_session_valid(expected_gen) {
            info!("Closing active obs-websocket session (server stopped or token rotated)");
            let _ = ws.close(Some(CloseFrame {
                code: CloseCode::Away,
                reason: "Server stopped or token rotated".into(),
            }));
            return;
        }
        let msg = match ws.read() {
            Ok(m) => m,
            Err(tungstenite::Error::Io(ref e))
                if e.kind() == std::io::ErrorKind::WouldBlock || e.kind() == std::io::ErrorKind::TimedOut =>
            {
                continue;
            }
            Err(_) => return,
        };
        if !is_session_valid(expected_gen) {
            let _ = ws.close(Some(CloseFrame {
                code: CloseCode::Away,
                reason: "Server stopped or token rotated".into(),
            }));
            return;
        }
        let Message::Text(txt) = msg else { continue };
        let Ok(v) = serde_json::from_str::<Value>(&txt) else { continue };
        let op = v.get("op").and_then(|x| x.as_u64()).unwrap_or(999);
        let d = v.get("d").cloned().unwrap_or(Value::Null);

        match op {
            // OpCode 1: Identify -> verify SHA-256 challenge-response -> reply OpCode 2: Identified
            1 => {
                let provided = d.get("authentication").and_then(|x| x.as_str()).unwrap_or("");
                if !constant_time_eq(provided.as_bytes(), expected_auth.as_bytes()) {
                    warn!("obs-websocket authentication failed — closing connection (4009)");
                    let _ = ws.close(Some(CloseFrame {
                        code: CloseCode::Library(4009),
                        reason: "Authentication failed".into(),
                    }));
                    return;
                }
                identified = true;
                let identified_msg = json!({
                    "op": 2,
                    "d": { "negotiatedRpcVersion": 1 }
                });
                if ws.send(Message::Text(identified_msg.to_string().into())).is_err() {
                    return;
                }
            }
            // OpCode 6: Request -> strictly require identified == true
            6 => {
                if !identified {
                    warn!("obs-websocket unauthenticated request rejected (4007)");
                    let _ = ws.close(Some(CloseFrame {
                        code: CloseCode::Library(4007),
                        reason: "Not identified".into(),
                    }));
                    return;
                }
                let req_type = d.get("requestType").and_then(|x| x.as_str()).unwrap_or("");
                let req_id = d.get("requestId").and_then(|x| x.as_str()).unwrap_or("");
                let req_data = d.get("requestData").cloned().unwrap_or(Value::Null);

                let (ok, resp_data) = dispatch_request(req_type, &req_data);
                let resp = json!({
                    "op": 7,
                    "d": {
                        "requestType": req_type,
                        "requestId": req_id,
                        "requestStatus": {
                            "result": ok,
                            "code": if ok { 100 } else { 204 }
                        },
                        "responseData": resp_data
                    }
                });
                if ws.send(Message::Text(resp.to_string().into())).is_err() {
                    return;
                }
            }
            _ => {}
        }
    }
}

fn dispatch_request(req_type: &str, data: &Value) -> (bool, Value) {
    match req_type {
        "GetVersion" => (
            true,
            json!({
                "obsVersion": obs::ObsContext::version_string(),
                "obsWebSocketVersion": "5.5.0",
                "rpcVersion": 1,
                "platform": "android",
                "platformDescription": "Android (Panggung Studio / libobs-gles)"
            }),
        ),
        "GetSceneList" => {
            let snap = ws_get_snapshot();
            let current = snap.scenes.get(snap.active_scene_idx).cloned().unwrap_or_default();
            let scenes: Vec<Value> = snap
                .scenes
                .iter()
                .enumerate()
                .map(|(i, n)| json!({ "sceneName": n, "sceneIndex": i }))
                .collect();
            (
                true,
                json!({
                    "currentProgramSceneName": current,
                    "scenes": scenes
                }),
            )
        }
        "CreateScene" => {
            let name = data.get("sceneName").and_then(|x| x.as_str()).unwrap_or("");
            (ws_create_scene(name), json!({}))
        }
        "SetCurrentProgramScene" => {
            let name = data.get("sceneName").and_then(|x| x.as_str()).unwrap_or("");
            (ws_switch_scene_by_name(name), json!({}))
        }
        "GetInputList" => {
            let snap = ws_get_snapshot();
            let inputs: Vec<Value> = snap
                .sources
                .iter()
                .map(|s| json!({ "inputName": s.name, "inputKind": s.id }))
                .collect();
            (true, json!({ "inputs": inputs }))
        }
        "SetInputMute" => {
            let name = data.get("inputName").and_then(|x| x.as_str()).unwrap_or("");
            let muted = data.get("inputMuted").and_then(|x| x.as_bool()).unwrap_or(false);
            ws_set_muted(name, muted);
            (true, json!({}))
        }
        "SetInputVolume" => {
            let name = data.get("inputName").and_then(|x| x.as_str()).unwrap_or("");
            let mul = data.get("inputVolumeMul").and_then(|x| x.as_f64()).unwrap_or(1.0) as f32;
            ws_set_volume(name, mul);
            (true, json!({}))
        }
        "GetRecordStatus" => {
            let snap = ws_get_snapshot();
            (true, json!({ "outputActive": snap.recording }))
        }
        "StartRecord" => {
            let ok = ws_start_record();
            (ok, json!({}))
        }
        "StopRecord" => {
            let path = ws_stop_record();
            (true, json!({ "outputPath": path }))
        }
        "PauseRecord" => (ws_pause_record(true), json!({})),
        "ResumeRecord" => (ws_pause_record(false), json!({})),
        "GetReplayBufferStatus" => {
            let snap = ws_get_snapshot();
            (true, json!({ "outputActive": snap.replay_active }))
        }
        "StartReplayBuffer" => {
            let sec = data.get("maxTimeSec").and_then(|x| x.as_i64()).unwrap_or(15);
            (ws_start_replay_buffer("", sec, 4500), json!({}))
        }
        "SaveReplayBuffer" => (ws_save_replay_buffer(), json!({})),
        "StopReplayBuffer" => {
            ws_stop_replay_buffer();
            (true, json!({}))
        }
        "SetVideoEncoder" => {
            let eid = data.get("encoderId").and_then(|x| x.as_str()).unwrap_or("android_mediacodec_h264");
            (ws_set_video_encoder(eid), json!({}))
        }
        "SetInputAudioSyncOffset" => {
            let name = data.get("inputName").and_then(|x| x.as_str()).unwrap_or("");
            let ms = data.get("inputAudioSyncOffset").and_then(|x| x.as_i64()).unwrap_or(0);
            (ws_set_audio_advanced(name, Some(ms * 1_000_000), None, None, None), json!({}))
        }
        "SetInputAudioBalance" => {
            let name = data.get("inputName").and_then(|x| x.as_str()).unwrap_or("");
            let bal = data.get("inputAudioBalance").and_then(|x| x.as_f64()).unwrap_or(0.5) as f32;
            (ws_set_audio_advanced(name, None, Some(bal), None, None), json!({}))
        }
        "SetInputAudioMonitorType" => {
            let name = data.get("inputName").and_then(|x| x.as_str()).unwrap_or("");
            let mt = data.get("monitorType").and_then(|x| x.as_i64()).unwrap_or(0) as i32;
            (ws_set_audio_advanced(name, None, None, None, Some(mt)), json!({}))
        }
        "GetInputAudioAdvanced" => {
            let name = data.get("inputName").and_then(|x| x.as_str()).unwrap_or("");
            let raw = ws_get_audio_advanced_json(name);
            let v: Value = serde_json::from_str(&raw).unwrap_or(json!({}));
            (true, v)
        }
        "GetStreamStatus" => {
            let snap = ws_get_snapshot();
            (true, json!({ "outputActive": snap.streaming }))
        }
        "GetStats" => {
            let snap = ws_get_snapshot();
            (
                true,
                json!({
                    "activeFps": snap.fps,
                    "averageFrameRenderTime": snap.frame_time_ms,
                    "renderTotalFrames": snap.total_frames,
                    "renderSkippedFrames": snap.lagged_frames
                }),
            )
        }
        "GetSourceFilterList" => {
            let name = data.get("sourceName").and_then(|x| x.as_str()).unwrap_or("");
            let raw = ws_list_filters_json(name);
            let arr: Vec<Value> = serde_json::from_str(&raw).unwrap_or_default();
            let filters: Vec<Value> = arr.into_iter().enumerate().map(|(idx, f)| {
                json!({
                    "filterEnabled": f.get("enabled").and_then(|x| x.as_bool()).unwrap_or(true),
                    "filterIndex": idx,
                    "filterKind": f.get("id").and_then(|x| x.as_str()).unwrap_or(""),
                    "filterName": f.get("name").and_then(|x| x.as_str()).unwrap_or(""),
                    "filterSettings": {}
                })
            }).collect();
            (true, json!({ "filters": filters }))
        }
        "CreateSourceFilter" => {
            let sname = data.get("sourceName").and_then(|x| x.as_str()).unwrap_or("");
            let fid = data.get("filterKind").and_then(|x| x.as_str()).unwrap_or("");
            let fname = data.get("filterName").and_then(|x| x.as_str()).unwrap_or(fid);
            let settings = data.get("filterSettings").map(|v| v.to_string()).unwrap_or_default();
            (ws_add_filter(sname, fid, fname, &settings), json!({}))
        }
        "RemoveSourceFilter" => {
            let sname = data.get("sourceName").and_then(|x| x.as_str()).unwrap_or("");
            let fname = data.get("filterName").and_then(|x| x.as_str()).unwrap_or("");
            (ws_remove_filter(sname, fname), json!({}))
        }
        "GetInputSettings" => {
            let name = data.get("inputName").and_then(|x| x.as_str()).unwrap_or("");
            let raw = ws_get_source_properties_json(name);
            let v: Value = serde_json::from_str(&raw).unwrap_or(json!({}));
            (true, v)
        }
        "SetInputSettings" => {
            let name = data.get("inputName").and_then(|x| x.as_str()).unwrap_or("");
            let settings = data.get("inputSettings").map(|v| v.to_string()).unwrap_or_default();
            (ws_update_source_settings(name, &settings), json!({}))
        }
        "SetCurrentSceneTransition" => {
            let tid = data.get("transitionName").and_then(|x| x.as_str()).unwrap_or("fade_transition");
            let ms = data.get("transitionDuration").and_then(|x| x.as_u64()).unwrap_or(300) as u32;
            (ws_set_transition(tid, ms), json!({}))
        }
        "SetStudioModeEnabled" => {
            let en = data.get("studioModeEnabled").and_then(|x| x.as_bool()).unwrap_or(false);
            ws_set_studio_mode(en);
            (true, json!({}))
        }
        "TriggerStudioModeTransition" => (ws_trigger_studio_transition(), json!({})),
        "RemuxRecording" => {
            let src = data.get("srcPath").and_then(|x| x.as_str()).unwrap_or("");
            let dst = data.get("dstPath").and_then(|x| x.as_str()).unwrap_or("");
            if !is_allowed_recording_path(src, true) || !is_allowed_recording_path(dst, false) {
                return (false, json!({ "error": "Path outside allowed recording directory" }));
            }
            (ws_remux_to_mp4(src, dst), json!({}))
        }
        "StartMultistream" => {
            let url = data.get("url").and_then(|x| x.as_str()).unwrap_or("");
            let key = data.get("key").and_then(|x| x.as_str()).unwrap_or("");
            let br = data.get("bitrateKbps").and_then(|x| x.as_i64()).unwrap_or(4500);
            (ws_start_multistream(url, key, br), json!({}))
        }
        "StopMultistreams" => {
            ws_stop_all_multistreams();
            (true, json!({}))
        }
        "StartCustomOutput" => {
            let oid = data.get("outputId").and_then(|x| x.as_str()).unwrap_or("null_output");
            let url = data.get("url").and_then(|x| x.as_str()).unwrap_or("");
            let br = data.get("bitrateKbps").and_then(|x| x.as_i64()).unwrap_or(4500);
            (ws_start_custom_output(oid, url, br), json!({}))
        }
        "StopCustomOutput" => {
            ws_stop_custom_output();
            (true, json!({}))
        }
        // SECURITY: Arbitrary Lua execution & native .so plugin loading are strictly blocked over remote API.
        "RunLuaScript" | "LoadPluginSo" => (
            false,
            json!({ "error": "Blocked over remote API for security. Run scripts and plugins locally in-app." }),
        ),
        "GetScriptList" => {
            let raw = crate::lua_engine::list_scripts_json();
            let v: Value = serde_json::from_str(&raw).unwrap_or(json!([]));
            (true, json!({ "scripts": v }))
        }
        "ToggleDualCanvas" => {
            let en = data.get("enabled").and_then(|x| x.as_bool()).unwrap_or(false);
            (crate::ws_toggle_dual_canvas(en), json!({}))
        }
        "CreateInput" => {
            let kind = data.get("inputKind").and_then(|x| x.as_str()).unwrap_or("");
            let name = data.get("inputName").and_then(|x| x.as_str()).unwrap_or("Input");
            let settings = data.get("inputSettings").map(|v| v.to_string()).unwrap_or_default();
            let mut guard = crate::STATE.lock().unwrap();
            let ok = if let Some(state) = guard.as_mut() {
                let idx = state.active_scene_idx;
                let bw = state.base_width;
                let bh = state.base_height;
                let scene = state.scenes[idx].scene;
                if let Some(actual_name) = crate::add_source_to_scene(scene, kind, name, Some(&settings), bw, bh) {
                    if !state.scenes[idx].sources.contains(&actual_name) {
                        state.scenes[idx].sources.push(actual_name);
                    }
                    true
                } else {
                    false
                }
            } else { false };
            (ok, json!({}))
        }
        "Undo" => (crate::obs_parity::perform_undo(), json!({})),
        "Redo" => (crate::obs_parity::perform_redo(), json!({})),
        "RecordUndoMute" => {
            let src = data.get("inputName").and_then(|x| x.as_str()).unwrap_or("");
            let prev = data.get("prev").and_then(|x| x.as_bool()).unwrap_or(false);
            let next = data.get("next").and_then(|x| x.as_bool()).unwrap_or(true);
            crate::ws_set_muted(src, next);
            crate::obs_parity::record_undo(crate::obs_parity::UndoAction::SetMute { source: src.into(), prev, next });
            (true, json!({}))
        }
        "GetProfileList" => (true, crate::obs_parity::list_profiles_json()),
        "CreateProfile" => {
            let name = data.get("profileName").and_then(|x| x.as_str()).unwrap_or("Custom");
            let cfg = data.get("config").cloned().unwrap_or_else(|| json!({ "width": 1920, "height": 1080, "fps": 60 }));
            crate::obs_parity::save_profile(name, cfg);
            (true, json!({}))
        }
        "GetPluginList" => (true, crate::obs_parity::list_plugins_json()),
        "ImportSceneCollection" => {
            let raw = data.get("collectionJson").and_then(|x| x.as_str()).unwrap_or("");
            match crate::obs_parity::import_desktop_collection_json(raw) {
                Ok(n) => (true, json!({ "importedScenes": n })),
                Err(e) => (false, json!({ "error": e })),
            }
        }
        "SetTransitionOverride" => {
            let from = data.get("fromScene").and_then(|x| x.as_str()).unwrap_or("Main Scene");
            let to = data.get("toScene").and_then(|x| x.as_str()).unwrap_or("Scene 2");
            let tid = data.get("transitionId").and_then(|x| x.as_str()).unwrap_or("swipe_transition");
            let ms = data.get("durationMs").and_then(|x| x.as_u64()).unwrap_or(350) as u32;
            crate::obs_parity::set_transition_override(from, to, tid, ms);
            (true, json!({}))
        }
        "AddAutoSwitcherRule" => {
            let pat = data.get("appPattern").and_then(|x| x.as_str()).unwrap_or("com.game.*");
            let sc = data.get("sceneName").and_then(|x| x.as_str()).unwrap_or("Main Scene");
            crate::obs_parity::add_auto_switcher_rule(pat, sc);
            (true, json!({}))
        }
        "SetVideoSettingsEx" => {
            let w = data.get("width").and_then(|x| x.as_u64()).unwrap_or(1280) as u32;
            let h = data.get("height").and_then(|x| x.as_u64()).unwrap_or(720) as u32;
            let fps = data.get("fps").and_then(|x| x.as_u64()).unwrap_or(30) as u32;
            let fmt = data.get("colorFormat").and_then(|x| x.as_i64()).unwrap_or(2) as i32;
            let sp = data.get("colorSpace").and_then(|x| x.as_i64()).unwrap_or(2) as i32;
            let rng = data.get("colorRange").and_then(|x| x.as_i64()).unwrap_or(1) as i32;
            let sc = data.get("scaleType").and_then(|x| x.as_i64()).unwrap_or(2) as i32;
            (crate::ws_reset_video_ex(w, h, fps, fmt, sp, rng, sc), json!({}))
        }
        "MoveSourceOrder" => {
            let n = data.get("sourceName").and_then(|x| x.as_str()).unwrap_or("");
            let dir = data.get("direction").and_then(|x| x.as_i64()).unwrap_or(0) as i32;
            (crate::studio_ops::ws_move_source_order(n, dir), json!({}))
        }
        "SetSourceLocked" => {
            let n = data.get("sourceName").and_then(|x| x.as_str()).unwrap_or("");
            let l = data.get("locked").and_then(|x| x.as_bool()).unwrap_or(false);
            (crate::studio_ops::ws_set_source_locked(n, l), json!({}))
        }
        "FitSourceToScreen" => {
            let n = data.get("sourceName").and_then(|x| x.as_str()).unwrap_or("");
            (crate::studio_ops::ws_fit_source_to_screen(n), json!({}))
        }
        "GetSourceTransform" => {
            let n = data.get("sourceName").and_then(|x| x.as_str()).unwrap_or("");
            let v: Value = serde_json::from_str(&crate::studio_ops::ws_get_source_transform_json(n)).unwrap_or(json!({}));
            (true, v)
        }
        "SetStudioTBar" => {
            let t = data.get("t").and_then(|x| x.as_f64()).unwrap_or(0.5) as f32;
            (crate::studio_ops::ws_set_studio_tbar(t), json!({}))
        }
        "FadeToBlack" => (crate::studio_ops::ws_fade_to_black(), json!({})),
        "RenameScene" => {
            let idx = data.get("sceneIndex").and_then(|x| x.as_u64()).unwrap_or(0) as usize;
            let nn = data.get("newName").and_then(|x| x.as_str()).unwrap_or("");
            (crate::studio_ops::ws_rename_scene(idx, nn), json!({}))
        }
        "DuplicateScene" => {
            let idx = data.get("sceneIndex").and_then(|x| x.as_u64()).unwrap_or(0) as usize;
            let nn = data.get("newName").and_then(|x| x.as_str()).unwrap_or("Scene Copy");
            (crate::studio_ops::ws_duplicate_scene(idx, nn), json!({}))
        }
        "DeleteScene" => {
            let idx = data.get("sceneIndex").and_then(|x| x.as_u64()).unwrap_or(0) as usize;
            (crate::studio_ops::ws_delete_scene(idx), json!({}))
        }
        _ => (false, json!({})),
    }
}

/* ---------- Zero-Dependency RFC 6234 SHA-256 + Base64 + CSPRNG ---------- */

pub fn compute_obs_ws_auth(password: &str, salt: &str, challenge: &str) -> String {
    let mut s1 = Vec::with_capacity(password.len() + salt.len());
    s1.extend_from_slice(password.as_bytes());
    s1.extend_from_slice(salt.as_bytes());
    let secret = base64_encode(&sha256(&s1));

    let mut s2 = Vec::with_capacity(secret.len() + challenge.len());
    s2.extend_from_slice(secret.as_bytes());
    s2.extend_from_slice(challenge.as_bytes());
    base64_encode(&sha256(&s2))
}

pub fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}

/// Canonical allowlist check for recording paths (prevents arbitrary filesystem access/remux).
pub fn is_allowed_recording_path(path: &str, must_exist: bool) -> bool {
    if path.is_empty() || path.contains("..") || path.contains('\0') {
        return false;
    }
    let p = std::path::Path::new(path);
    let canonical = if must_exist {
        match std::fs::canonicalize(p) {
            Ok(c) => c,
            Err(_) => return false,
        }
    } else {
        let Some(parent) = p.parent() else { return false; };
        let Some(fname) = p.file_name() else { return false; };
        match std::fs::canonicalize(parent) {
            Ok(cp) => cp.join(fname),
            Err(_) => return false,
        }
    };
    let s = canonical.to_string_lossy();
    const ALLOWED_PREFIXES: &[&str] = &[
        "/storage/emulated/0/Android/data/io.panggung.obs/",
        "/sdcard/Android/data/io.panggung.obs/",
        "/storage/emulated/0/Movies/Panggung",
        "/data/user/0/io.panggung.obs/",
        "/data/data/io.panggung.obs/",
    ];
    ALLOWED_PREFIXES.iter().any(|pref| s.starts_with(pref))
}

/// Fail-closed CSPRNG reading strictly from OS `/dev/urandom` (never falls back to weak timestamps).
pub fn random_b64(num_bytes: usize) -> Option<String> {
    let mut buf = vec![0u8; num_bytes];
    let mut f = File::open("/dev/urandom").ok()?;
    f.read_exact(&mut buf).ok()?;
    Some(base64_encode(&buf))
}

pub fn sha256(data: &[u8]) -> [u8; 32] {
    const K: [u32; 64] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ];
    let mut h: [u32; 8] = [
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    ];

    let bit_len = (data.len() as u64).wrapping_mul(8);
    let mut msg = data.to_vec();
    msg.push(0x80);
    while (msg.len() % 64) != 56 {
        msg.push(0);
    }
    msg.extend_from_slice(&bit_len.to_be_bytes());

    for chunk in msg.chunks_exact(64) {
        let mut w = [0u32; 64];
        for i in 0..16 {
            w[i] = u32::from_be_bytes([chunk[4 * i], chunk[4 * i + 1], chunk[4 * i + 2], chunk[4 * i + 3]]);
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16].wrapping_add(s0).wrapping_add(w[i - 7]).wrapping_add(s1);
        }
        let [mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut hh] = h;
        for i in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let ch = (e & f) ^ ((!e) & g);
            let t1 = hh.wrapping_add(s1).wrapping_add(ch).wrapping_add(K[i]).wrapping_add(w[i]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let maj = (a & b) ^ (a & c) ^ (b & c);
            let t2 = s0.wrapping_add(maj);
            hh = g; g = f; f = e; e = d.wrapping_add(t1);
            d = c; c = b; b = a; a = t1.wrapping_add(t2);
        }
        h[0] = h[0].wrapping_add(a);
        h[1] = h[1].wrapping_add(b);
        h[2] = h[2].wrapping_add(c);
        h[3] = h[3].wrapping_add(d);
        h[4] = h[4].wrapping_add(e);
        h[5] = h[5].wrapping_add(f);
        h[6] = h[6].wrapping_add(g);
        h[7] = h[7].wrapping_add(hh);
    }
    let mut out = [0u8; 32];
    for (i, word) in h.iter().enumerate() {
        out[4 * i..4 * i + 4].copy_from_slice(&word.to_be_bytes());
    }
    out
}

pub fn base64_encode(input: &[u8]) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity((input.len() + 2) / 3 * 4);
    for chunk in input.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = if chunk.len() > 1 { chunk[1] as u32 } else { 0 };
        let b2 = if chunk.len() > 2 { chunk[2] as u32 } else { 0 };
        let n = (b0 << 16) | (b1 << 8) | b2;
        out.push(TABLE[((n >> 18) & 63) as usize] as char);
        out.push(TABLE[((n >> 12) & 63) as usize] as char);
        out.push(if chunk.len() > 1 { TABLE[((n >> 6) & 63) as usize] as char } else { '=' });
        out.push(if chunk.len() > 2 { TABLE[(n & 63) as usize] as char } else { '=' });
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sha256_rfc6234_vectors() {
        let empty = sha256(b"");
        let empty_hex: String = empty.iter().map(|b| format!("{b:02x}")).collect();
        assert_eq!(empty_hex, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

        let abc = sha256(b"abc");
        let abc_hex: String = abc.iter().map(|b| format!("{b:02x}")).collect();
        assert_eq!(abc_hex, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    #[test]
    fn obs_ws_v5_official_auth_vector() {
        // Official obs-websocket v5 protocol specification test vector
        let auth = compute_obs_ws_auth(
            "supersecretpassword",
            "lM1GncleQOaCu9lT1yeUZhFYnqhsLLP1G5lAGo3ixaI=",
            "+IxH4CnCiqpX1rM9scsNynZzbOe4KhDeYcTNS3PDaeY=",
        );
        assert_eq!(auth, "1Ct943GAT+6YQUUX47Ia/ncufilbe6+oD6lY+5kaCu4=");
    }

    #[test]
    fn security_invariants() {
        assert!(!is_running(), "Remote server must be disabled by default");
        assert!(!start_obs_websocket_server(0, false, "short"), "Short passwords must be rejected");
        assert!(constant_time_eq(b"secret123", b"secret123"));
        assert!(!constant_time_eq(b"secret123", b"secret124"));
        assert!(!constant_time_eq(b"secret123", b"short"));

        let (ok_lua, _) = dispatch_request("RunLuaScript", &json!({ "code": "return 1" }));
        assert!(!ok_lua, "RunLuaScript must be blocked over remote API");

        let (ok_so, _) = dispatch_request("LoadPluginSo", &json!({ "soPath": "/data/local/tmp/evil.so" }));
        assert!(!ok_so, "LoadPluginSo must be blocked over remote API");

        let (ok_trav, _) = dispatch_request("RemuxRecording", &json!({ "srcPath": "../etc/passwd", "dstPath": "/tmp/x.mp4" }));
        assert!(!ok_trav, "Path traversal in RemuxRecording must be blocked");
    }
}
