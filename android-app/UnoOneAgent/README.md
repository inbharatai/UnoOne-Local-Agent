<div align="center">

# UnoOneAgent — Android Technical Implementation

<p align="center">
  <img src="https://img.shields.io/badge/AGP-8.10.0-1f6feb?style=for-the-badge" alt="AGP 8.10.0">
  <img src="https://img.shields.io/badge/Gradle-8.11.1-1f6feb?style=for-the-badge" alt="Gradle 8.11.1">
  <img src="https://img.shields.io/badge/Kotlin-2.2.21-7f52ff?style=for-the-badge" alt="Kotlin 2.2.21">
  <img src="https://img.shields.io/badge/Min%20SDK-28-success?style=for-the-badge" alt="Min SDK 28">
  <img src="https://img.shields.io/badge/Modules-13-0ea5e9?style=for-the-badge" alt="13 Modules">
  <img src="https://img.shields.io/badge/Voice-Sherpa--ONNX%20offline-0b7285?style=for-the-badge" alt="Sherpa-ONNX">
  <img src="https://img.shields.io/badge/Tests-204%20passing-22c55e?style=for-the-badge" alt="204 Tests">
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
| Gemma 3n E4B via LiteRT-LM | ✅ Implemented | `GemmaPlanner`, manual tool calling, GPU→CPU fallback, Mutex load, onTrimMemory unload + onResume reload |
| Parser blind-aid fixes | ✅ Implemented & verified | Activation/deactivation disambiguation, negative-intent patterns |
| Parser unit tests | ✅ 204 passing across 19 files | See [Validation Commands](#validation-commands) |
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

**Manifest** (`src/main/assets/models_manifest.json`): array of model descriptors (`id, folder, type, version, minRamMb, backend, defaultLanguage, files[]`), each file `{name, url, sha256, sizeBytes, archive, asset?, extractsTo?}`. `asset` names a file bundled in the app's `assets/` (copied from the APK instead of downloaded); `extractsTo` names the top directory an archive extracts to (required for tarballs like `sherpa-onnx-whisper-tiny.tar.bz2` whose top dir differs from the archive name). Models: `gemma-local` (LLM), `sherpa-asr-en` (English ASR), `sherpa-asr-whisper` (multilingual whisper ASR for hi/bn/ta/te/kn/ml), `vad` (English wake-word), `sherpa-tts-en` (English TTS), `sherpa-tts-hin/ben/tam/tel/kan/mal` (MMS TTS per Indic language), `punctuation`, `ocr-optional`.

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
- `getLlmModelPath()` finds the first `.litertlm`

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

`GemmaPlanner` loads a Gemma 3n E4B `.litertlm` via LiteRT-LM `0.13.1` and keeps a reusable `Conversation` with `UnoOneToolSet` registered (`automaticToolCalling = false` — manual tool calling).

- **GPU→CPU fallback**: `tryLoadBackend(GPU) ?: tryLoadBackend(CPU)`; winner recorded in `activeBackend()`.
- **`lastLoadError()`** surfaces device-compatibility status to the UI.
- **Crash-safe load**: `Mutex` serializes concurrent loads; a `createConversation` failure closes the half-built engine (no native LiteRT engine + GPU delegate leak); `isLoaded`/`activeBackend`/`lastLoadError` are `@Volatile`.
- **Memory pressure**: `UnoOneApplication.onTrimMemory(RUNNING_LOW | RUNNING_CRITICAL)` unloads the brain; `MainActivity.onResume` reloads it if it was previously loaded (transparent recovery).
- **`LocalBrain.runInference`** is a real thin wrapper around `GemmaPlanner.plan` — no mock output. Rule-based fallback via `RuleBasedParser` when no model is loaded.

---

## Command Parser

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

204 unit tests across 19 files. Parser-relevant tests cover: 6 activation phrases → `detect_objects`; 8 deactivation phrases → `deactivate_blind_aid`; note creation with/without colon; "remember to" stripping; compound `steps[]` (2- and 3-part); domain-specific preservation (skill steps, email, whatsapp, calendar); long press target extraction; activation/deactivation disambiguation; note deletion routing; "search for X" → `open_url` Google search; compound `open chrome and search for cats`.

---

## Safety Framework

### Tool-to-risk mapping

| Risk Level | Behavior | Tools |
|---|---|---|
| **DIRECT** | Execute immediately | `create_note`, `search_notes`, `summarize_text`, `speak_response`, `open_chrome`, `open_app`, `deactivate_blind_aid`, `check_calendar` |
| **CONFIRM** | Single confirmation dialog | `open_url`, `open_calendar_insert`, `open_dialer`, `share_text`, `read_screen`, `ocr_screen`, `open_camera`, `create_skill`, `click`, `type`, `long_press` |
| **STRONG_CONFIRM** | Must type "confirm" | `delete_notes`, `delete_all_notes`, `export_data`, `detect_objects`, `draft_email`, `send_whatsapp`, `system_control`, `find_and_click`, `fill` |
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

- `gemma-local` — Gemma 3n E4B `.litertlm`
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
# All unit tests (204 tests across 19 files)
./gradlew test

# Full debug APK build
./gradlew assembleDebug

# Lint (abortOnError + warningsAsErrors; 39 baselined advisories, 0 StaticFieldLeak)
./gradlew :app:lint

# Compile check (faster, no APK)
./gradlew compileDebugKotlin
```

### Test files (16)

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
| `core/.../safety/ToolPermissionRegistryTest.kt` | full tool→requirement mapping |
| `localbrain/.../PromptBuilderTest.kt` | prompt assembly + tool names |
| `modelmanager/.../ModelManifestTest.kt` | manifest parse, checksum, health, per-language `extractsTo`/`asset` fields |
| `modelmanager/.../ModelInstallerTest.kt` | resume, corrupt-recovery, zip + tar.bz2 extraction, `extractsTo` archive health/skip, idempotent skip, empty-file guard, complete-`.part` commit |
| `voice/.../VoiceLanguageMappingTest.kt` | en→transducer, Indic→whisper, per-language TTS folder mapping |

---

## Known Integration Gaps

These are honest, current limitations (not papered over):

| Component | Status | Detail |
|---|---|---|
| Manifest model URLs + integrity fields | ✅ Done (7 languages) | `sherpa-asr-en`/`sherpa-asr-whisper`/`sherpa-tts-{en,hin,ben,tam,tel,kan,mal}`/`vad` filled with verified HF/GitHub URLs + stream-computed SHA-256/size; espeak-ng-data bundled as app asset; `gemma-local`/`punctuation` URLs only. ASR for hi/bn/ta/te/kn/ml uses shared multilingual whisper-tiny int8 (language selected at runtime); TTS uses per-language MMS VITS. Wake word (`vad`) stays English. |
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