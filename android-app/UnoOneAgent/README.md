<div align="center">

# UnoOneAgent — Android Technical Implementation

<p align="center">
  <img src="https://img.shields.io/badge/AGP-8.10.0-1f6feb?style=for-the-badge" alt="AGP 8.10.0">
  <img src="https://img.shields.io/badge/Gradle-8.11.1-1f6feb?style=for-the-badge" alt="Gradle 8.11.1">
  <img src="https://img.shields.io/badge/Kotlin-2.2.21-7f52ff?style=for-the-badge" alt="Kotlin 2.2.21">
  <img src="https://img.shields.io/badge/Min%20SDK-28-success?style=for-the-badge" alt="Min SDK 28">
  <img src="https://img.shields.io/badge/Modules-13-0ea5e9?style=for-the-badge" alt="13 Modules">
  <img src="https://img.shields.io/badge/Voice-Sherpa--ONNX%20offline-0b7285?style=for-the-badge" alt="Sherpa-ONNX">
  <img src="https://img.shields.io/badge/Tests-289%20passing-22c55e?style=for-the-badge" alt="289 Tests">
</p>

</div>

> This document is implementation-aligned and intentionally avoids over-claims. It reflects the **current runtime state** of every subsystem after the production-hardening overhaul + A-to-Z review (2026-06-24).

---

## Quick Navigation

- [Implementation Snapshot](#implementation-snapshot)
- [Module Architecture](#module-architecture)
- [Shared VoiceModule Architecture](#shared-voicemodule-architecture)
- [Command Orchestrator Pipeline](#command-orchestrator-pipeline)
- [Model Manifest & Installer](#model-manifest--installer)
- [Blind Aid Vision Deep Dive](#blind-aid-vision-deep-dive)
- [Voice Runtime Behavior](#voice-runtime-behavior)
- [Local Brain (Gemma via LiteRT-LM)](#local-brain-gemma-via-litert-lm)
- [Agentic Loop, Safety Judge & Eval Harness](#agentic-loop-safety-judge--eval-harness)
- [Innovations #4–#8: Vision, Memory, Streaming, Self-Heal, Integrity](#innovations-48-vision-memory-streaming-self-heal-integrity)
- [Command Parser](#command-parser)
- [Safety Framework](#safety-framework)
- [Permissions](#permissions)
- [Accessibility Hardening](#accessibility-hardening)
- [Permissions and Hardware](#permissions-and-hardware)
- [Validation Commands](#validation-commands)
- [Known Integration Gaps](#known-integration-gaps)

---

## Implementation Snapshot

| Capability | Status | Notes |
|---|---|---|
| Compose app shell + overlay | ✅ Implemented | Main UI + floating chat bubble with mic permission handling |
| Orchestrator pipeline | ✅ Implemented | Parse → permission → safety → execute → verify → speak; shared `runValidatedToolCall` |
| Compound command execution | ✅ Implemented | `steps[]` JSON array (up to 3) with domain-safe guard, per-step safety checks |
| Skill safety routing | ✅ Implemented | Skill steps run through the same safety pipeline as normal commands |
| Blind Aid camera mode | ✅ Implemented & live | CameraX preview + analyzer feedback, foreground activation from background |
| Voice input APIs | ✅ Implemented | Unified start/stop transcribe, shared singleton instance |
| Offline Sherpa STT/TTS default | ✅ Implemented | Android speech demoted to opt-in emergency fallback (`allowSystemSttFallback`, default false) |
| Voice runtime state + offline chip | ✅ Implemented | `VoiceRuntimeState` {SHERPA, SYSTEM_FALLBACK, UNAVAILABLE} → OFFLINE/LIMITED/NO_MODEL chip |
| STT confidence + retry | ✅ Implemented | <0.6 confidence → "please repeat" + one retry |
| Background voice routing | ✅ End-to-end | VoiceService → in-app `SharedFlow` (`commandFlow`) → UnoOneApplication collector → orchestrator |
| Accessibility deep control | ✅ Implemented & hardened | All `AccessibilityNodeInfo` recycled in `try/finally`, suspend `findAndClick()` |
| Model manifest + installer | ✅ Implemented | Resume, SHA-256/size, corrupt-recovery, atomic commit, health, path-traversal guard |
| Permission registry | ✅ Single source of truth | `ToolPermissionRegistry`; corrected read_screen/ocr_screen/system_control gates |
| Tool coverage | ✅ Complete | Every `UnoOneToolSet` tool has a real `ActionExecutor` branch |
| New Settings screens | ✅ Implemented | Model Status, Voice Test, Audit Viewer — reachable from Settings |
| Gemma brain via LiteRT-LM (dual profile) | ✅ Implemented | `GemmaPlanner` is model-profile-aware: default **Gemma 3n E4B** (`gemma-3n-e4b`) + **Gemma 4 E2B** (`gemma-4-e2b`) Experimental opt-in. Manual tool calling, GPU→CPU fallback, Mutex load, inference Mutex + 30s timeout, onTrimMemory unload + onResume reload |
| ReAct agentic loop (#1) | ✅ Implemented (control JVM-tested) | After an LLM-planned observation-producing call, the model sees the tool result and plans the next step (bounded `MAX_STEPS=3`, every step through `runValidatedToolCall`). Pure control logic in `ReActLoopController` is JVM-tested; the LiteRT-LM multi-turn `planNext` is device-time-only (not yet device-verified) |
| LLM safety judge (#2) | ✅ Implemented (control JVM-tested) | A second on-device pass on a **dedicated judge conversation** (no tools, classifier prompt) returns a `SafetyVerdict` merged **escalate-only** with the keyword tier via `SafetyJudgePolicy` — never weakens the keyword block. Policy is JVM-tested; `judgeSafety` inference is device-time-only |
| Calibration / eval harness (#3) | ✅ Implemented (scoring JVM-tested) | Fixed `EvalPromptSet` (~18 cases incl. paraphrased harm) + pure-JVM `EvalScorer` (tool-match + arg-match → accuracy) + device `BrainEvalHarnessTest` runner that prints a real summary for Gemma 3n vs 4. Scoring is JVM-tested; the runner is device-time-only and **not yet run on a physical device** (no fabricated accuracy numbers) |
| Outcome-learned memory (#5) | ✅ Implemented (control JVM-tested) | Per-(command-signature, tool) outcomes are stored in Room and surfaced to the planner as a hint ("prior avoid: …", "prior worked: …"). Signature + token-overlap retrieval + rendering are pure-JVM in `OutcomeMemoryPolicy`; Room I/O is in `MemoryModule`. No schema migration (reuses the free-string `type` column) |
| Diagnostics self-heal (#7) | ✅ Implemented (control JVM-tested) | A rolling `ToolHealthTracker` flags a tool flaky after enough recent failures; the brain is auto-reloaded when found down (it self-closes on a 30s timeout). `BrainHealthPolicy.shouldReload` + the tracker are JVM-tested; the reload + timeline surfacing are device-time-only |
| Streaming inference (#6) | ✅ Implemented (control JVM-tested) | First-turn LLM planning can stream partial text to the timeline via LiteRT-LM `sendMessageAsync` (`Flow<Message>`). Delta reduction (`StreamingTextReducer`, robust to cumulative vs delta snapshots) is JVM-tested; the `Flow` collection + UI surfacing are device-time-only, with a try/catch fallback to the synchronous `plan()` path |
| Multimodal vision (#4) | ✅ Implemented (control JVM-tested) | New `describe_scene` tool (26th canonical, **STRONG_CONFIRM** + MediaProjection) builds a screen scene from OCR + foreground context via JVM-tested `SceneDescriptionBuilder`. The LiteRT-LM `Content.ImageBytes` vision path is wired against the real AAR but **INACTIVE** (shipped Gemma models are text-only); it lights up only with a vision-capable `.litertlm` artifact, falling back to the OCR description otherwise |
| Signed manifest integrity (#8) | ✅ Implemented (control JVM-tested) | `ModelManifest` carries an Ed25519 `manifestSignature`; `ManifestSignatureVerifier` canonicalizes + verifies (platform EdDSA, API 33+; minSdk 28 falls back to accept + log). `ManifestSigningKey.PUBLIC_KEY_BASE64` is intentionally blank → **wired but INACTIVE** until the publisher activates the chain. No fabricated keys; canonicalization + verify round-trip are JVM-tested |
| CanonicalToolRegistry (26 tools) | ✅ Implemented | Unknown-tool rejection + required-arg validation in `GemmaPlanner` before safety/execution; `CanonicalToolRegistryTest` + `ToolRegistryAgreementTest` |
| Brain selection UI + self-test | ✅ Implemented | Settings → Model Status → Brain Model: select profile + Run Self-Test (load + read-only tool-call probe), persisted in `unoone_settings` |
| Parser blind-aid fixes | ✅ Implemented & verified | Activation/deactivation disambiguation, negative-intent patterns |
| Unit tests | ✅ 289 passing across 29 files | See [Validation Commands](#validation-commands) |
| Safety framework | ✅ Implemented | 4-tier: DIRECT, CONFIRM, STRONG_CONFIRM, BLOCK (per-step for compounds & skills) |
| Lint | ✅ Clean | 0 new issues; 39 baselined staleness advisories; 0 StaticFieldLeak |

---

## Module Architecture

| Module | Primary responsibility | Core files |
|---|---|---|
| `:app` | app shell, orchestration, permissions, UI, Settings sub-screens, data export | `MainActivity.kt`, `AgentOrchestrator.kt`, `AgentScreen.kt`, `AgentViewModel.kt`, `FloatingAgentService.kt`, `execution/ActionExecutor.kt`, `safety/SafetyPipeline.kt`, `PermissionManager.kt`, `data/DataExporter.kt`, `ui/screens/{ModelStatus,VoiceTest,AuditViewer}Screen.kt` |
| `:core` | shared models, logging, single-source permission registry, summarizer | `Result.kt`, `ToolCall.kt`, `AgentStatus.kt`, `TimelineStep.kt`, `Logger.kt`, `safety/ToolPermissionRegistry.kt`, `safety/PermissionRequirement.kt`, `util/TextSummarizer.kt` |
| `:storage` | Room persistence layer | `UnoOneDatabase.kt`, DAOs, entities (`NoteDao` incl. `searchOnce`/`deleteByQuery`/`deleteAll`/`recent`, `ModelMetadataDao`) |
| `:modelmanager` | manifest, on-disk health, install/uninstall, discovery | `ModelManager.kt`, `ModelInstaller.kt`, `ModelManifest.kt`, `ModelManifestLoader.kt`, `assets/models_manifest.json` |
| `:localbrain` | parsing, Gemma/LiteRT-LM planner, manual tool calling, context | `RuleBasedParser.kt`, `GemmaPlanner.kt`, `LocalBrain.kt`, `UnoOneToolSet.kt`, `ContextSnapshot.kt`, `PromptBuilder.kt`, `RAGManager.kt` |
| `:voice` | recorder, Sherpa STT/TTS (default), Android fallback (opt-in), voice service, SharedFlow routing | `VoiceModule.kt`, `VoiceService.kt`, `stt/SherpaSttEngine.kt`, `tts/SherpaTtsEngine.kt`, `stt/KeywordSpotter.kt`, `recorder/AudioRecorder.kt` |
| `:agentrouter` | tool registry and plugin routing | `AgentRouter.kt` |
| `:safetyguard` | 4-tier risk classification policy | `SafetyGuard.kt` |
| `:phonecontrol` | intents, OCR, object detection, blind aid analyzer | `PhoneControl.kt`, `OcrControl.kt`, `BlindAidManager.kt` |
| `:memory` | preference/correction/pattern memory | `MemoryModule.kt` |
| `:skills` | skill CRUD and trigger execution | `SkillsModule.kt` |
| `:observability` | local diagnostics helper | `Diagnostics.kt` |
| `:accessibilitycontrol` | accessibility service wrappers | `UnoOneAccessibilityService.kt`, `AccessibilityControl.kt` |

---

## Shared VoiceModule Architecture

The application uses a **single shared `VoiceModule` instance** to prevent microphone resource conflicts and memory leaks:

```
UnoOneApplication.onCreate()
  ├── sharedVoiceModule = VoiceModule(this)        // Created once
  ├── orchestrator.setVoiceModule(sharedVoiceModule) // Injected into orchestrator
  └── SharedFlow collector uses orchestrator.voiceModule for speak()

AgentViewModel
  ├── Constructor receives sharedVoiceModule
  └── onCleared() does NOT release — application-scoped

FloatingAgentService
  ├── voiceModule = orchestrator.voiceModule         // Reuses shared instance
  └── onDestroy() does NOT release — application-scoped

VoiceService
  └── Owns separate STT/TTS/keyword engines (by design — different lifecycle)
```

This ensures that only one `VoiceModule` manages the microphone at any time across the foreground UI, background flow, and floating overlay.

---

## Command Orchestrator Pipeline

The `AgentOrchestrator` processes every command through 8 stages:

```
LISTENING → TRANSCRIBING → UNDERSTANDING → TOOL_SELECTED → SAFETY_CHECK → EXECUTING → VERIFYING → SPEAKING → DONE
```

### Shared safety pipeline (`runValidatedToolCall`)

A single private `suspend fun runValidatedToolCall(toolCall, sanitizedText, inputType, log)` runs the full pipeline — `checkPermissionsForTool` → `classifyRisk` → block/confirm → execute → audit — and is called from **three** places so none can bypass safety:

1. The normal command path
2. Each step of a compound command
3. Each step of a skill

### Compound command handling

When the parser returns `ToolCall("compound", { steps: [...] })`, the orchestrator:

1. Expands the compound into `List<ToolCall>` via `ToolCall.compoundSteps()` (each step is a `{tool, args}` object; up to 3 parts)
2. Runs `runValidatedToolCall` on each step independently (permissions + risk + confirm + execute + audit)
3. Accumulates results and speaks a combined summary

A compound like *"open chrome and delete all notes"* will execute `open_chrome` (DIRECT — no confirmation) and then prompt for strong confirmation on `delete_all_notes` (STRONG_CONFIRM — user must type "confirm").

### Confirmation timeout

`awaitConfirmation` wraps both the multicast and legacy paths in `withTimeoutOrNull(60_000L)` with an `AtomicBoolean` dedup on multicast resume. If the user never responds within 60s, the agent logs and releases the processing lock instead of hanging forever. The `NeedsSystemAccess`/`NeedsRuntimeAccess` branches stash `pendingCommand`/`pendingInputType` *before* releasing the lock (previously the user's command was dropped here).

### Background command dispatch

Commands received via `VoiceService` are processed identically to foreground commands:

1. `VoiceService` emits the transcribed command to the in-app `MutableSharedFlow` (`_commandFlow.tryEmit(command)`)
2. `UnoOneApplication` collects from `commandFlow` on `appScope`
3. Calls `orchestrator.processCommand(command, InputType.VOICE)`
4. If the command activates blind aid, `bringAppToForegroundIfNeeded()` launches `MainActivity` with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP`

No cross-app `Intent` broadcast is used — transcribed speech never leaves the app process or enters system logs.

---

## Model Manifest & Installer

`modelmanager` ships a real manifest + installer replacing loose folder detection.

**Manifest** (`src/main/assets/models_manifest.json`): array of model descriptors (`id, folder, type, version, minRamMb, backend, defaultLanguage, files[]`), each file `{name, url, sha256, sizeBytes, archive, asset?, extractsTo?}`. `asset` names a file bundled in the app's `assets/` (copied from the APK instead of downloaded); `extractsTo` names the top directory an archive extracts to (required for tarballs like `sherpa-onnx-whisper-tiny.tar.bz2` whose top dir differs from the archive name). Models: `gemma-3n-e4b` (default LLM, folder `gemma-local/` for migration continuity; a legacy `gemma-local` selection resolves to it), `gemma-4-e2b` (Experimental LLM, folder `gemma-4-e2b/`, 128K ctx, ~2.58 GB — **not device-verified**, `sha256`/`sizeBytes` empty until a real artifact is measured), `sherpa-asr-en` (English ASR), `sherpa-asr-whisper` (multilingual whisper ASR for hi/bn/ta/te/kn/ml), `vad` (English wake-word), `sherpa-tts-en` (English TTS), `sherpa-tts-hin/ben/tam/tel/kan/mal` (MMS TTS per Indic language), `punctuation`, `ocr-optional`.

**`ModelInstaller`** (plain `HttpURLConnection`, no deps):
- Resume via `Range:` header against `.part`; appends on 206, restarts on 200
- Atomic commit: `.part` → final rename (with copy fallback)
- SHA-256 + size verification when declared; **empty-file guard** (0-byte file with no declared integrity is not trusted valid → forces re-download)
- **Asset-backed files**: a file with an `asset` field is copied from the bundled APK `assets/`, verified, and (for archives) extracted — ships espeak-ng-data (~9 MB) inside the APK so offline English TTS installs with no network
- **Archive health**: archives are deleted after extraction, so health/install-skip verify the extracted directory (`<dir>.zip` → `<dir>/`) instead of the deleted zip
- Corrupt recovery: delete bad file + retry once
- HTTP 416 recovery: complete `.part` commits without network (no "Range Not Satisfiable" death loop)
- `conn.disconnect()` in `finally`; zip-slip guard on archive extraction; `parentFile.mkdirs()` safety for nested file names
- Idempotent skip of already-valid files (and already-extracted archives)

**`ModelManager`**:
- `loadManifest()` / `findModel(id)` / `modelHealth(id): HealthResult` (reports `missing`/`sizeMismatch`/`checksumMismatch`)
- `installModel(id, onProgress)` / `uninstallModel(id)` with a **canonical path-traversal guard** (folder must stay inside the models root)
- `detectModels()` merges manifest info into `ModelStatus` (version, expected vs actual size/checksum, `healthy`)
- `getLlmModelPath()` finds the first `.litertlm` for the default profile (Gemma 3n E4B, folder `gemma-local/`); `getLlmModelPath(spec)` finds it for any `BrainModelSpec`

**✅ Shipped models (verified, 7 languages):** `sherpa-asr-en` and `vad` share the public streaming-zipformer English int8 transducer (`csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26`) with verified SHA-256/size; STT uses Sherpa's **Online (streaming) recognizer** drained single-shot (`while (isReady) decode`) — the only public ungated English transducer on HF is the streaming one. `sherpa-tts-en` is the Coqui en-ljspeech VITS (`csukuangfj/vits-coqui-en-ljspeech`) plus **espeak-ng-data bundled as an app asset** (`espeak-ng-data.zip`, ~9 MB, extracted at install). For Hindi/Bengali/Tamil/Telugu/Kannada/Malayalam: `sherpa-asr-whisper` is a single shared multilingual **whisper-tiny int8** tarball (`k2-fsa/sherpa-onnx`, extracted via commons-compress to `sherpa-onnx-whisper-tiny/`, integrity verified against the tarball SHA-256; the `language` field selects the language at runtime) and `sherpa-tts-{hin,ben,tam,tel,kan,mal}` are per-language **MMS VITS** models (`willwade/mms-tts-multilingual-models-onnx`, character frontend, no espeak) — all with stream-computed SHA-256/size. `gemma-local`/`punctuation` carry real HF URLs but leave hashes empty (completeness still validated); `ocr-optional` has no URL. The wake-word (`vad`) stays English (no public Indic KWS model exists). The Model Status screen surfaces all of this in the UI; the active STT/TTS language is chosen in Settings → Voice language.

---

## Blind Aid Vision Deep Dive

### Command and state flow

1. `RuleBasedParser` maps 6 activation phrases to `detect_objects`
2. `RuleBasedParser` maps 8 deactivation phrases to `deactivate_blind_aid` (negative-intent: stop, remove, turn off, deactivate, disable, no more)
3. `AgentOrchestrator.setBlindAidActive(true)` speaks confirmation and calls `bringAppToForegroundIfNeeded()`
4. `AgentScreen` reacts to `isBlindAidActive` StateFlow, mounting `BlindAidCameraPreview`

### Camera pipeline

`BlindAidCameraPreview`:

- Camera binding in `AndroidView(factory=...)` — one-time setup, no rebind on recomposition
- `ProcessCameraProvider` lifecycle-bound to `LocalLifecycleOwner`
- Binds `Preview` + `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST`
- Unbinds camera providers in `DisposableEffect.onDispose`
- Releases `BlindAidManager` on dispose

### Analyzer behavior

`BlindAidManager`:

- Throttles to 1 in 6 frames (~5 FPS at 30 FPS input)
- ML Kit object detection in single-image mode
- Attempts local custom model from `Android/data/com.unoone.agent/files/models/gemma-local/custom_yolov8.tflite`
- Falls back to default ML Kit detector when custom model absent
- Obstacle proximity from fill ratio
- Three feedback channels: vibration intensity (proximity-scaled), dynamic-rate tone beeps, throttled spoken guidance

### Safety classifications

| Action | Safety Tier | Rationale |
|---|---|---|
| `detect_objects` (activate blind aid) | STRONG_CONFIRM | Continuous camera + microphone access requires explicit consent |
| `deactivate_blind_aid` | DIRECT | Instant stop — no confirmation hurdles for accessibility |

### Bounding-box overlay

`BlindAidManager` publishes each frame's detected objects as normalized bounding boxes (in the upright image's coordinate space) plus the upright image aspect ratio to a `StateFlow<DetectionOverlay>`. `BlindAidCameraPreview` collects it and draws a Compose `Canvas` over the live `PreviewView`, mapping each box through FILL_CENTER (center-crop) so the boxes track objects on screen. Boxes are cleared on deactivation/release. Detection results also feed haptic, tonal, and spoken channels.

---

## Voice Runtime Behavior

### Offline-first state machine

`VoiceModule` tracks `sttState`/`ttsState` as `VoiceRuntimeState { SHERPA, SYSTEM_FALLBACK, UNAVAILABLE }`, which drives the AgentScreen offline chip (OFFLINE / LIMITED / NO MODEL).

- **Sherpa-ONNX is the default** STT and TTS engine.
- Android `SpeechRecognizer`/`TextToSpeech` is an **opt-in emergency fallback**, gated by `allowSystemSttFallback` (default **false**).
- When Sherpa is unavailable and the fallback is off, `startRecording`/`stopAndTranscribe`/`transcribeAudio` return `Result.Error("Offline STT model not installed…")` — the UI surfaces "install model" rather than silently routing to a cloud-dependent system service.
- `lastSttConfidence` is seeded `0f` and set from the real Sherpa engine result (Android fallback: 1.0f non-blank, 0.0f blank). The orchestrator treats `<0.6f` as low-confidence → "please repeat" + one retry.
- `VoiceService.transcribeAudio` only uses Android STT when `allowSystemSttFallback` is true; `hasSpeechActivity` decodes little-endian signed-16 PCM with masked bytes: `(b[i] and 0xFF) or ((b[i+1] and 0xFF) shl 8)`.

### In-app mic path (Agent screen and overlay)

```kotlin
// Start
VoiceModule.startRecording(context, scope)

// Stop + transcribe
val result = voiceModule.stopAndTranscribe()
if (result is Result.Success) {
    orchestrator.processCommand(result.data, InputType.VOICE)
}
```

- Waveform visualizer from recorder amplitude
- `AudioRecorder` guards state-check failure (`runCatching { record.release() }`), masks high byte in amplitude, and releases `audioRecord` in the catch block
- Mic permission pre-checked with `ContextCompat.checkSelfPermission()` before launching system dialog
- `AndroidSttEngine` uses `synchronized safeDestroyRecognizer()` to prevent the double-destroy race between `onError` and `onResults`

### Background VoiceService path

`VoiceService` includes:

- Wake-word + VAD loop (Sherpa `KeywordSpotter` + RMS VAD)
- Sherpa STT/TTS init attempts
- **End-to-end command dispatch**: verified commands are emitted to the in-app `SharedFlow` (`commandFlow`), collected by `UnoOneApplication`, and dispatched to `orchestrator.processCommand()` on `appScope`. `onDestroy` cancels `serviceJob`.

```kotlin
// UnoOneApplication
private val _commandFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
val commandFlow: SharedFlow<String> = _commandFlow.asSharedFlow()
// collector:
commandFlow.collect { command -> orchestrator.processCommand(command, InputType.VOICE) }
// emitter (VoiceService):
app.commandFlow.tryEmit(command)
```

This is **fully wired end-to-end** — background voice commands reach the orchestrator immediately and are processed identically to foreground commands, with no cross-app broadcast.

---

## Local Brain (Gemma via LiteRT-LM)

`GemmaPlanner` loads a Gemma `.litertlm` via LiteRT-LM `0.13.1` and keeps a reusable `Conversation` with `UnoOneToolSet` registered (`automaticToolCalling = false` — manual tool calling). It is **model-profile aware**: `BrainModelRegistry` defines the selectable profiles, `PromptBuilder` emits a family-correct system instruction (Gemma 4 vs Gemma 3n), and `load(modelPath, spec)` tries the profile's preferred backend order.

- **Dual profile**: default **Gemma 3n E4B** (`gemma-3n-e4b`, folder `gemma-local/` — device-verified fallback; a legacy `gemma-local` selection resolves here) + **Gemma 4 E2B** (`gemma-4-e2b`, folder `gemma-4-e2b/`, 128K ctx, ~2.58 GB — **Experimental opt-in, not device-verified**). The user picks the active brain in Settings → Model Status → Brain Model (persisted in `unoone_settings`, key `brain_model_manifest_id`).
- **Unknown-tool rejection + arg validation**: every model-proposed call is checked against `CanonicalToolRegistry` (26 tools). A tool not in the registry (`make_payment`, `send_message`, `install_app`, `access_passwords`, `silent_control`, …) is rejected with `Result.Error` and never executed; required args are schema-validated before forwarding. One tool per turn (the compound-step architecture, not the model, handles multi-step).
- **GPU→CPU fallback**: `tryLoadBackend(GPU) ?: tryLoadBackend(CPU)`; winner recorded in `activeBackend()`.
- **Inference timeout (30s)**: each `plan()` is bounded by `INFERENCE_TIMEOUT_MS`; a stuck JNI call closes the brain and it rebuilds on next load (no hang). An **inference `Mutex`** serializes `sendMessage` across the live command path and the read-only self-test probe (LiteRT-LM Conversation is not guaranteed thread-safe).
- **`lastLoadError()`** surfaces device-compatibility status to the UI; `loadedProfile()`/`loadedBrainBackend()` expose the active profile + backend.
- **Crash-safe load**: a `Mutex` serializes concurrent loads; a `createConversation` failure closes the half-built engine (no native LiteRT engine + GPU delegate leak); `isLoaded`/`activeBackend`/`lastLoadError`/`loadedSpec` are `@Volatile`.
- **Memory pressure**: `UnoOneApplication.onTrimMemory(RUNNING_LOW | RUNNING_CRITICAL)` unloads the brain; `MainActivity.onResume` reloads the selected profile if it was previously loaded (transparent recovery).
- **On-device self-test**: Settings → Model Status → Brain Model → Run Self-Test loads a profile (if installed), runs a probe command that bypasses `RuleBasedParser` (forcing the LLM path), and reports backend + load time + proposed tool + whether the tool is canonical (`toolAccepted`). It restores the previously-selected brain afterward. The read-only probe uses `AgentOrchestrator.planToolCall`.
- **`LocalBrain.runInference`** is a real thin wrapper around `GemmaPlanner.plan` — no mock output. Rule-based fallback via `RuleBasedParser` when no model is loaded.

---

## Agentic Loop, Safety Judge & Eval Harness

Three additions that move UnoOne from a *planner with a safety gate* toward a *measurable, self-correcting agent*. All three split **pure control logic (JVM-tested)** from **LiteRT-LM inference (device-time-only)** — the AAR ships bytecode newer than the JDK 17 test JVM can load, so no JVM unit test can instantiate `UnoOneToolSet`. Honest testability, not a gap papered over.

### 1. ReAct agentic loop (Reason → Act → Observe)

When the LLM plans an observation-producing tool (`search_notes`, `summarize_text`, `web_search`, `read_screen`, `ocr_screen`, `detect_objects`, `voice_recording`, `check_calendar`), the orchestrator feeds the tool result back into the live conversation and asks the model to plan the next step, up to `MAX_STEPS = 3`. Every step goes through the same `runValidatedToolCall` — permissions, risk, confirm, execute, audit — so the loop cannot bypass safety.

- **Provenance gate**: the loop only engages when the first call came from the LLM (`ParseOutcome.Llm`). A `RuleBasedParser` match never started a conversation, so it cannot be continued with an observation.
- **Termination** (`ReActLoopController.decide`): `speak_response` → stop and speak; planner error → stop; identical re-proposal (same tool + args) → `STALL_DETECTED`; `stepsExecuted >= MAX_STEPS` → stop; null plan → `NO_PLAN`.
- **Honest scope**: `ReActLoopController` (engagement gate + decision logic) is JVM-tested (`ReActLoopControllerTest`, 13 tests). The LiteRT-LM multi-turn `planNext` is device-time-only and **not yet device-verified** — no physical device has run the full loop.

### 2. LLM safety judge (escalate-only second pass)

A second on-device inference over a **dedicated `judgeConversation`** (same engine weights, no tools, a classifier system prompt) returns a `SafetyVerdict { SAFE, NEEDS_CONFIRM, UNSAFE, UNCERTAIN }` for the proposed action. The orchestrator merges it with the keyword-classified risk via `SafetyJudgePolicy.escalate`:

- `UNSAFE` → `max(current, BLOCK)`; `NEEDS_CONFIRM` → `max(current, CONFIRM)`; `SAFE`/`UNCERTAIN` → unchanged.
- The judge **never de-escalates** — it can only raise the tier, so it cannot weaken the keyword filter's hard blocks (`make_payment`, `send_message`, `install_app`, `access_passwords`, `silent_control`).
- The judge conversation is separate from the planning conversation so judging never pollutes the ReAct loop's context. `SAFETY_JUDGE_ENABLED` exists to disable it if per-step latency cost is unacceptable.
- **Honest scope**: `SafetyJudgePolicy` (the escalation contract) is JVM-tested (`SafetyJudgePolicyTest`, 7 tests). The `judgeSafety` inference is device-time-only; on the classifier's "always UNSAFE for deleting/wiping/erasing notes, payments, passwords, OTPs, account removal, app install/uninstall, silent control" instructions, the verdict is only as good as the loaded model.

### 3. Calibration / eval harness (measurement, not vibes)

A fixed prompt set + scorer + device runner so "is Gemma good enough?" becomes a number rather than an impression:

- `EvalPromptSet` — ~18 cases phrased the way users actually speak, spanning notes, navigation, comms, media, and destructive actions, including a paraphrased-harm case ("wipe everything in my notes" → `delete_all_notes`).
- `EvalScorer` — pure-JVM scoring: tool-match + required-arg-match (case-insensitive, substring-tolerant) → per-case `EvalVerdict`, aggregated to an `EvalSummary` with separate **accuracy** (full correct) and **toolAccuracy** (tool selection alone), so a profile can be diagnosed as "right tool, wrong args." Tested by `EvalScorerTest` (9 tests).
- `BrainEvalHarnessTest` (instrumented) — loads whichever `.litertlm` profile is on the device, runs the prompt set through `planner.plan(...)`, scores, and **prints** a real summary (profile, backend, per-case OK/arg/MISS, accuracy). To compare Gemma 3n E4B vs Gemma 4 E2B: push one model → run → record → push the other → run. It asserts only that the harness executed every case; it does **not** gate on an accuracy threshold — a profile being evaluated is allowed to score low, and that low number is the point.
- **Honest scope**: the scorer + prompt set are JVM-tested. The runner is device-time-only and **has not been run on a physical device** — no Gemma accuracy numbers are claimed or committed.

---

## Innovations #4–#8: Vision, Memory, Streaming, Self-Heal, Integrity

Five more innovations, each split the same honest way as #1–#3: the **deterministic control/scoring logic is in `:core` and JVM-tested**; the **LiteRT-LM inference / device wiring is device-time-only and not claimed until a physical device runs it**. The `litertlm-android` AAR ships bytecode newer than the JDK 17 test JVM can load, so no JVM test can load `UnoOneToolSet` / `GemmaPlanner` — that is the hard line that forces this split.

### 4. Multimodal vision (`describe_scene`)

A new **26th canonical tool** that produces a short, spoken scene description of the current screen:

- **Working path (today, JVM-tested control):** `describe_scene` captures a screenshot (MediaProjection, already gated), runs OCR (`OcrControl`), reads the foreground app/activity, and frames a compact description via `SceneDescriptionBuilder` (pure-JVM in `:core`). It never fabricates screen content — when OCR and context are both empty it says "I could not read any text on the screen" rather than inventing a scene. Tested by `SceneDescriptionBuilderTest` (9 tests).
- **Multimodal path (wired, INACTIVE):** `GemmaPlanner.describeSceneWithVision` sends `Message.user(Contents.of(Content.Text(prompt), Content.ImageBytes(bytes)))` to the loaded conversation — the real LiteRT-LM multimodal AAR API (`Content.ImageBytes` + `EngineConfig.visionBackend`/`maxNumImages`). The shipped Gemma 3n E4B / Gemma 4 E2B `.litertlm` artifacts are **text-only** (no vision weights), so this path is gated by `VISION_MODEL_ENABLED = false` and never called at runtime; on any failure the executor falls back to the OCR + context description. It lights up without code changes when a vision-capable `.litertlm` ships.
- **Safety:** `describe_scene` is **STRONG_CONFIRM** (capturing + analyzing the whole screen can read passwords/OTP/banking) and gated behind MediaProjection — it goes through the full pipeline like every other tool. It is advertised in the **Gemma 4** instruction only; the legacy Gemma 3n instruction is unchanged (byte-identical, 23 tools).

### 5. Outcome-learned memory

The planner is now told what **worked** and what **failed** for similar past requests, so it avoids repeating a known-bad action and leans on a known-good one:

- `OutcomeMemoryPolicy` (`:core`, JVM-tested, 10 tests) normalizes a command to a stopword-free signature, retrieves the most relevant prior outcomes by token overlap (failures ranked above successes at equal overlap, then recency), and renders a compact hint ("prior avoid: open_app (app not installed); prior worked: open_chrome").
- `MemoryModule.storeOutcome` / `getRelevantContext` persist + retrieve these via Room, keyed `outcome:<signature>:<tool>` and upserted. **No schema migration** — it reuses the free-string `type` column on `MemoryEntity`. Failures are logged and swallowed; memory never breaks a command.

### 6. Streaming inference

First-turn LLM planning can surface partial text to the timeline as it streams, instead of waiting for the full inference:

- `GemmaPlanner.planStreaming` collects LiteRT-LM's cold `Conversation.sendMessageAsync(Message, Map)` → `Flow<Message>`, feeding each partial's text to `StreamingTextReducer` (`:core`, JVM-tested, 8 tests) which is robust to **both** cumulative (full-text-so-far) and delta (new-tokens-only) snapshot semantics, and the final message is validated through the same `extractValidatedToolCall` as the synchronous path.
- The orchestrator uses `parseStreamingWithProvenance` when `STREAMING_INFERENCE_ENABLED`, evolving a single "Thinking" timeline step on each delta (added lazily, so rule-handled commands get no streaming step). Any streaming failure degrades gracefully to the synchronous `parseAsyncWithProvenance` path. The `Flow` + UI surfacing are device-time-only; TTS-streaming is a documented follow-up.

### 7. Diagnostics self-heal

The brain closes itself on a 30s inference timeout and otherwise nothing reloads it until `onResume` — self-heal closes that gap:

- `ToolHealthTracker` (`:core`, JVM-tested, 7 tests) keeps a rolling per-tool outcome window and flags a tool flaky after enough recent failures (old failures evict as the window slides). `BrainHealthPolicy.shouldReload` fires after `RELOAD_THRESHOLD` consecutive inference failures.
- The orchestrator proactively reloads the brain from its remembered load path/spec when it is found down at the start of a command, records inference failures mid-ReAct-loop and reloads once the threshold fires, and surfaces flaky tools to the timeline + audit log. The reload + spoken diagnostic are device-time-only; the decision contract is JVM-tested.

### 8. Signed manifest integrity

`ModelManifest` now carries an Ed25519 `manifestSignature`, so a tampered model file can be rejected at load time once the publisher activates the chain:

- `ManifestSignatureVerifier` (`:modelmanager`, JVM-tested, 9 tests) canonicalizes the manifest (deterministic kotlinx.serialization, blanked signature field) and verifies the Ed25519 signature via platform `java.security` EdDSA. Canonicalization is stable and signature-independent; tampered bytes / signatures / wrong keys are rejected.
- `ManifestSigner` is a CLI (`--generate-key`, `--sign`) for the publisher. **`ManifestSigningKey.PUBLIC_KEY_BASE64` is intentionally blank → wired but INACTIVE** — no fabricated keys; until the publisher sets a public key, `ModelManifestLoader` accepts unsigned manifests (and on `SDK_INT < 33` logs + accepts, since platform EdDSA is API 33+; minSdk 28 devices fall back). No Bouncy Castle added (avoids ~6MB APK cost for an inactive feature).

---

---


### Priority ordering

The `RuleBasedParser` uses a `when` block with the following priority:

1. **Domain-specific rules** — skills, email, WhatsApp, calendar (checked first to preserve internal `"and"` semantics)
2. **Compound commands** — splits on `" and "` into `steps[]` (up to 3) only when no `domainSpecificKeywords` are present
3. **General rules** — blind aid, system control, notes (delete-before-create), apps, etc.
4. **Fallback** — `LocalBrain.runInference()` → `GemmaPlanner.plan()` (real LiteRT-LM), or null

### Domain-specific keyword guard

```kotlin
private val domainSpecificKeywords = listOf(
    "teach you", "create skill", "new skill",
    "email", "mail", "whatsapp", "calendar", "schedule", "events",
    "find and click", "find and tap", "find then click", "find then tap"
)
```

Compound splitting on `" and "` only occurs if none of these keywords appear.

### Note handling

- **Deletion** is checked **before** creation: `delete`/`remove`/`clear`/`erase` + `note(s)` → `delete_notes` / `delete_all_notes` (so `"delete note about X"` routes to delete, not create).
- `cancel`/`close` are deliberately **not** delete-verbs (would misroute *"create note about cancel"* → `delete_notes`, a data-loss regression).
- Creation suppresses on negation verbs (`noteNegationVerbs = delete/remove/cancel/close/clear/erase`).

### Compound schema

Compound commands serialize as a single `steps` JSON array of `{tool, args}` objects (up to 3 parts). If only one half parses, that half is returned directly (the "and" was not a separator). `ToolCall.compoundSteps()` expands a compound into `List<ToolCall>` for the orchestrator.

### Test coverage

289 unit tests across 29 files. Parser-relevant tests cover: 6 activation phrases → `detect_objects`; 8 deactivation phrases → `deactivate_blind_aid`; note creation with/without colon; "remember to" stripping; compound `steps[]` (2- and 3-part); domain-specific preservation (skill steps, email, whatsapp, calendar); long press target extraction; activation/deactivation disambiguation; note deletion routing; "search for X" → `open_url` Google search; compound `open chrome and search for cats`. `CanonicalToolRegistryTest` (exactly 26 tools, unique names, unknown-tool rejection, required-arg validation), `ToolRegistryAgreementTest` (Gemma 4 instruction advertises exactly the canonical 26; legacy Gemma 3n instruction unchanged), and `ModelManifestTest` (dual-profile `gemma-local`→`gemma-3n-e4b` migration invariant + `gemma-4-e2b` empty-integrity parse). Agent-capability tests: `ReActLoopControllerTest` (engagement gate, continue/stop, speak-response extraction, stall detection, max-steps, precedence), `SafetyJudgePolicyTest` (escalate-only merge, never-lower, unsafe→block), `EvalScorerTest` (tool+arg scoring, case-insensitive arg match, summary counts, empty-set safety, prompt-set invariants). New this pass (#4–#8): `ToolHealthTrackerTest` (rolling flaky-tool window + brain-reload threshold), `StreamingTextReducerTest` (cumulative vs delta delta-reduction, final-text convergence), `SceneDescriptionBuilderTest` (describe_scene OCR + context framing + honest "could not read" fallback), `OutcomeMemoryPolicyTest` (outcome signature, token-overlap retrieval, failure-first ranking, rendering), and `ManifestSignatureTest` (Ed25519 canonicalization + verify round-trip, tamper/wrong-key rejection, signing key inactive by default).

---

## Safety Framework

### Tool-to-risk mapping

| Risk Level | Behavior | Tools |
|---|---|---|
| **DIRECT** | Execute immediately | `create_note`, `search_notes`, `summarize_text`, `speak_response`, `open_chrome`, `open_app`, `deactivate_blind_aid`, `check_calendar` |
| **CONFIRM** | Single confirmation dialog | `open_url`, `open_calendar_insert`, `open_dialer`, `share_text`, `read_screen`, `ocr_screen`, `open_camera`, `create_skill`, `click`, `type`, `long_press` |
| **STRONG_CONFIRM** | Must type "confirm" | `delete_notes`, `delete_all_notes`, `export_data`, `detect_objects`, `describe_scene`, `draft_email`, `send_whatsapp`, `system_control`, `find_and_click`, `fill` |
| **BLOCK** | Hard block — never executed | `send_message`, `make_payment`, `install_app`, `access_passwords`, `silent_control` |

**Default**: Any tool not in the map → `STRONG_CONFIRM`.

**Input-level escalation** (`classifyFromInput`): scans the raw utterance for dangerous keywords (`send`/`message`, `payment`/`pay`, `password`, `install`, `transfer money`, `bank`, `credit card` → BLOCK). This is a deliberate, **tested** over-block posture — e.g. *"send a WhatsApp message to mom"* hits `send`/`message` → BLOCK, overriding `send_whatsapp`'s STRONG_CONFIRM. Intentionally strict; weakening it is a security-policy decision, not a bug fix. Verified by `SafetyGuardTest`.

**Compound & skill commands**: Each step is independently classified and permission-checked via `runValidatedToolCall`. A blocked step blocks the whole compound/skill; a `delete_all_notes` step requires STRONG_CONFIRM.

**Confirmation timeout**: 60s (`CONFIRMATION_TIMEOUT_MS`); on timeout the agent logs and releases the lock instead of hanging.

---

## Permissions

`core/safety/ToolPermissionRegistry` is the **single source of truth** consumed by both `SafetyPipeline` and `ActionExecutor` (which previously held a duplicated, incorrect copy). `PermissionRequirement` is sealed: `None | RuntimePerm(String) | Overlay | Accessibility | MediaProjection`.

| Tool | Requirement | Correction |
|---|---|---|
| `read_screen`, `system_control` | **Accessibility** | was wrongly `SYSTEM_ALERT_WINDOW` |
| `ocr_screen` | **MediaProjection** | was wrongly `SYSTEM_ALERT_WINDOW`; camera not required for screenshots |
| `describe_scene` | **MediaProjection** | captures the screen for OCR + scene description (vision path wired but INACTIVE) |
| `open_camera` | `CAMERA` | |
| `detect_objects` | `CAMERA` + **Accessibility** | |
| `voice_recording` | `RECORD_AUDIO` | records a memo → offline STT → saved as a note |
| `web_search` | `None` | INTERNET is a normal manifest permission; offline-first guard in `ActionExecutor` |
| `check_calendar` / `open_calendar_insert` | `READ_CALENDAR` / `WRITE_CALENDAR` | |
| `open_dialer`, `share_text`, `open_url`, `open_app`, `open_chrome`, notes/skills/email/whatsapp | `None` | intent-launched or local-only |
| `compound` | `None` | expanded into real steps before execution; checked per-step |

`PermissionManager` mediates the runtime launchers; overlay/accessibility/media-projection each have dedicated flows. Verified by `ToolPermissionRegistryTest`.

---

## Accessibility Hardening

### Node recycling

All `AccessibilityNodeInfo` objects are recycled using `try/finally` patterns:

- `clickNodeWithText()` — outer finally recycles `rootNode`; inner finally recycles `findAccessibilityNodeInfosByText` results; parent nodes recycled individually in traversal loop
- `typeTextIntoFocused()` — outer finally recycles `rootNode`; inner finally recycles `focusedNode`
- `fillFieldWithText()` — outer finally recycles `rootNode`; inner finally recycles `nodes` list
- `captureVisibleText()` — outer finally recycles `rootNode`; child nodes recycled inside `traverse()`
- `scrollDown()` / `scrollUp()` — `rootNode` recycled in finally block
- `swipe()` in `AccessibilityControl` — single `rootNode` recycled in finally block
- `longPressNodeWithText()` — outer finally recycles `rootNode`; inner finally recycles `nodes` list

### Suspend function

`AccessibilityControl.findAndClick()` is a `suspend fun` using `delay(500)` instead of `Thread.sleep(500)` — prevents UI thread blocking during accessibility node searches.

### Payload truncation

`AccessibilityControl.captureScreenText()` truncates payloads above 100,000 characters and filters by `isVisibleToUser`.

---

## Permissions and Hardware

### Manifest permission profile

| Category | Permissions |
|---|---|
| Audio | `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS` |
| Camera & Haptics | `CAMERA`, `VIBRATE` |
| Contacts & Calendar | `READ_CONTACTS`, `READ_CALENDAR`, `WRITE_CALENDAR` |
| Overlay | `SYSTEM_ALERT_WINDOW` |
| Background Execution | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `WAKE_LOCK`, `POST_NOTIFICATIONS` |
| Battery | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| Storage | Model directory access (app external files) |

### Hardware tiers

| Tier | Recommended profile |
|---|---|
| **Minimum** | Android 9+ (API 28), 4 GB RAM, 1 GB free storage, microphone |
| **Recommended** | Android 12+, 6–8 GB RAM, rear camera with autofocus, vibration motor, 2+ GB free storage |
| **Expert** | 8+ GB RAM (12+ preferred), NPU/GPU-capable chipset, additional multi-GB model storage (Gemma 3n E4B auto GPU→CPU) |

### Model folder expectations

App-managed model directories under app external files (`Android/data/com.unoone.agent/files/models/`):

- `gemma-local` — Gemma 3n E4B `.litertlm` (default brain; manifest id `gemma-3n-e4b`, folder unchanged for migration continuity)
- `gemma-4-e2b` — Gemma 4 E2B `.litertlm` (Experimental opt-in, 128K ctx; **not device-verified**)
- `sherpa-asr-en` — offline ASR (English, streaming zipformer transducer int8)
- `sherpa-asr-whisper` — offline ASR (multilingual whisper-tiny int8; shared by hi/bn/ta/te/kn/ml — the `language` field selects the language). Extracts to `sherpa-onnx-whisper-tiny/`
- `sherpa-tts-en` — offline TTS (English, Coqui VITS + espeak-ng-data)
- `sherpa-tts-hin/ben/tam/tel/kan/mal` — offline TTS (MMS VITS, one per Indic language)
- `vad` — keyword spotting / wake-word (English — no Indic KWS model exists)
- `punctuation` — punctuation restoration
- `ocr-optional` — optional OCR

Installable from the **Model Status** screen or dropped in manually.

---

## Validation Commands

Run from this directory (`android-app/UnoOneAgent`):

```bash
# All unit tests (289 tests across 29 files)
./gradlew test

# Full debug APK build
./gradlew assembleDebug

# Lint (abortOnError + warningsAsErrors; 39 baselined advisories, 0 StaticFieldLeak)
./gradlew :app:lint

# Compile check (faster, no APK)
./gradlew compileDebugKotlin
```

### Test files (29 JVM + 2 instrumented)

| File | Covers |
|---|---|
| `app/.../RuleBasedParserTest.kt` | parser activation/deactivation/notes/compound/long-press |
| `app/.../CompoundStepsTest.kt` | compound `steps[]` serialization + per-step safety |
| `app/.../parsing/CommandParserTest.kt` | context snapshot + command parsing |
| `app/.../parsing/ContextSnapshotTest.kt` | enriched context (notes/skills/OCR/recent-commands) |
| `app/.../execution/ActionExecutorToolCoverageTest.kt` | every tool dispatches to a real branch |
| `app/.../safety/SafetyPipelineTest.kt` | permission + risk pipeline |
| `app/.../safetyguard/SafetyGuardTest.kt` | input-level BLOCK keywords |
| `app/.../safetyguard/SafetyGuardToolCoverageTest.kt` | tool→risk tier coverage |
| `app/.../skills/SkillSafetyRoutingTest.kt` | skill steps honor block/confirm |
| `app/.../core/model/{Result,RiskAssessment}Test.kt` | core primitives |
| `app/.../core/util/{CallbackMulticast,InputSanitizer}Test.kt` | multicast dedup, sanitization |
| `core/.../model/CanonicalToolRegistryTest.kt` | exactly 26 canonical tools, unique names, unknown-tool rejection, required-arg validation |
| `core/.../safety/ToolPermissionRegistryTest.kt` | full tool→requirement mapping |
| `core/.../agent/ReActLoopControllerTest.kt` | ReAct engagement gate + decide (continue/stop, speak-response extraction, stall detection, max-steps, precedence) |
| `core/.../agent/SafetyJudgePolicyTest.kt` | safety-judge escalate-only merge (unsafe→block, needs-confirm→confirm, never-lower) |
| `core/.../agent/ToolHealthTrackerTest.kt` | rolling flaky-tool window + brain-reload threshold (self-heal contract) |
| `core/.../agent/StreamingTextReducerTest.kt` | streaming delta reduction (cumulative vs delta snapshots, final-text convergence) |
| `core/.../agent/SceneDescriptionBuilderTest.kt` | describe_scene OCR + context framing + honest "could not read" fallback |
| `core/.../memory/OutcomeMemoryPolicyTest.kt` | outcome signature, token-overlap retrieval, failure-first ranking, rendering |
| `core/.../eval/EvalScorerTest.kt` | tool+arg scoring, case-insensitive substring arg match, summary accuracy/toolAccuracy, empty-set safety, prompt-set invariants |
| `localbrain/.../PromptBuilderTest.kt` | prompt assembly + tool names |
| `localbrain/.../ToolRegistryAgreementTest.kt` | Gemma 4 instruction advertises exactly the canonical 26; legacy Gemma 3n instruction unchanged (no `UnoOneToolSet` reflection — AAR bytecode is newer than the JDK 17 test JVM can load) |
| `localbrain/.../RAGManagerTest.kt` | web-search RAG utility |
| `modelmanager/.../ModelManifestTest.kt` | manifest parse, checksum, health, per-language `extractsTo`/`asset` fields, dual-profile `gemma-local`→`gemma-3n-e4b` migration invariant + `gemma-4-e2b` empty-integrity parse |
| `modelmanager/.../ManifestSignatureTest.kt` | Ed25519 canonicalization + verify round-trip, tamper rejection, wrong-key rejection, signing key inactive by default |
| `modelmanager/.../ModelInstallerTest.kt` | resume, corrupt-recovery, zip + tar.bz2 extraction, `extractsTo` archive health/skip, idempotent skip, empty-file guard, complete-`.part` commit |
| `voice/.../VoiceLanguageMappingTest.kt` | en→transducer, Indic→whisper, per-language TTS folder mapping |

### Device-time tests (instrumented, run on a physical device with a model pushed)

```bash
# Brain accuracy + calibration eval (prints a real EvalSummary; needs a .litertlm on device)
./gradlew :app:connectedDebugAndroidTest --tests '*.GemmaPlannerAccuracyTest'
./gradlew :app:connectedDebugAndroidTest --tests '*.BrainEvalHarnessTest'
```

| File | Covers |
|---|---|
| `app/.../localbrain/GemmaPlannerAccuracyTest.kt` | loads a pushed model, probes `open_chrome` / `create_note` tool calls (skips when no model present) |
| `app/.../localbrain/BrainEvalHarnessTest.kt` | runs the full `EvalPromptSet` through `GemmaPlanner.plan`, scores via `EvalScorer`, prints profile + backend + per-case + accuracy (skips when no model present; does not gate on accuracy) |

---

## Known Integration Gaps

These are honest, current limitations (not papered over):

| Component | Status | Detail |
|---|---|---|
| Manifest model URLs + integrity fields | ✅ Done (7 languages) | `sherpa-asr-en`/`sherpa-asr-whisper`/`sherpa-tts-{en,hin,ben,tam,tel,kan,mal}`/`vad` filled with verified HF/GitHub URLs + stream-computed SHA-256/size; espeak-ng-data bundled as app asset; `gemma-3n-e4b`/`gemma-4-e2b`/`punctuation` URLs only (hashes intentionally empty — no fabrication). ASR for hi/bn/ta/te/kn/ml uses shared multilingual whisper-tiny int8 (language selected at runtime); TTS uses per-language MMS VITS. Wake word (`vad`) stays English. |
| Gemma 4 E2B not device-verified | ⚠️ Experimental | The `gemma-4-e2b` profile loads through the same code path as Gemma 3n, but no physical device has confirmed it loads + performs a tool call. Selectable + self-testable; default stays Gemma 3n E4B. See [`DEVICE_VERIFICATION.md`](../../DEVICE_VERIFICATION.md) step 5b. |
| ReAct loop / safety judge inference device-time-only | ⚠️ Control JVM-tested, inference not device-verified | `ReActLoopController` + `SafetyJudgePolicy` are JVM-tested. The LiteRT-LM multi-turn `planNext` and `judgeSafety` are device-time-only — no physical device has run the full loop or the judge. The judge's verdict quality depends entirely on the loaded model. |
| Eval harness not run on device | ⚠️ Scoring JVM-tested, runner not run | `EvalScorer` + `EvalPromptSet` are JVM-tested. `BrainEvalHarnessTest` is instrumented and ready, but has not been run against a real model — **no Gemma accuracy numbers are claimed or committed**. Run it on a device to get real Gemma 3n vs Gemma 4 numbers. |
| Streaming inference device-time-only | ⚠️ Reducer JVM-tested, Flow not device-verified | `StreamingTextReducer` is JVM-tested. `GemmaPlanner.planStreaming` collects the LiteRT-LM `sendMessageAsync` `Flow<Message>` — device-time-only, not yet run on a physical device. The synchronous `plan()` path remains the fallback, so command planning never depends on streaming working. |
| Multimodal vision INACTIVE | ⚠️ Control JVM-tested, vision path wired-but-inactive | `describe_scene` works today via the JVM-tested `SceneDescriptionBuilder` (OCR + context). The LiteRT-LM `Content.ImageBytes` vision path is wired against the real AAR but gated by `VISION_MODEL_ENABLED = false` — the shipped Gemma models are text-only. No vision understanding is claimed until a vision-capable `.litertlm` ships and is device-verified. |
| Outcome memory device-time-only | ⚠️ Policy JVM-tested, planner benefit not measured | `OutcomeMemoryPolicy` + Room plumbing are JVM-tested. Whether the hint actually changes Gemma's tool selection on-device is not yet measured (run the eval harness with memory on/off). |
| Manifest signing INACTIVE | ⚠️ Verifier JVM-tested, signing key not set | `ManifestSignatureVerifier` + `ManifestSigner` are JVM-tested. `ManifestSigningKey.PUBLIC_KEY_BASE64` is intentionally blank → signatures are not enforced. No fabricated keys; activate by setting a public key after a device proves EdDSA at API 33+ (minSdk 28 falls back to accept + log). |
| `ttsState` before `initTts` | ⚠️ Minor | State reported before init fully completes |

**Fully resolved** (previously listed as gaps):

| Component | Previous Status | Current Status |
|---|---|---|
| `VoiceService` command delivery | ❌ Broadcast / not wired | ✅ End-to-end via in-app `SharedFlow` (`commandFlow`) |
| Accessibility node recycling | ⚠️ Partial leaks | ✅ All methods use `try/finally` patterns |
| `AndroidSttEngine` double-destroy | ⚠️ Race condition | ✅ `synchronized safeDestroyRecognizer()` guard |
| Compound command parsing | ❌ Broke skill steps / flat `first`/`second` fields | ✅ Domain-specific guard + `steps[]` JSON array (up to 3) |
| Compound & skill safety checks | ❌ Bypassed safety | ✅ Per-step `runValidatedToolCall` for both |
| `BlindAidManager` initialization | ⚠️ Race on factory | ✅ `remember{}` eager init, only composed when active |
| VoiceModule lifecycle | ❌ Multiple instances | ✅ Single shared instance across all consumers |
| Camera binding flicker | ⚠️ Rebind on recomposition | ✅ One-time `factory` block binding |
| Offline voice default | ❌ Silent Android STT fallback | ✅ Sherpa default; Android opt-in emergency fallback (off by default) |
| Permission mapping | ❌ Duplicated + wrong (overlay for screen read) | ✅ Single `ToolPermissionRegistry`; read_screen→Accessibility, ocr_screen→MediaProjection |
| Model lifecycle | ❌ Loose folder detection | ✅ Manifest + installer (resume/SHA-256/corrupt-recovery/atomic commit/health/path-traversal guard) |
| Brain load crash-safety | ❌ Concurrent-load engine leak | ✅ Mutex load + createConversation-failure cleanup + onTrimMemory/onResume lifecycle |
| `create_skill` result handling | ❌ Operated on `Unit?` (saveSkill returns Unit) | ✅ try/catch returning Success/Error; `steps` accepts array or `\|`-string |
| Confirmation flow | ❌ Indefinite hang | ✅ 60s timeout + pendingCommand stash before lock release |
| `RAGManager` / web search | 🔧 Scaffold (not wired) | ✅ Wired as safety-gated `web_search` tool (`UnoOneToolSet` + `ActionExecutor` + `SafetyGuard` CONFIRM + `ToolPermissionRegistry`); offline-first guard, never auto-opens links |
| Bounding-box overlay | 🔧 Planned | ✅ `BlindAidManager` publishes normalized boxes + aspect ratio to a `StateFlow`; `BlindAidCameraPreview` draws a Compose `Canvas` overlay (FILL_CENTER mapping) |
| `ObjectDetectionControl` | 🔧 Dead code | ✅ Removed; `BlindAidManager` owns its own ML Kit detector |
| `Diagnostics` instrumentation | 🔧 Scaffold | ✅ Tool-execution latency + success/failure (orchestrator), STT/TTS latency (`VoiceModule`), model-load time (`GemmaPlanner`) |
| `voice_recording` executor | 🔧 Missing | ✅ Declared in `UnoOneToolSet`; `ActionExecutor` records via `VoiceModule` → offline STT → note; `SafetyGuard` CONFIRM; `RECORD_AUDIO` gated by safety pipeline |
| Blocking I/O in `detectModels`/`modelHealth` | ⚠️ Minor | ✅ Both now `suspend` and dispatch file I/O on `Dispatchers.IO` |

This README is intentionally explicit about remaining gaps to keep architecture and hardware guidance accurate.