# UnoOne v3 Xiaomi qualification procedure

**Status: PENDING — no physical device run has been performed by this change.** The 100-task corpus is a specification, not an implemented Android fixture host. JSON fixture recipes are sanitized and synthetic; missing fixture rendering must remain pending, never be substituted with personal Gmail/WhatsApp accounts. No production code changed.

## Prepare (Linux or Windows)
1. Use an isolated Xiaomi test profile with no personal accounts, contacts, photos or messages. Keep raw logs local: logcat, getprop serial, screenshots and netstats can contain identifiers. Sanitize copies before sharing. Do not record a private field or screenshot for privacy tasks; use redacted action/state attestations.
2. Build explicitly from the checked-out commit: `cd android-app/UnoOneAgent`; Linux `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`; Windows `./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest`. Record command, commit, dirty-tree diff hash, build variant, compiler/runtime versions and artifact SHA256. Build availability is not device qualification.
3. Connect USB, approve debugging on the phone; run `adb devices -l`, choose explicit serial. Never use an implicit first device. Check the APK hash against its build artifact manifest: Linux `sha256sum app-debug.apk`; Windows `Get-FileHash app-debug.apk -Algorithm SHA256`. With operator consent install using `adb -s SERIAL install -r APK` (no `-g`). Preserve test data; do not clear production app storage.
4. Compare installed bytes: `adb -s SERIAL shell pm path com.unoone.agent`; for each returned base/split path `adb -s SERIAL pull PATH LOCAL`; hash every pulled APK and compare against the exact installed artifact(s). An unavailable pull/hash comparison remains pending, not verified.
5. **Never silently enable accessibility.** The operator reads and approves the service disclosure and enables the UnoOne service manually in Android Accessibility Settings. Grant microphone, notification, overlay, and screen capture only when explicitly needed and only through visible platform prompts. Note Xiaomi HyperOS/MIUI battery/background restrictions; record actual settings, do not assume or silently change them. Revoke optional permissions after testing.
6. Install/import the exact local model through the supported app flow. Hash the actual model artifact and record runtime version plus requested AND actual backend from app diagnostics. Never label CPU fallback as GPU success. Unknown identity invalidates a measured pass.

## Real app evaluation surface inspected
`app/src/androidTest/java/com/unoone/agent/localbrain/BrainEvalHarnessTest.kt` exists. It tests CPU/GPU planner calls and logs `EvalScorer`; it does **not** execute native UI tasks and skips when no verified model is installed. `ModelStatusScreen` exposes **Run Self-Test** through `BrainSelfTest`. Neither is a v3 100-task automation endpoint.

To run only the real existing instrumented class after installing the debug and matching test APK, first inspect `adb -s SERIAL shell pm list instrumentation`. Use the exact reported runner: `adb -s SERIAL shell am instrument -w -e class com.unoone.agent.localbrain.BrainEvalHarnessTest REPORTED_COMPONENT`. Do not invent receiver/HTTP endpoint commands. Save raw output, including assumptions/skips. Android tests without a device or verified model are **pending/skipped**, not passed.

## Offline proof
Manually enable airplane mode and verify the visible state. Separately switch Wi-Fi and mobile data off (Android may leave Wi-Fi enabled in airplane mode); disconnect Ethernet/tethering and record all transports. Record a timestamped, sanitized Settings capture and operator attestation. Capture UID-attributed connections using an available, operator-approved packet/VPN capture or engineering network monitor; record tool, capture interval, UID mapping and original capture hash. Never root or bypass platform permissions merely to capture. Review DNS/TCP/QUIC/socket destinations for inference connections and record the review. `dumpsys connectivity`, netstats, airplane settings and logcat are supporting observations only; absence there does **not** prove zero connections. No usable capture or incomplete transport coverage => offline verification pending. Airplane mode alone is not offline-inference qualification.

## Execute and collect
Materialize/validate host data: `python3 evaluation/device-agent/build_corpus.py` then `python3 evaluation/device-agent/validate.py` (Windows: `py -3`). Provision each initial fixture and task-specific overlay in an isolated native test host; until such a host exists those tasks are pending. Observer verifies expected native state independently from the model response. All message/send/delete/payment/submit workflows stop at a draft or preview. Cancel tasks require timestamped cancellation acknowledgement and proof of no subsequent actions.

Linux: `bash scripts/test/unoone-v3-xiaomi.sh SERIAL APK OUT 1800`

Windows: `powershell -File scripts/test/unoone-v3-xiaomi.ps1 -Serial SERIAL -Apk APK -Out OUT -Seconds 1800`

These scripts only collect. They do not install, grant permissions, synthesize task results, or execute the corpus. Run the fixed `sustained-50.json` order while collecting every 10 seconds; do not cool down between tasks. Reset only synthetic task state. Record task start/end timestamps and connection-capture overlap. Continue capture until all 50 finish; if collection ends early, the run is incomplete. Note charging state, ambient temperature, battery baseline/end, peak PSS from meminfo, thermal sensor names/units, throttling, process restarts, crash buffer and ANR/exit-info records. Battery temperature is tenths of °C; do not call it CPU temperature. Missing thermal/ANR permissions are unavailable evidence, not zero heat/ANR. Use three matched sustained repetitions per backend for comparisons; report all runs, not only the best.

Logs may include events before the run; attribute crash/ANR by PID, UID and time. Do not clear global log buffers. Validate and aggregate manually recorded result JSON via `python3 evaluation/device-agent/validate.py RUN.json`. Keep raw captures, hashes and observer evidence with each run. No device: leave `pending-results.json` unchanged.


## V3 real E2B fixture instrumentation (PENDING)

New classes: `com.unoone.agent.localbrain.V3E2BPhysicalQualificationTest` and `com.unoone.agent.localbrain.V3FixtureBoundaryInstrumentedTest`. No physical execution or compilation was performed for these additions. No mocked runtime, substituted output, assumption skip, or production edits. E2B setup forces exact ModelManager integrity verification; missing/invalid artifact is an assertion failure. Use an isolated profile with the master switch explicitly enabled, no concurrent app model task, and the exact E2B artifact imported through the supported flow. Native load records actual backend, not assumed GPU success.

Exact Linux commands from repository root (operator must replace SERIAL):

```bash
export SERIAL=YOUR_XIAOMI_SERIAL
adb -s "$SERIAL" devices -l
(cd android-app/UnoOneAgent && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest)
adb -s "$SERIAL" install -r android-app/UnoOneAgent/app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" install -r android-app/UnoOneAgent/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$SERIAL" shell pm list instrumentation
adb -s "$SERIAL" shell am instrument -w -r -e class com.unoone.agent.localbrain.V3FixtureBoundaryInstrumentedTest com.unoone.agent.test/androidx.test.runner.AndroidJUnitRunner | tee v3-fixture-boundary.txt
adb -s "$SERIAL" shell am instrument -w -r -e class com.unoone.agent.localbrain.V3E2BPhysicalQualificationTest com.unoone.agent.test/androidx.test.runner.AndroidJUnitRunner | tee v3-e2b-qualification.txt
```

Verify the listed runner matches before invoking it. Inspect instrumentation output for failures, crashes, and total test count; adb/tee exit code alone is not a pass. Expected counts: 3 boundary tests, 5 E2B tests. Every E2B test loads and unloads a real engine independently. Retain logs locally and sanitize before sharing.

Fixture: Android Bitmap/Canvas draws a red square left and blue circle right with white Search label. Encoded PNG passes through real `LocalBrain.describeSceneWithVision` to `Content.ImageBytes`; controller tests use real `GemmaE2BBrain.plan` strict single-action parsing and `groundTarget` strict numeric GroundingBox parsing against the exact issued Search node. Grounding uses a typed full-display image envelope and real monotonic clock. The production 5-second snapshot deadline is NOT frozen or bypassed: slower inference fails qualification, even if its textual answer looks correct. No accessibility action is executed. Synthetic node metadata is fixture input, not mocked model output.

Limits: color/label/READY checks are weak instruction/semantic proxies, not vision accuracy measurements. Grounding is one simple fixture, not a broad benchmark. The master-gate test covers loaded-runtime rejection plus idle cancellation only; active JNI cancellation acknowledgement and no-post-cancel action proof remain PENDING. Fifty real preprocessing cycles cover JPEG decoding, duplicate/change detection, ownership/recycle and coordinate identity, NOT thermal stability or memory-leak proof. Native UI execution, screen capture consent, offline packet proof, sustained thermals and the 100-task corpus remain PENDING under the earlier protocol.
