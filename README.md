<div align="center">

  <p align="center">
    <img src="assets/logo.png" width="76" height="76" alt="Panggung Studio Logo" />
  </p>

  <h1>Panggung Studio</h1>

  <p>
    <b>Standalone Live Streaming &amp; Recording Studio for Android</b><br />
    <sub>Powered by libobs 33.0.3 · libobs-gles (GLES 3.2) · Hardware MediaCodec · libsrt v1.5.4 · Rust Core · Jetpack Compose</sub>
  </p>

  <p>
    <img src="https://img.shields.io/badge/Version-v0.2.0-0F766E?style=flat-square" alt="Version" />
    <img src="https://img.shields.io/badge/Platform-Android%2010%2B%20(API%2029%2B)-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Platform" />
    <img src="https://img.shields.io/badge/Arch-arm64--v8a%20(16KB)-0F766E?style=flat-square" alt="Architecture" />
    <img src="https://img.shields.io/badge/Engine-libobs%2033.0.3-302E31?style=flat-square&logo=obsstudio&logoColor=white" alt="Engine" />
    <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-14B8A6?style=flat-square&logo=jetpackcompose&logoColor=white" alt="UI" />
    <img src="https://img.shields.io/badge/License-GPL--2.0--or--later-10B981?style=flat-square" alt="License" />
  </p>

  <p>
    <a href="README.md"><b>English</b></a> &nbsp;•&nbsp;
    <a href="README_ID.md"><b>Bahasa Indonesia</b></a> &nbsp;•&nbsp;
    <a href="#user-guide"><b>Guide</b></a> &nbsp;•&nbsp;
    <a href="../../releases"><b>Download APK</b></a> &nbsp;•&nbsp;
    <a href="CHANGELOG.md"><b>Changelog</b></a> &nbsp;•&nbsp;
    <a href="BUILDING.md"><b>Build Guide</b></a>
  </p>

</div>

---

**Panggung Studio** (`io.panggung.obs`) is a 100% standalone mobile broadcast studio for Android powered by cross-compiled **libobs 33.0.3**, a custom **OpenGL ES 3.2 (`libobs-gles`)** compositor, **FFmpeg 8.1.3** + **libsrt v1.5.4**, a **Rust (`panggung-core`)** native engine + TLS 1.3 `obs-websocket v5` server, and an adaptive **Jetpack Compose** studio interface for Phone Portrait, Compact 3-Column Landscape, and Tablet / Samsung DeX.

It brings full scene compositing, multi-source capture, **RTMP / RTMPS / encrypted SRT / UDP MPEG-TS** live streaming, **TikTok Vertical 9:16** & **16:9 Landscape** broadcast, local **Hybrid MP4** recording, **10-bit HDR PQ/HLG**, and an **In-Game Floating Studio Hub** directly to Android — **zero PC required**.

## Interface Preview

<div align="center">

<p><b>Landscape &amp; Tablet / Samsung DeX</b> — 3-Column Studio Workspace</p>

<img src="assets/preview-landscape.png" alt="Panggung Studio Landscape and Tablet Workspace" width="94%" />

<br /><br />

<p><b>Phone Portrait &amp; TikTok 9:16 Live</b> — Studio UI + In-Game Floating Hub</p>

<img src="assets/social-preview.png" alt="Panggung Studio Phone Portrait and Live 9:16 Preview" width="94%" />

</div>

---

## Features

### Video, 10-bit HDR, SRT & Hardware Encoding

| Capability | Details |
| --- | --- |
| **MediaCodec Hardware Encoding** | H.264, HEVC (including Main10 10-bit HDR), AV1, and VP9 with Session Generation IDs, safe recreate-per-attempt fallback, and separated live bitrate adjustment (`obs_encoder_update`) for streaming vs. local recording. |
| **First-Class SRT & UDP MPEG-TS (`panggung_srt_output`)** | In-process hardware-encoded SRT/MPEG-TS using OBS's real MediaCodec H.264/HEVC + AAC encoders, dedicated I/O writer thread, bounded 128-packet FIFO with GOP-aware dropping, live ABR & congestion stats, AES passphrase encryption (redacted from logs), and strict codec gate (blocks unsupported AV1/VP9). |
| **Android 14–17 RTMPS Root CA Verification** | Loads `/apex/com.android.conscrypt/cacerts` and `/system/etc/security/cacerts` so `rtmps://` streams (YouTube, Facebook, Twitch) verify TLS certificates natively. |
| **GLES 3.2 Compositor** | Custom `libobs-gles` backend with `AHardwareBuffer` → `EGLImageKHR` + `EGLSyncKHR` + `samplerExternalOES` support. |
| **10-bit HDR Pipeline** | Rec. 2100 PQ & HLG compositing with `P010` / `GL_R16F` / `GL_RG16F` / `GL_RGBA16F` render targets and `android_mediacodec_hevc_hdr` hardware encoding (`primaries=bt2020`, `trc=smpte2084`). |
| **Bundled Software Encoders** | Cross-compiled `x264`, `x265 3.6`, and `libaom 3.10.0` (AV1) via **FFmpeg 8.1.3** (`libsrt v1.5.4`, `libxml2 v2.13.5`/DASH, `mbedTLS`). |
| **Dual Orientation Canvas** | Instant switching between **16:9 Landscape** (`1280×720` / `1920×1080`) and **9:16 Vertical Portrait** (`720×1280` / `1080×1920`) for TikTok Live, YouTube Shorts, and Instagram Live. |

### Capture, Audio & In-Game Broadcast

| Capability | Details |
| --- | --- |
| **Android 14–17 Screen + Internal Audio** | Single persistent `MediaProjection` `VirtualDisplay` with proportional aspect-preserving resize (`DisplayManager.DisplayListener` + `onCapturedContentResize` deduplication) and `AudioPlaybackCapture` per-app internal game audio. |
| **Background-Safe Camera2 + OIS/EIS** | 1-tap Front ↔ Back camera switching (`HandlerThread` + `AtomicLong` generation + `CountDownLatch` close sync), hardware **OIS** (preferred, zero crop) / **EIS** stabilization enabled from frame #1, and combined `CAMERA \| MICROPHONE \| MEDIA_PROJECTION` (`FGS=224`) so the camera stays live while playing games. |
| **In-Game Floating Studio Hub** | Auto-appears when entering a game (`● LIVE` / `● HUB` draggable pill). Expands to 1-tap **`REC`**, **`MIC`**, **`CAM`**, **`LAYOUT`**, **`SCENE`**, and **`STUDIO`**. |
| **In-Game Camera PiP Controls** | Tap **`CAM`** in-game to drag camera position, resize via corner handle (`◢`) or `S`/`M`/`L` presets, flip **`FRONT`/`BACK`**, rotate **`↻ 90°`**, mirror **`⇄ H`** / **`⇅ V`**, and lock with **`✓ DONE`** — preserving 100% true aspect ratio. |
| **1-Tap In-Game Layout Presets** | Tap **`LAYOUT`** in the Floating Hub to switch live between **`SPLIT 2`** (Top Camera + Full-Width Fit Game + Bottom TikTok Chat Safe Zone), **`3-SLOT`** (Camera + Game + Bottom Overlay/Chat), **`GAME+PiP`**, and **`FULL CAM`**. |
| **Browser, Media & GPU Shader Pack** | WebView Browser Source (`window.obsstudio`), FreeType2 text, Image/Media playback, **SpeexDSP 1.2.1** noise suppression + Stereo VU meters (`obs_volmeter_t`), GPU shader pack (`Composite Blur`, `Retro CRT`, `Stroke Glow`, `3D Perspective`, `Luma Threshold Key`, `Digital PTZ`, `Freeze Frame`, `GPU Ring Frame Delay`), plus experimental USB UVC / NDI® (BYOR) scaffolding. |

### Adaptive UI, Remote TLS 1.3, Resilience & Thermal Safety

| Capability | Details |
| --- | --- |
| **3-Tier Responsive Workspace** | Seamlessly adapts between **Phone Portrait**, **Compact 3-Column Landscape**, and **Tablet / Samsung DeX Full-Sidebar**, with persistent Orientation Lock (`AUTO` / `PORT` / `LAND`). |
| **Interactive Touch Preview** | Direct canvas drag, pinch-to-zoom, and center-pivot rotation with magnetic `0°/90°/180°/270°` snapping, alignment crosshair, Action/Title `SAFE` guides, `LOCK`, and `Fit` / `↻90` / `⇄` / `Prop` / `Flt` / `Xfrm` controls. |
| **Persistent LAN TLS 1.3 (`HTTPS :8080` & `WSS :4455`)** | Opt-in `obs-websocket v5` & Web Remote — **disabled by default**, localhost-only unless LAN is explicitly enabled, persisted ECDSA P-256 certificate with SHA-256 fingerprint & `Copy Cert FP`, mandatory **SHA-256 challenge-response + token auth**, instant revocation on `Rotate`, bounded Slowloris/flood worker pool, symlink/traversal path hardening, and remote Lua/plugin execution blocked. |
| **IRL Auto-Reconnect & Separated ABR** | Automatic reconnect across Wi-Fi ↔ 5G handoffs (`registerDefaultNetworkCallback`), separated **Network ABR vs Local Recording** bitrates (bad signal never degrades local MP4), and broadcast-scoped Wake Lock. |
| **Thermal Governor, Storage Guard & Crash Recovery** | 5-second `PowerManager.getThermalHeadroom` monitoring, automatic storage-full guard (`< 200 MB` start block / `< 100 MB` clean auto-stop), Keep-Screen-On, SAF shared-storage recording (`SafFdBridge`), atomic `.tmp` + `.bak` v2 scene collection auto-save with `.corrupt` quarantine, clean Back-button native teardown, and 16 KB ELF page-aligned native libraries. |

---

## User Guide

This guide explains **every single area and button** on screen so you can start streaming or recording right away.

### 1. Screen Layout Overview (Top to Bottom)

| Area | What It Is |
| --- | --- |
| **Top Bar** | App branding, live FPS & GPU render time, screen orientation lock (`AUTO`), and Remote Control (`QR`). |
| **Preview Canvas** | Live video preview of what viewers will see. Touch to drag, pinch to resize, or 2-finger twist to rotate the selected source. |
| **Selected Source Toolbar** | Quick actions for whichever source you tapped (`Fit`, `↻90`, `⇄`, `Prop`, `Flt`, `Xfrm`, `^`, `v`). |
| **Scenes Strip** | Switch between saved layouts/scenes (e.g., *Main Scene*, *Intro*, *Gameplay*, *BRB*) or tap **`+ Scene`** to create a new one. |
| **5 Main Tabs** | **`Sources`** (add camera/screen/overlays) · **`Mixer`** (audio volume & mute) · **`Studio`** (transitions & Studio Mode) · **`Output`** (stream URL/key, encoder, bitrate, recording) · **`Settings`** (resolution, orientation, floating hub, backups). |
| **Bottom Broadcast Bar** | Master controls: **`REC`** (record video), **`GO LIVE`** (start stream), **`REPLAY`** (save last seconds), **`STUDIO`** (Preview/Program split mode), plus status & dropped frames counter. |

---

### 2. Button-by-Button Dictionary

#### Top Bar & Preview Canvas
| Button / Indicator | What It Does |
| --- | --- |
| **`30.0 fps · 1.3ms`** | Live framerate and GPU render time per frame (lower ms = smoother). |
| **`AUTO` / `PORT` / `LAND`** | Locks the app screen orientation: Auto-rotate, Portrait only, or Landscape only. |
| **`QR`** | Opens **Remote Control & Web Remote** settings (enable `obs-websocket v5` / Web Remote, toggle LAN TLS 1.3 access, copy/rotate 16-char auth token, copy TLS SHA-256 fingerprint, or scan QR from another phone/PC). |
| **`SAFE`** *(on Preview)* | Shows dashed **Action Safe (90%)** and **Title Safe (80%)** broadcast guides so text/overlays don't get cut off by TikTok/YouTube UI. |
| **`LOCK` / `LOCKED`** *(on Preview)* | Locks touch gestures on the preview canvas so you don't accidentally move your camera or overlay while live. |

#### Selected Source Toolbar (Right Below Preview)
| Button | What It Does |
| --- | --- |
| **`Fit`** | Instantly fits the selected source cleanly to fill the canvas without stretching. |
| **`↻90`** | Rotates the selected source 90° clockwise (handy if a camera or overlay is sideways). |
| **`⇄`** | Mirrors/flips the selected source horizontally. |
| **`Prop`** | Opens source properties (e.g., change background color, edit text content, change Browser Overlay URL). |
| **`Flt`** | Opens **Filters** for the selected source (Chroma Key green screen, Color Grading, Composite Blur, Stroke Glow, Retro CRT, Noise Suppression, etc.). |
| **`Xfrm`** | Opens numeric **Transform** controls (exact X/Y position, scale, rotation, reset). |
| **`^` / `v`** | Moves the selected source **up** (in front) or **down** (behind) other layers in the scene. |

#### `Sources` Tab (Adding Video, Audio & Overlays)
| Button | What It Does |
| --- | --- |
| **`+ Cam Back`** | Adds or switches to your **Rear Camera** (with hardware OIS/EIS stabilization). |
| **`+ Cam Front`** | Adds or switches to your **Front / Selfie Camera** (1-tap race-free switching). |
| **`+ Screen`** | Captures your **Android Screen + Internal Game Audio** (Android 10–17). Tap → select *Share entire screen*. |
| **`+ Browser`** | Adds a transparent **WebView Browser Overlay** (for StreamElements/Streamlabs alerts, chat boxes, scoreboards). |
| **`+ Image`** | Picks an image/PNG logo/overlay from your gallery. |
| **`+ Media`** | Adds a local video or audio file (intro clip, BGM, stinger). |
| **`+ Text`** / **`+ Rich Text`** | Adds customizable text or styled gradient banners on screen. |
| **`+ Stats HUD`** | Adds a live on-screen overlay showing battery, temperature, and FPS. |
| **`OPEN` / `SHOW` / `MENU` / `DEL`** | On each source card: `OPEN` selects it, `SHOW`/`HIDE` toggles visibility, `MENU` opens Fit/Lock/Duplicate/Properties, `DEL` removes it. |

#### `Mixer` Tab (Audio Control)
| Control | What It Does |
| --- | --- |
| **Green/Yellow/Red Meter** | Live stereo audio level (VU meter). Aim for green/yellow; avoid constantly peaking red. |
| **Volume Slider** | Adjusts loudness of Microphone, Internal Game Audio, or Media sources independently. |
| **`100%` / `MUTED`** | 1-tap **Mute / Unmute** toggle for that audio source. |
| **`ADV`** | Advanced Audio Properties: Audio Sync Offset (ms), L/R Balance, Audio Tracks, and Monitoring. |

#### `Studio` Tab & Bottom **`STUDIO`** Button
| Control | What It Does |
| --- | --- |
| **Transitions (`Fade`, `Cut`, `Swipe`, `Slide`, `Wipe`)** | Sets the visual animation used when switching between scenes, plus duration (ms). |
| **`FADE TO BLACK`** | Smoothly fades your live output to black (and back). |
| **`STUDIO` / `STD ON`** | Enables **Studio Mode**: lets you edit a *Preview* scene privately while viewers still see the current *Program* scene. Tap the amber **`CUT → PROGRAM`** bar (or **`TRANSITION TO PROGRAM`**) to push your edits live. |

#### `Output` Tab & Bottom Broadcast Bar
| Button / Field | What It Does |
| --- | --- |
| **Encoder (`HW H.264`, `HW HEVC`, `HW HDR`, `SW x264`)** | Choose hardware encoding (**`HW H.264`** recommended for max compatibility on YouTube/TikTok/Twitch; **`HW HEVC`** for higher quality at lower bitrate; **`HW HDR`** for 10-bit PQ HDR). |
| **Bitrate (Kbps)** | Video quality/upload speed. Recommended: `2500–4500` Kbps for 720p30, `6000–8000` Kbps for 1080p60. |
| **Stream URL & Stream Key** | Enter your `rtmp://`, `rtmps://`, `srt://`, or `udp://` destination and stream key. |
| **`REC` / `START REC` / `PAUSE` / `RESUME` / `STOP REC`** | Records a local **Hybrid MP4** video into your device's `Movies` folder (safe even if app crashes; auto-stops if storage `< 100 MB`). |
| **`GO LIVE`** | Starts/stops live streaming to your configured server (turns red while live; auto-reconnects on Wi-Fi ↔ 5G drops). |
| **`REPLAY`** | Starts Replay Buffer so you can save highlight clips of the last few seconds on demand. |

#### `Settings` Tab
| Control | What It Does |
| --- | --- |
| **Canvas (`720p30`, `1080p30`, `1080p60`, `Vertical 720p`)** | Sets output resolution & aspect ratio. Use **`Vertical 720p`** (`720×1280`) for TikTok Live / Shorts / Reels. |
| **Screen Orientation Lock** | Choose `Auto`, `Lock Portrait`, or `Lock Landscape`. |
| **Floating Studio Hub Popup** | Automatically displays a draggable **`● LIVE` / `● HUB`** bubble over your game when you leave Panggung Studio. |
| **Keep Screen On** | Prevents your phone display from sleeping during a broadcast. |
| **All Settings (13 Pages)** | Full desktop-style OBS settings (General, Stream, Output, Audio, Video, Hotkeys, Advanced, etc.). |
| **Multiview** | Displays all your scenes in a grid for quick 1-tap switching. |
| **Save Col / Load Col** | Manually save or restore your Scene Collection (also auto-saved atomically on changes and exit). |

#### In-Game Floating Studio Hub (When Playing Games)
When you minimize Panggung Studio into a game, a small floating pill (**`● LIVE`** or **`● HUB`**) appears:
* **`REC`** — Start/stop local recording without leaving the game.
* **`MIC`** — Quick mute/unmute microphone.
* **`CAM`** — Opens live camera PiP overlay controls: drag to move, drag corner **`◢`** or tap **`S`/`M`/`L`** to resize, **`FRONT`/`BACK`** to flip camera, **`↻ 90°`** to rotate, **`⇄ H` / `⇅ V`** to mirror, **`✓ DONE`** to lock.
* **`LAYOUT`** — Cycles 1-tap TikTok/gaming layouts: **`SPLIT 2`** (Top Cam + Full-Width Game + Bottom Chat Safe Zone) → **`3-SLOT`** → **`GAME+PiP`** → **`FULL CAM`**.
* **`SCENE`** — Switch scenes on the fly.
* **`STUDIO`** — Return to Panggung Studio.

---

### 3. Step-by-Step Recipes

#### A. Live Stream to YouTube / Twitch / Facebook (RTMP / RTMPS)
1. Open **Sources** → tap **`+ Cam Back`** or **`+ Cam Front`** (and optionally **`+ Browser`** / **`+ Image`** for overlays).
2. Open **Output** → select **`HW H.264`**, set Bitrate (`4500` Kbps for 720p30), paste your **RTMPS/RTMP Server URL** and **Stream Key**.
3. Tap **`GO LIVE`** at the bottom bar!

#### B. TikTok Live Vertical (9:16) + Mobile Gaming with Facecam
1. Open **Settings** → tap **`Vertical 720p`** (`720×1280`) and make sure **Floating Studio Hub Popup** is ON.
2. Open **Sources** → tap **`+ Cam Front`** and **`+ Screen`** (select *Share entire screen*).
3. Open **Output** → enter your TikTok RTMP URL + Stream Key → tap **`GO LIVE`**.
4. Switch into your game → tap the floating **`● LIVE`** bubble → tap **`LAYOUT`** → choose **`SPLIT 2`** (or **`GAME+PiP`**).

#### C. IRL Outdoor Streaming with Encrypted SRT (Anti-Disconnect)
1. Open **Output** → select **`HW H.264`** or **`HW HEVC`**.
2. In **Server URL**, enter your SRT ingest URL, e.g.:
   `srt://your-server.com:9000?mode=caller&latency=200000&passphrase=YourSecretPass&pbkeylen=16`
3. Tap **`GO LIVE`**. Network ABR automatically adapts stream bitrate on weak 4G/5G without degrading local recording, and auto-reconnects across Wi-Fi ↔ cellular handoffs.

#### D. Control from Another Phone or PC (Web Remote + `obs-websocket v5` over TLS 1.3)
1. Tap **`QR`** in the top-right bar.
2. Toggle **Enable Remote Control** ON (and **Allow LAN Access (TLS 1.3)** if controlling from another device on the same Wi-Fi).
3. Scan the QR code or open `https://<device-ip>:8080` in a browser using the 16-character token (tap **`Copy Cert FP`** to verify the persistent TLS 1.3 SHA-256 fingerprint, or **`Rotate`** anytime to immediately revoke all connected clients).

---

## Verified Hardware Results (`v0.2.0` on `SM-S721B` Galaxy S24 FE, Android 17)

| Test Suite | Result | Verified Evidence |
| --- | --- | --- |
| **Core & HDR E2E (`tests/e2e_device_test.py`)** | ✅ **64 / 64 PASSED** | Full `obs-websocket v5`, 10-bit HDR PQ HEVC (`bt2020`/`smpte2084`), Replay Buffer, Filters, Multistream |
| **Barbar Chaos & Security (`tests/barbar_chaos_security_test.py`)** | ✅ **12 / 12 PASSED** | 22/22 `.so` 16KB ELF + `apksigner OK`, token rotation kick, 20 Slowloris + 30/30 HTTPS burst, symlink/traversal blocked, WS fuzz, SRT mid-stream `SIGKILL` hammer, concurrent SRT+Rec dual decode, **A/V sync drift `19.0ms`**, upgrade persistence, 0 leaks/crashes |
| **Hardened SRT / RTMPS (`tests/srt_reconnect_e2e_test.py`)** | ✅ **ALL PASSED** | Encrypted H.264 & HEVC SRT + full decode, AV1/VP9 rejected, UI `GO LIVE` → remote `StopStream` zero zombie reconnect, RTMPS valid/invalid CA verify, 0 passphrase leak |
| **UI Tap & Camera E2E (`tests/ui_tap_e2e_test.py`)** | ✅ **12 / 12 PASSED** | All 5 tabs, modals, 1-tap Front↔Back switches, `neon_green_rows=0`, 0 crashes |
| **1080p60 Stress & Soak (`tests/stress_test_all.py`)** | ✅ **7 / 7 PASSED** | 8 sources + 5 filters (`56.1fps`), 30s 1080p60 HEVC soak (`min_fps=60.0`, `delta_rss=-24.4 MB`, `33.7°C`) |
| **Strict Golden SSIM & Soak (`tests/golden_ssim_test.py`, `tests/soak_test.py`)** | ✅ **PASSED** | `SSIM=0.9995` (strict baseline required), `min_fps=30.0`, `peak_growth=+0.0 MB`, `crashes=0` |

## Requirements & Project Structure

* **Requirements**: `arm64-v8a`, Android 10+ (API 29+), Target SDK 35 (verified on Android 17), Android System WebView (for Browser Source).
* **Permissions**: Camera, Microphone, MediaProjection (Screen Capture), Notifications, and `SYSTEM_ALERT_WINDOW` (for In-Game Floating Studio Hub).

```text
panggung-studio/
├── app/                    # Android app (Jetpack Compose UI, Capture, Services, JNI bridge)
├── crates/panggung-core/   # Rust native core (libobs FFI, JNI, TLS 1.3 obs-websocket v5 & Web Remote)
├── native/
│   ├── libobs-gles/        # Custom OpenGL ES 3.2 graphics backend
│   └── android-plugins/    # MediaCodec encoders, panggung_srt_output, Android sources & 3P shader pack
├── plugin-sdk/             # Third-party Android OBS plugin SDK
├── tools/                  # Cross-compilation scripts (FFmpeg, x264, x265, libaom, libsrt, libobs)
├── tests/                  # Automated ADB + WebSocket E2E, chaos/security, UI tap, SSIM & soak suites
├── README.md               # Documentation & User Guide (English)
├── README_ID.md            # Dokumentasi & Guide (Bahasa Indonesia)
├── BUILDING.md             # Build & Test Guide (EN / ID)
└── CHANGELOG.md            # Release notes (EN / ID)
```

## Frequently Asked Questions (FAQ)

### Is Panggung Studio a free open-source OBS alternative for Android?
Yes. Panggung Studio is licensed under **GPL-2.0-or-later** and runs the real cross-compiled **libobs 33.0.3** engine natively on Android (`arm64-v8a`) — giving you scenes, multi-source compositing, hardware MediaCodec encoding, filters, and `obs-websocket v5` without a PC or subscription paywall.

### Can I live stream mobile games in TikTok Vertical 9:16 with facecam?
Yes. Switch the canvas to **`Vertical 720p`** (`720×1280`) or **`1080×1920`**, add **`+ Cam Front`** / **`+ Cam Back`** and **`+ Screen`**, tap **`GO LIVE`**, and use the in-game **Floating Studio Hub → `LAYOUT`** button to switch between **`SPLIT 2`** (Top Camera + Full-Width Game + Bottom TikTok Chat Safe Zone), **`3-SLOT`**, **`GAME+PiP`**, and **`FULL CAM`** with zero aspect-ratio distortion.

### Does the camera stay active when switching into a game on Android 14–17?
Yes. Panggung Studio combines `CAMERA | MICROPHONE | MEDIA_PROJECTION` (`FGS=224`) in a single Foreground Service so Android 14–17 never disconnects your Front or Back camera when you switch into fullscreen games like Mobile Legends.

### Which streaming protocols and encoders are supported?
RTMP, RTMPS (with Android 14–17 Root CA verification), Multistream RTMP, encrypted SRT (`libsrt` via `panggung_srt_output`), UDP MPEG-TS, HLS, DASH, RTSP, and NDI® (BYOR). Video encoders include hardware **MediaCodec** (`H.264`, `HEVC`, `HEVC 10-bit HDR PQ/HLG`, `AV1`, `VP9`) and bundled software encoders (`x264`, `x265 3.6`, `libaom 3.10.0`).

## Documentation & License

* [Bahasa Indonesia Documentation & Guide (`README_ID.md`)](README_ID.md)
* [Build & Test Guide (`BUILDING.md`)](BUILDING.md)
* [Release Changelog (`CHANGELOG.md`)](CHANGELOG.md)
* [AI / LLM Project Index (`llms.txt`)](llms.txt)

Licensed under **GPL-2.0-or-later** (aligned with upstream `libobs` / OBS Studio).
