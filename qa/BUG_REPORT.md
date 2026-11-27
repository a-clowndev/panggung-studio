# Deduplicated Defect & Gap Report (`v0.3.0-alpha.1`)

| Bug ID | Severity | Component | Summary | Reproduction / Evidence | Status |
| --- | --- | --- | --- | --- | --- |
| `BUG-001` | **Low** (Test Drift) | `tests/ui_tap_e2e_test.py:104` | Hardcoded `STUDIO v0.2.0` badge assertion failed Stage 1 on `v0.3.0-alpha.1` (`STUDIO v0.3.0-a1`). | Fixed to `contains="STUDIO v0."` — Stage 1 PASS verified on `SM-S721B`. | **CLOSED** |
| `BUG-002` | **Medium** (WS API Gap) | `crates/panggung-core/src/ws_server.rs:615` | Added `SetSourceCrop` obs-websocket handler; verified `SetSourceCrop(48,24,32,16)` → `GetSourceTransform` → `force-stop` → relaunch persisted `(48,24,32,16)` 100%. | `[PASS] BUG-002 VERIFIED` on `SM-S721B` | **CLOSED** |
| `BUG-003` | **Low** (Test Hygiene) | `tests/*.py` (5 suites) | Five legacy test scripts call `adb logcat -c` mid-test instead of `logcat -T <start_ts>`, which can mask earlier native crashes. | Static audit in `qa/REGRESSION_REPORT.md` | OPEN |
| `FLK-001` | **Low** (UI Test Flake) | `tests/ui_tap_e2e_test.py:185` | Output Tab `START REC`/`PAUSE`/`RESUME`/`STOP REC` UI tap occasionally misses a button timing window under `uiautomator dump` latency (`11/12` on re-run after passing on previous run). | WS `StartRecord`/`PauseRecord`/`ResumeRecord`/`StopRecord` passes 100% in `e2e_device_test.py` & `stress_test_all.py`. | OPEN (FLAKY) |
| `GAP-001` | **Medium** (Coverage) | Multi-SoC & Long Soak | Only tested on Samsung Exynos 2400e (`SM-S721B`); Qualcomm Snapdragon, MediaTek, Tablet/DeX, physical USB UVC, NDI BYOR, live cellular handoff, and 2–8h overnight soak remain untested/blocked. | `qa/DEVICE_MATRIX.md` | BLOCKED / NOT_TESTED |
