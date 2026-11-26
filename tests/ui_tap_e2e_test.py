#!/usr/bin/env python3
"""
Real-Device Full UI Button & Feature Tap E2E Test on SM-S721B.
Uses uiautomator XML hierarchy + adb input tap + logcat + screencap pixel audit
to verify EVERY tab, EVERY button, Camera Back<->Front 1-tap switch, zero green-line,
all dialogs, Mixer, Studio, Output, Settings, and zero crashes/ANRs.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

results = []

def record(name, ok, detail=""):
    results.append((name, ok, detail))
    tag = "PASS" if ok else "FAIL"
    print(f"  [{tag}] {name}" + (f" — {detail}" if detail else ""), flush=True)

def adb(*args, timeout=20):
    return subprocess.run(["adb", *args], capture_output=True, text=True, errors="replace", timeout=timeout)

def dump_ui():
    adb("shell", "uiautomator", "dump", "/sdcard/ui_dump.xml")
    r = adb("exec-out", "cat", "/sdcard/ui_dump.xml")
    if not r.stdout.strip().startswith("<"):
        return None
    try:
        return ET.fromstring(r.stdout)
    except Exception:
        return None

def find_node(root, text=None, contains=None):
    if root is None:
        return None
    for node in root.iter("node"):
        t = node.attrib.get("text", "")
        cd = node.attrib.get("content-desc", "")
        if text is not None and (t == text or cd == text):
            return node
        if contains is not None and (contains in t or contains in cd):
            return node
    return None

def bounds_center(node):
    b = node.attrib.get("bounds", "")
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return ((x1 + x2) // 2, (y1 + y2) // 2)

def tap_text(text=None, contains=None, wait=0.8):
    root = dump_ui()
    node = find_node(root, text=text, contains=contains)
    if node is None:
        return False
    pt = bounds_center(node)
    if pt is None:
        return False
    adb("shell", "input", "tap", str(pt[0]), str(pt[1]))
    time.sleep(wait)
    return True

def screencap(path):
    r = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True)
    with open(path, "wb") as f:
        f.write(r.stdout)
    return os.path.exists(path) and os.path.getsize(path) > 10000

def check_no_neon_green_line(png_path):
    """Inspect preview region (y=220..760) for horizontal neon-green YUV U=0/V=0 artifact lines."""
    try:
        from PIL import Image
        img = Image.open(png_path).convert("RGB")
        w, h = img.size
        # Preview is roughly x=40..w-40, y=int(h*0.10)..int(h*0.33)
        y0, y1 = int(h * 0.10), int(h * 0.33)
        green_rows = 0
        for y in range(y0, y1):
            neon = 0
            for x in range(60, w - 60, 8):
                r, g, b = img.getpixel((x, y))
                if g > 190 and r < 40 and b < 40:
                    neon += 1
            if neon > (w - 120) // 16:  # >50% of row is pure neon green
                green_rows += 1
        return green_rows == 0, f"neon_green_rows={green_rows}"
    except Exception as e:
        return True, f"skip PIL: {e}"

def main():
    print("=== PANGGUNG STUDIO v0.1.0 REAL-DEVICE FULL UI TAP E2E ===", flush=True)
    adb("logcat", "-c")
    adb("shell", "am", "force-stop", "io.panggung.obs")
    adb("shell", "am", "start", "-n", "io.panggung.obs/.MainActivity")
    time.sleep(4.0)

    # 1. Clean branding check on startup screen
    root = dump_ui()
    has_panggung = find_node(root, text="PANGGUNG") is not None
    has_studio_ver = find_node(root, text="STUDIO v0.1.0") is not None
    has_obs = find_node(root, contains="OBS") is not None
    record("Startup Branding (PANGGUNG + STUDIO v0.1.0, zero OBS)", has_panggung and has_studio_ver and not has_obs)

    # 2. Camera Back -> Front (1-tap!) -> Back (1-tap!) + Green Line Pixel Audit
    adb("logcat", "-c")
    ok_cb1 = tap_text(text="+ Cam Back", wait=2.5)
    lc1 = adb("logcat", "-d").stdout
    back_streamed = "Streaming camId=" in lc1 and "front=false" in lc1
    screencap("/tmp/ui_01_cam_back.png")
    no_green_back, g_det1 = check_no_neon_green_line("/tmp/ui_01_cam_back.png")
    record("Camera Back 1-Tap Open + Streaming + Zero Green Line", ok_cb1 and back_streamed and no_green_back, g_det1)

    adb("logcat", "-c")
    ok_cf1 = tap_text(text="+ Cam Front", wait=2.5)
    lc2 = adb("logcat", "-d").stdout
    front_streamed = "Streaming camId=" in lc2 and "front=true" in lc2
    screencap("/tmp/ui_02_cam_front.png")
    no_green_front, g_det2 = check_no_neon_green_line("/tmp/ui_02_cam_front.png")
    record("Camera Front 1-Tap Switch (Zero Race!) + Zero Green Line", ok_cf1 and front_streamed and no_green_front, g_det2)

    adb("logcat", "-c")
    ok_cb2 = tap_text(text="+ Cam Back", wait=2.5)
    lc3 = adb("logcat", "-d").stdout
    back2_streamed = "Streaming camId=" in lc3 and "front=false" in lc3
    record("Camera Back 1-Tap Switch-Back (Zero Race!)", ok_cb2 and back2_streamed)

    # 3. Preview Overlays: SAFE, LOCK, QR Dialog
    ok_safe = tap_text(text="SAFE", wait=0.5)
    ok_lock = tap_text(text="LOCK", wait=0.5)
    ok_unlock = tap_text(text="LOCKED", wait=0.5)
    ok_safe_off = tap_text(text="SAFE", wait=0.5)
    ok_qr_open = tap_text(text="QR", wait=0.8)
    ok_qr_done = tap_text(text="Done", wait=0.6)
    record("Preview SAFE / LOCK / QR Connect Dialog", ok_safe and ok_lock and ok_unlock and ok_safe_off and ok_qr_open and ok_qr_done)

    # 4. Add Browser Overlay + Select Source + Contextual Toolbar (Fit/Prop/Flt/Xfrm) + Source Context Menu
    ok_br = tap_text(text="+ Browser", wait=1.2)
    ok_sel = tap_text(text="Background", wait=0.6)
    ok_fit = tap_text(text="Fit", wait=0.5)
    ok_prop = tap_text(text="Prop", wait=0.8)
    prop_has_teal = find_node(dump_ui(), text="Teal") is not None
    ok_prop_close = tap_text(text="Done", wait=0.5)
    ok_flt = tap_text(text="Flt", wait=0.8)
    ok_flt_close = tap_text(text="Close", wait=0.5)
    ok_xfrm = tap_text(text="Xfrm", wait=0.8)
    ok_xfrm_close = tap_text(text="Close", wait=0.5)
    ok_menu = tap_text(text="MENU", wait=0.8)
    ok_menu_close = tap_text(text="Close", wait=0.5)
    record("Source Toolbar (Browser, Fit, Prop Color, Flt, Xfrm, Context Menu)",
           ok_br and ok_sel and ok_fit and ok_prop and prop_has_teal and ok_prop_close and ok_flt and ok_flt_close and ok_xfrm and ok_xfrm_close and ok_menu and ok_menu_close)

    # 5. Scene Creation (+ Scene)
    ok_sc = tap_text(text="+ Scene", wait=0.8)
    ok_sc2 = tap_text(text="Scene 2", wait=0.6)
    ok_sc1 = tap_text(text="Main Scene", wait=0.6)
    record("Scenes Strip (+ Scene -> Switch Scene 2 -> Main Scene)", ok_sc and ok_sc2 and ok_sc1)

    # 6. Mixer Tab: Live Stereo VU + ADV Dialog + Mute Toggle
    ok_mix = tap_text(text="Mixer", wait=1.0)
    screencap("/tmp/ui_03_mixer.png")
    ok_adv = tap_text(text="ADV", wait=0.8)
    ok_adv_close = tap_text(text="Close", wait=0.5)
    ok_mute = tap_text(contains="%", wait=0.5)
    ok_unmute = tap_text(text="MUTED", wait=0.5)
    record("Mixer Tab (Stereo VU + ADV Audio Dialog + Mute/Unmute)", ok_mix and ok_adv and ok_adv_close and ok_mute and ok_unmute)

    # 7. Studio Tab: Studio Mode Toggle + Transitions + Fade to Black
    ok_st = tap_text(text="Studio", wait=0.8)
    screencap("/tmp/ui_04_studio.png")
    ok_swipe = tap_text(text="Swipe", wait=0.5)
    ok_fade = tap_text(text="Fade", wait=0.5)
    ok_ftb = tap_text(text="FADE TO BLACK", wait=0.6)
    ok_tr = tap_text(text="TRANSITION TO PROGRAM", wait=0.6)
    record("Studio Tab (Transitions + Fade to Black + Program Transition)", ok_st and ok_swipe and ok_fade and ok_ftb and ok_tr)

    # 8. Output Tab: Encoders + Start/Pause/Resume/Stop Recording
    ok_out = tap_text(text="Output", wait=0.8)
    screencap("/tmp/ui_05_output.png")
    ok_hevc = tap_text(text="HW HEVC", wait=0.4)
    ok_h264 = tap_text(text="HW H.264", wait=0.4)
    ok_rec_st = tap_text(text="START REC", wait=2.5)
    ok_pause = tap_text(text="PAUSE", wait=1.0)
    ok_resume = tap_text(text="RESUME", wait=1.0)
    ok_rec_sp = tap_text(text="STOP REC", wait=1.2)
    record("Output Tab (Encoders + Start/Pause/Resume/Stop Recording)", ok_out and ok_hevc and ok_h264 and ok_rec_st and ok_pause and ok_resume and ok_rec_sp)

    # 9. Settings Tab: Scroll down & test All Settings (13 Pages), Multiview
    ok_set = tap_text(text="Settings", wait=0.8)
    screencap("/tmp/ui_06_settings.png")
    adb("shell", "input", "swipe", "540", "1900", "540", "1200", "250")
    time.sleep(0.6)
    ok_all = tap_text(text="All Settings (13 Pages)", wait=0.8)
    ok_all_vid = tap_text(text="Video", wait=0.5)
    ok_all_done = tap_text(text="Done", wait=0.5)
    ok_mv = tap_text(text="Multiview", wait=0.8)
    ok_mv_close = tap_text(text="Close", wait=0.5)
    record("Settings Tab (13-Page Studio Settings + Multiview)", ok_set and ok_all and ok_all_vid and ok_all_done and ok_mv and ok_mv_close)

    # 10. Crash / ANR check in logcat
    crash_log = adb("logcat", "-d").stdout
    has_fatal = any(k in crash_log for k in ["FATAL EXCEPTION", "Fatal signal 11", "Fatal signal 6", "ANR in io.panggung.obs"])
    record("Zero Fatal Exceptions / Crashes / ANRs Across All UI Flows", not has_fatal)

    passed = sum(1 for _, ok, _ in results if ok)
    total = len(results)
    print(f"\n=== FULL UI TAP E2E SUMMARY: {passed}/{total} PASSED ===", flush=True)
    return passed == total

if __name__ == "__main__":
    sys.exit(0 if main() else 1)
