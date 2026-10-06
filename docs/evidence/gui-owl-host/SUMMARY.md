# GUI-Owl real host evidence — three immutable runs

**Synthetic HOST evidence, NOT Android/phone qualification. No actions dispatched.** All physical/device gates remain pending. Native ARM build/package proof is a separate gate; no final Android gate values are asserted here.

| Run | Actual result | Host wall | Peak sampled child RSS |
|---|---|---:|---:|
| `real-probe` reduced first | Context1024 refused full GUI prompt; text `4`, red `Red`; **FAILED UTF-8 admission** (C3 28 reached inference); default fallback deliberately skipped | 28.021s | 3,159,760 KiB |
| `real-probe-default` UTF-fixed, pre-race fixes | Two synthetic moved-Search points inside bounds; malformed input rejected; active prefill cancellation | 155.702s | 3,313,776 KiB |
| `real-probe-final` post load-permit/stack-batch fixes | Recorded assertions passed, including stale load/request permits, malformed input, truncation, sequential reload/close; two synthetic points inside bounds | 166.063s | 3,319,156 KiB = **3.1654 GiB** |

Final GUI call walls: **74.082s / 71.878s**, not phone latency. Final VmHWM3,319,820KiB; minimum host MemAvailable2,484,920KiB; whole-cgroup peak4,134,780,928 bytes is NOT child RSS. Monitor100ms sampling cannot exclude sub100ms peaks. Cached/mmap startup is not cold-phone startup.

Final actual defaults: CPU2, mmap=true, repack=false, GPU=false, ctx2048, output256, imageEdge512, visionTokens256, hostReduced=false, deviceQualified=false. Actual 256×256 fixtures use64 image tokens; 1049 text +64 image +256 reserved output=1369. `load(modelRoot)` uses defaults without overrides. See raw stdout, invocation, monitor/harness scripts and current-runtime-default-source snapshot. Original reduced run flags remain reduced; no run is rewritten as default.

Final A=[743,673], B=[257,293], both inside independently authored Search rectangles. **Two toy synthetic outcomes are not an accuracy percentage or real-screen benchmark.** Negative stack traces and raw returns are preserved. Exit0 denotes harness completion, not that each operation succeeded. Active cancel returned generic `Vision/prefill failed` (519ms call wall,10ms cancel-to-join); not decode/vision-stage cancellation qualification.

## Current source check

MATCH: JNI, runtime, planner, prompt builder and codec source hashes match the final probe.

See `current-source-vs-final-probe.json` for full source hashes. Final pinned JNI SHA256 `f3c85dac4703d17211a539c6649b6894fc4884068f13ab0f524e0b0e25610a22`; runtime class `8884b30e8a7886bcd4e84fb448e0ea9b1220c38742852036f62af8b2c26068d3`. Full sources/classes/dependencies and preservation receipts are retained, including `/proc` loaded maps.

## Artifact identity and provenance

Decoder:2,497,282,208 bytes, SHA256 `8e1793b69bb4064671ab6529b43f5b943850f73a9244ad139c16e93a44709232`. Projector:453,974,336 bytes, SHA256 `b705d940e9b7f212235a16c9c4b8cc9dd9053a1ccf549b99fc069a0ae694b073`. Immutable third-party GGUF revision `9a79d301329062eb02e46f7ccad82c99075bfaaa`; runtime llama.cpp `4f5406761517648c23dbd60ea5ade37f77a316c9`. Full GGUF metadata, download/hash reverifications and model cards retained. Cards declare **MIT**, GGUF metadata **Apache-2.0**: unresolved discrepancy, no legal clearance. Canonical reference revision is not verified conversion provenance; **no independent conversion parity established**.

## Preservation and reproduction

Only UTF-8 text allowlist copied into Git. `original-files.json` records every original byte size/hash and disposition; `original-preservation-audit.json` verifies originals unchanged. All original raw files (including invalid-UTF8 stderr, PNGs, native libraries and classes) remain outside Git and are additionally copied into the ZIP identified by `original-archive.json`. The first stderr's `.utf8-escaped-view.txt` is clearly labeled DERIVED, not raw; original raw hash preserved. No model/library/JAR/PNG binaries copied into Git.

Each probe includes captured invocation/compile commands, harness, monitor, prompts, raw outputs/errors, memory samples and receipts. Historical preparation scripts are evidence, not safe rerun entrypoints: they contain original absolute destinations. Use `create-fixtures.py NEW_EMPTY_DIR` for PNG reconstruction without overwriting originals; compare PNG SHA256 against original-files.json. Fixture geometry is in each `fixture.json`; font/Pillow provenance is in fixture-environment.json. Never rerun historical scripts in the original evidence directories.
