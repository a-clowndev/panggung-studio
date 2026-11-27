# Execution Log — Exhaustive QA Audit (`v0.3.0-alpha.1`)

- **Timestamp**: `2026-11-27T17:03:41+0700`
- **Branch / Commit**: `v0.3.0-alpha.1` (`0beba68fb91b42878924e815b4244a1223b6f20c` + 17 uncommitted audit-fix files)
- **APK**: `io.panggung.obs` `versionName=0.3.0-alpha.1`, `versionCode=30001`
- **Device**: Samsung Galaxy S24 FE (`SM-S721B`, Android 17 / SDK 37, `arm64-v8a`)
- **Office Mode**: `STREAM_MUSIC` before=`0/15`, after=`0/15`, silent_verified=`True`

## Phase A — Repository Regression Suites

| Suite | Exit Code | Status | Elapsed (s) | Summary | Evidence |
| --- | --- | --- | --- | --- | --- |
| `e2e_device_test` | `0` | **PASS** | 31.8 | === FULL E2E REGRESSION SUMMARY: 64/64 PASSED === | `qa/evidence/logcat/reg_e2e_device_test.log` |
| `srt_reconnect_e2e_test` | `0` | **PASS** | 35.3 | === ALL HARDENED E2E TESTS PASSED === | `qa/evidence/logcat/reg_srt_reconnect_e2e_test.log` |
| `golden_ssim_test` | `0` | **PASS** | 1.3 | [PASS] Updated cropped golden reference (crop=iw*0.60:ih*0.42:iw*0.20:ih*0.06, YAVG=42.4, YMAX=226.0) | `qa/evidence/logcat/reg_golden_ssim_test.log` |
| `golden_ssim_verify` | `0` | **PASS** | 1.2 | [PASS] Cropped Preview SSIM=1.0000 (min=0.85, YAVG=42.4, YMAX=226.0) | `qa/evidence/logcat/reg_golden_ssim_verify.log` |
| `soak_test_15s` | `0` | **PASS** | 18.6 | SOAK_TEST_ASSERTIONS_PASSED | `qa/evidence/logcat/reg_soak_test_15s.log` |
| `zerocopy_procam_e2e_test` | `0` | **PASS** | 84.4 | === HARDENED v0.3.0-alpha.1 E2E SUMMARY: 7/7 PASSED === | `qa/evidence/logcat/reg_zerocopy_procam_e2e_test.log` |
| `ui_tap_e2e_test` | `1` | **FAIL** | 188.9 | === FULL UI TAP E2E SUMMARY: 11/12 PASSED === | `qa/evidence/logcat/reg_ui_tap_e2e_test.log` |
| `barbar_chaos_security_test` | `0` | **PASS** | 202.8 | === BARBAR CHAOS & SECURITY GAUNTLET SUMMARY: 12/12 PASSED === | `qa/evidence/logcat/reg_barbar_chaos_security_test.log` |
| `stress_test_all` | `0` | **PASS** | 104.9 | === ALL-FEATURES STRESS TEST SUMMARY: 7/7 PASSED === | `qa/evidence/logcat/reg_stress_test_all.log` |

## Phase B — Exhaustive Flow, Anomaly, Audio, Pixel & Performance Runs

| Flow ID | Scenario | Status | Actual Result | Evidence |
| --- | --- | --- | --- | --- |
| `F01` | Rapid Start->Immediate Stop & Repeated Start While Connecting | **PASS** | start1=True start2=True stop=True alive=True | `qa/evidence/metrics/exhaustive_qa_results.json` |
| `F02` | Mid-Recording Encoder Switch Anomaly + Playable Output | **PASS** | enc_switch=True decode=True dur=3.1s codec=h264 | `qa/evidence/ffprobe/f02_mid_switch.json` |
| `F03` | Extended WS API Surface (22 endpoints incl. Security Blocks) | **PASS** | passed=22/22 failed=[] | `qa/evidence/metrics/exhaustive_qa_results.json` |
| `F04` | Digital Audio + Non-Black Pixel + PTS Monotonicity Audit (8s Live Rec) | **PASS** | codec=h264/aac YAVG=16.5 drift=26.3ms mono=True audio_mean=-54.0dB | `qa/evidence/ffprobe/f04_digital_av_audit.json` |
| `F05` | Multi-Resolution Zero-Readback GPU-Surface Perf Benchmarks (4 profiles) | **PASS** | 720p30/1080p30/1080p60/9:16 measured | `qa/evidence/metrics/exhaustive_qa_results.json` |
| `F06` | Persistent Crop Round-Trip Across Force-Stop | **PARTIAL** | before={'crop_left': None, 'crop_top': None, 'crop_right': None, 'crop_bottom': None} after={'crop_left': None, 'crop_top': None, 'crop_right': None, 'crop_bottom': None} | `qa/evidence/metrics/exhaustive_qa_results.json` |
| `F07` | Android Lifecycle Chaos (Background/Foreground + Camera Perm Revoke/Regrant) | **PASS** | resumed=True recovered=True crashes=[] | `qa/evidence/logcat/phase_b_chaos.log` |
