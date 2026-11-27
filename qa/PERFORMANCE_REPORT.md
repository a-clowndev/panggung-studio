# Performance & Zero-Readback Report (`v0.3.0-alpha.1` on `SM-S721B`)

## 1. Multi-Resolution True Zero-Readback GPU-Surface Benchmarks

| Profile | Target FPS | Avg FPS | Min FPS | Frame Time p50 | Frame Time p95 | Frame Time p99 | Start PSS | Peak PSS | Batt Temp |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `720p30_landscape` | 30 | 30.0 | 30.0 | 3.47 ms | 4.84 ms | 4.84 ms | 150.8 MB | 153.5 MB | 39.7°C |
| `1080p30_landscape` | 30 | 30.0 | 30.0 | 4.28 ms | 4.73 ms | 4.73 ms | 150.9 MB | 170.0 MB | 39.8°C |
| `1080p60_landscape` | 60 | 60.0 | 60.0 | 4.22 ms | 4.52 ms | 4.52 ms | 150.6 MB | 173.6 MB | 39.8°C |
| `720p30_portrait_9x16` | 30 | 30.0 | 30.0 | 5.48 ms | 5.86 ms | 5.86 ms | 147.3 MB | 153.1 MB | 39.7°C |

## 2. Sustained Soak & Baseline Comparison

- **30s 1080p60 HEVC Soak (`stress_test_all.py`)**: `min_fps=60.0`, `max_ft=6.92ms`, `delta_rss=-31.8 MB` (zero leak), `max_temp=37.4°C`, `crashes=0`.
- **15s Soak (`soak_test.py`)**: `SOAK_TEST_ASSERTIONS_PASSED`.
- **Zero-Readback Impact vs `v0.2.0` Baseline**: Peak PSS during 1080p60 H.264 recording dropped from ~292 MB (CPU NV12 staging) to **173.6 MB** (**~119 MB reduction**), with `p95` frame render time at **4.52 ms** (well under 16.67 ms 60fps budget).

## 3. Digital Media & Audio Verification (Office Mode Silent)

| Recording | Decode | Video | Audio | Duration | A/V Drift | PTS Monotonic | YAVG (Non-Black) | Audio Mean dB | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `f02_mid_switch` | True | `h264 1280x720 (yuv420p)` | `aac 48000Hz` | 3.1s | 7.0 ms | True (93 pkts) | 172.0 (YMAX=172.0) | -26.8 dB | `qa/evidence/ffprobe/f02_mid_switch.json` |
| `f04_digital_av_audit` | True | `h264 1280x720 (yuv420p)` | `aac 48000Hz` | 8.13s | 26.3 ms | True (244 pkts) | 16.5 (YMAX=55.0) | -54.0 dB | `qa/evidence/ffprobe/f04_digital_av_audit.json` |
