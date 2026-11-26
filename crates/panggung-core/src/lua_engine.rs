use std::ffi::CString;
use std::ptr;
use std::sync::Mutex;

use log::{error, info};
use mlua::Lua;
use obs::sys;

struct LoadedScript {
    path: String,
    description: String,
}

static SCRIPTS: Mutex<Vec<LoadedScript>> = Mutex::new(Vec::new());

/// Executes an OBS Lua script with the `obslua` compatibility table (SCR-01 / SCR-03 / SCR-06).
pub fn load_lua_script(path: &str) -> Result<String, String> {
    let code = std::fs::read_to_string(path).map_err(|e| format!("read {path}: {e}"))?;
    run_lua_code(path, &code)
}

pub fn run_lua_code(label: &str, code: &str) -> Result<String, String> {
    let lua = Lua::new();
    let obs_tbl = lua.create_table().map_err(|e| e.to_string())?;

    // obs.script_log(level, msg)
    obs_tbl
        .set(
            "script_log",
            lua.create_function(|_, (lvl, msg): (i32, String)| {
                info!("[obslua:{lvl}] {msg}");
                Ok(())
            })
            .map_err(|e| e.to_string())?,
        )
        .map_err(|e| e.to_string())?;

    // obs.obs_get_version_string()
    obs_tbl
        .set(
            "obs_get_version_string",
            lua.create_function(|_, ()| Ok(obs::ObsContext::version_string()))
                .map_err(|e| e.to_string())?,
        )
        .map_err(|e| e.to_string())?;

    // obs.obs_source_set_text(source_name, text) + standard obs_source_update helper
    obs_tbl
        .set(
            "panggung_set_source_json",
            lua.create_function(|_, (name, json): (String, String)| {
                let ok = crate::ws_update_source_settings(&name, &json);
                Ok(ok)
            })
            .map_err(|e| e.to_string())?,
        )
        .map_err(|e| e.to_string())?;

    obs_tbl
        .set(
            "panggung_set_muted",
            lua.create_function(|_, (name, muted): (String, bool)| {
                crate::ws_set_muted(&name, muted);
                Ok(())
            })
            .map_err(|e| e.to_string())?,
        )
        .map_err(|e| e.to_string())?;

    obs_tbl
        .set(
            "panggung_switch_scene",
            lua.create_function(|_, name: String| Ok(crate::ws_switch_scene_by_name(&name)))
                .map_err(|e| e.to_string())?,
        )
        .map_err(|e| e.to_string())?;

    // Standard obslua source/data wrappers for upstream script compatibility
    obs_tbl
        .set(
            "obs_data_create",
            lua.create_function(|lua, ()| lua.create_table())
                .map_err(|e| e.to_string())?,
        )
        .map_err(|e| e.to_string())?;

    obs_tbl
        .set(
            "obs_data_set_string",
            lua.create_function(|_, (tbl, k, v): (mlua::Table, String, String)| {
                tbl.set(k, v)?;
                Ok(())
            })
            .map_err(|e| e.to_string())?,
        )
        .map_err(|e| e.to_string())?;

    obs_tbl
        .set(
            "obs_data_set_int",
            lua.create_function(|_, (tbl, k, v): (mlua::Table, String, i64)| {
                tbl.set(k, v)?;
                Ok(())
            })
            .map_err(|e| e.to_string())?,
        )
        .map_err(|e| e.to_string())?;

    obs_tbl
        .set(
            "obs_data_release",
            lua.create_function(|_, _tbl: mlua::Value| Ok(()))
                .map_err(|e| e.to_string())?,
        )
        .map_err(|e| e.to_string())?;

    obs_tbl
        .set(
            "obs_get_source_by_name",
            lua.create_function(|lua, name: String| {
                let Ok(c) = CString::new(name.as_str()) else { return Ok(mlua::Value::Nil); };
                let src = unsafe { sys::obs_get_source_by_name(c.as_ptr()) };
                if src.is_null() {
                    Ok(mlua::Value::Nil)
                } else {
                    unsafe { sys::obs_source_release(src); }
                    Ok(mlua::Value::String(lua.create_string(&name)?))
                }
            })
            .map_err(|e| e.to_string())?,
        )
        .map_err(|e| e.to_string())?;

    obs_tbl
        .set(
            "obs_source_update",
            lua.create_function(|_, (src_name, settings): (String, mlua::Table)| {
                let mut map = serde_json::Map::new();
                for pair in settings.pairs::<String, mlua::Value>().flatten() {
                    match pair.1 {
                        mlua::Value::String(s) => {
                            if let Ok(st) = s.to_str() {
                                map.insert(pair.0, serde_json::Value::String(st.to_string()));
                            }
                        }
                        mlua::Value::Integer(i) => {
                            map.insert(pair.0, serde_json::Value::Number(i.into()));
                        }
                        mlua::Value::Number(n) => {
                            if let Some(num) = serde_json::Number::from_f64(n) {
                                map.insert(pair.0, serde_json::Value::Number(num));
                            }
                        }
                        mlua::Value::Boolean(b) => {
                            map.insert(pair.0, serde_json::Value::Bool(b));
                        }
                        _ => {}
                    }
                }
                let json = serde_json::Value::Object(map).to_string();
                let _ = crate::ws_update_source_settings(&src_name, &json);
                Ok(())
            })
            .map_err(|e| e.to_string())?,
        )
        .map_err(|e| e.to_string())?;

    obs_tbl
        .set(
            "obs_source_release",
            lua.create_function(|_, _v: mlua::Value| Ok(()))
                .map_err(|e| e.to_string())?,
        )
        .map_err(|e| e.to_string())?;

    // Register as both global `obslua` and package.loaded["obslua"]
    lua.globals().set("obslua", obs_tbl.clone()).map_err(|e| e.to_string())?;
    let pkg: mlua::Table = lua.globals().get("package").map_err(|e| e.to_string())?;
    let loaded: mlua::Table = pkg.get("loaded").map_err(|e| e.to_string())?;
    loaded.set("obslua", obs_tbl).map_err(|e| e.to_string())?;

    lua.load(code).exec().map_err(|e| {
        error!("Lua script error ({label}): {e}");
        e.to_string()
    })?;

    let desc = if let Ok(f) = lua.globals().get::<mlua::Function>("script_description") {
        f.call::<String>(()).unwrap_or_default()
    } else {
        "Loaded OBS Lua script".to_string()
    };

    if let Ok(f) = lua.globals().get::<mlua::Function>("script_load") {
        let empty = lua.create_table().map_err(|e| e.to_string())?;
        let _ = f.call::<()>(empty);
    }

    SCRIPTS.lock().unwrap().push(LoadedScript {
        path: label.to_string(),
        description: desc.clone(),
    });
    info!("Loaded Lua script '{label}': {desc}");
    Ok(desc)
}

pub fn list_scripts_json() -> String {
    let guard = SCRIPTS.lock().unwrap();
    let arr: Vec<serde_json::Value> = guard
        .iter()
        .map(|s| serde_json::json!({ "path": s.path, "description": s.description }))
        .collect();
    serde_json::Value::Array(arr).to_string()
}

pub fn clear_scripts() {
    SCRIPTS.lock().unwrap().clear();
}
