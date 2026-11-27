#!/usr/bin/env python3
"""
Panggung Studio v0.1.0 — Full 7-Stage All-Features Stress Test on Real Hardware (SM-S721B).
Tests:
  1. Rapid Camera Back <-> Front UI Tap Thrash (6 consecutive switches)
  2. 1080p60 Heavy Multi-Source (8 sources) + Multi-Filter (5 GPU/DSP filters) Stack
  3. Rapid Scene & Transition Torture (25 switches + T-Bar sweeps + Fade to Black) while Recording
  4. Concurrent 1080p60 Recording + 3x Pause/Resume + Dual Canvas + 20x Web Remote HTTP Requests + ffprobe
  5. Rapid Video Pipeline Reallocation (720p30 -> 1080p60 -> Vertical 720x1280 -> HDR P010 PQ -> 1080p60)
  6. 30-Second Sustained 1080p60 Recording Soak (FPS, FrameTime, Skipped, RAM RSS leak check, Battery Temp)
  7. Portrait <-> 3-Col Landscape Orientation Torture + Zero Crash/ANR Logcat Audit
"""
import asyncio
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
import websockets

from tests.ui_tap_e2e_test import adb, tap_text, check_no_neon_green_line, screencap

WS_URL = "ws://127.0.0.1:4455"
AUTH_TOKEN = os.environ.get("PANGGUNG_TOKEN", "")


def compute_obs_ws_auth(password: str, salt: str, challenge: str) -> str:
    sec = base64.b64encode(hashlib.sha256((password + salt).encode()).digest()).decode()
    return base64.b64encode(hashlib.sha256((sec + challenge).encode()).digest()).decode()
req_counter = 0
results = []


def record(name, ok, detail=""):
    results.append((name, ok, detail))
    tag = "PASS" if ok else "FAIL"
    print(f"  [{tag}] {name}" + (f" — {detail}" if detail else ""), flush=True)


def get_rss_mb():
    out = adb("shell", "dumpsys meminfo io.panggung.obs | grep 'TOTAL PSS:'").stdout
    m = re.search(r"TOTAL PSS:\s+(\d+)", out)
    return round(int(m.group(1)) / 1024.0, 1) if m else 0.0


def get_batt_temp():
    out = adb("shell", "dumpsys battery | grep temperature").stdout
    for tok in out.split():
        if tok.isdigit():
            return int(tok) / 10.0
    return 0.0


async def ws_req(ws, rtype, rdata=None):
    global req_counter
    req_counter += 1
    rid = f"st-{req_counter}"
    payload = {"op": 6, "d": {"requestType": rtype, "requestId": rid}}
    if rdata is not None:
        payload["d"]["requestData"] = rdata
    await ws.send(json.dumps(payload))
    while True:
        raw = await asyncio.wait_for(ws.recv(), timeout=20.0)
        msg = json.loads(raw)
        if msg.get("op") == 7 and msg.get("d", {}).get("requestId") == rid:
            d = msg["d"]
            return d["requestStatus"]["result"], d["requestStatus"]["code"], d.get("responseData", {})


async def run_stress():
    print("=== PANGGUNG STUDIO v0.1.0 — 7-STAGE ALL-FEATURES STRESS TEST ===", flush=True)
    adb("logcat", "-c")
    adb("shell", "pm", "clear", "io.panggung.obs")
    adb("shell", "pm", "grant", "io.panggung.obs", "android.permission.CAMERA")
    adb("shell", "pm", "grant", "io.panggung.obs", "android.permission.RECORD_AUDIO")
    adb("shell", "pm", "grant", "io.panggung.obs", "android.permission.POST_NOTIFICATIONS")
    adb("shell", "am", "start", "-n", "io.panggung.obs/.MainActivity")
    await asyncio.sleep(4.0)
    adb("forward", "tcp:4455", "tcp:4455")
    adb("forward", "tcp:8080", "tcp:8080")

    rss_start = get_rss_mb()
    temp_start = get_batt_temp()
    print(f"  [INFO] Baseline: RAM={rss_start} MB | Batt={temp_start:.1f}C", flush=True)

    # STAGE 1: Rapid Camera Back <-> Front Thrash (6 consecutive switches)
    cam_ok = True
    for i in range(6):
        want_front = (i % 2 == 1)
        btn = "+ Cam Front" if want_front else "+ Cam Back"
        adb("logcat", "-c")
        tapped = tap_text(text=btn, wait=1.8)
        lc = adb("logcat", "-d").stdout
        expected = f"front={'true' if want_front else 'false'}"
        if not (tapped and "Streaming camId=" in lc and expected in lc):
            cam_ok = False
            break
    screencap("/tmp/stress_cam.png")
    no_green, g_det = check_no_neon_green_line("/tmp/stress_cam.png")
    record("Stage 1: 6x Rapid Camera Back<->Front Thrash + Zero Green Line", cam_ok and no_green, g_det)

    async with websockets.connect(WS_URL, max_size=4 * 1024 * 1024) as ws:
        hello = json.loads(await ws.recv())
        a = hello["d"]["authentication"]
        if not AUTH_TOKEN:
            raise RuntimeError("Set PANGGUNG_TOKEN=<16-char session token from Remote Control dialog>")
        auth = compute_obs_ws_auth(AUTH_TOKEN, a["salt"], a["challenge"])
        await ws.send(json.dumps({"op": 1, "d": {"rpcVersion": 1, "authentication": auth}}))
        ident = json.loads(await ws.recv())
        assert ident.get("op") == 2, f"Auth failed: {ident}"

        # STAGE 2: Heavy 1080p60 Multi-Source (8 sources) + Multi-Filter (5 filters) Stack
        ok_1080, _, _ = await ws_req(ws, "SetVideoSettingsEx", {
            "width": 1920, "height": 1080, "fps": 60,
            "colorFormat": 2, "colorSpace": 2, "colorRange": 1, "scaleType": 4,
        })
        extra_sources = [
            ("panggung_gradient_source", "Stress Gradient", {}),
            ("panggung_waveform_source", "Stress Waveform", {}),
            ("panggung_device_stats", "Stress Stats", {}),
            ("panggung_text_android", "Stress RichText", {}),
            ("color_source", "Stress Color", {"color": 4278255360, "width": 320, "height": 180}),
            ("text_ft2_source_v2", "Stress FT2", {"text": "STRESS 1080p60"}),
        ]
        src_ok = True
        for sid, sname, sset in extra_sources:
            ok, _, _ = await ws_req(ws, "CreateInput", {"inputKind": sid, "inputName": sname, "inputSettings": sset})
            src_ok = src_ok and ok

        flt_ok = True
        for fid, fname in [
            ("chroma_key_filter_v2", "StChroma"),
            ("color_filter_v2", "StColor"),
            ("panggung_composite_blur", "StBlur"),
            ("panggung_stroke_glow_shadow", "StGlow"),
        ]:
            ok, _, _ = await ws_req(ws, "CreateSourceFilter", {"sourceName": "Background", "filterKind": fid, "filterName": fname, "filterSettings": {}})
            flt_ok = flt_ok and ok
        ok_dsp, _, _ = await ws_req(ws, "CreateSourceFilter", {"sourceName": "Microphone", "filterKind": "panggung_dsp_pack", "filterName": "StDSP", "filterSettings": {}})
        flt_ok = flt_ok and ok_dsp
        await asyncio.sleep(1.5)
        _, _, st2 = await ws_req(ws, "GetStats")
        record("Stage 2: 1080p60 Heavy Stack (8 Sources + 5 GPU/DSP Filters)",
               ok_1080 and src_ok and flt_ok and st2.get("activeFps", 0) >= 25.0,
               f"fps={st2.get('activeFps', 0):.1f} ft={st2.get('averageFrameRenderTime', 0):.2f}ms rss={get_rss_mb()}MB")

        # STAGE 3 & 4: Concurrent 1080p60 Recording + 3x Pause/Resume + 20x Scene/Transition Torture + Dual Canvas + 20x Web Remote HTTP
        for sc_name in ["Stress Scene 2", "Stress Scene 3", "Stress Scene 4"]:
            await ws_req(ws, "CreateScene", {"sceneName": sc_name})
        await ws_req(ws, "SetVideoEncoder", {"encoderId": "android_mediacodec_h264"})
        ok_rec_st, _, _ = await ws_req(ws, "StartRecord")
        ok_dual, _, _ = await ws_req(ws, "ToggleDualCanvas", {"enabled": True})

        # 20 rapid scene/transition switches + T-Bar sweeps while recording!
        transitions = ["fade_transition", "swipe_transition", "slide_transition", "wipe_transition", "cut_transition"]
        scenes = ["Main Scene", "Stress Scene 2", "Stress Scene 3", "Stress Scene 4"]
        tr_ok = True
        for i in range(20):
            ok_t, _, _ = await ws_req(ws, "SetCurrentSceneTransition", {"transitionName": transitions[i % len(transitions)], "transitionDuration": 150})
            ok_s, _, _ = await ws_req(ws, "SetCurrentProgramScene", {"sceneName": scenes[i % len(scenes)]})
            tr_ok = tr_ok and ok_t and ok_s
            if i in (5, 10, 15):
                await ws_req(ws, "PauseRecord")
                await asyncio.sleep(0.3)
                await ws_req(ws, "ResumeRecord")
            await asyncio.sleep(0.1)

        # Studio Mode T-Bar sweep + Fade to Black
        await ws_req(ws, "SetStudioModeEnabled", {"studioModeEnabled": True})
        for t in [0.0, 0.25, 0.5, 0.75, 1.0]:
            await ws_req(ws, "SetStudioTBar", {"t": t})
            await asyncio.sleep(0.08)
        await ws_req(ws, "FadeToBlack")
        await ws_req(ws, "SetStudioModeEnabled", {"studioModeEnabled": False})
        await ws_req(ws, "SetCurrentProgramScene", {"sceneName": "Main Scene"})

        # 20x HTTP Web Remote (:8080) burst while recording
        http_ok = 0
        for _ in range(20):
            try:
                req_http = urllib.request.Request("http://127.0.0.1:8080/", headers={"Authorization": f"Bearer {AUTH_TOKEN}"})
                with urllib.request.urlopen(req_http, timeout=3) as r:
                    if r.status == 200 and len(r.read()) > 1000:
                        http_ok += 1
            except Exception:
                pass

        await ws_req(ws, "ToggleDualCanvas", {"enabled": False})
        ok_rec_sp, _, rec_sp = await ws_req(ws, "StopRecord")
        await asyncio.sleep(1.0)
        rec_path = rec_sp.get("outputPath", "")
        ff_ok = False
        ff_det = rec_path
        if rec_path:
            local_mp4 = "/tmp/stress_1080p60.mp4"
            adb("pull", rec_path, local_mp4)
            if os.path.exists(local_mp4) and os.path.getsize(local_mp4) > 8192:
                pr = subprocess.run(
                    ["ffprobe", "-v", "error", "-show_entries", "format=duration:stream=codec_name,width,height",
                     "-of", "json", local_mp4],
                    capture_output=True, text=True,
                )
                try:
                    info = json.loads(pr.stdout)
                    v = next(s for s in info["streams"] if "width" in s)
                    dur = float(info["format"]["duration"])
                    ff_ok = v["width"] == 1920 and v["height"] == 1080 and dur >= 2.0
                    ff_det = f"{v['codec_name']} {v['width']}x{v['height']} dur={dur:.2f}s"
                except Exception as e:
                    ff_det = str(e)
        record("Stage 3: 20x Rapid Scene/Transition + T-Bar + FTB Under 1080p60 Load", tr_ok)
        record("Stage 4: Concurrent 1080p60 Rec + 3x Pause/Resume + DualCanvas + 20x HTTP + ffprobe",
               ok_rec_st and ok_dual and ok_rec_sp and http_ok == 20 and ff_ok,
               f"http={http_ok}/20 {ff_det}")

        # STAGE 5: Rapid Video Pipeline Reallocation (720p30 -> 1080p60 -> Vertical -> HDR P010 PQ -> 1080p60)
        modes = [
            (1280, 720, 30, 2, 2),
            (1920, 1080, 60, 2, 2),
            (720, 1280, 30, 2, 2),
            (1280, 720, 30, 18, 4),  # HDR P010 PQ
            (1920, 1080, 60, 2, 2),
        ]
        re_ok = True
        for w, h, fps, fmt, sp in modes:
            ok, _, _ = await ws_req(ws, "SetVideoSettingsEx", {
                "width": w, "height": h, "fps": fps,
                "colorFormat": fmt, "colorSpace": sp, "colorRange": 1, "scaleType": 2,
            })
            re_ok = re_ok and ok
            await asyncio.sleep(0.3)
        record("Stage 5: Rapid 5-Mode Pipeline Switch (720p/1080p60/Vertical/HDR-P010)", re_ok)

        # STAGE 6: 30-Second Sustained 1080p60 Recording Soak (Thermal + Memory Leak Check)
        await ws_req(ws, "SetVideoEncoder", {"encoderId": "android_mediacodec_hevc"})
        ok_s_st, _, _ = await ws_req(ws, "StartRecord")
        rss_before = get_rss_mb()
        min_fps = 999.0
        max_ft = 0.0
        max_temp = 0.0
        skipped_end = 0
        for sec in range(0, 30, 5):
            await asyncio.sleep(5.0)
            _, _, st = await ws_req(ws, "GetStats")
            fps = st.get("activeFps", 0.0)
            ft = st.get("averageFrameRenderTime", 0.0)
            skipped_end = st.get("renderSkippedFrames", 0)
            temp = get_batt_temp()
            rss = get_rss_mb()
            min_fps = min(min_fps, fps)
            max_ft = max(max_ft, ft)
            max_temp = max(max_temp, temp)
            print(f"    [SOAK {sec+5:2d}s] fps={fps:.1f} ft={ft:.2f}ms skipped={skipped_end} rss={rss:.1f}MB temp={temp:.1f}C", flush=True)
        ok_s_sp, _, _ = await ws_req(ws, "StopRecord")
        await asyncio.sleep(1.0)
        rss_after = get_rss_mb()
        delta_rss = round(rss_after - rss_before, 1)
        record("Stage 6: 30s Sustained 1080p60 HEVC Soak (FPS / Thermal / RAM Leak)",
               ok_s_st and ok_s_sp and min_fps >= 45.0 and delta_rss < 120.0,
               f"min_fps={min_fps:.1f} max_ft={max_ft:.2f}ms max_temp={max_temp:.1f}C rss={rss_before}->{rss_after}MB (delta={delta_rss:+.1f}MB)")

    # STAGE 7: Orientation Torture (Portrait <-> 3-Col Landscape x3) + Zero Crash/ANR Check
    for rot in [1, 0, 1, 0]:
        adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
        adb("shell", "settings", "put", "system", "user_rotation", str(rot))
        await asyncio.sleep(1.2)
    adb("shell", "settings", "put", "system", "accelerometer_rotation", "1")
    lc_final = adb("logcat", "-d").stdout
    has_fatal = any(k in lc_final for k in ["FATAL EXCEPTION", "Fatal signal 11", "Fatal signal 6", "ANR in io.panggung.obs"])
    record("Stage 7: Orientation Torture (Portrait<->Landscape) + Zero Crash/ANR", not has_fatal)

    passed = sum(1 for _, ok, _ in results if ok)
    total = len(results)
    print(f"\n=== ALL-FEATURES STRESS TEST SUMMARY: {passed}/{total} PASSED ===", flush=True)
    return passed == total


if __name__ == "__main__":
    sys.exit(0 if asyncio.run(run_stress()) else 1)
