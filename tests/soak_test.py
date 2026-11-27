#!/usr/bin/env python3
"""
Soak & Thermal Stability Test Harness (QA-06) for Panggung Studio on real Android hardware.
Polls GetStats via authenticated obs-websocket v5 (SHA-256) + battery temperature via ADB.
Requires PANGGUNG_TOKEN env var from Remote Control dialog.
"""
import argparse
import asyncio
import base64
import hashlib
import json
import os
import subprocess
import time
import websockets


def compute_obs_ws_auth(password: str, salt: str, challenge: str) -> str:
    sec = base64.b64encode(hashlib.sha256((password + salt).encode()).digest()).decode()
    return base64.b64encode(hashlib.sha256((sec + challenge).encode()).digest()).decode()


def get_battery_temp():
    out = subprocess.run(["adb", "shell", "dumpsys battery | grep temperature"], capture_output=True, text=True).stdout
    for tok in out.split():
        if tok.isdigit():
            return int(tok) / 10.0
    return 0.0


async def soak(duration_sec: int, token: str):
    if not token:
        raise RuntimeError("Pass --token or set PANGGUNG_TOKEN=<16-char session token>")
    subprocess.run(["adb", "forward", "tcp:4455", "tcp:4455"], check=True)
    async with websockets.connect("ws://127.0.0.1:4455") as ws:
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
        while time.time() - start < duration_sec:
            await ws.send(json.dumps({"op": 6, "d": {"requestType": "GetStats", "requestId": f"s{samples}"}}))
            msg = json.loads(await ws.recv())
            d = msg.get("d", {}).get("responseData", {})
            fps = d.get("activeFps", 0.0)
            skipped = d.get("renderSkippedFrames", 0)
            temp = get_battery_temp()
            min_fps = min(min_fps, fps)
            max_temp = max(max_temp, temp)
            samples += 1
            print(f"[{int(time.time()-start):4d}s] fps={fps:.1f} skipped={skipped} batt_temp={temp:.1f}C")
            await asyncio.sleep(5)
        print(f"SOAK COMPLETE: samples={samples} min_fps={min_fps:.1f} max_temp={max_temp:.1f}C")


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--seconds", type=int, default=30)
    p.add_argument("--token", type=str, default=os.environ.get("PANGGUNG_TOKEN", ""))
    args = p.parse_args()
    asyncio.run(soak(args.seconds, args.token))
