# Multitask delivery evidence

Build **0.6.0-alpha-v3**, versionCode **6**, October 6, 2026. Comparison base `55c15c9d87c2bd9706d968e8110bec1dd118eeba`.

- Final serial gate `multitask8`: JVM/lint/debug and instrumentation assembly all exit 0.
- Final XML totals: **842 tests**, no failures/errors/skips.
- Browser: typecheck, 10 unit tests, 15 Playwright fixtures passed.
- Lint: no new issues; **15 historical errors remain filtered by the existing baseline**.
- Debug APK: 408,464,391 bytes; SHA-256 `17aed5c11911333131acef55235a9f8673f539e4d002618e544019f5b7f005f0`. Same public debug signer certificate as the previous delivery, verified by apksigner.
- APK contains TaskCoordinator, TaskBoardScreen, DraftQualityGate, PixelWindowPolicy and packaged MNN libraries; this is packaging evidence, not device execution.
- No attached phone/emulator; all physical qualification remains pending.

`results.json` records per-module counts, hashes and evidence boundaries. Raw gate logs and commands are retained. No binary model or APK is committed.

## Negative evidence and harness corrections

The original real-host marker run in [multitask-host](../multitask-host/README.md) FAILED: B omitted its own marker. It remains unchanged. The [draft gate run](../multitask-draft-gate/README.md) passed two native literal checks using actual Qwen/MNN, with RESPONDED outcomes, not factual verification. Both first candidates passed, so real-model repair and repeated-failure paths were not exercised. The unsupported urgency in B remains preserved.

Earlier integrated attempts caught API wiring/test-fixture problems and one real admission-receipt bug. Robolectric 4.12 could not open/fsync a directory through its RandomAccessFile-based Linux shadow. A **test-only** host directory-channel shadow models that missing operation with real host fsync; production Android durability checks were not bypassed. A direct-tool fixture deadlocked the paused SDK Main thread; its admitted test worker now starts on that thread, while independent scheduler concurrency tests remain. The stopped failed run is explicitly recorded, not passed.

## Remaining boundaries

Browser/Skills use shared leases/journal but remain independent producers, not live coordinator task rows/families. Full-display OCR rejects any other reported window, including overlays; unreported windows/OEM composition need device testing. Controls depend on accessible native semantics; this is not any-app autonomy, universal secret detection, sustained background operation or investment readiness certification.
