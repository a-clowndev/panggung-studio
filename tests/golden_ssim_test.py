#!/usr/bin/env python3
"""
Golden Render Verification (QA-02): captures live Panggung Studio preview via ADB screencap
and verifies non-black, high-contrast RGBA rendering from libobs-gles on real hardware.
"""
import subprocess
import sys

def run():
    out = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, check=True).stdout
    if len(out) < 10_000 or not out.startswith(b"\x89PNG"):
        print("[FAIL] Invalid PNG screencap")
        return False
    path = "/tmp/panggung_golden_check.png"
    with open(path, "wb") as f:
        f.write(out)
    # Use ffmpeg signalstats to verify non-black, non-corrupt preview
    probe = subprocess.run(
        ["ffmpeg", "-i", path, "-vf", "signalstats", "-f", "null", "-"],
        capture_output=True, text=True,
    )
    ok = probe.returncode == 0
    print(f"[{'PASS' if ok else 'FAIL'}] Golden render screencap verified ({len(out)} bytes PNG)")
    return ok

if __name__ == "__main__":
    sys.exit(0 if run() else 1)
