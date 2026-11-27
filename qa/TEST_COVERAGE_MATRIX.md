# Master Test Coverage Matrix (`v0.3.0-alpha.1`)

## Exact Inventory-Denominated Metrics (Post `BUG-001` & `BUG-002` Verification)

| Metric | Numerator / Denominator | Percentage | Notes |
| --- | --- | --- | --- |
| **Inventory Coverage** | `36 / 36` top-level features mapped | **100.0%** | Covers 82 JNI, 63 WS handlers, 16 C plugins, 155 UI elements |
| **Executed Feature Test Coverage** | `32 / 36` features executed | **88.9%** | 2 BLOCKED (UVC, NDI), 2 PARTIAL/NOT_TESTED (live Screen AHB consent, real cellular handoff) |
| **Feature Pass Rate (of executed)** | `31 / 32` PASS (`1 PARTIAL`) | **96.9%** | `BUG-002` (`SetSourceCrop` + persistent crop across `force-stop`) now **PASS**; only `FEAT-012` live MediaProjection consent remains `PARTIAL` |
| **UI Interaction Pass Rate** | `18 / 19` stages (`11/12` + `7/7`) | **94.7%** | `BUG-001` fixed (Stage 1 PASS); Output Tab UI tap showed 1 timing flake (`FLK-001`) |
| **Flow Transition Coverage** | `16 / 16` PASS | **100.0%** | `FLOW-11` (Scene + Crop + TLS Cert persistence across `force-stop`) now **100% PASS** |
| **Regression Pass Rate** | `8 / 9` executed runs PASS | **88.9%** | `ui_tap_e2e_test.py` hit `FLK-001` on Output Tab; all core/barbar/stress/SRT/zerocopy/SSIM/soak suites 100% PASS |
| **Security Test Pass Rate** | `9 / 9` security controls PASS | **100.0%** | Zero leaks, zero crashes, all attack vectors blocked |
| **Device Coverage** | `1 / 5` target device classes tested | **20.0%** | Only `SM-S721B` (Exynos/Android 17) physically attached |
