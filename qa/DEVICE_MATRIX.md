# Device Matrix — Panggung Studio QA Audit

| Device ID | Model | SoC / ABI | Android OS / SDK | RAM / Display | Concurrent Dual-Cam HAL | Media Volume (Office Mode) | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `DEV-01` | Samsung Galaxy S24 FE (`SM-S721B`) | Exynos 2400e / `arm64-v8a` | Android 17 (API 37) | 8 GB / 1080×2340 | Supported (`Back=0 + Front=1`) | `0 / 15` (Verified silent) | **TESTED** |
| `DEV-02` | Qualcomm Snapdragon Reference Device | Snapdragon / `arm64-v8a` | Android 12–15 | N/A (Not connected) | Unknown | N/A | **NOT_TESTED (Hardware unavailable)** |
| `DEV-03` | MediaTek Dimensity Reference Device | MediaTek / `arm64-v8a` | Android 12–15 | N/A (Not connected) | Unknown | N/A | **NOT_TESTED (Hardware unavailable)** |
| `DEV-04` | Tablet / Samsung DeX External Display | `arm64-v8a` | Android 13+ | N/A (Not connected) | N/A | N/A | **NOT_TESTED (Hardware unavailable)** |
| `DEV-05` | USB UVC External Capture Card | USB OTG | Android 10+ | N/A (No UVC dongle attached) | N/A | N/A | **BLOCKED (No physical UVC hardware)** |

## Build Under Test
- **Branch**: `v0.3.0-alpha.1`
- **Base Commit**: `0beba68fb91b42878924e815b4244a1223b6f20c` (+ 17 uncommitted audit-fix files in working tree)
- **Installed APK**: `io.panggung.obs`, `versionName=0.3.0-alpha.1`, `versionCode=30001`, `minSdk=29`, `targetSdk=35`
- **Office Mode**: Verified `STREAM_MUSIC = 0 / 15` before test start; audio monitoring disabled; zero audible output permitted.
