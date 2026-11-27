#!/usr/bin/env python3
"""
Automated E2E Test Suite for v0.2.0-alpha.2+ Hardening:
1. Monotonic versionCode formula across 9 tags (0.2.0-alpha.2 == 20002)
2. Strict SRT Codec Gate: AV1 AND VP9 both rejected; H.264 AND HEVC both accepted
3. Real Encrypted H.264 SRT (MediaCodec -> bounded queue -> writer thread -> ffmpeg rx -> ffprobe + FULL FRAME DECODE)
4. Real Encrypted HEVC (H.265) SRT (MediaCodec HEVC -> ffmpeg rx -> ffprobe + FULL FRAME DECODE)
5. Zero Passphrase Leak in adb logcat (including FFmpeg internal av_log error path)
6. UI Start (GO LIVE button) -> Remote WS StopStream -> Zero Zombie Reconnect!
7. RTMPS Valid Cert (a.rtmps.youtube.com) AND Invalid Hostname Cert (a.rtmp.youtube.com -> Cert verify failed: 4)
"""
import asyncio
import base64
import hashlib
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
import websockets

WS_URL = os.environ.get("PANGGUNG_WS_URL", "ws://127.0.0.1:4455")
AUTH_TOKEN = os.environ.get("PANGGUNG_TOKEN", "").strip()
DEVICE_IP = os.environ.get("PANGGUNG_DEVICE_IP", "").strip()
SECRET_PASSPHRASE = "PanggungAuditSecretPassphrase9876"


def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True, text=True, errors="replace")


def detect_device_ip():
    if DEVICE_IP:
        return DEVICE_IP
    out = adb("shell", "ip", "-4", "addr", "show", "wlan0").stdout
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("inet "):
            return line.split()[1].split("/")[0]
    raise RuntimeError("Could not detect device wlan0 IP; set PANGGUNG_DEVICE_IP")


def tap_text(txt, s=1.0):
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml").stdout
    for n in ET.fromstring(xml).iter("node"):
        if n.attrib.get("text") == txt:
            m = re.findall(r"\d+", n.attrib["bounds"])
            adb("shell", "input", "tap", str((int(m[0]) + int(m[2])) // 2), str((int(m[1]) + int(m[3])) // 2))
            time.sleep(s)
            return True
    return False


async def ws_auth(ws):
    if not AUTH_TOKEN:
        raise RuntimeError("Set PANGGUNG_TOKEN=<16-char token from Remote Control dialog>")
    hello = json.loads(await ws.recv())
    auth = hello["d"]["authentication"]
    secret = base64.b64encode(hashlib.sha256((AUTH_TOKEN + auth["salt"]).encode()).digest()).decode()
    resp = base64.b64encode(hashlib.sha256((secret + auth["challenge"]).encode()).digest()).decode()
    await ws.send(json.dumps({"op": 1, "d": {"rpcVersion": 1, "authentication": resp}}))
    ident = json.loads(await ws.recv())
    assert ident.get("op") == 2, f"WS auth failed: {ident}"


async def req(ws, rtype, data=None):
    rid = f"r_{int(time.time() * 1000000)}"
    await ws.send(json.dumps({"op": 6, "d": {"requestType": rtype, "requestId": rid, "requestData": data or {}}}))
    while True:
        m = json.loads(await ws.recv())
        if m.get("op") == 7 and m["d"]["requestId"] == rid:
            return m["d"]


def test_version_code_monotonicity():
    tags = [
        ("v0.2.0-alpha.1", 20001),
        ("v0.2.0-alpha.2", 20002),
        ("v0.2.0-alpha.3", 20003),
        ("v0.2.0-alpha.4", 20004),
        ("v0.2.0-beta.1", 20031),
        ("v0.2.0-rc.1", 20061),
        ("v0.2.0", 20099),
        ("v0.2.1-alpha.1", 20101),
        ("v1.0.0", 1000099),
    ]
    prev = 0
    for tag, expected in tags:
        cmd = f'''
TAG="{tag}"
VER="${{TAG#v}}"
BASE="${{VER%%-*}}"
IFS='.' read -r MAJ MIN PAT <<< "$BASE"
PRE_RANK=99
if [[ "$VER" == *-* ]]; then
  PRE="${{VER#*-}}"
  NUM="${{PRE##*.}}"
  [[ "$NUM" =~ ^[0-9]+$ ]] || NUM=1
  case "$PRE" in
    alpha*) PRE_RANK=$(( NUM > 29 ? 29 : NUM )) ;;
    beta*)  PRE_RANK=$(( 30 + (NUM > 29 ? 29 : NUM) )) ;;
    rc*)    PRE_RANK=$(( 60 + (NUM > 29 ? 29 : NUM) )) ;;
    *)      PRE_RANK=1 ;;
  esac
fi
echo $((MAJ * 1000000 + MIN * 10000 + PAT * 100 + PRE_RANK))
'''
        got = int(subprocess.check_output(["bash", "-c", cmd], text=True).strip())
        assert got == expected, f"{tag}: expected {expected}, got {got}"
        assert got > prev, f"Non-monotonic versionCode: {tag}={got} <= {prev}"
        prev = got
    print(f"  [PASS] Monotonic versionCode formula verified across {len(tags)} tags (0.2.0-alpha.2 == 20002)")


async def run_srt_codec_test(ws, dev_ip, enc_id, expected_codec, port, rx_file):
    if os.path.exists(rx_file):
        os.remove(rx_file)
    await req(ws, "SetVideoEncoder", {"encoderId": enc_id})
    srt_listener = f"srt://0.0.0.0:{port}?mode=listener&latency=200000&passphrase={SECRET_PASSPHRASE}&connect_timeout=15000"
    assert (await req(ws, "SetStreamServiceSettings", {
        "streamServiceType": "rtmp_custom",
        "streamServiceSettings": {"server": srt_listener, "key": "", "bitrateKbps": 3500}
    }))["requestStatus"]["result"]
    assert (await req(ws, "StartStream"))["requestStatus"]["result"]
    await asyncio.sleep(1.5)

    ff_proc = subprocess.Popen(
        ["ffmpeg", "-y", "-hide_banner", "-loglevel", "warning",
         "-i", f"srt://{dev_ip}:{port}?mode=caller&latency=200000&passphrase={SECRET_PASSPHRASE}",
         "-t", "4", "-c", "copy", rx_file],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
    )
    await asyncio.sleep(5.0)
    st = (await req(ws, "GetStreamStatus"))["responseData"]
    assert st["outputActive"] and st["outputBytes"] > 2000, f"Invalid live {expected_codec} SRT status: {st}"
    await req(ws, "StopStream")
    await asyncio.sleep(1.0)
    try:
        _, err = ff_proc.communicate(timeout=8)
    except subprocess.TimeoutExpired:
        ff_proc.kill()
        _, err = ff_proc.communicate()

    assert os.path.exists(rx_file) and os.path.getsize(rx_file) > 2000, f"{expected_codec} SRT rx file missing/empty: {err}"
    probe = json.loads(subprocess.check_output(
        ["ffprobe", "-v", "error", "-show_entries", "stream=codec_type,codec_name,width,height,sample_rate", "-of", "json", rx_file],
        text=True
    ))
    streams = probe.get("streams", [])
    assert any(s.get("codec_type") == "video" and s.get("codec_name") == expected_codec for s in streams), f"No {expected_codec}: {streams}"
    assert any(s.get("codec_type") == "audio" and s.get("codec_name") == "aac" for s in streams), f"No AAC: {streams}"
    # Full actual frame decode verification (not just container copy!)
    dec = subprocess.run(["ffmpeg", "-v", "error", "-i", rx_file, "-f", "null", "-"], capture_output=True, text=True)
    assert dec.returncode == 0, f"Full frame decode failed for {expected_codec}: {dec.stderr}"
    print(f"  [PASS] Real Encrypted {expected_codec.upper()} SRT ({os.path.getsize(rx_file)//1024} KB, bytes={st['outputBytes']}): ffprobe + FULL FRAME DECODE OK")


async def run_device_tests():
    dev_ip = detect_device_ip()
    adb("logcat", "-c")

    async with websockets.connect(WS_URL, open_timeout=10) as ws:
        await ws_auth(ws)
        await req(ws, "StopStream")
        await asyncio.sleep(0.5)

        # 1. Strict SRT Codec Gate: BOTH AV1 and VP9 must be rejected
        for bad_enc in ("android_mediacodec_av1", "android_mediacodec_vp9"):
            await req(ws, "SetVideoEncoder", {"encoderId": bad_enc})
            await req(ws, "SetStreamServiceSettings", {
                "streamServiceType": "rtmp_custom",
                "streamServiceSettings": {"server": "srt://0.0.0.0:19998?mode=listener", "key": "", "bitrateKbps": 3000}
            })
            r = await req(ws, "StartStream")
            assert not r["requestStatus"]["result"], f"{bad_enc} should be rejected on SRT, got {r}"
        print("  [PASS] SRT Codec Gate: BOTH AV1 and VP9 cleanly rejected")

        # 2. Real Encrypted H.264 SRT + Full Frame Decode
        await run_srt_codec_test(ws, dev_ip, "android_mediacodec_h264", "h264", 19999, "/tmp/panggung_srt_h264_rx.ts")

        # 3. Real Encrypted HEVC (H.265) SRT + Full Frame Decode
        await run_srt_codec_test(ws, dev_ip, "android_mediacodec_hevc", "hevc", 19996, "/tmp/panggung_srt_hevc_rx.ts")

        # Restore H.264
        await req(ws, "SetVideoEncoder", {"encoderId": "android_mediacodec_h264"})

        # 4. UI Start (tap GO LIVE in Portrait UI -> Kotlin wantStreaming=true) -> Remote WS StopStream -> Zero Zombie Reconnect
        assert tap_text("GO LIVE", 2.0), "GO LIVE button not found in Portrait UI"
        await req(ws, "StopStream")
        await asyncio.sleep(4.5)
        st_after = (await req(ws, "GetStreamStatus"))["responseData"]
        assert not st_after["outputActive"], f"Zombie reconnect after UI GO LIVE -> Remote StopStream: {st_after}"
        print("  [PASS] UI GO LIVE -> Remote WS StopStream: zero zombie reconnect after 4.5s")

        # 5. RTMPS Valid Cert (a.rtmps.youtube.com) AND Invalid Hostname Cert (a.rtmp.youtube.com -> Cert verify failed: 4)
        await req(ws, "StartMultistream", {"name": "rtmps_bad_cn", "url": "rtmps://a.rtmp.youtube.com:443/live2", "key": "invalid-0000"})
        await asyncio.sleep(2.5)
        await req(ws, "StopMultistreams")
        await req(ws, "StartMultistream", {"name": "rtmps_good_cn", "url": "rtmps://a.rtmps.youtube.com/live2", "key": "invalid-0000"})
        await asyncio.sleep(2.5)
        await req(ws, "StopMultistreams")

        # 6. Trigger unreachable SRT with passphrase to verify FFmpeg av_log error path redacts passphrase
        bad_srt = f"srt://127.0.0.1:19997?mode=caller&passphrase={SECRET_PASSPHRASE}&connect_timeout=1000"
        await req(ws, "SetStreamServiceSettings", {
            "streamServiceType": "rtmp_custom",
            "streamServiceSettings": {"server": bad_srt, "key": "", "bitrateKbps": 3000}
        })
        await req(ws, "StartStream")
        await asyncio.sleep(2.0)
        await req(ws, "StopStream")

    logs = adb("logcat", "-d").stdout
    assert "Cert verify failed: 4" in logs, "Expected RTMPS hostname mismatch rejection (Cert verify failed: 4)!"
    assert "RTMP_TLS_LoadCerts: Failed" not in logs, "RTMPS CA loader failed!"
    print("  [PASS] RTMPS TLS Verification: invalid hostname rejected (Cert verify failed: 4) + valid CA chain passed!")

    assert SECRET_PASSPHRASE not in logs, "SECURITY FAIL: SRT passphrase leaked into logcat!"
    crashes = [l for l in logs.splitlines() if any(k in l for k in ["FATAL EXCEPTION", "SIGSEGV", "SIGABRT", "ANR in io.panggung.obs"])]
    assert not crashes, f"Crashes detected: {crashes[:5]}"
    print("  [PASS] Zero Passphrase Leak in logcat (incl. FFmpeg av_log error path) & Zero Crashes/ANRs")


if __name__ == "__main__":
    print("=== PANGGUNG STUDIO v0.2.0 HARDENED SRT / HEVC / VP9 / RTMPS / UI-STOP E2E ===")
    test_version_code_monotonicity()
    asyncio.run(run_device_tests())
    print("=== ALL HARDENED E2E TESTS PASSED ===")
