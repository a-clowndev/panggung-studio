#!/usr/bin/env python3
"""
Soak & Thermal Stability Test Harness (QA-06) for Panggung Studio on real Android hardware.
Polls GetStats via obs-websocket v5 + battery temperature via ADB dumpsys battery.
Default duration: 30s (pass --minutes 120 for full 2-hour soak test).
"""
import argparse
import asyncio
import json
import subprocess
import time
import websockets


def get_battery_temp():
    out = subprocess.run(["adb", "shell", "dumpsys battery | grep temperature"], capture_output=True, text=True).stdout
    for tok in out.split():
        if tok.isdigit():
            return int(tok) / 10.0
    return 0.0


async def soak(duration_sec: int):
    subprocess.run(["adb", "forward", "tcp:4455", "tcp:4455"], check=True)
    async with websockets.connect("ws://127.0.0.1:4455") as ws:
        await ws.recv()
        await ws.send(json.dumps({"op": 1, "d": {"rpcVersion": 1}}))
        await ws.recv()
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
    p.add_argument("--seconds", type=int, default=15)
    args = p.parse_args()
    asyncio.run(soak(args.seconds))
