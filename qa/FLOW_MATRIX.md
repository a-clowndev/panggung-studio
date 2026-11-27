# Flow & State Machine Matrix — Panggung Studio (`v0.3.0-alpha.1`)

Total critical state-machine & anomaly flows mapped: **16**

| Flow ID | State Machine | Transition / Sequence | Anomaly / Verification Target |
| --- | --- | --- | --- |
| `FLOW-01` | Stream Lifecycle | `IDLE -> CONNECTING -> LIVE -> STOPPING -> IDLE` | Normal & Rapid Start->Stop (<200ms) |
| `FLOW-02` | Stream Anomaly | `CONNECTING (unreachable SRT) -> StopStream` | Interrupted connect, zero hang |
| `FLOW-03` | Stream Reconnect | `UI GO LIVE -> Remote WS StopStream` | Must cancel auto-reconnect (zero zombie) |
| `FLOW-04` | Stream Chaos | `LIVE SRT -> Receiver SIGKILL mid-stream -> 4x Hammer` | Zero deadlock/UAF, bounded memory |
| `FLOW-05` | Record Lifecycle | `IDLE -> REC -> PAUSE -> RESUME -> STOP -> ffprobe` | GPU-Surface encode_credit backpressure |
| `FLOW-06` | Record Anomaly | `Active REC -> Mid-flight Encoder Switch (H.264->HEVC)` | No crash, valid playable MP4 |
| `FLOW-07` | Concurrent Output | `Active REC + Active Encrypted SRT + Studio Mode CUT` | Both MP4 & SRT decode cleanly |
| `FLOW-08` | Camera Lifecycle | `Back <-> Front 6x Rapid Thrash + Dual-Cam Back+Front` | Strict HAL check, EGLSync fence, 0 green line |
| `FLOW-09` | Permission Chaos | `Active Camera -> Revoke CAMERA -> Re-grant -> Resume` | No fatal crash, clean recovery |
| `FLOW-10` | App Lifecycle | `Foreground -> Home Background -> Foreground + Back Exit x3` | Clean teardown, zero Crash Recovery banner |
| `FLOW-11` | Persistence | `Create Scene + Apply Crop -> Force-Stop / APK Upgrade -> Relaunch` | Scene, Crop & TLS Fingerprint survive |
| `FLOW-12` | Security State | `WSS/HTTPS Auth -> Token Rotate -> Old Client Kick & Reject` | Immediate revocation across WSS & HTTPS |
| `FLOW-13` | DoS / Fuzz State | `20 Slowloris + 30x Burst + 2MB Body + Malformed WS Frames` | Bounded workers, responsive, 0 crash |
| `FLOW-14` | Filesystem Gate | `StartCustomOutput & RemuxRecording traversal/symlink` | All 4 escape vectors blocked |
| `FLOW-15` | Pipeline Realloc | `720p30 -> 1080p60 -> Vertical 9:16 -> HDR P010 -> 1080p60` | Zero GLES/MediaCodec crash |
| `FLOW-16` | Orientation | `Portrait <-> 3-Col Landscape rapid rotation torture` | Zero TextureView surface race |
