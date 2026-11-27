use std::io::{Read, Write};
use std::net::TcpListener;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::thread;
use std::time::Duration;

use log::{error, info};

use crate::ws_server::constant_time_eq;

static WEB_RUNNING: AtomicBool = AtomicBool::new(false);
static WEB_GENERATION: AtomicU64 = AtomicU64::new(0);

const UNAUTHORIZED_HTML: &str = r#"<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>Panggung Remote — Authentication Required</title>
<style>
  body { margin:0; font-family:system-ui,-apple-system,sans-serif; background:#0e1016; color:#f1f5f9; display:flex; align-items:center; justify-content:center; min-height:100vh; padding:16px; }
  .box { background:#161922; border:1px solid #262b3a; border-radius:12px; padding:20px; max-width:360px; width:100%; }
  h1 { font-size:17px; margin:0 0 8px; }
  p { font-size:13px; color:#94a3b8; margin:0 0 14px; }
  input { width:100%; box-sizing:border-box; padding:10px; border-radius:8px; border:1px solid #334155; background:#0b0d12; color:#f1f5f9; font-family:monospace; margin-bottom:10px; }
  button { width:100%; padding:10px; border-radius:8px; border:none; background:#14b8a6; color:#042f2e; font-weight:700; cursor:pointer; }
</style>
</head>
<body>
  <div class="box">
    <h1>Panggung Studio Remote</h1>
    <p>Enter the session token shown in Panggung Studio &rarr; Remote Control.</p>
    <form method="POST" action="/auth">
      <input type="password" name="token" placeholder="Session Token" required autofocus/>
      <button type="submit">Unlock Remote</button>
    </form>
  </div>
</body>
</html>"#;

const REMOTE_HTML: &str = r#"<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>Panggung Studio Web Remote</title>
<style>
  body { margin:0; font-family: system-ui, -apple-system, sans-serif; background:#0e1016; color:#f1f5f9; padding:16px; }
  h1 { font-size:18px; margin:0 0 8px; display:flex; justify-content:space-between; align-items:center; }
  .badge { font-size:12px; padding:4px 8px; border-radius:6px; background:#1e293b; color:#38bdf8; font-family:monospace; }
  .card { background:#161922; border-radius:10px; padding:12px; margin-bottom:12px; }
  .row { display:flex; gap:8px; flex-wrap:wrap; }
  button { background:#2563eb; color:#fff; border:none; border-radius:8px; padding:10px 14px; font-weight:600; cursor:pointer; }
  button.red { background:#dc2626; }
  button.green { background:#16a34a; }
  button.sec { background:#334155; }
  button.active { outline:2px solid #38bdf8; background:#1d4ed8; }
  .stat { font-family:monospace; font-size:13px; color:#94a3b8; }
</style>
</head>
<body>
  <h1>Panggung Web Remote <span id="conn" class="badge">Connecting...</span></h1>
  <div class="card stat" id="stats">FPS: -- | Frame: -- ms | Skipped: --</div>
  <div class="card">
    <div style="margin-bottom:8px;font-weight:600">Scenes</div>
    <div id="scenes" class="row"></div>
  </div>
  <div class="card">
    <div style="margin-bottom:8px;font-weight:600">Controls</div>
    <div class="row">
      <button id="recBtn" onclick="toggleRec()">Start Record</button>
      <button class="sec" onclick="req('StartReplayBuffer',{maxTimeSec:15})">Start Replay</button>
      <button class="green" onclick="req('SaveReplayBuffer')">Save Replay</button>
      <button class="sec" onclick="req('TriggerStudioModeTransition')">Studio Transition</button>
    </div>
  </div>
  <div class="card">
    <div style="margin-bottom:8px;font-weight:600">Audio Mixer</div>
    <div id="mixer"></div>
  </div>
<script>
const TOKEN = "__PANGGUNG_SESSION_TOKEN__";
const wsUrl = `ws://${location.hostname}:4455`;
let ws, rid = 0, recActive = false, identified = false;

// Pure-JS SHA-256 -> Base64 (works on plain http:// LAN origins without crypto.subtle)
function sha256B64(ascii) {
  function rrot(v, a) { return (v >>> a) | (v << (32 - a)); }
  const K = [
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
  ];
  const bytes = new TextEncoder().encode(ascii);
  const bitLen = bytes.length * 8;
  const paddedLen = Math.ceil((bytes.length + 9) / 64) * 64;
  const msg = new Uint8Array(paddedLen);
  msg.set(bytes);
  msg[bytes.length] = 0x80;
  const dv = new DataView(msg.buffer);
  dv.setUint32(paddedLen - 8, Math.floor(bitLen / 0x100000000), false);
  dv.setUint32(paddedLen - 4, bitLen >>> 0, false);
  const H = [0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19];
  const w = new Uint32Array(64);
  for (let off = 0; off < paddedLen; off += 64) {
    for (let i = 0; i < 16; i++) w[i] = dv.getUint32(off + i * 4, false);
    for (let i = 16; i < 64; i++) {
      const s0 = rrot(w[i-15],7) ^ rrot(w[i-15],18) ^ (w[i-15] >>> 3);
      const s1 = rrot(w[i-2],17) ^ rrot(w[i-2],19) ^ (w[i-2] >>> 10);
      w[i] = (w[i-16] + s0 + w[i-7] + s1) >>> 0;
    }
    let [a,b,c,d,e,f,g,h] = H;
    for (let i = 0; i < 64; i++) {
      const s1 = rrot(e,6) ^ rrot(e,11) ^ rrot(e,25);
      const ch = (e & f) ^ ((~e) & g);
      const t1 = (h + s1 + ch + K[i] + w[i]) >>> 0;
      const s0 = rrot(a,2) ^ rrot(a,13) ^ rrot(a,22);
      const maj = (a & b) ^ (a & c) ^ (b & c);
      const t2 = (s0 + maj) >>> 0;
      h=g; g=f; f=e; e=(d+t1)>>>0; d=c; c=b; b=a; a=(t1+t2)>>>0;
    }
    H[0]=(H[0]+a)>>>0; H[1]=(H[1]+b)>>>0; H[2]=(H[2]+c)>>>0; H[3]=(H[3]+d)>>>0;
    H[4]=(H[4]+e)>>>0; H[5]=(H[5]+f)>>>0; H[6]=(H[6]+g)>>>0; H[7]=(H[7]+h)>>>0;
  }
  const out = new Uint8Array(32);
  const odv = new DataView(out.buffer);
  for (let i = 0; i < 8; i++) odv.setUint32(i * 4, H[i], false);
  let bin = '';
  for (let i = 0; i < 32; i++) bin += String.fromCharCode(out[i]);
  return btoa(bin);
}

function req(type, data) {
  if (!ws || ws.readyState !== 1 || !identified) return;
  ws.send(JSON.stringify({op:6, d:{requestType:type, requestId:'w'+(++rid), requestData:data||{}}}));
}
function toggleRec() { req(recActive ? 'StopRecord' : 'StartRecord'); }
function connect() {
  identified = false;
  ws = new WebSocket(wsUrl);
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.op === 0) {
      const auth = m.d && m.d.authentication;
      let authStr = '';
      if (auth && auth.salt && auth.challenge) {
        const secret = sha256B64(TOKEN + auth.salt);
        authStr = sha256B64(secret + auth.challenge);
      }
      ws.send(JSON.stringify({op:1, d:{rpcVersion:1, authentication: authStr}}));
      return;
    }
    if (m.op === 2) {
      identified = true;
      document.getElementById('conn').textContent = 'Authenticated :4455';
      pollOnce();
      return;
    }
    if (m.op !== 7) return;
    const t = m.d.requestType, r = m.d.responseData || {};
    if (t === 'GetStats') {
      document.getElementById('stats').textContent = `FPS: ${(r.activeFps||0).toFixed(1)} | Render: ${(r.averageFrameRenderTime||0).toFixed(2)} ms | Skipped: ${r.renderSkippedFrames||0}`;
    } else if (t === 'GetSceneList') {
      const el = document.getElementById('scenes'); el.textContent = '';
      (r.scenes||[]).forEach(s => {
        const b = document.createElement('button');
        b.textContent = s.sceneName;
        if (s.sceneName === r.currentProgramSceneName) b.className = 'active';
        b.onclick = () => req('SetCurrentProgramScene', {sceneName: s.sceneName});
        el.appendChild(b);
      });
    } else if (t === 'GetRecordStatus') {
      recActive = !!r.outputActive;
      const b = document.getElementById('recBtn');
      b.textContent = recActive ? 'Stop Record' : 'Start Record';
      b.className = recActive ? 'red' : '';
    } else if (t === 'GetInputList') {
      const el = document.getElementById('mixer'); el.textContent = '';
      (r.inputs||[]).forEach(i => {
        const d = document.createElement('div');
        d.style.marginBottom = '6px';
        const sp = document.createElement('span');
        sp.textContent = i.inputName + ' ';
        const bm = document.createElement('button');
        bm.className = 'sec'; bm.textContent = 'Mute';
        bm.onclick = () => req('SetInputMute', {inputName: i.inputName, inputMuted: true});
        const bu = document.createElement('button');
        bu.className = 'sec'; bu.textContent = 'Unmute';
        bu.style.marginLeft = '4px';
        bu.onclick = () => req('SetInputMute', {inputName: i.inputName, inputMuted: false});
        d.appendChild(sp); d.appendChild(bm); d.appendChild(bu);
        el.appendChild(d);
      });
    }
  };
  ws.onclose = (ev) => {
    identified = false;
    document.getElementById('conn').textContent = ev.code === 4009 ? 'Auth Failed (4009)' : 'Reconnecting...';
    if (ev.code !== 4009) setTimeout(connect, 2000);
  };
}
function pollOnce() {
  if (ws && ws.readyState === 1 && identified) {
    req('GetStats'); req('GetSceneList'); req('GetRecordStatus'); req('GetInputList');
  }
}
setInterval(pollOnce, 1500);
connect();
</script>
</body>
</html>
"#;

pub fn stop_web_remote_server() {
    WEB_RUNNING.store(false, Ordering::SeqCst);
    WEB_GENERATION.fetch_add(1, Ordering::SeqCst);
}

fn extract_http_token(req: &str) -> Option<String> {
    // 1. Authorization: Bearer <token>
    for line in req.lines() {
        let lower = line.to_ascii_lowercase();
        if lower.starts_with("authorization: bearer ") {
            return Some(line[22..].trim().to_string());
        }
        // 2. HttpOnly Cookie: panggung_token=<token>
        if lower.starts_with("cookie:") {
            for part in line[7..].split(';') {
                if let Some((k, v)) = part.trim().split_once('=') {
                    if k == "panggung_token" {
                        return Some(v.trim().to_string());
                    }
                }
            }
        }
    }
    // 3. POST /auth form body: token=<token>
    if let Some((_, body)) = req.split_once("\r\n\r\n") {
        for pair in body.trim().split('&') {
            if let Some((k, v)) = pair.split_once('=') {
                if k == "token" {
                    return Some(v.trim().to_string());
                }
            }
        }
    }
    None
}

pub fn start_web_remote_server(port: u16, lan_access: bool, auth_token: &str) -> bool {
    if auth_token.len() < 8 {
        return false;
    }
    stop_web_remote_server();
    thread::sleep(Duration::from_millis(120));

    let generation = WEB_GENERATION.fetch_add(1, Ordering::SeqCst) + 1;
    let bind_host = if lan_access { "0.0.0.0" } else { "127.0.0.1" };
    let addr = format!("{bind_host}:{port}");
    let listener = match TcpListener::bind(&addr) {
        Ok(l) => {
            let _ = l.set_nonblocking(true);
            info!("Built-in Web Remote UI listening on http://{addr} (auth=REQUIRED, lan={lan_access})");
            l
        }
        Err(e) => {
            error!("Failed to bind Web Remote on {addr}: {e}");
            return false;
        }
    };

    WEB_RUNNING.store(true, Ordering::SeqCst);
    let expected = auth_token.to_string();

    thread::Builder::new()
        .name("panggung-web-remote".into())
        .spawn(move || {
            while WEB_RUNNING.load(Ordering::SeqCst) && WEB_GENERATION.load(Ordering::SeqCst) == generation {
                match listener.accept() {
                    Ok((mut stream, _)) => {
                        let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
                        let mut buf = [0u8; 2048];
                        let n = stream.read(&mut buf).unwrap_or(0);
                        let req = String::from_utf8_lossy(&buf[..n]);
                        let is_post_auth = req.starts_with("POST /auth");
                        let provided = extract_http_token(&req).unwrap_or_default();
                        let ok = constant_time_eq(provided.as_bytes(), expected.as_bytes());
                        let resp = if is_post_auth && ok {
                            format!(
                                "HTTP/1.1 303 See Other\r\nLocation: /\r\nSet-Cookie: panggung_token={}; HttpOnly; SameSite=Strict; Path=/\r\nCache-Control: no-store\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                                expected
                            )
                        } else if ok {
                            let body = REMOTE_HTML.replace("__PANGGUNG_SESSION_TOKEN__", &expected);
                            format!(
                                "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                                body.len(),
                                body
                            )
                        } else {
                            format!(
                                "HTTP/1.1 401 Unauthorized\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                                UNAUTHORIZED_HTML.len(),
                                UNAUTHORIZED_HTML
                            )
                        };
                        let _ = stream.write_all(resp.as_bytes());
                    }
                    Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(80));
                    }
                    Err(_) => {
                        thread::sleep(Duration::from_millis(100));
                    }
                }
            }
            info!("Built-in Web Remote UI stopped");
        })
        .is_ok()
}
