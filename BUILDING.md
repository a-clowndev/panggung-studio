# Building & Testing Panggung Studio / Panduan Build & Test

> **English** & **Bahasa Indonesia** quick reference for compiling native dependencies, `libobs`, Rust `panggung-core`, and the Android APK (`arm64-v8a`).

---

## Prerequisites / Prasyarat

* **Linux x86_64** build host (recommended)
* **Android SDK** (API 35/36) & **Android NDK r27+** (`ANDROID_NDK_HOME` configured)
* **Rust toolchain** with `aarch64-linux-android` target (`rustup target add aarch64-linux-android`)
* **CMake 3.22+**, **Ninja**, **Perl**, **Python 3.10+** (with `websockets` for E2E tests), **JDK 17+**

---

## 1. Cross-Compile Native Dependencies (FFmpeg, x264, x265, libaom, libsrt, SpeexDSP, FreeType2, Lua)

```sh
./tools/build-deps-full.sh
```

> **ID**: Menjalankan cross-compile seluruh library dependensi native ke `native/deps/install/` untuk arsitektur `arm64-v8a`.

---

## 2. Build `libobs 33.0.3`, `libobs-gles`, and Android Plugins

```sh
./tools/build-libobs.sh
```

> **ID**: Mengompilasi `libobs.so`, backend grafis `libobs-gles.so` (GLES 3.2 + `AHardwareBuffer`), dan `libobs-android-plugins.so` (MediaCodec + Android sources) lalu menyalin `.so` & aset ke `app/src/main/`.

---

## 3. Build Rust Core (`libpanggung.so`)

```sh
cargo build --release --target aarch64-linux-android -p panggung-core
cp target/aarch64-linux-android/release/libpanggung.so app/src/main/jniLibs/arm64-v8a/
```

---

## 4. Build & Install Android APK

### Debug APK
```sh
./tools/gradle/*/bin/gradle assembleDebug
adb install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
```

### Signed Release APK
Create a local keystore (kept out of git via `.gitignore`) and configure signing in `app/build.gradle.kts`, then run:
```sh
./tools/gradle/*/bin/gradle assembleRelease
adb install --no-streaming -r app/build/outputs/apk/release/app-release.apk
```

> **Tip (Wireless ADB)**: If streamed install stalls over Wi-Fi, push and install directly:
> ```sh
> adb push app/build/outputs/apk/release/app-release.apk /data/local/tmp/panggung.apk
> adb shell pm install -r /data/local/tmp/panggung.apk
> adb shell rm -f /data/local/tmp/panggung.apk
> ```

---

## 5. Automated Device E2E & Stress Testing / Pengujian E2E di Perangkat Asli

Connect an `arm64-v8a` Android device via ADB, then run:

```sh
# 1. Core + 10-bit HDR + Lua + Multistream + Replay Buffer (65 tests)
python3 tests/e2e_device_test.py

# 2. UI Tap + Front/Back Camera + Autorotate E2E (12 tests)
python3 tests/ui_tap_e2e_test.py

# 3. 1080p60 HEVC Soak & Memory Leak Stress Test
python3 tests/stress_test_all.py
```

### Optional: Verify Hardware Encoding & Background Camera via Logcat
```sh
adb logcat -d -s PanggungService:I Camera2Capture:I ScreenCapture:I
```
Look for:
* `Screen + Internal Audio started cleanly (FGS=224)` (confirms `CAMERA | MICROPHONE | MEDIA_PROJECTION` active simultaneously)
* `Proportional VirtualDisplay resize -> 1280x590` (confirms zero-letterbox screen capture)
* `Camera2Capture: Streaming camId=...` with zero `Camera error=3` disconnects in-game.
