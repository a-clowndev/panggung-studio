#!/usr/bin/env python3
"""
Golden Render SSIM Verification (QA-02): captures live Panggung Studio preview via ADB screencap,
crops strictly to the preview canvas region (preventing bright UI chrome from masking a black preview),
verifies non-black luminance via ffmpeg signalstats, and computes real SSIM against a golden reference.
"""
import os
import re
import subprocess
import sys

GOLDEN_REF = os.environ.get("PANGGUNG_GOLDEN_REF", "/tmp/panggung_golden_ref_crop.png")
CAPTURE_RAW = "/tmp/panggung_golden_raw.png"
CAPTURE_CROP = "/tmp/panggung_golden_crop.png"
# Default crop targets the preview canvas area (override via PANGGUNG_CROP=w:h:x:y)
PREVIEW_CROP = os.environ.get("PANGGUNG_CROP", "iw*0.60:ih*0.42:iw*0.20:ih*0.06")
MIN_SSIM = float(os.environ.get("PANGGUNG_MIN_SSIM", "0.85"))


def run():
    out = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, check=True).stdout
    if len(out) < 10_000 or not out.startswith(b"\x89PNG"):
        print("[FAIL] Invalid PNG screencap")
        return False
    with open(CAPTURE_RAW, "wb") as f:
        f.write(out)

    # 1. Crop strictly to the preview canvas region
    crop_proc = subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", CAPTURE_RAW, "-vf", f"crop={PREVIEW_CROP}", CAPTURE_CROP],
        capture_output=True, text=True,
    )
    if crop_proc.returncode != 0 or not os.path.exists(CAPTURE_CROP):
        print(f"[FAIL] Preview crop failed: {crop_proc.stderr}")
        return False

    # 2. Verify non-black luminance ONLY on the cropped preview canvas
    stat_proc = subprocess.run(
        ["ffmpeg", "-v", "info", "-i", CAPTURE_CROP, "-vf", "signalstats,metadata=print:file=-", "-f", "null", "-"],
        capture_output=True, text=True,
    )
    m_avg = re.search(r"lavfi\.signalstats\.YAVG=([0-9.]+)", stat_proc.stdout)
    m_max = re.search(r"lavfi\.signalstats\.YMAX=([0-9.]+)", stat_proc.stdout)
    yavg = float(m_avg.group(1)) if m_avg else 0.0
    ymax = float(m_max.group(1)) if m_max else 0.0
    if stat_proc.returncode != 0 or yavg < 5.0 or ymax < 20.0:
        print(f"[FAIL] Black/corrupt preview canvas detected (YAVG={yavg:.2f}, YMAX={ymax:.2f})")
        return False

    # 3. Compute real SSIM on cropped preview against golden reference
    # Require explicit --update-baseline to create/overwrite golden reference (never silently auto-PASS!)
    update_baseline = "--update-baseline" in sys.argv
    if update_baseline:
        with open(CAPTURE_CROP, "rb") as src, open(GOLDEN_REF, "wb") as dst:
            dst.write(src.read())
        print(f"[PASS] Updated cropped golden reference (crop={PREVIEW_CROP}, YAVG={yavg:.1f}, YMAX={ymax:.1f})")
        return True
    if not os.path.exists(GOLDEN_REF):
        print(f"[FAIL] Golden reference '{GOLDEN_REF}' missing! Run with --update-baseline first to record a verified reference.")
        return False

    ssim_proc = subprocess.run(
        ["ffmpeg", "-i", CAPTURE_CROP, "-i", GOLDEN_REF, "-lavfi", "ssim", "-f", "null", "-"],
        capture_output=True, text=True,
    )
    m_ssim = re.search(r"All:([0-9.]+)", ssim_proc.stderr)
    ssim_val = float(m_ssim.group(1)) if m_ssim else 0.0
    ok = ssim_proc.returncode == 0 and ssim_val >= MIN_SSIM
    print(f"[{'PASS' if ok else 'FAIL'}] Cropped Preview SSIM={ssim_val:.4f} (min={MIN_SSIM}, YAVG={yavg:.1f}, YMAX={ymax:.1f})")
    return ok


if __name__ == "__main__":
    sys.exit(0 if run() else 1)
