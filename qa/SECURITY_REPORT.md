# Security Audit Report — Panggung Studio (`v0.3.0-alpha.1`)

| Security Control | Test ID | Result | Evidence |
| --- | --- | --- | --- |
| APK Signing (`apksigner verify`) & 16 KB ELF Alignment (22/22 `.so`) | `B01` | **PASS** | `qa/evidence/logcat/reg_barbar_chaos_security_test.log` |
| Token Rotation Immediate Session Revocation (WSS + HTTPS) | `B02` | **PASS** | `kicked=True old_wss_rej=True old_https_rej=True new_ok=True` |
| Web Remote DoS Torture (20 Slowloris + 30x Burst + 2MB Body) | `B03` | **PASS** | `burst=30/30 (2.5s) oversize_rej=True alive=True` |
| Filesystem Path Traversal & Symlink Escape (`StartCustomOutput`, `RemuxRecording`) | `B04` | **PASS** | All 4 attack vectors blocked |
| Malformed / Fuzz obs-websocket Protocol + 80x Rapid Pipeline | `B05` | **PASS** | `stats_ok=80/80`, 0 crash |
| Remote Arbitrary Lua & Native `.so` Plugin Loading Blocked (`RunLuaScript`, `LoadPluginSo`) | `F03` | **PASS** | Both rejected over WS |
| Persistent LAN TLS 1.3 (`TLSv1.3`) + Plain HTTP/WS Rejected on LAN | `B09`, `B11` | **PASS** | `fp_match=True http_rej=True ws_rej=True` |
| RTMPS Root CA Verification (Invalid Hostname Rejected + Valid CA Chain Passed) | `srt_reconnect` | **PASS** | `Cert verify failed: 4` on invalid CN |
| Global Zero Credential Leak (SRT Passphrase + WS Tokens) | `B12`, `srt_reconnect` | **PASS** | `leaks=[]` across full logcat |

**Security Test Pass Rate**: **9 / 9 (100.0%)** executed security controls PASSED.
