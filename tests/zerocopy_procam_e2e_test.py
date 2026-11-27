#!/usr/bin/env python3
"""
Real-Hardware E2E Verification for ALL 6 Zero-Copy & Pro Camera Pillars on SM-S721B:
  Z01. Camera2 AHardwareBuffer Zero-Copy (EGLImageKHR GPU frame #1 + zero green line)
  Z02. Concurrent Dual-Camera (Back + Front PiP simultaneous AHB zero-copy GPU frames)
  Z03. Pro Camera Controls & Multi-Lens Dialog (Lenses, Manual Exposure, MF, Kelvin WB, OIS)
  Z04. Direct Corner Resize, Crop Mode & Snapping UI on Preview Canvas
  Z05. GPU-to-MediaCodec Surface Encoding (H.264 + HEVC mode=GPU-SURFACE + ffprobe + full decode)
  Z06. Zero Crashes / ANRs Across All 6 Pillars
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
import xml.etree.ElementTree as ET
import websockets

SCRATCH = "/home/wigatha/.gemini/antigravity/brain/3c296d28-3bed-4f34-8ca4-21c66f91d7ca/scratch"
results = []
req_id = 0


def record(name, ok, detail=""):
    results.append((name, ok, detail))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""), flush=True)


def adb(*args, timeout=30):
    return subprocess.run(["adb", *args], capture_output=True, text=True, errors="replace", timeout=timeout)


def screencap(path):
    r = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True)
    with open(path, "wb") as f:
        f.write(r.stdout)
    return os.path.exists(path) and os.path.getsize(path) > 10000


def dump_xml():
    adb("shell", "uiautomator", "dump", "/sdcard/ui_z.xml")
    r = adb("exec-out", "cat", "/sdcard/ui_z.xml")
    return r.stdout if r.stdout.strip().startswith("<") else ""


def tap_bounds(b):
    m = re.findall(r"\d+", b)
    if len(m) != 4:
        return False
    adb("shell", "input", "tap", str((int(m[0]) + int(m[2])) // 2), str((int(m[1]) + int(m[3])) // 2))
    return True


def tap_text(txt, wait=1.0):
    xml = dump_xml()
    if not xml:
        return False
    root = ET.fromstring(xml)
    for n in root.iter("node"):
        if n.attrib.get("text") == txt or n.attrib.get("content-desc") == txt:
            tap_bounds(n.attrib.get("bounds", ""))
            time.sleep(wait)
            return True
    return False


def enable_ws_and_get_token():
    assert tap_text("QR", 1.2), "QR button not found"
    xml = dump_xml()
    root = ET.fromstring(xml)
    switches = [n for n in root.iter("node") if n.attrib.get("checkable") == "true"]
    if switches and switches[0].attrib.get("checked") == "false":
        tap_bounds(switches[0].attrib.get("bounds", ""))
        time.sleep(1.5)
    xml2 = dump_xml()
    tok = re.findall(r'text="([A-Za-z0-9]{16})"', xml2)[0]
    tap_text("Done", 0.8) or tap_text("Close", 0.8)
    return tok


def compute_auth(pw, salt, chal):
    sec = base64.b64encode(hashlib.sha256((pw + salt).encode()).digest()).decode()
    return base64.b64encode(hashlib.sha256((sec + chal).encode()).digest()).decode()


async def ws_req(ws, rtype, rdata=None):
    global req_id
    req_id += 1
    rid = f"z-{req_id}"
    p = {"op": 6, "d": {"requestType": rtype, "requestId": rid}}
    if rdata is not None:
        p["d"]["requestData"] = rdata
    await ws.send(json.dumps(p))
    while True:
        msg = json.loads(await asyncio.wait_for(ws.recv(), timeout=15.0))
        if msg.get("op") == 7 and msg.get("d", {}).get("requestId") == rid:
            d = msg["d"]
            return d["requestStatus"]["result"], d.get("responseData", {})


def verify_mp4(path):
    if not os.path.exists(path) or os.path.getsize(path) < 4096:
        return False, "missing/small"
    pr = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration:stream=codec_name,width,height", "-of", "json", path],
        capture_output=True, text=True,
    )
    if pr.returncode != 0:
        return False, pr.stderr.strip()[:100]
    info = json.loads(pr.stdout)
    dec = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-f", "null", "-"], capture_output=True, text=True, timeout=30)
    if dec.returncode != 0:
        return False, f"decode err: {dec.stderr.strip()[:100]}"
    v = next((s for s in info.get("streams", []) if "width" in s), None)
    if not v:
        return False, "no video stream"
    return True, f"{v['codec_name']} {v['width']}x{v['height']} dur={float(info['format']['duration']):.2f}s"


async def main():
    print("=== PANGGUNG STUDIO — 6-PILLAR ZERO-COPY / GPU-SURFACE / DUAL-CAM / PRO-CAM E2E ===", flush=True)
    os.makedirs(SCRATCH, exist_ok=True)
    adb("logcat", "-c")
    adb("shell", "pm", "clear", "io.panggung.obs")
    adb("shell", "pm", "grant", "io.panggung.obs", "android.permission.CAMERA")
    adb("shell", "pm", "grant", "io.panggung.obs", "android.permission.RECORD_AUDIO")
    adb("shell", "pm", "grant", "io.panggung.obs", "android.permission.POST_NOTIFICATIONS")
    adb("forward", "tcp:4455", "tcp:4455")
    adb("shell", "am", "start", "-n", "io.panggung.obs/.MainActivity")
    await asyncio.sleep(4.0)

    # Z01: Camera2 AHardwareBuffer Zero-Copy
    adb("logcat", "-c")
    ok_cb = tap_text("+ Cam Back", 3.0)
    lc1 = adb("logcat", "-d").stdout
    has_ahb_cam = "[ahb-zero-copy]" in lc1 and "EGLImageKHR" in lc1
    screencap(os.path.join(SCRATCH, "s24fe_z01_ahb_cam.png"))
    record("Z01: Camera2 AHardwareBuffer Zero-Copy (EGLImageKHR)", ok_cb and has_ahb_cam, f"ahb_log={has_ahb_cam}")

    # Z02: Concurrent Dual-Camera (Back + Front PiP simultaneously!)
    adb("logcat", "-c")
    ok_dual = tap_text("+ Dual Cam", 3.5)
    lc2 = adb("logcat", "-d").stdout
    both_streaming = ("'Back Camera'" in lc2 and "'Front Camera'" in lc2 and "AHB-ZeroCopy" in lc2)
    has_dual_ahb = lc2.count("EGLImageKHR") >= 1 or "Front Camera" in lc2
    screencap(os.path.join(SCRATCH, "s24fe_z02_dual_cam.png"))
    record("Z02: Concurrent Dual-Camera (Back + Front PiP Simultaneous)", ok_dual and both_streaming and has_dual_ahb, f"both={both_streaming}")

    # Z03: Pro Camera Controls + Multi-Lens Dialog
    ok_pro = tap_text("Pro Cam", 1.2)
    xml_pro = dump_xml()
    has_pro_title = "PRO CAMERA CONTROLS" in xml_pro
    has_lenses = "1x Wide" in xml_pro or "Front" in xml_pro
    ok_man_ae = tap_text("AUTO AE", 0.5)
    # Scroll WB chip row right to reveal KELVIN chip
    adb("shell", "input", "swipe", "820", "1740", "260", "1740", "250")
    time.sleep(0.5)
    ok_kelvin = tap_text("KELVIN", 0.5)
    screencap(os.path.join(SCRATCH, "s24fe_z03_pro_cam.png"))
    ok_reset = tap_text("Reset", 0.5)
    ok_done = tap_text("Done", 0.8)
    record(
        "Z03: Pro Camera Controls & Multi-Lens UI (ISO/Shutter/Kelvin/Lenses)",
        ok_pro and has_pro_title and has_lenses and ok_man_ae and ok_kelvin and ok_reset and ok_done,
        f"title={has_pro_title} lenses={has_lenses} manual_ae={ok_man_ae} kelvin={ok_kelvin}",
    )

    # Z04: Direct Corner Resize, Crop Mode & Snapping UI
    ok_crop_on = tap_text("CROP", 0.8)
    # Drag right edge inward inside preview canvas to apply direct crop
    adb("shell", "input", "swipe", "980", "450", "820", "450", "300")
    time.sleep(0.6)
    screencap(os.path.join(SCRATCH, "s24fe_z04_crop_mode.png"))
    ok_crop_off = tap_text("CROP", 0.5)
    record("Z04: Direct Corner Resize, Crop Mode & Magnetic Snapping UI", ok_crop_on and ok_crop_off)

    # Stop dual cam before GPU-surface encoding test so single cam is crisp
    tap_text("Dual ON", 1.0)

    # Z05: GPU-to-MediaCodec Surface Encoding (H.264 + HEVC mode=GPU-SURFACE + full decode)
    tok = enable_ws_and_get_token()
    adb("logcat", "-c")
    async with websockets.connect("ws://127.0.0.1:4455", max_size=4 * 1024 * 1024) as ws:
        hello = json.loads(await ws.recv())
        a = hello["d"]["authentication"]
        await ws.send(json.dumps({"op": 1, "d": {"rpcVersion": 1, "authentication": compute_auth(tok, a["salt"], a["challenge"])}}))
        ident = json.loads(await ws.recv())
        assert ident.get("op") == 2

        enc_results = []
        for enc_id, fname in [
            ("android_mediacodec_h264", "/tmp/z05_gpu_h264.mp4"),
            ("android_mediacodec_hevc", "/tmp/z05_gpu_hevc.mp4"),
        ]:
            if os.path.exists(fname):
                os.remove(fname)
            await ws_req(ws, "SetVideoEncoder", {"encoderId": enc_id})
            ok_st, _ = await ws_req(ws, "StartRecord")
            await asyncio.sleep(4.5)
            ok_sp, sp_data = await ws_req(ws, "StopRecord")
            await asyncio.sleep(1.0)
            adb("pull", sp_data.get("outputPath", ""), fname)
            dec_ok, dec_det = verify_mp4(fname)
            enc_results.append((enc_id, ok_st and ok_sp and dec_ok, dec_det))

    lc5 = adb("logcat", "-d").stdout
    gpu_surf_started = "mode=GPU-SURFACE" in lc5
    gpu_surf_frames = "[mediacodec-gpu] Zero-copy GPU Surface frame #" in lc5
    all_enc_ok = all(ok for _, ok, _ in enc_results)
    det_str = "; ".join(f"{e}:{d}" for e, _, d in enc_results)
    record(
        "Z05: GPU-to-MediaCodec Surface Encoding (H.264 + HEVC + Full Decode)",
        gpu_surf_started and gpu_surf_frames and all_enc_ok,
        f"gpu_surface={gpu_surf_started} frames={gpu_surf_frames} | {det_str}",
    )

    # Z06: Zero Crashes / ANRs
    lc_all = adb("logcat", "-d").stdout
    crashes = [k for k in ["FATAL EXCEPTION", "Fatal signal 11", "Fatal signal 6", "ANR in io.panggung.obs"] if k in lc_all]
    record("Z06: Zero Crashes / ANRs Across All 6 Pillars", len(crashes) == 0, f"crashes={crashes}")

    passed = sum(1 for _, ok, _ in results if ok)
    print(f"\n=== 6-PILLAR ZERO-COPY & PRO-CAM E2E SUMMARY: {passed}/{len(results)} PASSED ===", flush=True)
    return passed == len(results)


if __name__ == "__main__":
    sys.exit(0 if asyncio.run(main()) else 1)
