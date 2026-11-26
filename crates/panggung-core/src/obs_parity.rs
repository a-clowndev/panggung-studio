use std::collections::HashMap;
use std::sync::Mutex;

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

#[derive(Clone, Debug, Serialize, Deserialize)]
pub enum UndoAction {
    SwitchScene { prev_idx: usize, next_idx: usize },
    SetMute { source: String, prev: bool, next: bool },
    SetVolume { source: String, prev: f32, next: f32 },
}

#[derive(Default)]
pub struct ParityState {
    pub undo_stack: Vec<UndoAction>,
    pub redo_stack: Vec<UndoAction>,
    pub transition_table: HashMap<String, (String, u32)>, // "From->To" -> (transition_id, ms)
    pub scene_switch_rules: Vec<(String, String)>,        // (app_pkg_regex, scene_name)
    pub profiles: HashMap<String, Value>,
    pub active_profile: String,
    pub loaded_plugins: Vec<(String, bool)>,              // (name, enabled)
    pub output_timer_sec: u32,
}

static PARITY: Mutex<Option<ParityState>> = Mutex::new(None);

fn with_parity<F, R>(f: F) -> R
where
    F: FnOnce(&mut ParityState) -> R,
{
    let mut g = PARITY.lock().unwrap();
    let st = g.get_or_insert_with(|| {
        let mut ps = ParityState::default();
        ps.active_profile = "Default".into();
        ps.profiles.insert(
            "Default".into(),
            json!({ "width": 1280, "height": 720, "fps": 30, "encoder": "android_mediacodec_h264", "bitrate": 4500 }),
        );
        ps.loaded_plugins = vec![
            ("libobs-mod-image-source.so".into(), true),
            ("libobs-mod-obs-ffmpeg.so".into(), true),
            ("libobs-mod-obs-filters.so".into(), true),
            ("libobs-mod-obs-outputs.so".into(), true),
            ("libobs-mod-obs-transitions.so".into(), true),
            ("libobs-mod-rtmp-services.so".into(), true),
            ("libobs-mod-text-freetype2.so".into(), true),
            ("libobs-mod-obs-x264.so".into(), true),
            ("libobs-mod-panggung-3p-pack.so".into(), true),
            ("libobs-mod-panggung-shader.so".into(), true),
        ];
        ps
    });
    f(st)
}

pub fn record_undo(action: UndoAction) {
    with_parity(|st| {
        st.undo_stack.push(action);
        st.redo_stack.clear();
        if st.undo_stack.len() > 100 {
            st.undo_stack.remove(0);
        }
    });
}

pub fn perform_undo() -> bool {
    let act = with_parity(|st| st.undo_stack.pop());
    let Some(a) = act else { return false; };
    match &a {
        UndoAction::SetMute { source, prev, .. } => crate::ws_set_muted(source, *prev),
        UndoAction::SetVolume { source, prev, .. } => crate::ws_set_volume(source, *prev),
        UndoAction::SwitchScene { prev_idx, .. } => {
            let mut g = crate::STATE.lock().unwrap();
            if let Some(s) = g.as_mut() {
                let _ = crate::switch_program_scene(s, *prev_idx);
            }
        }
    }
    with_parity(|st| st.redo_stack.push(a));
    true
}

pub fn perform_redo() -> bool {
    let act = with_parity(|st| st.redo_stack.pop());
    let Some(a) = act else { return false; };
    match &a {
        UndoAction::SetMute { source, next, .. } => crate::ws_set_muted(source, *next),
        UndoAction::SetVolume { source, next, .. } => crate::ws_set_volume(source, *next),
        UndoAction::SwitchScene { next_idx, .. } => {
            let mut g = crate::STATE.lock().unwrap();
            if let Some(s) = g.as_mut() {
                let _ = crate::switch_program_scene(s, *next_idx);
            }
        }
    }
    with_parity(|st| st.undo_stack.push(a));
    true
}

pub fn set_transition_override(from_scene: &str, to_scene: &str, tr_id: &str, ms: u32) {
    with_parity(|st| {
        st.transition_table.insert(format!("{from_scene}->{to_scene}"), (tr_id.to_string(), ms));
    });
}

pub fn add_auto_switcher_rule(app_pattern: &str, scene_name: &str) {
    with_parity(|st| {
        st.scene_switch_rules.push((app_pattern.to_string(), scene_name.to_string()));
    });
}

pub fn save_profile(name: &str, cfg: Value) {
    with_parity(|st| {
        st.profiles.insert(name.to_string(), cfg);
        st.active_profile = name.to_string();
    });
}

pub fn list_profiles_json() -> Value {
    with_parity(|st| {
        json!({
            "activeProfile": st.active_profile,
            "profiles": st.profiles
        })
    })
}

pub fn list_plugins_json() -> Value {
    with_parity(|st| {
        let arr: Vec<Value> = st.loaded_plugins.iter().map(|(n, en)| json!({ "name": n, "enabled": en })).collect();
        json!({ "plugins": arr })
    })
}

/// Imports OBS Studio desktop / Streamlabs / XSplit JSON scene collections (PRO-03 / PRO-04 / DLG-19)
pub fn import_desktop_collection_json(raw: &str) -> Result<usize, String> {
    let v: Value = serde_json::from_str(raw).map_err(|e| e.to_string())?;
    let mut imported = 0usize;
    // OBS Desktop format: { "sources": [ { "id": "scene", "name": "..." }, ... ] }
    if let Some(sources) = v.get("sources").and_then(|s| s.as_array()) {
        for s in sources {
            let id = s.get("id").and_then(|x| x.as_str()).unwrap_or("");
            let name = s.get("name").and_then(|x| x.as_str()).unwrap_or("");
            if id == "scene" && !name.is_empty() {
                if crate::ws_create_scene(name) {
                    imported += 1;
                }
            }
        }
    }
    // Streamlabs / Panggung format: { "scenes": [ "Scene A" | { "name": "Scene A" } ] }
    if let Some(scenes) = v.get("scenes").and_then(|s| s.as_array()) {
        for s in scenes {
            let name = s.as_str().or_else(|| s.get("name").and_then(|x| x.as_str())).unwrap_or("");
            if !name.is_empty() && crate::ws_create_scene(name) {
                imported += 1;
            }
        }
    }
    Ok(imported)
}
