#!/usr/bin/env python3
"""
Golden Render SSIM Verification (QA-02): captures live Panggung Studio preview via ADB screencap,
verifies non-black luminance via ffmpeg signalstats, and computes real SSIM against a golden
reference frame (passing if SSIM >= 0.85, or recording baseline if no reference exists yet).
"""
import os
import re
import subprocess
import sys

GOLDEN_REF = os.environ.get("PANGGUNG_GOLDEN_REF", "/tmp/panggung_golden_ref.png")
CAPTURE_PATH = "/tmp/panggung_golden_check.png"
MIN_SSIM = float(os.environ.get("PANGGUNG_MIN_SSIM", "0.85"))


def run():
    out = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, check=True).stdout
    if len(out) < 10_000 or not out.startswith(b"\x89PNG"):
        print("[FAIL] Invalid PNG screencap")
        return False
    with open(CAPTURE_PATH, "wb") as f:
        f.write(out)

    # 1. Verify non-black / non-corrupt luminance using signalstats metadata
    stat_proc = subprocess.run(
        ["ffmpeg", "-v", "info", "-i", CAPTURE_PATH, "-vf", "signalstats,metadata=print:file=-", "-f", "null", "-"],
        capture_output=True, text=True,
    )
    m_avg = re.search(r"lavfi\.signalstats\.YAVG=([0-9.]+)", stat_proc.stdout)
    m_max = re.search(r"lavfi\.signalstats\.YMAX=([0-9.]+)", stat_proc.stdout)
    yavg = float(m_avg.group(1)) if m_avg else 0.0
    ymax = float(m_max.group(1)) if m_max else 0.0
    if stat_proc.returncode != 0 or yavg < 5.0 or ymax < 20.0:
        print(f"[FAIL] Black/corrupt frame detected (YAVG={yavg:.2f}, YMAX={ymax:.2f})")
        return False

    # 2. Compute real SSIM against golden reference frame
    if not os.path.exists(GOLDEN_REF):
        with open(GOLDEN_REF, "wb") as f:
            f.write(out)
        print(f"[PASS] Saved initial golden reference ({len(out)} bytes, YAVG={yavg:.1f}, YMAX={ymax:.1f})")
        return True

    ssim_proc = subprocess.run(
        ["ffmpeg", "-i", CAPTURE_PATH, "-i", GOLDEN_REF, "-lavfi", "ssim", "-f", "null", "-"],
        capture_output=True, text=True,
    )
    m_ssim = re.search(r"All:([0-9.]+)", ssim_proc.stderr)
    ssim_val = float(m_ssim.group(1)) if m_ssim else 0.0
    ok = ssim_proc.returncode == 0 and ssim_val >= MIN_SSIM
    print(f"[{'PASS' if ok else 'FAIL'}] Golden SSIM={ssim_val:.4f} (min={MIN_SSIM}, YAVG={yavg:.1f}, YMAX={ymax:.1f})")
    return ok


if __name__ == "__main__":
    sys.exit(0 if run() else 1)
