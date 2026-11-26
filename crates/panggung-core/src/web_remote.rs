use std::io::{Read, Write};
use std::net::TcpListener;
use std::thread;

use log::{error, info};

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
const wsUrl = `ws://${location.hostname}:4455`;
let ws, rid = 0, recActive = false;
function req(type, data) {
  if (!ws || ws.readyState !== 1) return;
  ws.send(JSON.stringify({op:6, d:{requestType:type, requestId:'w'+(++rid), requestData:data||{}}}));
}
function toggleRec() { req(recActive ? 'StopRecord' : 'StartRecord'); }
function connect() {
  ws = new WebSocket(wsUrl);
  ws.onopen = () => {
    document.getElementById('conn').textContent = 'Connected :4455';
    ws.send(JSON.stringify({op:1, d:{rpcVersion:1}}));
    setTimeout(poll, 300);
  };
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.op !== 7) return;
    const t = m.d.requestType, r = m.d.responseData || {};
    if (t === 'GetStats') {
      document.getElementById('stats').textContent = `FPS: ${(r.activeFps||0).toFixed(1)} | Render: ${(r.averageFrameRenderTime||0).toFixed(2)} ms | Skipped: ${r.renderSkippedFrames||0}`;
    } else if (t === 'GetSceneList') {
      const el = document.getElementById('scenes'); el.innerHTML = '';
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
      const el = document.getElementById('mixer'); el.innerHTML = '';
      (r.inputs||[]).forEach(i => {
        const d = document.createElement('div');
        d.style.marginBottom = '6px';
        d.innerHTML = `<span>${i.inputName}</span> <button class="sec" onclick="req('SetInputMute',{inputName:'${i.inputName}',inputMuted:true})">Mute</button> <button class="sec" onclick="req('SetInputMute',{inputName:'${i.inputName}',inputMuted:false})">Unmute</button>`;
        el.appendChild(d);
      });
    }
  };
  ws.onclose = () => { document.getElementById('conn').textContent = 'Reconnecting...'; setTimeout(connect, 2000); };
}
function poll() {
  if (ws && ws.readyState === 1) {
    req('GetStats'); req('GetSceneList'); req('GetRecordStatus'); req('GetInputList');
  }
  setTimeout(poll, 1500);
}
connect();
</script>
</body>
</html>
"#;

pub fn spawn_web_remote_server(port: u16) {
    thread::Builder::new()
        .name("panggung-web-remote".into())
        .spawn(move || {
            let addr = format!("0.0.0.0:{port}");
            let listener = match TcpListener::bind(&addr) {
                Ok(l) => {
                    info!("Built-in Web Remote UI listening on http://{addr}");
                    l
                }
                Err(e) => {
                    error!("Failed to bind Web Remote on {addr}: {e}");
                    return;
                }
            };
            for mut stream in listener.incoming().flatten() {
                let mut buf = [0u8; 1024];
                let _ = stream.read(&mut buf);
                let resp = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    REMOTE_HTML.len(),
                    REMOTE_HTML
                );
                let _ = stream.write_all(resp.as_bytes());
            }
        })
        .ok();
}
