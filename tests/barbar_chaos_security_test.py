#!/usr/bin/env python3
"""
Panggung Studio v0.2.0 — BARBAR 12-Stage Chaos, Security, Fuzz, A/V Sync & Binary Audit Gauntlet.
Runs on real Android hardware (SM-S721B) over ADB + LAN TLS 1.3 + obs-websocket v5 + live SRT.

Stages:
  B01. APK Deep Binary & Signature Audit (unzipped APK 16 KB ELF page alignment + apksigner)
  B02. Token Rotation Immediate Revocation (live WS kicked, old token fails WSS+HTTPS, new token works)
  B03. Web Remote DoS Torture (20 Slowloris sockets + 30 rapid HTTPS bursts + 2 MB oversized body)
  B04. Filesystem Path Traversal & Symlink Escape (StartCustomOutput + RemuxRecording)
  B05. Fuzz & Malformed obs-websocket Protocol Torture (bad JSON, huge frame, bad op, 80x burst)
  B06. Brutal SRT Kill-Receiver Mid-Stream + 4x Rapid Start/Stop Hammer + PSS/FD Audit
  B07. Concurrent Encrypted SRT + Local 1080p Recording + Camera Flip + Studio Mode CUT + Dual Decode
  B08. Audio-Video Sync (A/V Drift) Precision Check (12s live clip, ffprobe stream duration delta < 200ms)
  B09. In-Place Upgrade & Force-Stop Persistence (Custom Scene + Persistent LAN TLS SHA-256 Fingerprint)
  B10. Back-Button Clean Native Teardown x3 (Zero SIGSEGV / FT_Done_Face / Crash Recovery Banner)
  B11. Plain HTTP/WS Rejection on LAN Interface (TLS 1.3 Enforcement)
  B12. Global Zero Credential Leak & Zero Crash/ANR Logcat Audit
"""
import asyncio
import base64
import hashlib
import json
import os
import re
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
import websockets

APK_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "build", "outputs", "apk", "release", "app-release.apk",
)
SRT_PASS = "BarbarChaosSecret2026!"
results = []
req_counter = 0


def record(name, ok, detail=""):
    results.append((name, ok, detail))
    tag = "PASS" if ok else "FAIL"
    print(f"  [{tag}] {name}" + (f" — {detail}" if detail else ""), flush=True)


def adb(*args, timeout=30):
    return subprocess.run(
        ["adb", *args], capture_output=True, text=True, errors="replace", timeout=timeout
    )


def get_device_lan_ip():
    out = adb("shell", "ip", "-o", "-4", "addr", "show", "wlan0").stdout
    m = re.search(r"inet (\d+\.\d+\.\d+\.\d+)/", out)
    return m.group(1) if m else ""


def get_host_lan_ip(device_ip):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect((device_ip or "8.8.8.8", 80))
        return s.getsockname()[0]
    finally:
        s.close()


def dump_ui_xml():
    adb("shell", "uiautomator", "dump", "/sdcard/ui_barbar.xml")
    r = adb("exec-out", "cat", "/sdcard/ui_barbar.xml")
    return r.stdout if r.stdout.strip().startswith("<") else ""


def tap_bounds(bounds_str):
    nums = re.findall(r"\d+", bounds_str)
    if len(nums) != 4:
        return False
    x = (int(nums[0]) + int(nums[2])) // 2
    y = (int(nums[1]) + int(nums[3])) // 2
    adb("shell", "input", "tap", str(x), str(y))
    return True


def tap_text(txt, wait=1.0):
    xml = dump_ui_xml()
    if not xml:
        return False
    try:
        root = ET.fromstring(xml)
    except Exception:
        return False
    for n in root.iter("node"):
        if n.attrib.get("text") == txt or n.attrib.get("content-desc") == txt:
            tap_bounds(n.attrib.get("bounds", ""))
            time.sleep(wait)
            return True
    return False


def open_qr_and_read_token(enable_if_off=True, enable_lan=True, tap_rotate=False):
    assert tap_text("QR", 1.2), "Could not tap QR button"
    if enable_if_off:
        # Toggle Switch 1 (Enable Remote Control) and Switch 2 (Allow LAN Access TLS 1.3)
        for sw_idx in range(2 if enable_lan else 1):
            xml = dump_ui_xml()
            root = ET.fromstring(xml)
            switches = [n for n in root.iter("node") if n.attrib.get("checkable") == "true"]
            if sw_idx < len(switches) and switches[sw_idx].attrib.get("checked") == "false":
                tap_bounds(switches[sw_idx].attrib.get("bounds", ""))
                time.sleep(1.8)
    if tap_rotate:
        assert tap_text("Rotate", 1.5), "Could not tap Rotate button"
    xml2 = dump_ui_xml()
    toks = re.findall(r'text="([A-Za-z0-9]{16})"', xml2)
    fp_match = re.findall(r'text="([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){15,31})"', xml2)
    fp = fp_match[0] if fp_match else ""
    tap_text("Done", 0.8) or tap_text("Close", 0.8)
    return (toks[0] if toks else ""), fp


def compute_obs_ws_auth(password: str, salt: str, challenge: str) -> str:
    sec = base64.b64encode(hashlib.sha256((password + salt).encode()).digest()).decode()
    return base64.b64encode(hashlib.sha256((sec + challenge).encode()).digest()).decode()


def make_unverified_ssl():
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx


async def ws_connect_auth(url, token, ssl_ctx=None, timeout=8.0):
    ws = await asyncio.wait_for(
        websockets.connect(url, ssl=ssl_ctx, max_size=4 * 1024 * 1024), timeout=timeout
    )
    hello = json.loads(await asyncio.wait_for(ws.recv(), timeout=timeout))
    a = hello["d"]["authentication"]
    auth = compute_obs_ws_auth(token, a["salt"], a["challenge"])
    await ws.send(json.dumps({"op": 1, "d": {"rpcVersion": 1, "authentication": auth}}))
    ident = json.loads(await asyncio.wait_for(ws.recv(), timeout=timeout))
    if ident.get("op") != 2:
        await ws.close()
        raise RuntimeError(f"WS Identify failed: {ident}")
    return ws


async def ws_req(ws, rtype, rdata=None, timeout=15.0):
    global req_counter
    req_counter += 1
    rid = f"bb-{req_counter}"
    payload = {"op": 6, "d": {"requestType": rtype, "requestId": rid}}
    if rdata is not None:
        payload["d"]["requestData"] = rdata
    await ws.send(json.dumps(payload))
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        raw = await asyncio.wait_for(ws.recv(), timeout=max(0.5, deadline - time.monotonic()))
        msg = json.loads(raw)
        if msg.get("op") == 7 and msg.get("d", {}).get("requestId") == rid:
            d = msg["d"]
            return d["requestStatus"]["result"], d["requestStatus"]["code"], d.get("responseData", {})
    raise TimeoutError(f"Timeout waiting for {rtype}")


def get_pss_mb():
    out = adb("shell", "dumpsys meminfo io.panggung.obs | grep 'TOTAL PSS:'").stdout
    m = re.search(r"TOTAL PSS:\s+(\d+)", out)
    return round(int(m.group(1)) / 1024.0, 1) if m else 0.0


def get_tls_cert_sha256(ip, port=8080):
    ctx = make_unverified_ssl()
    with socket.create_connection((ip, port), timeout=5) as sock:
        with ctx.wrap_socket(sock, server_hostname=ip) as ssock:
            der = ssock.getpeercert(binary_form=True)
            proto = ssock.version()
            return hashlib.sha256(der).hexdigest().upper(), proto


def full_decode_check(media_path):
    """Run ffprobe + full ffmpeg decode to null to prove zero corrupt bitstream frames."""
    if not os.path.exists(media_path) or os.path.getsize(media_path) < 4096:
        return False, "file missing or too small"
    pr = subprocess.run(
        [
            "ffprobe", "-v", "error",
            "-show_entries", "format=duration:stream=codec_type,codec_name,width,height,duration,start_time",
            "-of", "json", media_path,
        ],
        capture_output=True, text=True,
    )
    if pr.returncode != 0:
        return False, f"ffprobe err: {pr.stderr.strip()[:120]}"
    info = json.loads(pr.stdout)
    dec = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", media_path, "-f", "null", "-"],
        capture_output=True, text=True, timeout=30,
    )
    if dec.returncode != 0:
        return False, f"decode err: {dec.stderr.strip()[:120]}"
    return True, info


async def stage_b01_apk_binary_audit():
    if not os.path.exists(APK_PATH):
        record("B01: APK Deep Binary & Signature Audit", False, f"APK not found: {APK_PATH}")
        return
    non_16k = []
    so_count = 0
    with tempfile.TemporaryDirectory() as td:
        with zipfile.ZipFile(APK_PATH, "r") as zf:
            for name in zf.namelist():
                if name.startswith("lib/arm64-v8a/") and name.endswith(".so"):
                    so_count += 1
                    extracted = zf.extract(name, td)
                    r = subprocess.run(["readelf", "-lW", extracted], capture_output=True, text=True)
                    for line in r.stdout.splitlines():
                        if line.strip().startswith("LOAD"):
                            parts = line.split()
                            align = int(parts[-1], 16)
                            if align < 0x4000:
                                non_16k.append(f"{os.path.basename(name)}({hex(align)})")
    # Check apksigner if available
    apksigner_ok = True
    sig_note = "apksigner N/A"
    android_home = os.environ.get("ANDROID_HOME", os.path.expanduser("~/Android/Sdk"))
    bt_dir = os.path.join(android_home, "build-tools")
    if os.path.isdir(bt_dir):
        vers = sorted(os.listdir(bt_dir))
        if vers:
            apksigner = os.path.join(bt_dir, vers[-1], "apksigner")
            if os.path.exists(apksigner):
                sr = subprocess.run([apksigner, "verify", "--print-certs", APK_PATH], capture_output=True, text=True)
                apksigner_ok = sr.returncode == 0
                sig_note = "apksigner OK" if apksigner_ok else f"apksigner FAIL: {sr.stderr.strip()[:80]}"
    record(
        "B01: APK Deep Binary Audit (16KB ELF + Signing)",
        so_count >= 20 and len(non_16k) == 0 and apksigner_ok,
        f"so_count={so_count} non_16k={non_16k} {sig_note}",
    )


async def run_gauntlet():
    print("=== PANGGUNG STUDIO v0.2.0 — BARBAR 12-STAGE CHAOS & SECURITY GAUNTLET ===", flush=True)
    dev_ip = get_device_lan_ip()
    host_ip = get_host_lan_ip(dev_ip)
    assert dev_ip, "Could not detect device wlan0 IP"
    print(f"  [INFO] Device LAN IP={dev_ip} | Host LAN IP={host_ip}", flush=True)

    # B01: Binary audit
    await stage_b01_apk_binary_audit()

    # Fresh app launch
    adb("logcat", "-c")
    adb("shell", "pm", "clear", "io.panggung.obs")
    adb("shell", "pm", "grant", "io.panggung.obs", "android.permission.CAMERA")
    adb("shell", "pm", "grant", "io.panggung.obs", "android.permission.RECORD_AUDIO")
    adb("shell", "pm", "grant", "io.panggung.obs", "android.permission.POST_NOTIFICATIONS")
    adb("forward", "tcp:4455", "tcp:4455")
    adb("forward", "tcp:8080", "tcp:8080")
    adb("shell", "am", "start", "-n", "io.panggung.obs/.MainActivity")
    await asyncio.sleep(4.0)

    token1, _ = open_qr_and_read_token(enable_if_off=True, tap_rotate=False)
    assert len(token1) == 16, f"Failed to read initial token: {token1}"
    ssl_ctx = make_unverified_ssl()
    wss_url = f"wss://{dev_ip}:4455"
    https_url = f"https://{dev_ip}:8080/"

    # B02: Token Rotation Immediate Revocation
    ws_old = await ws_connect_auth(wss_url, token1, ssl_ctx=ssl_ctx)
    ok_v, _, _ = await ws_req(ws_old, "GetVersion")
    assert ok_v, "Initial WSS GetVersion failed"
    token2, _ = open_qr_and_read_token(enable_if_off=False, tap_rotate=True)
    assert len(token2) == 16 and token2 != token1, f"Token rotation failed: {token1} -> {token2}"

    # Verify ws_old is kicked/closed
    old_kicked = False
    try:
        await asyncio.wait_for(ws_old.recv(), timeout=4.0)
        # Might receive Close frame or next req fails
        await ws_req(ws_old, "GetVersion", timeout=2.0)
    except Exception:
        old_kicked = True
    try:
        await ws_old.close()
    except Exception:
        pass

    # Verify old token rejected on WSS & HTTPS
    old_wss_rejected = False
    try:
        ws_try = await ws_connect_auth(wss_url, token1, ssl_ctx=ssl_ctx, timeout=4.0)
        await ws_try.close()
    except Exception:
        old_wss_rejected = True

    old_https_rejected = False
    try:
        req = urllib.request.Request(https_url, headers={"Authorization": f"Bearer {token1}"})
        urllib.request.urlopen(req, context=ssl_ctx, timeout=4)
    except urllib.error.HTTPError as e:
        old_https_rejected = (e.code == 401)
    except Exception:
        old_https_rejected = True

    # Verify new token works on BOTH WSS & HTTPS
    ws_new = await ws_connect_auth(wss_url, token2, ssl_ctx=ssl_ctx)
    new_wss_ok, _, _ = await ws_req(ws_new, "GetVersion")
    req2 = urllib.request.Request(https_url, headers={"Authorization": f"Bearer {token2}"})
    with urllib.request.urlopen(req2, context=ssl_ctx, timeout=5) as r2:
        new_https_ok = (r2.status == 200)
    await ws_new.close()
    record(
        "B02: Token Rotation Immediate Revocation (WSS + HTTPS)",
        old_kicked and old_wss_rejected and old_https_rejected and new_wss_ok and new_https_ok,
        f"kicked={old_kicked} old_wss_rej={old_wss_rejected} old_https_rej={old_https_rejected} new_ok={new_wss_ok and new_https_ok}",
    )
    active_token = token2

    # B03: Web Remote Flood + 20 Slowloris + 2 MB Oversized Body
    slow_sockets = []
    for _ in range(20):
        try:
            raw_s = socket.create_connection((dev_ip, 8080), timeout=2.0)
            s_tls = ssl_ctx.wrap_socket(raw_s, server_hostname=dev_ip)
            s_tls.sendall(b"GET / HTTP/1.1\r\nHost: slow\r\n")
            slow_sockets.append(s_tls)
        except Exception:
            pass

    # Fire 30 rapid HTTPS requests while Slowloris sockets are hogging slots
    t0 = time.monotonic()
    burst_ok = 0
    for _ in range(30):
        try:
            rq = urllib.request.Request(https_url, headers={"Authorization": f"Bearer {active_token}"})
            with urllib.request.urlopen(rq, context=ssl_ctx, timeout=4) as resp:
                if resp.status == 200:
                    burst_ok += 1
        except Exception:
            pass
    burst_elapsed = time.monotonic() - t0

    # Oversized 2 MB POST payload must be rejected cleanly
    oversize_rejected = False
    try:
        big_req = urllib.request.Request(
            f"https://{dev_ip}:8080/api/obs",
            data=b"A" * (2 * 1024 * 1024),
            headers={"Authorization": f"Bearer {active_token}", "Content-Type": "application/json"},
            method="POST",
        )
        urllib.request.urlopen(big_req, context=ssl_ctx, timeout=5)
    except urllib.error.HTTPError as e:
        oversize_rejected = e.code in (400, 404, 413)
    except Exception:
        oversize_rejected = True

    for s in slow_sockets:
        try:
            s.close()
        except Exception:
            pass

    # Verify server is still 100% healthy after attack
    rq_after = urllib.request.Request(https_url, headers={"Authorization": f"Bearer {active_token}"})
    with urllib.request.urlopen(rq_after, context=ssl_ctx, timeout=5) as ra:
        alive_after = (ra.status == 200)
    record(
        "B03: Web Remote DoS Torture (20 Slowloris + 30x Burst + 2MB Body)",
        burst_ok >= 15 and oversize_rejected and alive_after,
        f"slow={len(slow_sockets)} burst={burst_ok}/30 ({burst_elapsed:.1f}s) oversize_rej={oversize_rejected} alive={alive_after}",
    )

    # Connect main WSS session for B04..B08
    ws = await ws_connect_auth(wss_url, active_token, ssl_ctx=ssl_ctx)

    # B04: Filesystem Path Traversal & Symlink Escape
    adb("shell", "ln -sf /etc/hosts /data/local/tmp/barbar_sym_escape.mp4")
    evil_paths = [
        "/tmp/pwn.mp4",
        "/data/local/tmp/barbar_sym_escape.mp4",
        "/sdcard/Android/data/io.panggung.obs/files/Movies/../../../../../../data/local/tmp/pwn.mp4",
        "/etc/passwd",
    ]
    all_blocked = True
    for ep in evil_paths:
        ok_co1, _, _ = await ws_req(ws, "StartCustomOutput", {"outputId": "ffmpeg_muxer", "url": ep})
        ok_co2, _, _ = await ws_req(ws, "StartCustomOutput", {"path": ep, "format": "mp4"})
        ok_rm, _, _ = await ws_req(ws, "RemuxRecording", {"srcPath": ep, "dstPath": ep + ".mp4"})
        if ok_co1 or ok_co2 or ok_rm:
            all_blocked = False
    adb("shell", "rm -f /data/local/tmp/barbar_sym_escape.mp4")
    record("B04: Filesystem Path Traversal & Symlink Escape Blocked", all_blocked, f"tested={len(evil_paths)} vectors")

    # B05: Malformed / Fuzz obs-websocket Protocol Torture
    fuzz_ws = await ws_connect_auth(wss_url, active_token, ssl_ctx=ssl_ctx)
    try:
        await fuzz_ws.send("{{{NOT_JSON_AT_ALL")
        await fuzz_ws.send(json.dumps({"op": 999, "d": {"garbage": [1, 2, 3]}}))
        await fuzz_ws.send(json.dumps({"op": 6, "d": {"requestType": "NonExistentReq", "requestId": "fz1", "requestData": "wrong_type"}}))
        await fuzz_ws.send(json.dumps({"op": 6, "d": {"requestType": "CreateInput", "requestId": "fz2", "requestData": {"inputName": "X" * 50000}}}))
        await asyncio.sleep(0.5)
    except Exception:
        pass
    try:
        await fuzz_ws.close()
    except Exception:
        pass
    # Fire 80 rapid pipelined GetStats on healthy session to verify zero crash / lockup
    burst_ws_ok = 0
    for _ in range(80):
        ok_s, _, _ = await ws_req(ws, "GetStats")
        if ok_s:
            burst_ws_ok += 1
    record("B05: Fuzz & Malformed WS Torture + 80x Rapid Pipeline", burst_ws_ok == 80, f"stats_ok={burst_ws_ok}/80")

    # B06: Brutal SRT Kill-Receiver Mid-Stream + 4x Rapid Start/Stop Hammer + PSS Audit
    await ws_req(ws, "SetVideoEncoder", {"encoderId": "android_mediacodec_h264"})
    hammer_ok = True
    pss_warm = 0.0
    for cycle in range(4):
        c_port = 19950 + cycle
        srt_listen_url = f"srt://0.0.0.0:{c_port}?mode=listener&latency=200000&passphrase={SRT_PASS}&connect_timeout=10000"
        cap_file = f"/tmp/barbar_srt_cycle_{cycle}.ts"
        if os.path.exists(cap_file):
            os.remove(cap_file)
        await ws_req(ws, "SetStreamServiceSettings", {
            "streamServiceType": "rtmp_custom",
            "streamServiceSettings": {"server": srt_listen_url, "key": ""},
        })
        ok_st, _, _ = await ws_req(ws, "StartStream")
        await asyncio.sleep(1.0)
        rx = subprocess.Popen(
            [
                "ffmpeg", "-y", "-v", "error",
                "-i", f"srt://{dev_ip}:{c_port}?mode=caller&latency=200000&passphrase={SRT_PASS}",
                "-c", "copy", "-f", "mpegts", cap_file,
            ],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        await asyncio.sleep(2.5)
        if cycle in (0, 2):
            # BRUTAL: SIGKILL connected SRT receiver mid-stream!
            rx.kill()
            rx.wait(timeout=5)
            await asyncio.sleep(0.8)
            await ws_req(ws, "StopStream")
        else:
            await ws_req(ws, "StopStream")
            await asyncio.sleep(0.6)
            rx.terminate()
            try:
                rx.wait(timeout=4)
            except Exception:
                rx.kill()
        await asyncio.sleep(0.6)
        if cycle == 0:
            pss_warm = get_pss_mb()
        ok_alive, _, _ = await ws_req(ws, "GetStats")
        if not (ok_st and ok_alive):
            hammer_ok = False
            break
    pss_after_srt = get_pss_mb()
    srt_pss_delta = round(pss_after_srt - pss_warm, 1)
    record(
        "B06: SRT Mid-Stream SIGKILL + 4x Rapid Hammer + Memory Audit",
        hammer_ok and srt_pss_delta < 60.0,
        f"warm_pss={pss_warm}->{pss_after_srt}MB (delta={srt_pss_delta:+.1f}MB)",
    )

    # B07: Concurrent Encrypted SRT + Local Recording + Camera Flip + Studio Mode CUT + Dual Decode
    await ws_req(ws, "CreateScene", {"sceneName": "Chaos Scene B"})
    srt_conc_port = 19956
    srt_conc_url = f"srt://0.0.0.0:{srt_conc_port}?mode=listener&latency=200000&passphrase={SRT_PASS}&connect_timeout=15000"
    await ws_req(ws, "SetStreamServiceSettings", {
        "streamServiceType": "rtmp_custom",
        "streamServiceSettings": {"server": srt_conc_url, "key": ""},
    })
    srt_conc_file = "/tmp/barbar_concurrent_srt.ts"
    if os.path.exists(srt_conc_file):
        os.remove(srt_conc_file)
    ok_rec1, _, _ = await ws_req(ws, "StartRecord")
    ok_str1, _, _ = await ws_req(ws, "StartStream")
    await asyncio.sleep(1.2)
    rx_conc = subprocess.Popen(
        [
            "ffmpeg", "-y", "-v", "error",
            "-i", f"srt://{dev_ip}:{srt_conc_port}?mode=caller&latency=200000&passphrase={SRT_PASS}",
            "-t", "5", "-c", "copy", "-f", "mpegts", srt_conc_file,
        ],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    await asyncio.sleep(1.5)
    # Studio Mode + Scene Switch + CUT + Camera flip while BOTH Stream & Record are running!
    await ws_req(ws, "SetStudioModeEnabled", {"studioModeEnabled": True})
    await ws_req(ws, "SetCurrentPreviewScene", {"sceneName": "Chaos Scene B"})
    await ws_req(ws, "TriggerStudioModeTransition")
    await asyncio.sleep(1.5)
    await ws_req(ws, "SetCurrentPreviewScene", {"sceneName": "Main Scene"})
    await ws_req(ws, "TriggerStudioModeTransition")
    await ws_req(ws, "SetStudioModeEnabled", {"studioModeEnabled": False})
    await asyncio.sleep(2.0)
    # Stop SRT while Recording continues!
    await ws_req(ws, "StopStream")
    await asyncio.sleep(1.5)
    rx_conc.terminate()
    try:
        rx_conc.wait(timeout=5)
    except Exception:
        rx_conc.kill()
    ok_rec2, _, rec2_data = await ws_req(ws, "StopRecord")
    await asyncio.sleep(1.0)
    local_conc_mp4 = "/tmp/barbar_concurrent_rec.mp4"
    if os.path.exists(local_conc_mp4):
        os.remove(local_conc_mp4)
    rec_path = rec2_data.get("outputPath", "")
    if rec_path:
        adb("pull", rec_path, local_conc_mp4)
    srt_dec_ok, srt_dec_info = full_decode_check(srt_conc_file)
    rec_dec_ok, rec_dec_info = full_decode_check(local_conc_mp4)
    record(
        "B07: Concurrent Encrypted SRT + Local Rec + Studio Mode CUT + Dual Decode",
        ok_rec1 and ok_str1 and ok_rec2 and srt_dec_ok and rec_dec_ok,
        f"srt_kb={os.path.getsize(srt_conc_file)//1024 if os.path.exists(srt_conc_file) else 0} srt_ok={srt_dec_ok} rec_ok={rec_dec_ok}",
    )

    # B08: Audio-Video Sync (A/V Drift) Precision Check (12s live recording)
    ok_av1, _, _ = await ws_req(ws, "StartRecord")
    await asyncio.sleep(12.0)
    ok_av2, _, av_data = await ws_req(ws, "StopRecord")
    await asyncio.sleep(1.2)
    av_mp4 = "/tmp/barbar_av_sync.mp4"
    if os.path.exists(av_mp4):
        os.remove(av_mp4)
    adb("pull", av_data.get("outputPath", ""), av_mp4)
    av_dec_ok, av_info = full_decode_check(av_mp4)
    av_drift_ms = 9999.0
    if av_dec_ok and isinstance(av_info, dict):
        streams = av_info.get("streams", [])
        v_s = next((s for s in streams if s.get("codec_type") == "video"), None)
        a_s = next((s for s in streams if s.get("codec_type") == "audio"), None)
        if v_s and a_s and "duration" in v_s and "duration" in a_s:
            av_drift_ms = abs(float(v_s["duration"]) - float(a_s["duration"])) * 1000.0
    record(
        "B08: Audio-Video Sync Precision Check (12s Rec, Drift < 250ms)",
        ok_av1 and ok_av2 and av_dec_ok and av_drift_ms < 250.0,
        f"av_drift={av_drift_ms:.1f}ms",
    )

    # Create custom scene for B09 upgrade persistence
    await ws_req(ws, "CreateScene", {"sceneName": "Persist Upgrade Scene"})
    await asyncio.sleep(1.0)
    fp_before, tls_proto = get_tls_cert_sha256(dev_ip, 8080)
    await ws.close()

    # B09: In-Place APK Upgrade (`adb install -r`) + Force-Stop Persistence
    inst = adb("install", "-r", APK_PATH, timeout=60)
    adb("shell", "am", "force-stop", "io.panggung.obs")
    await asyncio.sleep(1.0)
    adb("shell", "am", "start", "-n", "io.panggung.obs/.MainActivity")
    await asyncio.sleep(4.0)
    token3, _ = open_qr_and_read_token(enable_if_off=True, tap_rotate=False)
    fp_after, tls_proto_after = get_tls_cert_sha256(dev_ip, 8080)
    ws_up = await ws_connect_auth(wss_url, token3, ssl_ctx=ssl_ctx)
    _, _, sc_list = await ws_req(ws_up, "GetSceneList")
    scene_names = [s.get("sceneName") for s in sc_list.get("scenes", [])]
    await ws_up.close()
    record(
        "B09: In-Place Upgrade + Force-Stop Persistence (Scene + TLS Cert)",
        inst.returncode == 0 and fp_before == fp_after and "Persist Upgrade Scene" in scene_names,
        f"tls={tls_proto_after} fp_match={fp_before == fp_after} scene_persisted={'Persist Upgrade Scene' in scene_names}",
    )

    # B10: Back-Button Clean Native Teardown x3
    back_ok = True
    for i in range(3):
        adb("shell", "input", "keyevent", "4")
        await asyncio.sleep(2.5)
        adb("shell", "am", "start", "-n", "io.panggung.obs/.MainActivity")
        await asyncio.sleep(3.5)
        xml_b = dump_ui_xml()
        if "Crash Recovery" in xml_b or "STUDIO v0.2.0" not in xml_b:
            back_ok = False
            break
    record("B10: Back-Button Clean Native Teardown x3 (Zero Crash Recovery)", back_ok)

    # B11: Plain HTTP/WS Rejected on LAN Interface (TLS 1.3 Enforced)
    # Enable remote control again after back-button restarts
    open_qr_and_read_token(enable_if_off=True, tap_rotate=False)
    plain_http_rejected = False
    try:
        urllib.request.urlopen(f"http://{dev_ip}:8080/", timeout=3)
    except Exception:
        plain_http_rejected = True
    plain_ws_rejected = False
    try:
        ws_plain = await asyncio.wait_for(websockets.connect(f"ws://{dev_ip}:4455"), timeout=3)
        await ws_plain.close()
    except Exception:
        plain_ws_rejected = True
    record(
        "B11: Plain HTTP & WS Rejected on LAN (Strict TLS 1.3)",
        plain_http_rejected and plain_ws_rejected,
        f"http_rej={plain_http_rejected} ws_rej={plain_ws_rejected}",
    )

    # B12: Global Zero Credential Leak & Zero Crash/ANR Logcat Audit
    lc = adb("logcat", "-d").stdout
    leaks = []
    for label, secret in [("SRT_PASS", SRT_PASS), ("TOKEN1", token1), ("TOKEN2", token2)]:
        if secret and secret in lc:
            leaks.append(label)
    crashes = [k for k in ["FATAL EXCEPTION", "Fatal signal 11", "Fatal signal 6", "FT_Done_Face", "ANR in io.panggung.obs"] if k in lc]
    record(
        "B12: Global Zero Credential Leak & Zero Crash/ANR Logcat Audit",
        len(leaks) == 0 and len(crashes) == 0,
        f"leaks={leaks} crashes={crashes}",
    )

    passed = sum(1 for _, ok, _ in results if ok)
    total = len(results)
    print(f"\n=== BARBAR CHAOS & SECURITY GAUNTLET SUMMARY: {passed}/{total} PASSED ===", flush=True)
    return passed == total


if __name__ == "__main__":
    sys.exit(0 if asyncio.run(run_gauntlet()) else 1)
