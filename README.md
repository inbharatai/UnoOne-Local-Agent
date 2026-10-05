# UnoOne V3

**Local Android agent · native-owned control · development checkpoint**

UnoOne combines local speech, deterministic Android actions and bounded on-device model proposals. Native code—not the model or a webpage—owns authorization, cancellation and result verification.

> **Development build for phone testing — not device-qualified.** Version **0.5.0-alpha-v3** passes the recorded host gates and includes the real Android arm64 Qwen/MNN backend. It is not a production release or a claim that every Android workflow works. Physical speech, real-app, thermal, battery and cancellation qualification must still be performed on the phone. Record your exact checkout with `git rev-parse HEAD`.

### Limits first

- **No physical tests in this repair checkpoint. All 100 device tasks remain pending**, including the 50-task sustained thermal/battery subset. No measured speech accuracy, native Stop latency or real-app completion rate is established.
- **Reviewed local image analysis is reachable from Settings and uses the selected model.** Each capture needs review and opt-in; known secrets are denied/masked and unknown content carries a warning. Output is advice only, never arbitrary visual clicking; Android inference remains pending qualification.
- **Skills V2 native workflow import/review/export/run UI is reachable.** Candidate updates need trusted fixtures; manual learn recording is unavailable. General multi-app workflows and deployed partner UnoBridge remain incomplete. **Qwen3.5-2B MNN is compiled and integrated, opt-in; Android inference is PENDING.** GUI-Owl and AppFunctions remain experiment/unavailable routes.
- Browser fixtures do not establish arbitrary website support. Accepted dispatch and page-provided claims are not independently verified task completion; file/rich controls may require human takeover.
- Playback-time voice Stop is **conditional on OS AEC being available and actually enabled**. This is not measured echo suppression. UI Stop remains the fallback.
- This is a **pure-local CPU candidate**, with no cloud inference fallback and no NPU/GPU performance claim. Hardware, model, backend and build qualification remain separate requirements.

[Repair report](docs/UNOONE_V3_REPAIR_REPORT.md) · [Development status](docs/UNOONE_V3_DEVELOPMENT_STATUS.md) · [Architecture](docs/UNOONE_V3_ARCHITECTURE.md) · [Xiaomi qualification](docs/UNOONE_V3_XIAOMI_TEST.md)

## 1. Control boundaries

![Conceptual architecture, not hardware or live telemetry: native ownership gates observations and proposals through validation, confirmation, dispatch and native postconditions. Browser JavaScript is DOM-only. Reviewed image advice, Skills V2 UI and opt-in Qwen are integrated source paths; device qualification and trusted update fixtures remain pending. GUI-Owl/AppFunctions remain experimental or unavailable.](docs/images/v3-native-control.svg)

**Text equivalent:** explicit user command → native scope/goal → fresh immutable observation → deterministic proposal or one local model proposal → strict typed validation → native policy and bound confirmation → guarded dispatch → fresh observation → native postcondition. Stop enters before the busy lock and invalidates execution ownership. Screen and webpage text are untrusted data, never authority.

The browser is a separate lane: native code owns planning and confirmation; its JavaScript bundle is an **unprivileged DOM adapter**, with no page-visible model or consent bridge. Native task, document and target checks apply. Neither a model's “Done” nor an accepted dispatch proves success.

| Source boundary | Wired source scope | Do not infer |
|---|---|---|
| `core/device` + `accessibilitycontrol` | Immutable observations, typed actions, epochs, native predicates, guarded Accessibility actions | Universal GUI control or device qualification |
| `app` + `phonecontrol` | Native command ownership, scoped goals, installed-app resolution, explicit local diagnostics | Arbitrary multi-app completion or automatic screenshot admission |
| `localbrain` + `modelmanager` | Shared local engine, pinned profiles, integrity checks, non-destructive selection | Qualified CPU vision or GPU/NPU acceleration |
| `securebrowser` + Page Agent | Native-owned controller and DOM-only adapter | Website authority over consent or generic verified task success |
| `voice` | Local speech, microphone/call gates, priority Stop, guarded playback | Perfect recognition, universal barge-in or measured latency |
| `skills` | Skills V2 native import/review/export/run UI and contracts | Trusted candidate-update fixtures or manual learn recording |

Current native goals are narrow: installed-app open, current-app **ClickSearch / ReadScreen / Scroll / Back**, bounded name-find and reviewed draft intents. These are bounded native actions, not arbitrary visual clicks or “all apps work perfectly.” Unknown, ambiguous or unreviewed targets hand over. See [controller contracts](docs/UNOONE_V3_CONTROLLER.md), [perception](docs/UNOONE_V3_PERCEPTION.md) and [safety](docs/UNOONE_V3_SAFETY.md).

## 2. Speech and Stop

![Conceptual speech and Stop flow: call and microphone ownership checks gate capture. During TTS, only available and enabled AEC permits wake-qualified exact Stop or Cancel. Otherwise capture stops and buffers are discarded; UI Stop remains available. Native priority Stop revokes ownership and cancels work. Physical qualification is pending.](docs/images/v3-speech-stop.svg)

**Text equivalent:** verified local speech assets and signed-PCM endpointing → call/microphone ownership checks → ordinary local recognition and guarded routing. Non-speaking inference has stop-only monitoring. During TTS, only wake-qualified exact Stop/Cancel is admitted when OS AEC is available **and enabled**; otherwise capture stops, buffers are discarded and UI Stop is the fallback. Approvals, compound commands and ordinary commands are not admitted by this exception.

Priority native Stop bypasses the busy lock, revokes epochs and queued handoff generations, denies pending confirmation and cancels/drains work. Source repairs cover endpoint arithmetic, verified speech initialization, playback errors/timeouts, utterance ownership and cleanup. **Host tests do not prove audible playback, microphone recognition, echo safety or JNI cancellation timing.**

English/Hindi/Hinglish human-audio qualification, names/recipient accuracy, wake false accepts and playback-time Stop still require device measurements. See [speech qualification](docs/UNOONE_V3_SPEECH_QUALIFICATION.md).

## 3. Evidence and model progression

![Conceptual evidence and migration: current host tests and APK assembly are recorded, while 100 device tasks and release gates remain pending. New or unknown selection defaults to E2B; existing verified E4B files and persisted selection are preserved. No automatic deletion or cloud fallback.](docs/images/v3-evidence-migration.svg)

**Text equivalent:** passing host checks establish a source checkpoint, not a device release. Real host Qwen toy probes observed text `4`, JSON sum `4`, and image `red`; these are not accuracy benchmarks or Android runs. Android model behavior, speech, 100 device tasks and sustained thermal/battery gates remain pending. The final host gate passed; exact results and artifact hashes are recorded below. Model selection is non-destructive: new/unknown → E2B; verified E4B and persisted E4B selection → preserve. Release/main delivery follows agreed qualification gates, not APK assembly alone.

### Recorded development delivery: `0.5.0-alpha-v3`

| Evidence | Recorded result | Boundary |
|---|---|---|
| Final Android gate | **Exit 0** | All JVM tests, app lint, debug APK and Android-test APK assembly |
| JVM XML totals | **770 tests; 0 failures, 0 errors, 0 skips** | Host tests, not instrumented device execution |
| Browser runtime | Typecheck; **10 unit tests; 15 Playwright fixtures passed** | Real DOM bundle on host Chromium, not Android/model task qualification |
| App lint | No reported errors or warnings | **15 historical errors suppressed by the existing baseline** |
| Qwen/MNN | Android arm64 native compile/link; real host text, JSON and image executions | Not Android inference or an accuracy benchmark |
| Production JNI harness | Real outputs, cancellation admission, budgets, close/reload checked | Includes a preserved failed image case and its verified preprocessing correction |
| APK | Signature verified; MNN, JNI, C++ runtime and DOM asset inspected | Debug signer; package assembly is not device testing |
| Physical qualification | **Not run; 100 tasks pending** | No real-phone speech, thermal, battery or full workflow success claim |

Receipts: [Gradle command](docs/evidence/phone-delivery/gradle.command.txt), [build log](docs/evidence/phone-delivery/gradle.log.txt), [exit](docs/evidence/phone-delivery/gradle.exit.txt), [results](docs/evidence/phone-delivery/results.json), [build-input hashes](docs/evidence/phone-delivery/build-input-sha256.json), [Playwright](docs/evidence/phone-delivery/playwright.log.txt), [real host model smoke](docs/evidence/qwen-host/SUMMARY.md), and [production JNI evidence](docs/evidence/qwen-jni/SUMMARY.md).

Debug APK: **407,940,115 bytes**; SHA-256:

```text
e5946267b4fc7907d09851c5853252b571795a0abf2f4418df7f91a0ffef440b
```

[APK checksums](docs/evidence/phone-delivery/SHA256SUMS) and [debug signer](docs/evidence/phone-delivery/apk-signature.txt) identify the tested artifacts. APK/model binaries are not stored in Git; build from this checkout or use the matching delivered APK. Never uninstall or clear working data to bypass a signature mismatch.

The [earlier repair4 checkpoint](docs/evidence/repair4/test-results.json) remains historical. Later source changes require new validation; neither old nor current host receipts prove phone performance. The [deep review](docs/UNOONE_V3_DEEP_REVIEW.md) preserves earlier failures rather than relabelling them as passes.

### Model policy

| | New / unknown selection | Retained selection |
|---|---|---|
| Profile | Gemma 4 E2B | Gemma 4 E4B |
| Pinned file | `gemma-4-E2B-it.litertlm` | `gemma-4-E4B-it.litertlm` |
| Exact bytes | 2,588,147,712 | 3,659,530,240 |
| Integration | Shared runtime; reviewed image-advice path integrated | Retained text planning/chat; adapter vision unavailable |
| Migration | Default for new/unknown selections | Keep verified files and persisted E4B selection until explicit selection change |

Both require manifest-bound exact filename, size and SHA-256 verification. No arbitrary model-file discovery, automatic old-model removal or silent cloud fallback is introduced. LiteRT-LM is pinned to **0.13.1**. The **2,048-token mobile context cap** and inherited RAM policy are configuration, not measured hardware requirements. CPU image execution requires physical qualification; no GPU/NPU speed or battery claims are made.

Pinned revisions/hashes and provenance: [model strategy](docs/UNOONE_V3_MODEL_STRATEGY.md). Preservation rules: [migration/rollback](docs/UNOONE_V3_MIGRATION_ROLLBACK.md). **Qwen is an explicit experimental choice**, not an automatic migration: compiled pinned MNN integrates with LocalBrain and the browser. Its complete **9-artifact set totals 1,386,691,327 bytes**; CPU **2 threads**, context **2,048**, output cap **256**. Android inference is pending. See the [Qwen artifact contract](docs/UNOONE_V3_QWEN35_MNN.md) and [phone setup](docs/UNOONE_V3_PHONE_SETUP.md). Raw model weights stay outside Git.

Sources: [Qwen export pin](https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/tree/35781816d7b6a9dcb273a6765ac9563401951c3c), [base model/license](https://huggingface.co/Qwen/Qwen3.5-2B), [MNN revision](https://github.com/alibaba/MNN/tree/024a946b0b8fcf87c8a418229fadd4cd7858ffba), [MNN Apache-2.0 license](https://github.com/alibaba/MNN/blob/024a946b0b8fcf87c8a418229fadd4cd7858ffba/LICENSE), and [packaged native license notices](android-app/UnoOneAgent/localbrain/src/main/cpp/licenses/).

## 4. Build and install a local debug candidate

### Prerequisites

- **JDK 17** (`JAVA_HOME` set), **Android SDK platform 35**, build tools/platform tools and accepted SDK licenses. Set `ANDROID_HOME` or configure `android-app/UnoOneAgent/local.properties` with `sdk.dir`.
- **NDK 27.2.12479018 and CMake 3.22.1**. CMake FetchContent downloads the hash-pinned MNN source archive; first build requires network access. Qwen JNI is **arm64-v8a only**; a base APK install on another ABI is not Qwen compatibility.
- **Node.js 22.12+** and npm for the locked browser build; Python 3 for repository checks.
- Network access for initial dependencies, Playwright Chromium and separately provisioned models. Local inference does not make first-time acquisition offline.
- Start each recipe at the **repository root**. Build the DOM adapter **before Gradle**: Android rejects a missing or obsolete privileged-runtime asset.

For comprehensive Windows Git Bash + PowerShell commands, same-certificate update precautions, permissions and actual Settings controls, use [the phone setup guide](docs/UNOONE_V3_PHONE_SETUP.md).

### Linux / Bash

```bash
# From repository root; stop if a check fails.
set -e
python3 scripts/ci/check_repo_invariants.py

cd web-runtime/page-agent-unoone
npm ci
npm run typecheck
npm test
npx playwright install --with-deps chromium
npm run test:e2e
npm run bundle:android

cd ../../android-app/UnoOneAgent
bash ./gradlew --no-daemon --no-parallel --max-workers=2 \
  clean testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleDebugAndroidTest

# Optional: authorized development phone; this is not qualification.
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Windows / PowerShell

```powershell
# From repository root. Use Python 3's python, not the Store python3 alias.
function Check-Exit {
    if ($LASTEXITCODE -ne 0) { throw "Command failed: $LASTEXITCODE" }
}
python scripts/ci/check_repo_invariants.py; Check-Exit

Set-Location web-runtime/page-agent-unoone
npm ci; Check-Exit
npm run typecheck; Check-Exit
npm test; Check-Exit
npx playwright install chromium; Check-Exit
npm run test:e2e; Check-Exit
npm run bundle:android; Check-Exit

Set-Location ../../android-app/UnoOneAgent
.\gradlew.bat --no-daemon --no-parallel --max-workers=2 `
  clean testDebugUnitTest :app:lintDebug `
  :app:assembleDebug :app:assembleDebugAndroidTest
Check-Exit

# Optional: authorized development phone; this is not qualification.
adb install -r app/build/outputs/apk/debug/app-debug.apk
Check-Exit
```

Exact resource-constrained checkpoint flags are in [repair4.command](docs/evidence/repair4/repair4.command). Installing the debug APK is not model provisioning or validation. Follow the [Xiaomi procedure](docs/UNOONE_V3_XIAOMI_TEST.md) for model integrity, permissions, backend recording, airplane-mode checks and device gates. Review [migration/rollback](docs/UNOONE_V3_MIGRATION_ROLLBACK.md) before overwriting a valuable installation.

## 5. Repository map and reading order

| Path | Purpose |
|---|---|
| [`android-app/UnoOneAgent/`](android-app/UnoOneAgent/) | Modular Kotlin/Compose Android application; API 28+ |
| [`web-runtime/page-agent-unoone/`](web-runtime/page-agent-unoone/) | DOM adapter, TypeScript checks and browser fixtures |
| [`evaluation/`](evaluation/) | Qualification corpus and scorers; authored cases are not passes |
| [`docs/evidence/repair4/`](docs/evidence/repair4/) | Checkpoint commands, logs, hashes and pending benchmarks |
| [`docs/`](docs/) | Architecture, safety, repair reports and device procedures |

Read in order: [repair report](docs/UNOONE_V3_REPAIR_REPORT.md) → [architecture](docs/UNOONE_V3_ARCHITECTURE.md) → [model strategy](docs/UNOONE_V3_MODEL_STRATEGY.md) → [safety](docs/UNOONE_V3_SAFETY.md) → [benchmarks](docs/UNOONE_V3_BENCHMARKS.md) → [Xiaomi procedure](docs/UNOONE_V3_XIAOMI_TEST.md).

### Historical evidence—not V3 qualification

Existing records remain untouched: [V2 validation, July 16](docs/DEVICE_VALIDATION_2026-07-16.md), [July 17](docs/DEVICE_VALIDATION_2026-07-17.md), [E4B hardening audit](docs/E4B_RUNTIME_HARDENING_AUDIT.md), [E4B Xiaomi handoff](docs/E4B_XIAOMI14_HANDOFF.md), [device verification](DEVICE_VERIFICATION.md) and [V3 baseline audit](docs/UNOONE_V3_BASELINE_AUDIT.md). Historical E4B CPU/planner results apply only to their recorded artifact/device/build; they do not certify this uncommitted V3 worktree.

All three diagrams are **conceptual technical diagrams**, not hardware photographs, screenshots or live telemetry. Solid boundaries describe current wired source or recorded evidence; dashed boundaries describe incomplete/unavailable features or pending progression. Text equivalents carry the same limitations without relying on the images.
