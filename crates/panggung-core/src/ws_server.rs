use std::net::TcpListener;
use std::thread;

use log::{error, info, warn};
use serde_json::{Value, json};
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

pub fn spawn_obs_websocket_server(port: u16) {
    thread::Builder::new()
        .name("obs-ws-v5".into())
        .spawn(move || {
            let addr = format!("0.0.0.0:{port}");
            let listener = match TcpListener::bind(&addr) {
                Ok(l) => {
                    info!("obs-websocket v5 server listening on ws://{addr}");
                    l
                }
                Err(e) => {
                    error!("Failed to bind obs-websocket on {addr}: {e}");
                    return;
                }
            };

            for stream in listener.incoming().flatten() {
                thread::spawn(move || handle_client(stream));
            }
        })
        .ok();
}

fn handle_client(stream: std::net::TcpStream) {
    let mut ws = match accept(stream) {
        Ok(w) => w,
        Err(e) => {
            warn!("WS handshake failed: {e}");
            return;
        }
    };

    // OpCode 0: Hello (obs-websocket v5.5.0, no auth required by default on local LAN)
    let hello = json!({
        "op": 0,
        "d": {
            "obsStudioVersion": obs::ObsContext::version_string(),
            "obsWebSocketVersion": "5.5.0",
            "rpcVersion": 1
        }
    });
    if ws.send(Message::Text(hello.to_string().into())).is_err() {
        return;
    }

    while let Ok(msg) = ws.read() {
        let Message::Text(txt) = msg else { continue };
        let Ok(v) = serde_json::from_str::<Value>(&txt) else { continue };
        let op = v.get("op").and_then(|x| x.as_u64()).unwrap_or(999);
        let d = v.get("d").cloned().unwrap_or(Value::Null);

        match op {
            // OpCode 1: Identify -> reply OpCode 2: Identified
            1 => {
                let identified = json!({
                    "op": 2,
                    "d": { "negotiatedRpcVersion": 1 }
                });
                let _ = ws.send(Message::Text(identified.to_string().into()));
            }
            // OpCode 6: Request -> reply OpCode 7: RequestResponse
            6 => {
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
                let _ = ws.send(Message::Text(resp.to_string().into()));
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
        "RunLuaScript" => {
            let label = data.get("label").and_then(|x| x.as_str()).unwrap_or("remote.lua");
            let code = data.get("code").and_then(|x| x.as_str()).unwrap_or("");
            match crate::lua_engine::run_lua_code(label, code) {
                Ok(desc) => (true, json!({ "description": desc })),
                Err(e) => (false, json!({ "error": e })),
            }
        }
        "GetScriptList" => {
            let raw = crate::lua_engine::list_scripts_json();
            let v: Value = serde_json::from_str(&raw).unwrap_or(json!([]));
            (true, json!({ "scripts": v }))
        }
        "LoadPluginSo" => {
            let so = data.get("soPath").and_then(|x| x.as_str()).unwrap_or("");
            let dp = data.get("dataPath").and_then(|x| x.as_str()).unwrap_or("");
            (crate::ws_load_plugin_so(so, dp), json!({}))
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
                let added = crate::add_source_to_scene(scene, kind, name, Some(&settings), bw, bh);
                if added { state.scenes[idx].sources.push(name.to_string()); }
                added
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
