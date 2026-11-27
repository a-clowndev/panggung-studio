#!/usr/bin/env python3
"""
Soak & Thermal Stability Test Harness (QA-06) for Panggung Studio on real Android hardware.
Polls GetStats via authenticated obs-websocket v5 (SHA-256) + battery temperature + RSS memory + logcat crash/ANR audit.
Enforces hard pass/fail assertions on FPS, memory growth, ANRs, and native crashes.
"""
import argparse
import asyncio
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import websockets


def compute_obs_ws_auth(password: str, salt: str, challenge: str) -> str:
    sec = base64.b64encode(hashlib.sha256((password + salt).encode()).digest()).decode()
    return base64.b64encode(hashlib.sha256((sec + challenge).encode()).digest()).decode()


def get_battery_temp() -> float:
    out = subprocess.run(["adb", "shell", "dumpsys battery | grep temperature"], capture_output=True, text=True, errors="replace").stdout
    for tok in out.split():
        if tok.isdigit():
            return int(tok) / 10.0
    return 0.0


def get_rss_mb() -> float:
    out = subprocess.run(["adb", "shell", "dumpsys", "meminfo", "io.panggung.obs"], capture_output=True, text=True, errors="replace").stdout
    m = re.search(r"TOTAL PSS:\s+(\d+)", out)
    return round(int(m.group(1)) / 1024.0, 1) if m else 0.0


async def soak(duration_sec: int, token: str, min_fps_threshold: float, max_rss_growth_mb: float):
    if not token:
        raise RuntimeError("Pass --token or set PANGGUNG_TOKEN=<16-char session token>")
    subprocess.run(["adb", "forward", "tcp:4455", "tcp:4455"], check=True)
    subprocess.run(["adb", "logcat", "-c"], check=True)
    rss_start = get_rss_mb()

    async with websockets.connect("ws://127.0.0.1:4455", open_timeout=10) as ws:
        hello = json.loads(await ws.recv())
        a = hello["d"]["authentication"]
        auth = compute_obs_ws_auth(token, a["salt"], a["challenge"])
        await ws.send(json.dumps({"op": 1, "d": {"rpcVersion": 1, "authentication": auth}}))
        ident = json.loads(await ws.recv())
        assert ident.get("op") == 2, f"Auth failed: {ident}"

        start = time.time()
        samples = 0
        min_fps = 999.0
        max_temp = 0.0
        max_rss = rss_start

        while time.time() - start < duration_sec:
            await ws.send(json.dumps({"op": 6, "d": {"requestType": "GetStats", "requestId": f"s{samples}"}}))
            msg = json.loads(await ws.recv())
            d = msg.get("d", {}).get("responseData", {})
            fps = d.get("activeFps", 0.0)
            skipped = d.get("renderSkippedFrames", 0)
            temp = get_battery_temp()
            rss = get_rss_mb()
            min_fps = min(min_fps, fps)
            max_temp = max(max_temp, temp)
            max_rss = max(max_rss, rss)
            samples += 1
            print(f"[{int(time.time()-start):4d}s] fps={fps:.1f} skipped={skipped} rss={rss:.1f}MB batt_temp={temp:.1f}C", flush=True)
            await asyncio.sleep(5)

    rss_end = get_rss_mb()
    rss_growth = rss_end - rss_start
    logs = subprocess.check_output(["adb", "logcat", "-d"], text=True, errors="replace")
    crashes = [l for l in logs.splitlines() if any(k in l for k in ["FATAL EXCEPTION", "SIGSEGV", "SIGABRT", "ANR in io.panggung.obs"])]

    print(f"SOAK SUMMARY: samples={samples} min_fps={min_fps:.1f} rss_start={rss_start:.1f}MB rss_end={rss_end:.1f}MB growth={rss_growth:+.1f}MB max_temp={max_temp:.1f}C crashes={len(crashes)}")
    assert samples >= 1, "No soak samples collected"
    assert min_fps >= min_fps_threshold, f"FAIL: min_fps {min_fps:.1f} < threshold {min_fps_threshold}"
    assert rss_growth <= max_rss_growth_mb, f"FAIL: RSS memory growth {rss_growth:+.1f}MB > {max_rss_growth_mb}MB"
    assert not crashes, f"FAIL: Crashes/ANRs detected during soak: {crashes[:5]}"
    print("SOAK_TEST_ASSERTIONS_PASSED")


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--seconds", type=int, default=30)
    p.add_argument("--token", type=str, default=os.environ.get("PANGGUNG_TOKEN", ""))
    p.add_argument("--min-fps", type=float, default=20.0)
    p.add_argument("--max-rss-growth-mb", type=float, default=100.0)
    args = p.parse_args()
    asyncio.run(soak(args.seconds, args.token, args.min_fps, args.max_rss_growth_mb))
