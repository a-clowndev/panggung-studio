# Regression Report — Panggung Studio (`v0.3.0-alpha.1`)

- **Regression Pass Rate**: **8 / 9 (88.9%)** executed runs
- **Only Failure**: `tests/ui_tap_e2e_test.py` (`11/12 PASSED`, exit code `1`) — Stage 1 failed because test still hardcodes `"STUDIO v0.2.0"` while APK displays `"STUDIO v0.3.0-a1"` (`BUG-001`, test-drift defect, NOT an app crash).

## Static Quality Audit of Existing Test Suites

| Test File | Identified Weaknesses |
| --- | --- |
| `tests/ui_tap_e2e_test.py` | Hardcoded `STUDIO v0.2.0` badge assertion (caused genuine FAIL on v0.3.0-alpha.1); calls `adb logcat -c` mid-test |
| `tests/barbar_chaos_security_test.py` | Calls `adb logcat -c` at startup; broad `except Exception: pass` on DoS burst cleanup |
| `tests/stress_test_all.py` | Calls `adb logcat -c` mid-test during camera thrash; broad `except Exception: pass` on HTTP loop |
| `tests/srt_reconnect_e2e_test.py` | Calls `adb logcat -c` before logcat secret scan |
| `tests/soak_test.py` | Calls `adb logcat -c` at startup |
| `tests/e2e_device_test.py` | Clean (`64/64 PASSED`) |
| `tests/golden_ssim_test.py` | Clean (strict `--update-baseline` enforced; `SSIM=1.0000`) |
| `tests/zerocopy_procam_e2e_test.py` | Clean (`7/7 PASSED`, uses `tempfile` & unbroken `logcat -T`) |
