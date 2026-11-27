# Feature Inventory — Panggung Studio (`v0.3.0-alpha.1`)

Total top-level features mapped: **36** (covering 82 JNI externals, 63 WS handlers, 16 C plugin registrations, 12 permissions, 2 services).

| Feature ID | Domain | Capability | Source Reference | Entry Point | Support |
| --- | --- | --- | --- | --- | --- |
| `FEAT-001` | Scene & Source | Scene CRUD (Create/Rename/Duplicate/Delete/Switch) | `crates/panggung-core/src/lib.rs:1256, studio_ops.rs:94` | UI + WS | Android 10+ |
| `FEAT-002` | Scene & Source | Source CRUD, Ordering, Visibility & Lock | `crates/panggung-core/src/studio_ops.rs:13` | UI + WS | Android 10+ |
| `FEAT-003` | Scene & Source | Transform, Fit, Center, Reset, Rotate, Mirror | `crates/panggung-core/src/studio_ops.rs:47` | UI + WS | Android 10+ |
| `FEAT-004` | Scene & Source | Direct Corner Resize, Edge Crop & Magnetic Snapping | `app/src/main/java/io/panggung/obs/ui/components/InteractivePreviewCanvas.kt:190` | UI Gesture + JNI | Android 10+ |
| `FEAT-005` | Scene & Source | Studio Mode (Preview/Program, T-Bar, Transitions, FTB) | `crates/panggung-core/src/studio_ops.rs:158` | UI + WS | Android 10+ |
| `FEAT-006` | Scene & Source | Scene Collection v2 Atomic Persistence (.tmp/.bak/.corrupt + Crop) | `crates/panggung-core/src/lib.rs:797` | Auto-save + JNI | Android 10+ |
| `FEAT-007` | Camera | Camera2 AHardwareBuffer Zero-Copy (EGLImageKHR + EGLSyncKHR) | `app/src/main/java/io/panggung/obs/capture/Camera2Capture.kt:340, native/android-plugins/android-sources.c:210` | UI + JNI | Android 10+ |
| `FEAT-008` | Camera | Camera2 CPU I420 Fallback (Unsupported AHB) | `app/src/main/java/io/panggung/obs/capture/Camera2Capture.kt:530, native/android-plugins/android-sources.c:695` | Fallback | Android 10+ |
| `FEAT-009` | Camera | Multi-Lens Discovery & Switching (0.6x UW / 1x Wide / Front) | `app/src/main/java/io/panggung/obs/capture/Camera2Capture.kt:100` | Pro Cam UI | Android 10+ |
| `FEAT-010` | Camera | Concurrent Dual-Camera (Back + Front PiP via concurrentCameraIds) | `app/src/main/java/io/panggung/obs/capture/Camera2Capture.kt:147, MainActivity.kt:1570` | + Dual Cam UI | Android 11+ HAL |
| `FEAT-011` | Camera | Pro Camera Controls (ISO, Shutter, EV, AE/AWB Lock, MF, Kelvin WB, Zoom, Torch, OIS/EIS) | `app/src/main/java/io/panggung/obs/ui/modals/ProCameraSheet.kt:36, Camera2Capture.kt:215` | Pro Cam UI | Android 10+ |
| `FEAT-012` | Capture | Screen Capture Zero-Copy AHB + IllegalArgumentException CPU RGBA Fallback | `app/src/main/java/io/panggung/obs/capture/ScreenCapture.kt:167, native/android-plugins/android-sources.c:670` | + Screen UI | Android 10–17 |
| `FEAT-013` | Capture | Internal Game Audio Capture (AudioPlaybackCapture + Per-App UID) | `app/src/main/java/io/panggung/obs/capture/ScreenCapture.kt:220` | + Screen UI | Android 10–17 |
| `FEAT-014` | Capture | Microphone Capture (AudioInputCapture) + Stereo VU + SpeexDSP | `app/src/main/java/io/panggung/obs/capture/AudioInputCapture.kt:1` | Mixer Tab | Android 10+ |
| `FEAT-015` | Capture | Browser Overlay (WebView + window.obsstudio) | `app/src/main/java/io/panggung/obs/capture/WebViewCapture.kt:1` | + Browser UI | Android 10+ |
| `FEAT-016` | Capture | Image, Media (FFmpeg), FreeType2 Text, Rich Gradient Text, Stats HUD | `app/src/main/java/io/panggung/obs/MainActivity.kt:856` | Sources Tab | Android 10+ |
| `FEAT-017` | Capture | USB UVC External Capture Card (Experimental) | `app/src/main/java/io/panggung/obs/capture/UsbUvcCapture.kt:1` | USB OTG | Android 10+ OTG |
| `FEAT-018` | Capture | NDI® (BYOR) Discovery & Capture (Experimental) | `app/src/main/java/io/panggung/obs/capture/NdiManager.kt:1` | NDI SDK | BYOR NDI |
| `FEAT-019` | Encoding | GPU-to-MediaCodec Surface Encoding (COLOR_FormatSurface + Zero-Readback libobs) | `native/android-plugins/android-mediacodec.c:130, native/obs-studio/libobs/obs-video.c:409` | Output/WS | Android 10+ |
| `FEAT-020` | Encoding | MediaCodec H.264 & HEVC Hardware Encoders (OBS Video Clock PTS) | `native/android-plugins/android-mediacodec.c:240` | Output/WS | Android 10+ |
| `FEAT-021` | Encoding | MediaCodec HEVC Main10 10-Bit HDR PQ/HLG (P010, bt2020/smpte2084) | `native/android-plugins/android-mediacodec.c:365` | Output/WS | SoC HDR10 |
| `FEAT-022` | Encoding | MediaCodec AV1 & VP9 Hardware Encoders | `native/android-plugins/android-mediacodec.c:665` | Output/WS | SoC AV1/VP9 |
| `FEAT-023` | Encoding | Software Encoders (obs_x264 / x265 / libaom) | `tools/build-libobs.sh:88` | Output/WS | arm64-v8a |
| `FEAT-024` | Broadcast | RTMP & RTMPS Streaming (Android 14-17 Conscrypt/System Root CA) | `native/obs-android-patches/obs-33.0.3-android.patch` | Output/WS | Android 10–17 |
| `FEAT-025` | Broadcast | First-Class Hardware SRT & UDP MPEG-TS (panggung_srt_output, AES, GOP-drop, Codec Gate) | `native/android-plugins/panggung-3p-pack.c:950` | Output/WS | Android 10+ |
| `FEAT-026` | Broadcast | Multistream RTMP & Custom Outputs (with Path Gate) | `crates/panggung-core/src/ws_server.rs:479` | WS/Output | Android 10+ |
| `FEAT-027` | Broadcast | Hybrid MP4 Local Recording, Pause/Resume, Remux & Replay Buffer | `crates/panggung-core/src/lib.rs:1284, 1360` | UI + WS | Android 10+ |
| `FEAT-028` | Broadcast | Concurrent Streaming + Local Recording (Independent Encoders & Separated ABR) | `crates/panggung-core/src/lib.rs:2121, ThermalGovernor.kt:85` | UI + Service | Android 10+ |
| `FEAT-029` | Resilience | Auto-Reconnect + Wi-Fi<->5G Network Handoff + Broadcast Wake Lock | `app/src/main/java/io/panggung/obs/service/PanggungService.kt:1` | Service | Android 10+ |
| `FEAT-030` | Resilience | Thermal Governor (5s headroom) + Storage-Full Guard (<200MB block / <100MB stop) | `app/src/main/java/io/panggung/obs/androidplus/ThermalGovernor.kt:1, MainActivity.kt:340` | Background | Android 11+ |
| `FEAT-031` | Security | Persistent LAN TLS 1.3 (HTTPS :8080 & WSS :4455, SHA-256 FP, Token Rotation Kick) | `crates/panggung-core/src/tls_config.rs:1, ws_server.rs:189, web_remote.rs:1` | QR Dialog + LAN | Android 10+ |
| `FEAT-032` | Security | Web Remote Slowloris/Flood Bounds, Symlink/Traversal Block, Zero Credential Leak | `crates/panggung-core/src/web_remote.rs:1, ws_server.rs:660` | LAN/Localhost | Android 10+ |
| `FEAT-033` | Overlay | In-Game Floating Studio Hub (REC, MIC, CAM PiP, LAYOUT, SCENE, STUDIO) | `app/src/main/java/io/panggung/obs/service/FloatingBubbleService.kt:1` | Overlay Service | SYSTEM_ALERT_WINDOW |
| `FEAT-034` | Remote | obs-websocket v5 API (63 Request Handlers) | `crates/panggung-core/src/ws_server.rs:276` | WS/WSS :4455 | Android 10+ |
| `FEAT-035` | Filters | Native 3P GPU/DSP Filter Pack (Blur, CRT, Glow, 3D, LumaKey, PTZ, Freeze, Delay, DSP) | `native/android-plugins/panggung-3p-pack.c:1` | Flt UI + WS | Android 10+ |
| `FEAT-036` | Platform | 16 KB ELF Page Alignment (22/22 arm64-v8a .so) & Clean Native Teardown | `tools/build-libobs.sh:96, crates/panggung-core/src/lib.rs:380` | APK + Back Exit | Android 15–17 |
