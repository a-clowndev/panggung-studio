# Changelog / Catatan Rilis

All notable changes to **Panggung Studio** (`io.panggung.obs`) are documented here.
Format follows [Semantic Versioning](https://semver.org/) (`vMAJOR.MINOR.PATCH`).

---

## [v0.1.0] — Initial Standalone Release

Standalone live streaming & recording studio for Android 10+ · `arm64-v8a` · `io.panggung.obs`

### What's New (English)

- **Zero-Copy GLES 3.2 & 10-bit HDR Pipeline.** Cross-compiled `libobs 33.0.3` with custom `libobs-gles` (`AHardwareBuffer` → `EGLImageKHR` + `EGLSyncKHR`), `P010` / `GL_RGBA16F` 10-bit HDR PQ/HLG compositing, and hardware `MediaCodec` H.264 / HEVC / HEVC Main10 HDR / AV1 / VP9 encoding alongside `x264`, `x265 3.6`, and `libaom 3.10.0`.
- **Background-Safe Camera2 & Proportional Screen Capture.** Combined `CAMERA | MICROPHONE | MEDIA_PROJECTION` Foreground Service (`FGS=224`) so Front/Back cameras stay live in-game without Android 14–17 disconnects, paired with proportional aspect-preserving `VirtualDisplay` resize (`DisplayManager.DisplayListener`) and Per-App Internal Audio capture.
- **In-Game Floating Studio Hub & 1-Tap TikTok Portrait 9:16 Layouts.** Draggable `● LIVE` overlay pill with 1-tap `REC`, `MIC`, `CAM` (live drag, corner `◢` / `S`/`M`/`L` resize, `FRONT`/`BACK` flip, `↻ 90°` rotate, `⇄ H` / `⇅ V` mirror, `✓ DONE` lock), `LAYOUT` (`SPLIT 2`, `3-SLOT`, `GAME+PiP`, `FULL CAM` with 100% true aspect ratio), and `SCENE` switching.
- **Adaptive 3-Tier Jetpack Compose UI.** Seamless Phone Portrait, Compact 3-Column Landscape, and Tablet / Samsung DeX Full-Sidebar layouts, Orientation Lock (`AUTO`/`PORT`/`LAND`), interactive touch preview with magnetic snap, `obs-websocket v5` (`:4455`), Lua 5.4 scripting, USB UVC, NDI® BYOR, SpeexDSP noise suppression, and Thermal Governor.

### Yang Baru (Bahasa Indonesia)

- **Engine GLES 3.2 Zero-Copy & 10-bit HDR.** Ditenagai `libobs 33.0.3`, `libobs-gles` (`AHardwareBuffer`), komposisi 10-bit HDR PQ/HLG, serta encoder hardware `MediaCodec` (H.264, HEVC, HEVC HDR, AV1, VP9) dan software (`x264`, `x265`, `libaom`).
- **Kamera Aktif Saat Main Game & Screen Capture Proporsional.** Izin FGS gabungan (`FGS=224`) menjaga kamera depan/belakang tetap hidup di dalam game, lengkap dengan auto-resize layar proporsional bebas bar hitam & audio internal game.
- **Floating Studio Hub In-Game & Preset Layout TikTok 9:16.** Pil melayang `● LIVE` untuk kontrol `REC`, `MIC`, `CAM` (drag posisi, resize sudut `◢` / `S·M·L`, ganti `FRONT`/`BACK`, rotate `↻ 90°`, flip `⇄ H`/`⇅ V`), dan 1-tap `LAYOUT` (`SPLIT 2`, `3-SLOT`, `GAME+PiP`, `FULL CAM`) langsung dari dalam game.
- **UI Adaptif 3-Mode & Kontrol Lengkap.** Mendukung HP Portrait, Landscape 3-Kolom, dan Tablet/Samsung DeX, kunci orientasi, preview sentuh magnetik, `obs-websocket v5` (`:4455`), Lua 5.4, USB UVC, NDI®, SpeexDSP, dan Thermal Governor.
