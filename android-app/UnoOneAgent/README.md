# UnoOneAgent Android — V3 development alpha

Current Android build and runtime guidance. **V3 is not device-qualified or release-qualified.** V3 is a development label, not a newly assigned APK version; build configuration retains its existing alpha version. See the repository [README](../../README.md) and [model strategy](../../docs/UNOONE_V3_MODEL_STRATEGY.md).

## Current source boundaries

- Android API 28+, JDK 17, Android SDK 35; AGP 8.10.0, Gradle 8.11.1 and Kotlin 2.2.21.
- Gemma 4 E2B is the default local planning profile; E4B remains selectable/recoverable. Persisted E4B selection is not silently migrated.
- Qwen3.5-2B is an explicit opt-in: compiled pinned MNN runtime is integrated with LocalBrain and browser selection. Nine artifacts total **1,386,691,327 bytes**, CPU 2 threads, context 2,048, max output 256. Real host toy probes observed text `4`, JSON sum `4`, image `red`; Android device inference is **PENDING**.
- Skills → Reviewed native workflows (Skills V2) exposes import/review/export/run. Candidate updates require trusted fixtures; manual learn recording is unavailable.
- Current-app ClickSearch/ReadScreen/Scroll/Back, bounded name-find and reviewed draft intents are supported source paths, not universal app automation.
- Deterministic parsing precedes local inference. Canonical tool validation and native permission/safety policy precede execution. A schema-valid call does not prove recipient correctness or task success.
- LiteRT-LM uses manual tool calling; `AUTO` retains the conservative CPU backend. Other backend choices require device qualification.
- English/Hindi offline speech, Accessibility/OCR, independent CameraX/MediaPipe Blind Aid and document tooling are implemented; this does not establish held-out speech accuracy or physical task success.

The upstream E4B artifact is multimodal. **E4B image input is disabled in UnoOne's current runtime configuration**, not absent from that artifact. Reviewed per-capture local image analysis is now reachable through Settings → Developer (opt-in) → Open runtime and screen diagnostics and uses the selected model. Known secrets are denied/masked; unknown content still needs review and carries a warning. Output is advice only, not arbitrary visual clicks. Do not describe this as qualified multimodal phone control or Gemma audio-native inference.

## Modules and model storage

Modules: `app`, `core`, `storage`, `modelmanager`, `languagepacks`, `localbrain`, `voice`, `agentrouter`, `safetyguard`, `phonecontrol`, `memory`, `skills`, `observability`, `accessibilitycontrol`, `securebrowser`.

Models use app-private storage; all-files access is not required. Profile paths under `models/`:

```text
brain/gemma-4-e2b/gemma-4-E2B-it.litertlm
brain/gemma-4-e4b/gemma-4-E4B-it.litertlm
```

Speech packs live under `speech/shared/` and `speech/languages/`; independent Blind Aid assets under `vision/blind-aid/`. Select/install the desired profile through **Model Status & Install**. Installation uses the declared artifact and staging, not the largest file found on disk. Do not delete E2B as obsolete: it is the current default.

The registry and model manifest define IDs, pinned files and integrity checks. Byte integrity, load success, strict self-test and physical qualification are separate states. Both profiles use an 8,192 MB minimum RAM policy, 12,288 MB recommendation and 2,048-token default context: configured policy, not measured minimum working memory. See [model strategy](../../docs/UNOONE_V3_MODEL_STRATEGY.md) for provenance and pending image/grounding qualification.

## Speech and mode boundaries

**Reply voice** selects English/Hindi TTS output rather than reloading STT. Microphone ownership, TTS suppression and OS AEC checks are implementation controls, not measured echo cancellation or interruption performance. Human audio collection/export and held-out evaluation remain pending; see [speech qualification](../../docs/UNOONE_V3_SPEECH_QUALIFICATION.md).

Blind Aid is independent of Gemma and can suspend planning to reduce memory contention. Profile-specific unload/recovery, cancellation and thermal behavior need physical evidence. Security-level settings alter native policy; prototype security-off behavior is not a production safety guarantee.

## Secure Browser model lease

The browser chooses the previously loaded phone profile, otherwise the selected profile, otherwise default E2B. The native lease flow reserves process-wide ownership, verifies the chosen artifact, unloads the phone planner and requires close acknowledgement before browser allocation. It prevents competing phone recovery allocation, closes the browser planner on release and restores the previous phone profile when required and permitted by close/residency state.

This is profile-aware, not an E4B-only lease. Coordination source and fake-engine tests do not prove JNI drainage or physical single residency; failure/quarantine behavior still needs device qualification.

The website-facing adapter is DOM-only; the privileged planning loop is native-owned. The former website-facing privileged bridge/session/nonce description is historical, not current architecture. Native validation and security policy govern actions; credentials, OTPs, CAPTCHA, payments and legal acceptance require special handling/manual takeover under applicable policy. Prototype security-off settings are not evidence of production safety.

## Build instructions — commands, not current pass receipts

Start each block at the **repository root**. Install JDK 17, Android SDK 35, **NDK 27.2.12479018**, **CMake 3.22.1** and Node.js 22.12+ and configure the SDK. CMake FetchContent downloads hash-pinned MNN revision `024a946b0b8fcf87c8a418229fadd4cd7858ffba`. Qwen JNI builds only for **arm64-v8a**; do not claim 32-bit Qwen support. Raw model weights are not committed. See the [paste-ready Windows phone setup](../../docs/UNOONE_V3_PHONE_SETUP.md), [model artifact pin/license](../../docs/UNOONE_V3_QWEN35_MNN.md), and [native notices](localbrain/src/main/cpp/licenses/). Physical hardware is required for runtime, speech, Accessibility, background voice and camera qualification.

Build/copy the browser asset first:

```bash
cd web-runtime/page-agent-unoone
npm ci --no-audit --no-fund
npm run typecheck
npm test
npm run bundle:android
# Browser fixtures only; not physical Android/model task evidence:
npx playwright install chromium
npm run test:e2e
```

Generated asset: `android-app/UnoOneAgent/securebrowser/src/main/assets/page-agent/unoone-page-agent.js` relative to repository root.

From repository root, run Android host gates:

```bash
cd android-app/UnoOneAgent
./gradlew clean testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
```

Android-test APK assembly is not instrumentation execution. The archived **repair4** receipt records a successful host gate for an earlier frozen snapshot (717 JVM tests). Source has changed since that receipt: it does **not** validate this edited worktree. Latest integrated `qwen2` gates are still running; no final pass is claimed. A future main commit/push is planned, with no invented commit ID. See [repair report](../../docs/UNOONE_V3_REPAIR_REPORT.md); bind new receipts to the exact final source before making fresh pass claims.

## Install and collect evidence

From repository root after building:

```bash
adb install -r android-app/UnoOneAgent/app/build/outputs/apk/debug/app-debug.apk
```

If streamed installation is blocked:

```bash
adb push android-app/UnoOneAgent/app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/unoone-debug.apk
adb shell pm install -r /data/local/tmp/unoone-debug.apk
```

HyperOS may disable Accessibility after updates; verify/re-enable it before cross-app tests. Record device/OS/build identity, profile/hash/backend, latency, memory, thermal/battery behavior, cancellation/unload acknowledgement, external-app verification, browser lease/restoration and voice/recovery behavior. Failures remain failures; unexecuted tasks remain pending. Follow the [V3 benchmark plan](../../docs/UNOONE_V3_BENCHMARKS.md): the 100-task matrix is pending, not an achieved score.

## Historical evidence — archived references, not current qualification

These existing records are retained unchanged and must be read within their original source/build/device boundaries:

- [E4B Xiaomi 14 handoff](../../docs/E4B_XIAOMI14_HANDOFF.md).
- [Device verification history](../../DEVICE_VERIFICATION.md), including historical E2B records.
- [Repair report](../../docs/UNOONE_V3_REPAIR_REPORT.md) and archived repair4 host receipts.

Historical Xiaomi 14 E4B integrity/load and 43/43 planner cases do not establish V3 held-out accuracy, current E2B qualification, sustained browser performance or release readiness. Historical E2B evidence likewise does not qualify the current selectable E2B path. Old E2B-removal migration instructions are not current V3 installation guidance.

## Qualification and release holds

Keep V3 unqualified until final-source host validation and actual profile/device testing are recorded: strict self-test, reviewed screenshot-input execution on device, sustained cancellation/memory/thermal/offline operation, browser tasks and human speech evaluation. Release also requires current CI, licence/notice review, production storage/catalogue signing, signed APK/checksum publication and security/privacy/dependency review. Historical host receipts and artifact capability claims do not substitute for these gates.
