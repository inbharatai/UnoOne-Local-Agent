# Floating voice development delivery

Version0.8.0-alpha-voice/code8; baseb9beff5fd43e9d61c2f1c29ff54866991a808799.

Final clean voice8 gates: compile/JVM/lint/debug+instrumentation assembly exit0. Clean app/core/voice outputs before build; model/runtime native sources unchanged. **1006 JVM tests**,0failures/errors/skips. Browser clean npm ci/typecheck10unit15Playwright passed; production npm audit0 vulnerabilities; invariant checker11 mutation tests passed. Existing lint baseline still filters15 historical errors; no new suppressions.

Debug APK 425,329,787bytes SHA256 `77598ef26a061796f832c24179ee95d4a52ca0047d746936f605c466eebc1ffe`; same signing certificate as earlier deliveries. DEX contains unified voice/latency/overlay/Stop implementation and excludes both rejected compact prompt candidates. Native Owl dependency/page alignment and DOM/source byte match checked; pre-existing CameraX image utility ELF exception remains, not whole-app16KB-device certification.

## Evidence boundaries
Native-first no-model paths tested through actual coordinator/runtime with fake Android device effects/voice ports and held model lease—not phone performance. Unique microphone/queued speech owner, stale approval, cutoff, overlay window, Stop/revocation and diagnostics tests are host regressions. Independent whole-diff source review plus targeted rechecks found and repaired concrete issues; they are bounded, not exhaustive certification.

[Two real Owl prompt experiments](../floating-voice-prompt/README.md) preserved failures. V1baseline2/2 versuscandidate0/2; V2baseline4/4 versuscandidate3/4. Both faster candidates were rejected and removed from production source sets, preserving strict output validation. No model weights/CPUthreads/KVcache/endpoints changed. FULL prompt remains baseline; no model-inference speedup claimed.

Earlier API/mock migration, disabled diagnostic clock, old Android Main dispatch and capture/overlay/speech races were repaired, not hidden by weakening guards. Failed/hung earlier runs were not passed; raw local logs retained. 

No attachedphone/emulator; no acoustic accuracy, user-perceived latency, p95 device claim, all-app autonomy or perfection. Real-device install, speech/Stop, notification/overlay and model/thermal/battery qualification remain pending.
