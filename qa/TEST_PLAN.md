# Master Test Plan — Panggung Studio (`v0.3.0-alpha.1`)

## 1. Objectives & Constraints
- Exhaustive end-to-end verification across Android Kotlin/Compose, Rust FFI/JNI, Native C plugins (`libobs-android-plugins.so`, `libobs-mod-panggung-3p-pack.so`), `libobs` / `libobs-gles`, MediaCodec GPU-Surface encoding, SRT/RTMPS, Security, and Performance.
- **Office Mode (Highest Priority)**: Media volume locked at `0/15`, zero audible playback, speaker monitoring OFF, digital audio verification only.
- **Audit Integrity**: Zero production code edits during audit; zero weakened assertions; all failures, flakes, and blocks reported transparently with reproducible evidence under `qa/evidence/`.

## 2. Test Batches
1. **Batch 1 — Static Discovery & Inventory**: Enumerate all 28 Kotlin files, 11 Rust files, 20 Native C/H files, 82 JNI externals, 62 WS handlers, 155 UI interactive elements, 16 C plugin registrations, 12 permissions, 2 services, and audit all 8 existing test suites.
2. **Batch 2 — Full Repository Regression (All 8 Suites)**: Run `e2e_device_test.py`, `ui_tap_e2e_test.py`, `stress_test_all.py`, `golden_ssim_test.py`, `soak_test.py`, `srt_reconnect_e2e_test.py`, `barbar_chaos_security_test.py`, `zerocopy_procam_e2e_test.py` with full log capture.
3. **Batch 3 — Exhaustive Flow Anomaly, State Machine & Untested Feature Harness (`qa/scripts/exhaustive_qa_runner.py`)**:
   - Rapid Start→Stop, Stop-while-connecting, Delete-source-while-recording, Mid-recording encoder switch
   - Persistent crop round-trip (`GetSourceTransform` + `force-stop` + relaunch)
   - Full untested WS API surface (Undo/Redo, Profiles, Plugins, AutoSwitcher, Audio Advanced, Order/Lock/Fit, Remote Lua/Plugin block)
   - Permission revocation & recovery (`CAMERA`), App background/foreground lifecycle chaos
   - Digital audio verification (`volumedetect`, `ffprobe` PTS monotonicity, Office Mode volume=0 check)
   - True zero-readback performance benchmarks (720p30, 1080p30, 1080p60, Portrait 9:16: FPS, frame-time p50/p95/p99, PSS, thermal)
   - Media pixel audit (non-black `YAVG`, non-frozen frame diff, zero green lines, strict PTS monotonicity)
4. **Batch 4 — Final Synthesis & Release Verdict**: Compute exact inventory-denominated coverage metrics, deduplicate all defects into `BUG_REPORT.md`, and issue evidence-based release verdict in `RELEASE_READINESS.md`.
