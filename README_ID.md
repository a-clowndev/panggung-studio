<div align="center">

  <p align="center">
    <img src="assets/logo.png" width="76" height="76" alt="Logo Panggung Studio" />
  </p>

  <h1>Panggung Studio</h1>

  <p>
    <b>Studio Live Streaming &amp; Screen Recording Standalone untuk Android</b><br />
    <sub>Ditenagai libobs 33.0.3 · libobs-gles (GLES 3.2 Zero-Copy) · Akselerasi Hardware MediaCodec · Rust Core · Jetpack Compose</sub>
  </p>

  <p>
    <img src="https://img.shields.io/badge/Versi-v0.1.0-0F766E?style=flat-square" alt="Versi" />
    <img src="https://img.shields.io/badge/Platform-Android%2010%2B%20(API%2029%2B)-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Platform" />
    <img src="https://img.shields.io/badge/Arsitektur-arm64--v8a-0F766E?style=flat-square" alt="Arsitektur" />
    <img src="https://img.shields.io/badge/Engine-libobs%2033.0.3-302E31?style=flat-square&logo=obsstudio&logoColor=white" alt="Engine" />
    <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-14B8A6?style=flat-square&logo=jetpackcompose&logoColor=white" alt="UI" />
    <img src="https://img.shields.io/badge/Lisensi-GPL--2.0--or--later-10B981?style=flat-square" alt="Lisensi" />
  </p>

  <p>
    <a href="README.md"><b>English</b></a> &nbsp;•&nbsp;
    <a href="README_ID.md"><b>Bahasa Indonesia</b></a> &nbsp;•&nbsp;
    <a href="../../releases"><b>Download APK</b></a> &nbsp;•&nbsp;
    <a href="CHANGELOG.md"><b>Catatan Rilis</b></a> &nbsp;•&nbsp;
    <a href="BUILDING.md"><b>Panduan Build</b></a>
  </p>

</div>

---

**Panggung Studio** (`io.panggung.obs`) adalah studio broadcast Android **100% standalone (tanpa PC)** yang ditenagai **libobs 33.0.3** hasil cross-compile, backend grafis **OpenGL ES 3.2 (`libobs-gles`)** zero-copy, **FFmpeg 8.1.3**, core **Rust (`panggung-core`)** + server `obs-websocket v5`, dan antarmuka **Jetpack Compose** adaptif untuk HP Portrait, Compact 3-Kolom Landscape, serta Tablet / Samsung DeX.

Aplikasi ini menghadirkan komposisi multi-scene penuh, perekaman **Hybrid MP4** lokal, **10-bit HDR PQ/HLG**, live streaming **Landscape 16:9** maupun **Portrait Vertikal 9:16 (TikTok Live / Shorts / Reels)**, serta **Floating Studio Hub di dalam game** lengkap dengan kontrol drag, resize, rotate, flip kamera PiP, dan preset layout 1-tap.

## Pratinjau Antarmuka

<div align="center">

<p><b>Landscape &amp; Tablet / Samsung DeX</b> — Workspace Studio 3-Kolom</p>

<img src="assets/preview-landscape.png" alt="Panggung Studio Mode Landscape dan Tablet" width="94%" />

<br /><br />

<p><b>HP Portrait &amp; Live TikTok 9:16</b> — Studio UI + Floating Hub In-Game</p>

<img src="assets/social-preview.png" alt="Panggung Studio Mode Portrait dan Live 9:16" width="94%" />

</div>

## Fitur Utama

### Video, 10-bit HDR & Hardware Encoding

| Kemampuan | Detail |
| --- | --- |
| **MediaCodec Hardware Encoding** | Mendukung H.264, HEVC (termasuk Main10 10-bit HDR), AV1, dan VP9 dengan Session Generation ID, fallback aman per-percobaan, serta pengaturan bitrate live (`obs_encoder_update`) tanpa memutus stream. |
| **Compositor GLES 3.2** | Backend kustom `libobs-gles` dengan dukungan `AHardwareBuffer` → `EGLImageKHR` + `EGLSyncKHR` + `samplerExternalOES` (pipeline Camera2 & WebView saat ini memakai buffer upload I420/RGBA). |
| **Pipeline 10-bit HDR** | Komposisi Rec. 2100 PQ & HLG menggunakan `P010` / `GL_R16F` / `GL_RG16F` / `GL_RGBA16F` serta encoder hardware `android_mediacodec_hevc_hdr` (`primaries=bt2020`, `trc=smpte2084`). |
| **Encoder Software Terintegrasi** | Dilengkapi `x264`, `x265 3.6`, dan `libaom 3.10.0` (AV1) melalui **FFmpeg 8.1.3** (`libsrt v1.5.4`, `libxml2 v2.13.5`/DASH, `mbedTLS`). |
| **Canvas Landscape & Portrait 9:16** | Mendukung penuh **Landscape 16:9** (`1280×720` / `1920×1080`) dan **Portrait Vertikal 9:16** (`720×1280` / `1080×1920`) khusus TikTok Live, YouTube Shorts, dan Instagram Live. |

### Capture, Audio & Broadcast di Dalam Game

| Kemampuan | Detail |
| --- | --- |
| **Screen Capture + Audio Internal (Android 14–17)** | `MediaProjection` `VirtualDisplay` persisten dengan resize proporsional otomatis mengikuti rotasi layar (`DisplayManager.DisplayListener`, bebas bar hitam & bebas gepeng) serta tangkapan audio internal game (`AudioPlaybackCapture`). |
| **Camera2 Aktif di Background (Depan & Belakang)** | Ganti kamera Depan ↔ Belakang 1-tap bebas race condition (`HandlerThread` + `AtomicLong` + `CountDownLatch`) dengan kombinasi izin FGS **`CAMERA \| MICROPHONE \| MEDIA_PROJECTION` (`FGS=224`)** agar kamera tidak mati saat masuk game. |
| **Floating Studio Hub In-Game** | Muncul otomatis saat keluar studio / masuk game (pil **`● LIVE`** / **`● HUB`** yang bisa digeser). Tap untuk membuka tombol cepat **`REC`**, **`MIC`**, **`CAM`**, **`LAYOUT`**, **`SCENE`**, dan **`STUDIO`**. |
| **Kontrol Kamera PiP Langsung di Game** | Tap **`CAM`** di Floating Hub untuk geser posisi kamera (drag), ubah ukuran lewat sudut **`◢`** atau preset **`S`/`M`/`L`**, ganti **`FRONT`/`BACK`**, putar **`↻ 90°`**, balik **`⇄ H`** / **`⇅ V`**, lalu kunci dengan **`✓ DONE`** (rasio asli tetap utuh 100%). |
| **Preset Layout 1-Tap (Cocok TikTok Gaming)** | Tap **`LAYOUT`** di Floating Hub untuk berpindah cepat antar **`SPLIT 2`** (Atas Kamera + Tengah Game Fit Full-Width + Bawah Ruang Chat/Gift TikTok), **`3-SLOT`** (Kamera + Game + Slot Overlay Bawah), **`GAME+PiP`**, dan **`FULL CAM`**. |
| **Browser, Media & I/O Eksperimental** | WebView Browser Source (`window.obsstudio`), teks FreeType2, pemutar gambar/media, peredam bising **SpeexDSP 1.2.1** + Stereo VU Meter (`obs_volmeter_t`), paket filter GPU (`Composite Blur`, `Retro CRT`, `Stroke Glow`, `3D Perspective`, `Luma Threshold Key`, `Digital PTZ`, `Freeze Frame`, `GPU Ring Frame Delay`), serta fondasi eksperimental USB UVC / NDI® (BYOR). |

### UI Adaptif, Remote Control & Perlindungan Thermal

| Kemampuan | Detail |
| --- | --- |
| **Layout Responsif 3-Mode** | Otomatis menyesuaikan tampilan **HP Portrait**, **Landscape 3-Kolom Ringkas**, dan **Tablet / Samsung DeX Full-Sidebar**, lengkap dengan kunci orientasi **`AUTO` / `PORT` / `LAND`**. |
| **Preview Sentuh Interaktif** | Drag posisi, pinch-to-zoom, dan rotasi poros tengah dengan magnet snap **`0°/90°/180°/270°`**, crosshair tengah, serta tombol `Fit`, `Center`, `Flip`, dan `Reset`. |
| **Remote API Aman & Persistensi Penuh** | Server `obs-websocket v5` (`:4455`) & Web Remote (`:8080`) **nonaktif secara default**, hanya `localhost` kecuali LAN diaktifkan eksplisit, wajib **autentikasi SHA-256 challenge-response + token**, eksekusi Lua/plugin lewat remote diblokir. Persistensi koleksi scene v2 penuh (scene, source, transform, visibility, lock, audio, filter) otomatis tersimpan. |
| **Thermal Governor & Crash Recovery** | Pemantauan suhu/headroom tiap 5 detik dengan penyesuaian bitrate otomatis saat panas, Keep-Screen-On, penyimpanan SAF (`SafFdBridge`), serta banner pemulihan crash. |

## Persyaratan

* **Arsitektur & OS**: `arm64-v8a`, Android 10+ (API 29+), Target SDK 35 (teruji di Android 17).
* **Izin Aplikasi**: Kamera, Mikrofon, MediaProjection (Tangkapan Layar), dan `SYSTEM_ALERT_WINDOW` (Tampil di atas aplikasi lain untuk Floating Studio Hub).
* **Dependensi**: Android System WebView (untuk Browser Source). Dukungan encoder hardware HEVC 10-bit HDR dan AV1 bergantung pada chipset perangkat.

## Panduan Mulai Cepat (Quick Start)

1. **Install Aplikasi**: Build dari source mengikuti [BUILDING.md](BUILDING.md) lalu install via ADB:
   ```sh
   adb install --no-streaming -r app/build/outputs/apk/release/app-release.apk
   ```
2. **Tambah Sumber (Sources)**:
   * Tap **`+ Cam Front`** atau **`+ Cam Back`** untuk menambahkan kamera depan/belakang.
   * Tap **`+ Screen`** → pilih **Share entire screen** → **Share screen** untuk menangkap layar game + audio internal.
3. **Mulai Live (Landscape 16:9 atau Portrait TikTok 9:16)**:
   * Masukkan RTMP Server URL + Stream Key di tab **Output** (atau kendalikan via `obs-websocket v5` di port `:4455`).
   * Untuk **Live TikTok Vertikal**, ubah resolusi canvas ke **`720×1280`** (atau **`1080×1920`**) di **Settings**.
   * Tap **`GO LIVE`** lalu buka game favorit Anda.
4. **Atur Semua Langsung dari Dalam Game (Floating Studio Hub)**:
   * Tap pil **`● LIVE`** → tap **`LAYOUT`** untuk memilih **`SPLIT 2`** / **`3-SLOT`** / **`GAME+PiP`** / **`FULL CAM`**.
   * Tap **`CAM`** untuk drag posisi, resize (`◢` atau `S`/`M`/`L`), ganti **`FRONT`/`BACK`**, putar **`↻ 90°`**, atau mirror **`⇄ H`** / **`⇅ V`**, lalu tap **`✓ DONE`** untuk menyembunyikan frame panduan.

## Hasil Verifikasi Hardware Asli (`SM-S721B` Galaxy S24 FE, Android 17)

| Suite Pengujian | Hasil | Catatan |
| --- | --- | --- |
| **Core & HDR E2E (`tests/e2e_device_test.py`)** | **65 / 65 LULUS** | Termasuk 10-bit HDR PQ HEVC (`bt2020` / `smpte2084`), Lua 5.4, Replay Buffer, Filter, Multistream |
| **UI Tap & Kamera E2E (`tests/ui_tap_e2e_test.py`)** | **12 / 12 LULUS** | Semua tab, modal, ganti kamera Depan/Belakang 1-tap, 10× rotasi cepat (`0` crash) |
| **Soak Test 1080p60 HEVC (`tests/stress_test_all.py`)** | **60.0 FPS Stabil** | Soak 30 detik, `skipped=0`, `delta_rss = -23.3 MB` (tanpa memory leak), `0` crash/ANR |
| **Live TikTok Portrait 9:16 + Mobile Legends** | **Terverifikasi Live** | `ffprobe`: `h264 720×1280` + `aac`, `FGS=224` (`0` kamera putus), `SPLIT 2` fit penuh tanpa gepeng |

## Struktur Proyek

```text
panggung-studio/
├── app/                    # Aplikasi Android (UI Jetpack Compose, Capture, Service, JNI)
├── crates/panggung-core/   # Core Rust native (libobs FFI, JNI, obs-websocket v5, Lua 5.4)
├── native/
│   ├── libobs-gles/        # Backend grafis OpenGL ES 3.2 + AHardwareBuffer zero-copy
│   └── android-plugins/    # Encoder MediaCodec, source Camera/Screen/Audio/WebView Android
├── plugin-sdk/             # SDK plugin OBS pihak ketiga untuk Android
├── tools/                  # Skrip cross-compile (FFmpeg, x264, x265, libaom, libsrt, libobs)
├── tests/                  # Suite test otomatis ADB + WebSocket (E2E, UI tap, stress test)
├── README.md               # Dokumentasi (English)
├── README_ID.md            # Dokumentasi (Bahasa Indonesia)
├── BUILDING.md             # Panduan Build & Test (EN / ID)
└── CHANGELOG.md            # Catatan Rilis (EN / ID)
```

## Pertanyaan Umum (FAQ)

### Apakah Panggung Studio adalah aplikasi gratis open-source seperti OBS untuk Android?
Ya. Panggung Studio berlisensi **GPL-2.0-or-later** dan menjalankan engine **libobs 33.0.3** asli secara native di Android (`arm64-v8a`) — lengkap dengan multi-scene, komposisi layer, encoder hardware MediaCodec, filter audio/video, dan `obs-websocket v5` tanpa PC dan tanpa biaya langganan.

### Bisa dipakai live streaming game di TikTok Portrait 9:16 pakai facecam?
Bisa. Ubah canvas ke **`720×1280`** atau **`1080×1920`**, tambahkan **`+ Cam Front`** / **`+ Cam Back`** dan **`+ Screen`**, tap **`GO LIVE`**, lalu dari dalam game tap **Floating Studio Hub → `LAYOUT`** untuk berpindah cepat antara **`SPLIT 2`** (Atas Kamera + Tengah Game Fit Full-Width + Bawah Ruang Chat TikTok), **`3-SLOT`**, **`GAME+PiP`**, dan **`FULL CAM`** tanpa gepeng.

### Apakah kamera tetap nyala saat masuk ke dalam game di Android 14–17?
Ya. Panggung Studio menggabungkan Foreground Service `CAMERA | MICROPHONE | MEDIA_PROJECTION` (`FGS=224`) sehingga Android 14–17 tidak memutus kamera depan/belakang saat Anda bermain game layar penuh seperti Mobile Legends.

### Protokol streaming dan encoder apa saja yang didukung?
RTMP, RTMPS, Multistream RTMP, SRT (`libsrt`), HLS, DASH, RTSP, dan NDI® (BYOR). Encoder video mencakup hardware **MediaCodec** (`H.264`, `HEVC`, `HEVC 10-bit HDR PQ/HLG`, `AV1`, `VP9`) serta software (`x264`, `x265 3.6`, `libaom 3.10.0`).

## Dokumentasi & Lisensi

* [English Documentation (`README.md`)](README.md)
* [Panduan Build & Test (`BUILDING.md`)](BUILDING.md)
* [Catatan Rilis (`CHANGELOG.md`)](CHANGELOG.md)
* [Indeks AI / LLM (`llms.txt`)](llms.txt)

Dirilis di bawah lisensi **GPL-2.0-or-later** (mengikuti lisensi upstream `libobs` / OBS Studio).
