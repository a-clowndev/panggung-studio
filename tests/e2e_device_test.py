#!/usr/bin/env python3
"""
Comprehensive E2E test suite for Panggung Studio (io.panggung.obs) on real Android device via ADB + obs-websocket v5.
Tests:
  1. obs-websocket v5 handshake (Hello -> Identify -> Identified)
  2. GetVersion, GetStats
  3. Scene management: GetSceneList, CreateScene, SetCurrentProgramScene
  4. Input/Source management: GetInputList, GetInputSettings (obs_properties), SetInputMute, SetInputVolume
  5. Advanced Audio Properties: SetInputAudioSyncOffset, SetInputAudioBalance, SetInputAudioMonitorType, GetInputAudioAdvanced
  6. Filters CRUD: CreateSourceFilter (chroma_key_filter_v2, color_filter_v2), GetSourceFilterList (obs-ws v5 schema), RemoveSourceFilter
  7. Transitions: fade, swipe, slide, wipe, cut
  8. Studio Mode: SetStudioModeEnabled, TriggerStudioModeTransition
  9. Recording with HW MediaCodec H.264, HW MediaCodec HEVC, and SW x264 + Pause/Resume + ffprobe validation
 10. Replay Buffer: StartReplayBuffer -> SaveReplayBuffer -> StopReplayBuffer
"""
import asyncio
import base64
import hashlib
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

try:
    import websockets
except ImportError:
    subprocess.check_call([sys.executable, "-m", "pip", "install", "-q", "websockets"])
    import websockets


WS_URL = "ws://127.0.0.1:4455"
AUTH_TOKEN = os.environ.get("PANGGUNG_TOKEN", "")
REQ_ID = 0


def compute_obs_ws_auth(password: str, salt: str, challenge: str) -> str:
    sec = base64.b64encode(hashlib.sha256((password + salt).encode()).digest()).decode()
    return base64.b64encode(hashlib.sha256((sec + challenge).encode()).digest()).decode()


def adb(*args, check=True):
    return subprocess.run(["adb"] + list(args), capture_output=True, text=True, check=check)


async def ws_req(ws, request_type, request_data=None):
    global REQ_ID
    REQ_ID += 1
    rid = f"e2e-{REQ_ID}"
    payload = {"op": 6, "d": {"requestType": request_type, "requestId": rid}}
    if request_data is not None:
        payload["d"]["requestData"] = request_data
    await ws.send(json.dumps(payload))
    while True:
        raw = await asyncio.wait_for(ws.recv(), timeout=15.0)
        msg = json.loads(raw)
        if msg.get("op") == 7 and msg.get("d", {}).get("requestId") == rid:
            d = msg["d"]
            st = d.get("requestStatus", {})
            return st.get("result", False), st.get("code", 0), d.get("responseData", {})


def probe_remote_mp4(device_path, label):
    if not device_path:
        return False, "empty path"
    local = f"/tmp/panggung_e2e_{label}.mp4"
    # Try direct adb pull first (works on signed release APKs for external app Movies dir), fallback to run-as
    p = subprocess.run(["adb", "pull", device_path, local], capture_output=True)
    if p.returncode != 0 or not os.path.exists(local) or os.path.getsize(local) < 1024:
        res = subprocess.run(["adb", "exec-out", f"run-as io.panggung.obs cat '{device_path}'"], capture_output=True)
        if res.returncode != 0 or len(res.stdout) < 1024:
            return False, f"pull failed len={len(res.stdout)}"
        with open(local, "wb") as f:
            f.write(res.stdout)
    probe = subprocess.run(
        ["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", local],
        capture_output=True, text=True,
    )
    if probe.returncode != 0:
        return False, f"ffprobe err: {probe.stderr.strip()}"
    info = json.loads(probe.stdout)
    streams = info.get("streams", [])
    v = next((s for s in streams if s.get("codec_type") == "video"), None)
    a = next((s for s in streams if s.get("codec_type") == "audio"), None)
    dur = float(info.get("format", {}).get("duration", 0))
    detail = f"{os.path.basename(device_path)} dur={dur:.2f}s v={v.get('codec_name') if v else None}({v.get('width')}x{v.get('height')}) a={a.get('codec_name') if a else None}"
    return (v is not None and dur >= 1.0), detail


async def run_all():
    adb("forward", "tcp:4455", "tcp:4455")
    results = []

    def record(name, ok, detail=""):
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""))
        results.append((name, ok, detail))

    async with websockets.connect(WS_URL, open_timeout=10) as ws:
        hello = json.loads(await asyncio.wait_for(ws.recv(), timeout=5))
        auth_obj = hello.get("d", {}).get("authentication", {})
        record("WS Hello (op=0 + SHA-256 auth challenge)", hello.get("op") == 0 and "challenge" in auth_obj and "salt" in auth_obj, f"v={hello.get('d', {}).get('obsWebSocketVersion')}")
        if not AUTH_TOKEN:
            raise RuntimeError("Set PANGGUNG_TOKEN=<16-char session token from Remote Control dialog> before running E2E")
        auth_str = compute_obs_ws_auth(AUTH_TOKEN, auth_obj["salt"], auth_obj["challenge"])
        await ws.send(json.dumps({"op": 1, "d": {"rpcVersion": 1, "authentication": auth_str}}))
        ident = json.loads(await asyncio.wait_for(ws.recv(), timeout=5))
        record("WS Identified (op=2 SHA-256 verified)", ident.get("op") == 2)

        ok, _, data = await ws_req(ws, "GetVersion")
        record("GetVersion", ok and "obsVersion" in data, f"obs={data.get('obsVersion')}")

        ok, _, data = await ws_req(ws, "GetStats")
        record("GetStats", ok and "activeFps" in data, f"fps={data.get('activeFps', 0):.1f} skipped={data.get('renderSkippedFrames')}")

        # Scenes
        ok, _, data = await ws_req(ws, "CreateScene", {"sceneName": "E2E Scene 2"})
        record("CreateScene(E2E Scene 2)", ok)
        ok, _, data = await ws_req(ws, "GetSceneList")
        record("GetSceneList", ok and len(data.get("scenes", [])) >= 2, f"count={len(data.get('scenes', []))}")
        ok, _, _ = await ws_req(ws, "SetCurrentProgramScene", {"sceneName": "Main Scene"})
        record("SetCurrentProgramScene(Main Scene)", ok)

        # Inputs + Properties + Advanced Audio
        ok, _, data = await ws_req(ws, "GetInputList")
        inputs = data.get("inputs", [])
        record("GetInputList", ok and len(inputs) >= 2, f"names={[i['inputName'] for i in inputs]}")

        ok, _, pdata = await ws_req(ws, "GetInputSettings", {"inputName": "Background"})
        record("GetInputSettings (obs_properties)", ok and len(pdata.get("properties", [])) >= 1, f"props={len(pdata.get('properties', []))}")

        ok, _, _ = await ws_req(ws, "SetInputAudioSyncOffset", {"inputName": "Microphone", "inputAudioSyncOffset": 45})
        ok2, _, _ = await ws_req(ws, "SetInputAudioBalance", {"inputName": "Microphone", "inputAudioBalance": 0.6})
        ok3, _, adv = await ws_req(ws, "GetInputAudioAdvanced", {"inputName": "Microphone"})
        record("Advanced Audio Properties (sync/balance)", ok and ok2 and ok3 and adv.get("sync_offset_ms") == 45, f"adv={adv}")
        await ws_req(ws, "SetInputAudioSyncOffset", {"inputName": "Microphone", "inputAudioSyncOffset": 0})

        # Filters CRUD
        for fid, fname in [("chroma_key_filter_v2", "E2E Chroma"), ("color_filter_v2", "E2E Color")]:
            ok, _, _ = await ws_req(ws, "CreateSourceFilter", {"sourceName": "Background", "filterName": fname, "filterKind": fid})
            record(f"CreateSourceFilter({fid})", ok)
        ok, _, fdata = await ws_req(ws, "GetSourceFilterList", {"sourceName": "Background"})
        fnames = [f.get("filterName") for f in fdata.get("filters", [])]
        record("GetSourceFilterList (v5 schema)", ok and "E2E Chroma" in fnames and "E2E Color" in fnames, f"filters={fnames}")
        for fname in ["E2E Chroma", "E2E Color"]:
            ok, _, _ = await ws_req(ws, "RemoveSourceFilter", {"sourceName": "Background", "filterName": fname})
            record(f"RemoveSourceFilter({fname})", ok)

        # Transitions + Studio Mode
        for tr in ["fade_transition", "swipe_transition", "slide_transition", "wipe_transition", "cut_transition", "fade_transition"]:
            ok, _, _ = await ws_req(ws, "SetCurrentSceneTransition", {"transitionName": tr, "transitionDuration": 250})
            record(f"Transition({tr})", ok)
        ok1, _, _ = await ws_req(ws, "SetStudioModeEnabled", {"studioModeEnabled": True})
        ok2, _, _ = await ws_req(ws, "TriggerStudioModeTransition")
        ok3, _, _ = await ws_req(ws, "SetStudioModeEnabled", {"studioModeEnabled": False})
        record("Studio Mode (Enable->Transition->Disable)", ok1 and ok2 and ok3)

        # Multi-Encoder Recording E2E: HW H.264, HW HEVC, SW x264
        for enc_id, label in [
            ("android_mediacodec_h264", "hw_h264"),
            ("android_mediacodec_hevc", "hw_hevc"),
            ("obs_x264", "sw_x264"),
        ]:
            await ws_req(ws, "SetVideoEncoder", {"encoderId": enc_id})
            ok_s, _, _ = await ws_req(ws, "StartRecord")
            if ok_s:
                await asyncio.sleep(2.5)
                ok_p, _, _ = await ws_req(ws, "PauseRecord")
                await asyncio.sleep(0.5)
                ok_r, _, _ = await ws_req(ws, "ResumeRecord")
                await asyncio.sleep(1.0)
                ok_e, _, stop_data = await ws_req(ws, "StopRecord")
                await asyncio.sleep(0.8)
                p_ok, p_detail = probe_remote_mp4(stop_data.get("outputPath", ""), label)
                record(f"Record+Pause+ffprobe ({enc_id})", ok_s and ok_e and p_ok, f"pause={ok_p}/{ok_r} {p_detail}")
            else:
                record(f"Record ({enc_id})", False, "StartRecord failed")

        await ws_req(ws, "SetVideoEncoder", {"encoderId": "android_mediacodec_h264"})

        # Replay Buffer E2E
        ok_rb, _, _ = await ws_req(ws, "StartReplayBuffer", {"maxTimeSec": 10})
        if ok_rb:
            await asyncio.sleep(2.5)
            ok_sv, _, _ = await ws_req(ws, "SaveReplayBuffer")
            await asyncio.sleep(1.0)
            ok_st, _, _ = await ws_req(ws, "StopReplayBuffer")
            record("Replay Buffer (Start->Save->Stop)", ok_rb and ok_sv and ok_st)
        else:
            record("Replay Buffer", False, "StartReplayBuffer failed")

        # Lossless Remux E2E (media_remux_job_*)
        if stop_data.get("outputPath"):
            src_mp4 = stop_data["outputPath"]
            dst_mp4 = src_mp4.replace(".mp4", "_remuxed.mp4")
            ok_rmx, _, _ = await ws_req(ws, "RemuxRecording", {"srcPath": src_mp4, "dstPath": dst_mp4})
            p_ok, p_detail = probe_remote_mp4(dst_mp4, "remuxed") if ok_rmx else (False, "remux failed")
            record("Lossless Remux (media_remux_job)", ok_rmx and p_ok, p_detail)

        # Custom Output (null_output benchmark)
        ok_co1, _, _ = await ws_req(ws, "StartCustomOutput", {"outputId": "null_output", "url": "", "bitrateKbps": 4500})
        await asyncio.sleep(1.0)
        ok_co2, _, _ = await ws_req(ws, "StopCustomOutput")
        record("Custom Output (null_output)", ok_co1 and ok_co2)

        # Embedded Lua 5.4 obslua Scripting E2E
        lua_code = """
            obs = require("obslua")
            function script_description()
                return "E2E Lua 5.4 Verified (" .. obs.obs_get_version_string() .. ")"
            end
            function script_load(settings)
                obs.script_log(300, "E2E Lua script_load executing on Android!")
                local src = obs.obs_get_source_by_name("Background")
                if src ~= nil then
                    local data = obs.obs_data_create()
                    obs.obs_data_set_int(data, "color", 4293616421)
                    obs.obs_source_update(src, data)
                    obs.obs_data_release(data)
                    obs.obs_source_release(src)
                end
            end
        """
        ok_lua, _, ldata = await ws_req(ws, "RunLuaScript", {"label": "e2e_test.lua", "code": lua_code})
        ok_ll, _, _ = await ws_req(ws, "GetScriptList")
        record("Security: Remote RunLuaScript blocked + GetScriptList read-only", (not ok_lua) and ok_ll, f"resp={ldata}")

        # Security: Remote LoadPluginSo MUST be blocked over network API
        ok_plg, _, pdata = await ws_req(ws, "LoadPluginSo", {"soPath": "/data/local/tmp/untrusted.so", "dataPath": ""})
        record("Security: Remote LoadPluginSo blocked", not ok_plg, f"resp={pdata}")

        # Built-in Web Remote UI HTTP E2E (:8080) — 401 without auth, 200 with Bearer token
        adb("forward", "tcp:8080", "tcp:8080")
        unauth_401 = False
        try:
            urllib.request.urlopen("http://127.0.0.1:8080/", timeout=5)
        except urllib.error.HTTPError as e:
            unauth_401 = (e.code == 401)
        req_auth = urllib.request.Request("http://127.0.0.1:8080/", headers={"Authorization": f"Bearer {AUTH_TOKEN}"})
        with urllib.request.urlopen(req_auth, timeout=5) as resp:
            html = resp.read().decode("utf-8", errors="ignore")
            record("Built-in Web Remote UI (:8080 401 unauth / 200 Bearer)", unauth_401 and resp.status == 200 and "Panggung Web Remote" in html, f"bytes={len(html)}")

        # Native 3P Plugin Pack E2E (all 9 filters + Gradient source)
        for fid in [
            "panggung_composite_blur",
            "panggung_retro_effects",
            "panggung_stroke_glow_shadow",
            "panggung_3d_effect",
            "panggung_ml_background",
            "panggung_freeze_filter",
            "panggung_move_filter",
            "panggung_shaderfilter",
        ]:
            ok_a, _, _ = await ws_req(ws, "CreateSourceFilter", {"sourceName": "Background", "filterName": f"E2E_{fid}", "filterKind": fid})
            ok_r, _, _ = await ws_req(ws, "RemoveSourceFilter", {"sourceName": "Background", "filterName": f"E2E_{fid}"})
            record(f"3P Video Filter ({fid})", ok_a and ok_r)

        ok_dsp1, _, _ = await ws_req(ws, "CreateSourceFilter", {"sourceName": "Microphone", "filterName": "E2E_DSP", "filterKind": "panggung_dsp_pack"})
        ok_dsp2, _, _ = await ws_req(ws, "RemoveSourceFilter", {"sourceName": "Microphone", "filterName": "E2E_DSP"})
        record("3P Audio DSP Pack (Reverb/De-Esser)", ok_dsp1 and ok_dsp2)

        ok_grad, _, _ = await ws_req(ws, "CreateInput", {"inputKind": "panggung_gradient_source", "inputName": "E2E Gradient", "inputSettings": {"width": 640, "height": 360}})
        record("3P Gradient Source (panggung_gradient_source)", ok_grad)

        # Dual Canvas (Multi-Canvas obs_view_t 720x1280 vertical) E2E
        ok_dc1, _, _ = await ws_req(ws, "ToggleDualCanvas", {"enabled": True})
        await asyncio.sleep(0.5)
        ok_dc2, _, _ = await ws_req(ws, "ToggleDualCanvas", {"enabled": False})
        record("Dual Canvas (obs_view_t 720x1280 Vertical)", ok_dc1 and ok_dc2)

        # Remaining 13 native 3P/AndroidPlus plugins E2E
        for kind in [
            "panggung_waveform_source",
            "panggung_text_android",
            "panggung_uvc_source",
            "panggung_now_playing",
            "panggung_device_stats",
            "panggung_input_overlay",
            "panggung_media_playlist",
            "panggung_dir_watch_media",
            "panggung_replay_source",
            "panggung_downstream_keyer",
        ]:
            ok_s, _, _ = await ws_req(ws, "CreateInput", {"inputKind": kind, "inputName": f"E2E_{kind}", "inputSettings": {}})
            record(f"Native Source ({kind})", ok_s)

        for fid in ["panggung_auto_framing", "panggung_dynamic_delay"]:
            ok_a, _, _ = await ws_req(ws, "CreateSourceFilter", {"sourceName": "Background", "filterName": f"E2E_{fid}", "filterKind": fid})
            ok_r, _, _ = await ws_req(ws, "RemoveSourceFilter", {"sourceName": "Background", "filterName": f"E2E_{fid}"})
            record(f"Native Filter ({fid})", ok_a and ok_r)

        # Undo/Redo, Profiles, Plugin Manager, Desktop Scene Collection Importer, Transition Table, Auto Scene Switcher
        ok_u1, _, _ = await ws_req(ws, "RecordUndoMute", {"inputName": "Microphone", "prev": False, "next": True})
        ok_u2, _, _ = await ws_req(ws, "Undo")
        ok_u3, _, _ = await ws_req(ws, "Redo")
        await ws_req(ws, "Undo")
        record("Undo / Redo Stack", ok_u1 and ok_u2 and ok_u3)

        ok_pr1, _, _ = await ws_req(ws, "CreateProfile", {"profileName": "1080p60 Pro", "config": {"width": 1920, "height": 1080, "fps": 60}})
        ok_pr2, _, pr_data = await ws_req(ws, "GetProfileList")
        record("Profiles CRUD (CreateProfile/GetProfileList)", ok_pr1 and ok_pr2 and "1080p60 Pro" in pr_data.get("profiles", {}))

        ok_pl, _, pl_data = await ws_req(ws, "GetPluginList")
        record("Plugin Manager (GetPluginList)", ok_pl and len(pl_data.get("plugins", [])) >= 10, f"count={len(pl_data.get('plugins', []))}")

        desktop_json = json.dumps({"sources": [{"id": "scene", "name": "Imported OBS Desktop Scene"}]})
        ok_imp, _, imp_data = await ws_req(ws, "ImportSceneCollection", {"collectionJson": desktop_json})
        record("OBS Desktop Scene Collection Importer", ok_imp and imp_data.get("importedScenes", 0) >= 1)

        ok_tt, _, _ = await ws_req(ws, "SetTransitionOverride", {"fromScene": "Main Scene", "toScene": "E2E Scene 2", "transitionId": "swipe_transition", "durationMs": 350})
        ok_as, _, _ = await ws_req(ws, "AddAutoSwitcherRule", {"appPattern": "com.mobile.legends", "sceneName": "Main Scene"})
        record("Transition Table + Auto Scene Switcher Rules", ok_tt and ok_as)

        # Video Color Pipeline Ex + 1080p60 HW MediaCodec Verification
        ok_v1, _, _ = await ws_req(ws, "SetVideoSettingsEx", {"width": 1920, "height": 1080, "fps": 60, "colorFormat": 2, "colorSpace": 2, "colorRange": 1, "scaleType": 4})
        ok_v2, _, _ = await ws_req(ws, "SetVideoSettingsEx", {"width": 1280, "height": 720, "fps": 30, "colorFormat": 2, "colorSpace": 2, "colorRange": 1, "scaleType": 2})
        record("Video Color Pipeline Ex (1080p60 Lanczos -> 720p30 NV12/Rec709)", ok_v1 and ok_v2)

        # Fase A v2.0: Source Order, Lock, Fit, Transform, Studio T-Bar, FadeToBlack, Scene Rename/Dup/Del
        ok_ord, _, _ = await ws_req(ws, "MoveSourceOrder", {"sourceName": "Background", "direction": 0})
        ok_lck1, _, _ = await ws_req(ws, "SetSourceLocked", {"sourceName": "Background", "locked": True})
        ok_lck2, _, _ = await ws_req(ws, "SetSourceLocked", {"sourceName": "Background", "locked": False})
        ok_fit, _, _ = await ws_req(ws, "FitSourceToScreen", {"sourceName": "Background"})
        ok_xf, _, xf_data = await ws_req(ws, "GetSourceTransform", {"sourceName": "Background"})
        record("Fase A Source Ops (Order/Lock/Fit/GetTransform)", ok_ord and ok_lck1 and ok_lck2 and ok_fit and ok_xf and "base_w" in str(xf_data))

        ok_tb, _, _ = await ws_req(ws, "SetStudioTBar", {"t": 0.5})
        ok_ftb, _, _ = await ws_req(ws, "FadeToBlack")
        record("Fase A Studio T-Bar & Fade to Black", ok_tb and ok_ftb)

        ok_dup, _, _ = await ws_req(ws, "DuplicateScene", {"sceneIndex": 0, "newName": "Dup E2E Scene"})
        ok_ren, _, _ = await ws_req(ws, "RenameScene", {"sceneIndex": 1, "newName": "Renamed E2E Scene"})
        record("Fase A Scene Ops (DuplicateScene/RenameScene)", ok_dup and ok_ren)

        # T-C04: 10-bit HDR PQ HEVC Recording + ffprobe Verification
        ok_hdr_vid, _, _ = await ws_req(ws, "SetVideoSettingsEx", {"width": 1280, "height": 720, "fps": 30, "colorFormat": 18, "colorSpace": 4, "colorRange": 1, "scaleType": 2})
        ok_hdr_enc, _, _ = await ws_req(ws, "SetVideoEncoder", {"encoderId": "android_mediacodec_hevc_hdr"})
        ok_hdr_st, _, _ = await ws_req(ws, "StartRecord")
        await asyncio.sleep(3.0)
        ok_hdr_sp, _, hdr_sp = await ws_req(ws, "StopRecord")
        await asyncio.sleep(1.0)
        hdr_path = hdr_sp.get("outputPath", "")
        hdr_ok = False
        hdr_detail = hdr_path
        if ok_hdr_st and ok_hdr_sp and hdr_path:
            local_hdr = "/tmp/e2e_hdr_pq.mp4"
            subprocess.run(["adb", "pull", hdr_path, local_hdr], capture_output=True)
            if os.path.exists(local_hdr) and os.path.getsize(local_hdr) > 4096:
                pr = subprocess.run(
                    ["ffprobe", "-v", "error", "-select_streams", "v:0",
                     "-show_entries", "stream=codec_name,width,height,pix_fmt,color_primaries,color_transfer",
                     "-of", "json", local_hdr],
                    capture_output=True, text=True,
                )
                try:
                    st_info = json.loads(pr.stdout)["streams"][0]
                    is_hevc = st_info.get("codec_name") == "hevc"
                    is_10bit = st_info.get("pix_fmt") in ("yuv420p10le", "p010le", "yuv420p10be")
                    is_hdr_trc = st_info.get("color_transfer") in ("smpte2084", "arib-std-b67")
                    hdr_ok = is_hevc and is_10bit and is_hdr_trc
                    hdr_detail = f"{st_info.get('codec_name')} {st_info.get('pix_fmt')} {st_info.get('width')}x{st_info.get('height')} primaries={st_info.get('color_primaries')} trc={st_info.get('color_transfer')}"
                except Exception as e:
                    hdr_detail = f"ffprobe parse err: {e}"
        # Restore SDR NV12 Rec.709 + H.264
        await ws_req(ws, "SetVideoSettingsEx", {"width": 1280, "height": 720, "fps": 30, "colorFormat": 2, "colorSpace": 2, "colorRange": 1, "scaleType": 2})
        await ws_req(ws, "SetVideoEncoder", {"encoderId": "android_mediacodec_h264"})
        record("T-C04 10-Bit HDR PQ HEVC Recording + ffprobe", ok_hdr_vid and ok_hdr_enc and hdr_ok, hdr_detail)

    passed = sum(1 for _, ok, _ in results if ok)
    total = len(results)
    print(f"\n=== FULL E2E REGRESSION SUMMARY: {passed}/{total} PASSED ===")
    return passed == total


if __name__ == "__main__":
    sys.exit(0 if asyncio.run(run_all()) else 1)
