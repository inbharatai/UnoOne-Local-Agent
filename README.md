# UnoOne V3

**Local Android agent · native-owned control · development checkpoint**

UnoOne combines local speech, deterministic Android actions and bounded on-device model proposals. Native code—not the model or a webpage—owns authorization, cancellation and result verification.

> **Current development build: 0.8.1-alpha-voice / versionCode 9 — conservative cleanup, local gates passed; physical qualification PENDING.** **1006 JVM tests passed with zero failures/errors/skips**, lint and debug/instrumentation APK assembly passed. Removed verified unused private code/imports, one redundant direct dependency declaration and redundant disabled-detector baseline entries; working features and evidence remain. [Current receipts](docs/evidence/cleanup-delivery/results.json) · [Cleanup scope](docs/REPOSITORY_CLEANUP.md) · [Floating voice guide](docs/FLOATING_VOICE_GUIDE.md). This is not unrestricted phone autonomy or a measured speed/size gain.
>
> **Historical 0.7 Owl receipts, not current 0.8 gates:** 884 JVM tests passed with zero failures/errors/skips; lint and both APK assemblies passed. Owl native dependency closure and 16KB ELF/ZIP alignment were inspected. One pre-existing CameraX image utility library remains non-16KB ELF-compatible; this is not whole-app 16KB-device certification. [Historical receipts](docs/evidence/owl-delivery/results.json). Do not substitute these historical totals or artifacts for the current 0.8 receipts.

## Floating voice — supported scope first

- Say `open Settings`, `read screen`, `scroll down in Settings`, or `focus "Search" in Settings`; exact supported grammar and eligible native widgets only. App names can be omitted with fresh frozen **CurrentUnderlying** evidence. Voice labels match **case-insensitive UNIQUE**, values stay exact; ambiguity asks rather than guesses. Exact parser-owned global Home/Recents/Notifications commands retain native dispatch (**DISPATCH/UNVERIFIED**, not verified completion); Back/Scroll retain frozen app scope. Greeting/mic-check and language-change native shortcuts remain available.
- Say `Use Owl to focus "Search" in Settings` for explicit known scope, then review the actual floating panel/spoken scope and say exactly `confirm` or use its bound button. Separate capture consent and model prerequisites apply. **No native-failure-to-Owl fallback, unknown Owl chat or general open-ended task completion.**
- Self-hiding registered overlays requires drained capture and an available native Stop notification; stale restore cannot reopen old chat. Own bubble/chat metadata uses exact registered window IDs from public accessibility nodes; only narrowly proven structural OS bars are tolerated for source metadata. Screenshot foreign-pixel and known-widget checks remain strict—not all-app image control.
- Foreground capture retires before awaiting task results, permitting a new spoken confirmation capture without replacing the original source/generation. Whole-utterance MAX_DURATION cutoff rejects ordinary commands and approvals; exact Stop keeps priority. Unique microphone ownership handles and passive-yield coordination do not establish physical audio qualification. Native/Owl busy periods retain conditional stop-only monitoring, not general voice-queue or conversational multitasking. Playback Stop remains conditional on available/enabled AEC; no ordinary-command TTS barge-in. Spoken cue is default; Visual/Haptic is opt-in with touch-exploration spoken fallback.
- Final speech ownership repair closes the reviewed stale-completion race: an old playback completion after disable/clear/re-enable cannot release a new playback owner. This is source-level closure, not physical audio qualification. Default capture uses Sherpa PCM; opt-in public Android STT fallback uses only the API 31+ on-device recognizer when available, with no cloud/provider-dependent fallback. Unsupported devices require local speech models.
- Settings → **Latency diagnostics and listening cue** opens **Latency diagnostics**: **Collect timing metadata**, **Clear**, **Refresh**, **Export JSON…**, and **Spoken (default)** / **Visual / haptic (where supported)**. Local timing diagnostics are OFF by default; disabled/null-token recorder paths do not read the diagnostic clock. They collect opt-in metadata with explicit document-picker export: no raw text/audio/screens. TTFT/first-audible onset/native phase timings are not established; playback is a wait proxy. Sample-counted p95 needs at least 20 successful samples and remains pilot data. ASR 850 ms/KWS 500 ms, weights, threads and native backend are unchanged.
- Real host compact-prompt A/B **failed format validity**: V1 both cases, V2 focus 1/4. Both candidates are **rejected for production and moved to evaluation outside production source sets (clean final APK DEX inspection confirms both candidates absent)**; faster invalid returns are not a usable speedup. [Raw negative evidence and hashes](docs/evidence/floating-voice-prompt/README.md). No phone performance or whole-phone perfection claim.

See [the guide](docs/FLOATING_VOICE_GUIDE.md) for retained notes/drafts/calendar/recording/share/dialer/skill rules, safety gates, precise grammar, consent, accessibility and evidence boundaries.

## GUI-Owl 1.5 4B — opt-in, bounded screen tasks

**[Phone setup, exact artifact pins, consent walkthrough and evidence limitations](docs/GUI_OWL_4B_PHONE_SETUP.md).** Select via **Settings → Model Status & Install → Planning profile**; install the two-file bundle, verify SHA-256, then **Load Brain** / **Run Owl Self-Test**. Use **Tasks → GUI-Owl approved screen task** for explicitly approved native goals. Generic Owl chat, drafts, browser and general image advice are unsupported.

- **LLAMA_CPP CPU, Android arm64-v8a only**, llama.cpp pin `4f5406761517648c23dbd60ea5ade37f77a316c9`; real mtmd vision. Decoder Q4_K_M + Q8_0 projector total **2,951,256,544 bytes (~2.951 GB)** from third-party revision `9a79d301329062eb02e46f7ccad82c99075bfaaa`. Required exact hashes and immutable download links are in the guide; weights are outside Git/APK. Hugging Face is for downloads, not cloud inference.
- **Gemma E2B remains default**; existing E4B/Qwen profiles and user data remain. No automatic deletion or fallback.
- Android-reported total **≥8192 MiB**, available **≥5,098,740,192 bytes (~4.75 GiB)** and no low-memory flag: conservative policy, not measured peak. Marketed 8 GB may fail; 12 GB-class recommended for early testing, never guaranteed. Accessibility and separate MediaProjection/task consent are required.
- Quant source-conversion revision **UNKNOWN**; canonical reference `3f061c2c562cc860c42bf32542a70e07a7ff4840` is not proven conversion provenance. Cards say **MIT**, GGUF metadata **Apache-2.0**: unresolved discrepancy, verify before commercial use/redistribution; **no legal clearance**.
- Two real host synthetic GUI predictions landed inside independent Search rectangles after the UTF fix. **Two examples, not an accuracy percentage or phone proof.** The original reduced-context rejection and failed UTF admission remain preserved. The [post-fix real host rerun and all three preserved probes](docs/evidence/gui-owl-host/SUMMARY.md) record matching JNI/runtime/planner/prompt/codec source hashes at archival check: final GUI calls74.082s/71.878s, peak sampled host RSS3.1654GiB, not phone measurements. Unknown image/canvas controls, overlays and IME frequently yield NEEDS_USER; practice is a test surface, not real-world autonomy.


### Limits first

- **No physical tests in this repair checkpoint. All 100 device tasks remain pending**, including the 50-task sustained thermal/battery subset. No measured speech accuracy, native Stop latency or real-app completion rate is established.
- **Reviewed local image analysis is reachable from Settings for supported Gemma/Qwen profiles.** GUI-Owl instead uses its dedicated approved screen-task route; generic image advice is unsupported for Owl. Each capture needs review and opt-in; known secrets are denied/masked and unknown content carries a warning. Output is advice only, never arbitrary visual clicking; Android inference remains pending qualification.
- **Skills V2 native workflow import/review/export/run UI is reachable.** Candidate updates need trusted fixtures; manual learn recording is unavailable. General multi-app workflows and deployed partner UnoBridge remain incomplete. **Qwen3.5-2B MNN is compiled and integrated, opt-in; Android inference is PENDING.** GUI-Owl is the experimental bounded route described above; AppFunctions remains unavailable.
- Browser fixtures do not establish arbitrary website support. Accepted dispatch and page-provided claims are not independently verified task completion; file/rich controls may require human takeover.
- Playback-time voice Stop is **conditional on OS AEC being available and actually enabled**. This is not measured echo suppression. UI Stop remains the fallback.
- This is a **pure-local CPU candidate**, with no cloud inference fallback and no NPU/GPU performance claim. Hardware, model, backend and build qualification remain separate requirements.

[Current floating voice guide](docs/FLOATING_VOICE_GUIDE.md) · [Current receipts](docs/evidence/floating-voice-delivery/results.json) · [Architecture](docs/UNOONE_V3_ARCHITECTURE.md) · [Xiaomi qualification](docs/UNOONE_V3_XIAOMI_TEST.md) · [Historical repair report](docs/UNOONE_V3_REPAIR_REPORT.md) · [Historical development snapshots](docs/UNOONE_V3_DEVELOPMENT_STATUS.md)

## 1. Control boundaries

![Conceptual architecture, not hardware or live telemetry: native ownership gates observations and proposals through validation, confirmation, dispatch and native postconditions. Browser JavaScript is DOM-only. Reviewed image advice, Skills V2 UI and opt-in Qwen are integrated source paths; device qualification and trusted update fixtures remain pending. GUI-Owl is experimental; AppFunctions remains unavailable.](docs/images/v3-native-control.svg)

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

Current native goals include installed-app open, current-app **ReadScreen / Scroll / Back**, bounded name-find, and explicit quoted **write / focus / click / select tab** interactions with optional `in <installed app>` scope. App-qualified interactions do not implicitly launch apps: use an explicit OpenApp sequence. Only native-known fields, tabs and search semantics on accessible surfaces are eligible—not arbitrary custom buttons, canvases or visual clicks. Unknown/ambiguous targets and human takeover require `NEEDS_USER`; sensitive-action blocks are heuristic, not universal guarantees. See [controller contracts](docs/UNOONE_V3_CONTROLLER.md), [perception](docs/UNOONE_V3_PERCEPTION.md) and [safety](docs/UNOONE_V3_SAFETY.md).

## Bounded multitasking — new candidate capabilities

![Conceptual native task flow: explicit admission feeds one interactive and two non-UI slots. Model calls serialize; preparation has two bounded children with separate notes/draft outputs. Browser and Skills own leases and WAL but are not live Task Board worker rows. Dashed future/qualification boxes are not current evidence.](docs/images/v3-native-taskflow.svg)

**Text equivalent:** queue up to 32 waiting tasks with priority and aging; run one interactive worker and at most two non-UI workers, sharing a single UI owner and serialized model calls. Native scopes and isolated task outputs bound each task. A preparation parent delegates at most two children, depth one, with shared budgets. Local notes search and about-100-word text preparation produce **separate outputs**—the draft does not automatically use notes and never sends itself.

- **Tasks** exposes receipts, graph, role/priority, selected-task output, per-family cancellation and global Stop. Typed enqueue remains available while busy. Ordinary voice intake can add tasks only when ordinary capture/admission is available; busy-model monitoring is Stop-only, **not general voice-queue multitasking or TTS barge-in**; acoustic Stop qualification remains pending.
- Draft mode supports optional exact required phrases (up to 6 unique, 80 characters each / 256 combined), with at most one repair. Literal checks can yield RESPONDED, not fact verification; failed checks/model errors require NEEDS_USER.
- Durable bounded metadata supports recovery review, **not resume/replay/playback**. Clear history is explicit and idle-only. Full instructions are not in the new metadata journal; existing legacy logs/memory and saved UI state are separate—not an app-wide RAM-only claim. Browser/Skills retain independent leases and use the same verified metadata journal; do not infer unified live worker rows or coordinator family cancellation for all lanes.
- This is not parallel LLM inference, a background-service bot or multiple bot accounts. Safe native semantics are not arbitrary app control. The Qwen challenger retains real historical host evidence; Android inference remains unqualified.

Read [multitasking scope, commands and 10-case safe phone protocol](docs/UNOONE_MULTITASKING.md) and the [October 6 source audit, base `55c15`](docs/UNOONE_MULTITASK_AUDIT.md). Historical 0.6 full JVM gate: **842 passed, zero failures/errors/skips**. App lint and both APK assemblies passed; [matching receipts](docs/evidence/multitask-delivery/results.json). An earlier hosted CI attempt failed runner allocation; the successful 0.6 hosted CI rerun is historical only. Both hosted workflows passed for historical Owl commit b9beff5; the new voice commit needs its own hosted CI result.

The [original real-host multitask probe](docs/evidence/multitask-host/README.md) remains **FAILED** (B omitted its marker; JVM exit 1). The [fresh draft-gate probe](docs/evidence/multitask-draft-gate/README.md) passed **2/2 literal checks**, yielding RESPONDED, not factual verification. Both first candidates passed; repair/repeated-failure branches were unexercised in that run. Unsupported urgency remains in B's raw output. These are not a general accuracy percentage or phone claim; retain both runs without cherry-picking. Historical 0.6 host gates passed; that historical debug APK is **408,464,391 bytes**, SHA-256 `17aed5c11911333131acef55235a9f8673f539e4d002618e544019f5b7f005f0`. [Signed artifact/build receipts](docs/evidence/multitask-delivery/results.json) retain the evidence boundary.

## 2. Speech and Stop

![Conceptual speech and Stop flow: call and microphone ownership checks gate capture. During TTS, only available and enabled AEC permits wake-qualified exact Stop or Cancel. Otherwise capture stops and buffers are discarded; UI Stop remains available. Native priority Stop revokes ownership and cancels work. Physical qualification is pending.](docs/images/v3-speech-stop.svg)

**Text equivalent:** verified local speech assets and signed-PCM endpointing → call/microphone ownership checks → ordinary local recognition and guarded routing. Non-speaking inference has stop-only monitoring. During TTS, only wake-qualified exact Stop/Cancel is admitted when OS AEC is available **and enabled**; otherwise capture stops, buffers are discarded and UI Stop is the fallback. Approvals, compound commands and ordinary commands are not admitted by this exception.

Priority native Stop bypasses the busy lock, revokes epochs and queued handoff generations, denies pending confirmation and cancels/drains work. Source repairs cover endpoint arithmetic, verified speech initialization, playback errors/timeouts, utterance ownership and cleanup. Global Stop fan-out and BlindAid producer camera/detector acknowledgment barriers are implemented source with host gate tests; actual native/CameraX qualification remains pending. **Host tests do not prove audible playback, microphone recognition, echo safety or JNI cancellation timing.**

English/Hindi/Hinglish human-audio qualification, names/recipient accuracy, wake false accepts and playback-time Stop still require device measurements. See [speech qualification](docs/UNOONE_V3_SPEECH_QUALIFICATION.md).

## 3. Evidence and model progression

![Conceptual evidence and migration: current host tests and APK assembly are recorded, while 100 device tasks and release gates remain pending. New or unknown selection defaults to E2B; existing verified E4B files and persisted selection are preserved. No automatic deletion or cloud fallback.](docs/images/v3-evidence-migration.svg)

**Text equivalent:** passing host checks establish a source checkpoint, not a device release. Real host Qwen toy probes observed text `4`, JSON sum `4`, and image `red`; these are not accuracy benchmarks or Android runs. Android model behavior, speech, 100 device tasks and sustained thermal/battery gates remain pending. The historical 0.5 final host gate passed; its results and hashes are recorded below. Historical 0.6 host gates passed with 842 JVM tests and zero failures/errors/skips; physical gates remain pending. Model selection is non-destructive: new/unknown → E2B; verified E4B and persisted E4B selection → preserve. Release/main delivery follows agreed qualification gates, not APK assembly alone.

### Historical development delivery: `0.5.0-alpha-v3` — not current 0.8 voice gates

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

Historical 0.5 debug APK: **407,940,115 bytes**; SHA-256:

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
- **NDK 27.2.12479018 and CMake 3.22.1**. CMake FetchContent downloads hash-pinned MNN and llama.cpp source archives; first build requires network access. Qwen and Owl JNI are **arm64-v8a only**; a base APK install on another ABI is not runtime compatibility. The [current 0.8 receipts](docs/evidence/floating-voice-delivery/results.json) record inspected Owl native dependency closure, Owl 16KB ELF compatibility and passed 16KB ZIP alignment. The pre-existing CameraX `libimage_processing_util_jni.so` remains non-16KB ELF-compatible; this is not whole-app 16KB-device certification or physical qualification.
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
| [`docs/evidence/floating-voice-delivery/`](docs/evidence/floating-voice-delivery/) | Current 0.8 artifact/build receipts; not device qualification |
| [`docs/evidence/repair4/`](docs/evidence/repair4/) | Historical checkpoint commands, logs, hashes and pending benchmarks |
| [`docs/`](docs/) | Architecture, safety, repair reports and device procedures |

Read current material first: [floating voice guide](docs/FLOATING_VOICE_GUIDE.md) → [0.8 receipts](docs/evidence/floating-voice-delivery/results.json) → [architecture](docs/UNOONE_V3_ARCHITECTURE.md) → [phone setup](docs/UNOONE_V3_PHONE_SETUP.md) / [Owl setup](docs/GUI_OWL_4B_PHONE_SETUP.md) (use their current-entry banners, not historical APK identities) → [model strategy](docs/UNOONE_V3_MODEL_STRATEGY.md) → [safety](docs/UNOONE_V3_SAFETY.md) → [benchmarks](docs/UNOONE_V3_BENCHMARKS.md) → [Xiaomi procedure](docs/UNOONE_V3_XIAOMI_TEST.md). [Repository cleanup scope](docs/REPOSITORY_CLEANUP.md) explains preservation and validation boundaries.

Then consult the historical progression: July V2/E4B records below → [repair report](docs/UNOONE_V3_REPAIR_REPORT.md) / [development snapshots](docs/UNOONE_V3_DEVELOPMENT_STATUS.md) → [0.5 receipts](docs/evidence/phone-delivery/results.json) → [0.6 receipts](docs/evidence/multitask-delivery/results.json) → [0.7 receipts](docs/evidence/owl-delivery/results.json). Earlier passes and failures are not current-build or phone qualification.

### Historical evidence—not V3 qualification

Existing historical bodies remain preserved (some have added historical-navigation banners): [V2 validation, July 16](docs/DEVICE_VALIDATION_2026-07-16.md), [July 17](docs/DEVICE_VALIDATION_2026-07-17.md), [E4B hardening audit](docs/E4B_RUNTIME_HARDENING_AUDIT.md), [E4B Xiaomi handoff](docs/E4B_XIAOMI14_HANDOFF.md), [device verification](DEVICE_VERIFICATION.md) and [V3 baseline audit](docs/UNOONE_V3_BASELINE_AUDIT.md). Historical E4B CPU/planner results apply only to their recorded artifact/device/build; they do not certify the current V3/0.8 build.

All four diagrams are **conceptual technical diagrams**, not hardware photographs, screenshots or live telemetry. Solid boundaries describe current wired source or recorded evidence; dashed boundaries describe incomplete/unavailable features or pending progression. Text equivalents carry the same limitations without relying on the images.
