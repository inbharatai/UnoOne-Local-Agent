<div align="center">

# UnoOneAgent — Android Technical Implementation

<p align="center">
  <img src="https://img.shields.io/badge/Build-Gradle%208.7-1f6feb?style=for-the-badge" alt="Gradle">
  <img src="https://img.shields.io/badge/Min%20SDK-28-success?style=for-the-badge" alt="Min SDK 28">
  <img src="https://img.shields.io/badge/Modules-13-0ea5e9?style=for-the-badge" alt="13 Modules">
  <img src="https://img.shields.io/badge/Vision-CameraX%20%2B%20ML%20Kit-2563eb?style=for-the-badge" alt="Vision">
  <img src="https://img.shields.io/badge/Tests-9%20Passing-22c55e?style=for-the-badge" alt="9 Tests">
</p>

</div>

> This document is implementation-aligned and intentionally avoids over-claims. It reflects the **current runtime state** of every subsystem.

---

## Quick Navigation

- [Implementation Snapshot](#implementation-snapshot)
- [Module Architecture](#module-architecture)
- [Shared VoiceModule Architecture](#shared-voicemodule-architecture)
- [Command Orchestrator Pipeline](#command-orchestrator-pipeline)
- [Blind Aid Vision Deep Dive](#blind-aid-vision-deep-dive)
- [Voice Runtime Behavior](#voice-runtime-behavior)
- [Command Parser](#command-parser)
- [Safety Framework](#safety-framework)
- [Accessibility Hardening](#accessibility-hardening)
- [Permissions and Hardware](#permissions-and-hardware)
- [Validation Commands](#validation-commands)
- [Known Integration Gaps](#known-integration-gaps)

---

## Implementation Snapshot

| Capability | Status | Notes |
|---|---|---|
| Compose app shell + overlay | ✅ Implemented | Main UI + floating chat bubble with mic permission handling |
| Orchestrator pipeline | ✅ Implemented | Parse → permission → safety → execute → verify → speak |
| Compound command execution | ✅ Implemented | Splits on `" and "` with domain-safe guard, per-part safety checks |
| Blind Aid camera mode | ✅ Implemented & live | CameraX preview + analyzer feedback, foreground activation from background |
| Voice input APIs | ✅ Implemented | Unified start/stop transcribe, shared singleton instance |
| Background voice routing | ✅ End-to-end | VoiceService → package-local broadcast → UnoOneApplication receiver → orchestrator |
| Accessibility deep control | ✅ Implemented & hardened | All `AccessibilityNodeInfo` recycled in `try/finally`, suspend `findAndClick()` |
| Parser blind-aid fixes | ✅ Implemented & verified | Activation/deactivation disambiguation, negative-intent patterns |
| Parser compound commands | ✅ Implemented | Domain-specific guard prevents skill/email/whatsapp/calendar split |
| Parser unit tests | ✅ 9/9 passing | Activation, deactivation, notes, compound, long press, disambiguation |
| Safety framework | ✅ Implemented | 4-tier: DIRECT, CONFIRM, STRONG_CONFIRM, BLOCK |

---

## Module Architecture

| Module | Primary responsibility | Core files |
|---|---|---|
| `:app` | app shell, orchestration, permissions, UI | `MainActivity.kt`, `AgentOrchestrator.kt`, `AgentScreen.kt`, `AgentViewModel.kt`, `FloatingAgentService.kt` |
| `:core` | shared models and logging | `Result.kt`, `ToolCall.kt`, `AgentStatus.kt`, `TimelineStep.kt`, `Logger.kt` |
| `:storage` | Room persistence layer | `UnoOneDatabase.kt`, DAOs, entities |
| `:modelmanager` | model folder management and checksum verification | `ModelManager.kt` |
| `:localbrain` | parsing/inference utilities | `RuleBasedParser.kt`, `LocalBrain.kt`, `PromptBuilder.kt`, `RAGManager.kt` |
| `:voice` | recorder, STT/TTS engines, voice service, broadcast routing | `VoiceModule.kt`, `AndroidSttEngine.kt`, `VoiceService.kt` |
| `:agentrouter` | tool registry and routing fallback | `AgentRouter.kt` |
| `:safetyguard` | risk classification policy | `SafetyGuard.kt` |
| `:phonecontrol` | intents, OCR, object detection, blind aid analyzer | `PhoneControl.kt`, `OcrControl.kt`, `ObjectDetectionControl.kt`, `BlindAidManager.kt` |
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
  └── BroadcastReceiver uses orchestrator.voiceModule for speak()

AgentViewModel
  ├── Constructor receives sharedVoiceModule
  ├── orchestrator.setVoiceModule(voiceModuleInstance) // Redundant but harmless
  └── onCleared() does NOT release — application-scoped

FloatingAgentService
  ├── voiceModule = orchestrator.voiceModule         // Reuses shared instance
  └── onDestroy() does NOT release — application-scoped

VoiceService
  └── Owns separate STT/TTS/keyword engines (by design — different lifecycle)
```

This ensures that only one `VoiceModule` manages the microphone at any time across the foreground UI, background broadcast, and floating overlay.

---

## Command Orchestrator Pipeline

The `AgentOrchestrator` processes every command through 8 stages:

```
LISTENING → TRANSCRIBING → UNDERSTANDING → TOOL_SELECTED → SAFETY_CHECK → EXECUTING → VERIFYING → SPEAKING → DONE
```

### Compound command handling

When the parser returns `ToolCall("compound", ...)`, the orchestrator:

1. Deserializes both halves (`first_tool`, `first_args`, `second_tool`, `second_args`)
2. Runs **permission checks** on each half independently
3. Runs **safety classification** on each half independently
4. Executes each half with its own confirmation flow if needed
5. Speaks results for each completed action

This means a compound like *"open chrome and delete all notes"* will execute `open_chrome` (DIRECT — no confirmation) and then prompt for strong confirmation on `delete_all_notes` (STRONG_CONFIRM — user must type "confirm").

### Background command dispatch

Commands received via `VoiceService` broadcast are processed identically to foreground commands:

1. `VoiceService` sends `ACTION_VOICE_COMMAND` broadcast with `RECEIVER_NOT_EXPORTED`
2. `UnoOneApplication` BroadcastReceiver receives it on `appScope`
3. Calls `orchestrator.processCommand(command, InputType.VOICE)`
4. If the command activates blind aid, `bringAppToForegroundIfNeeded()` launches `MainActivity` with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP`

---

## Blind Aid Vision Deep Dive

### Command and state flow

1. `RuleBasedParser` maps 6 activation phrases to `detect_objects`
2. `RuleBasedParser` maps 8 deactivation phrases to `deactivate_blind_aid` (including negative-intent patterns: stop, remove, turn off, deactivate, disable, no more)
3. `AgentOrchestrator.setBlindAidActive(true)` speaks confirmation and calls `bringAppToForegroundIfNeeded()`
4. `AgentScreen` reacts to `isBlindAidActive` StateFlow, mounting `BlindAidCameraPreview`

### Camera pipeline

`BlindAidCameraPreview`:

- Camera binding is done in `AndroidView(factory=...)` — one-time setup, no rebind on recomposition
- Uses `ProcessCameraProvider` lifecycle-bound to `LocalLifecycleOwner`
- Binds `Preview` + `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST`
- Unbinds camera providers in `DisposableEffect.onDispose`
- Releases `BlindAidManager` on dispose

### Analyzer behavior

`BlindAidManager`:

- Throttles processing to 1 in 6 frames (~5 FPS at 30 FPS input)
- Runs ML Kit object detection in single-image mode
- Attempts local custom model load from `Android/data/com.unoone.agent/files/models/gemma-local/custom_yolov8.tflite`
- Falls back to default ML Kit detector when custom model is absent
- Computes obstacle proximity from fill ratio
- Emits three feedback channels:
  - **Vibration intensity** — scales with obstacle proximity
  - **Tone beeps** — dynamic rate sound generator
  - **Throttled spoken guidance** — distance alert thresholds via VoiceModule callback

### Safety classifications

| Action | Safety Tier | Rationale |
|---|---|---|
| `detect_objects` (activate blind aid) | STRONG_CONFIRM | Continuous camera + microphone access requires explicit consent |
| `deactivate_blind_aid` | DIRECT | Instant stop — no confirmation hurdles for accessibility |

### Current visual limitation

No Compose-rendered bounding-box overlay is drawn over the camera preview. Detection results feed haptic, tonal, and spoken channels only.

---

## Voice Runtime Behavior

### In-app mic path (Agent screen and overlay)

Current code path:

```kotlin
// Start
VoiceModule.startRecording(context, scope)

// Stop + transcribe
val result = voiceModule.stopAndTranscribe()
if (result is Result.Success) {
    orchestrator.processCommand(result.data, InputType.VOICE)
}
```

Runtime behavior:

- Falls back to Android `SpeechRecognizer` when Sherpa STT is not initialized
- Android STT `onRmsChanged` is wired to waveform amplitude updates
- Recognizer-first fallback avoids recorder resource contention
- `AndroidSttEngine` uses `synchronized safeDestroyRecognizer()` to prevent double-destroy race condition between `onError` and `onResults`
- Mic permission is pre-checked with `ContextCompat.checkSelfPermission()` before launching system dialog

### Background VoiceService path

`VoiceService` includes:

- Wake-word + VAD loop scaffolding
- Sherpa STT/TTS init attempts
- Keyword spotting and RMS VAD loop
- **End-to-end command dispatch**: verified commands are broadcast via `ACTION_VOICE_COMMAND` with `setPackage(packageName)` and `RECEIVER_NOT_EXPORTED`, received by `UnoOneApplication`'s `BroadcastReceiver`, and dispatched to `orchestrator.processCommand()` on `appScope`

Broadcast receiver registration (in `UnoOneApplication.onCreate()`):

```kotlin
ContextCompat.registerReceiver(
    this, commandReceiver, IntentFilter(ACTION_VOICE_COMMAND),
    RECEIVER_NOT_EXPORTED
)
```

This is **fully wired end-to-end** — background voice commands reach the orchestrator immediately and are processed identically to foreground commands.

---

## Command Parser

### Priority ordering

The `RuleBasedParser` uses a `when` block with the following priority:

1. **Domain-specific rules** — skills, email, WhatsApp, calendar (checked first to preserve internal `"and"` semantics)
2. **Compound commands** — splits on `" and "` only when no `domainSpecificKeywords` are present
3. **General rules** — blind aid, system control, notes, apps, etc.
4. **Fallback** — `LocalBrain.runInference()` (currently returns mock JSON)

### Domain-specific keyword guard

```kotlin
private val domainSpecificKeywords = listOf(
    "teach you", "create skill", "new skill",
    "email", "mail", "whatsapp", "calendar", "schedule", "events"
)
```

Compound splitting on `" and "` only occurs if none of these keywords appear in the input.

### Negative-intent patterns

Deactivation phrases include negative-intent keywords: stop, remove, turn off, deactivate, disable, no more — ensuring `"deactivate blind aid"` is never confused with activation.

### Test coverage (9/9 passing)

| Test | What it verifies |
|---|---|
| `testBlindAidActivationTriggers` | 6 activation phrases → `detect_objects` |
| `testBlindAidDeactivationTriggers` | 8 deactivation phrases → `deactivate_blind_aid` |
| `testNoteCreationTriggers` | `"remember: pick up groceries"` → `create_note` |
| `testNoteCreationWithoutColon` | `"add note buy milk"` → `create_note` (content = "buy milk") |
| `testNoteRememberToStripsToPrefix` | `"remember to buy groceries"` → content = "buy groceries" |
| `testCompoundCommand` | `"scroll down and go home"` → `compound(system_control, system_control)` |
| `testCompoundCommandDoesNotBreakSkillSteps` | Skill steps with "and" preserved intact |
| `testLongPressWithText` | `"long press on settings"` → `system_control(action=long_press, target=settings)` |
| `testActivationNotConfusedByDeactivation` | `"deactivate blind aid"` → deactivation, not activation |

---

## Safety Framework

### Tool-to-risk mapping

| Risk Level | Behavior | Tools |
|---|---|---|
| **DIRECT** | Execute immediately | `create_note`, `search_notes`, `summarize_text`, `speak_response`, `open_chrome`, `open_app`, `deactivate_blind_aid` |
| **CONFIRM** | Single confirmation dialog | `open_url`, `open_calendar_insert`, `open_dialer`, `share_text` |
| **STRONG_CONFIRM** | Must type "confirm" | `delete_notes`, `delete_all_notes`, `export_data`, `detect_objects` |
| **BLOCK** | Hard block — never executed | `send_message`, `make_payment`, `install_app`, `access_passwords`, `silent_control` |

**Default**: Any tool not in the map → `STRONG_CONFIRM`

**Compound commands**: Each half is independently classified and permission-checked. The `compound` tool itself never reaches the safety classifier — both halves are evaluated separately.

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
| Storage | Model directory access permissions |

### Hardware tiers

| Tier | Recommended profile |
|---|---|
| **Minimum** (functional baseline) | Android 9+ (API 28), 4 GB RAM, 1 GB free storage, microphone |
| **Recommended** (smooth blind aid + voice UX) | Android 12+, 6–8 GB RAM, rear camera with autofocus, vibration motor, 2+ GB free storage |
| **Expert** (local model tier) | 8+ GB RAM (12+ preferred), NPU-capable chipset, additional multi-GB model storage |

### Model folder expectations

App-managed model directories under app external files:

- `gemma-local`
- `sherpa-asr`
- `sherpa-tts`
- `vad`
- `punctuation`
- `ocr-optional`

---

## Validation Commands

Run from this directory (`android-app/UnoOneAgent`):

```bash
# Run unit tests (9 tests)
./gradlew.bat :app:testDebugUnitTest

# Compile debug build
./gradlew.bat :app:assembleDebug

# Compile check (faster, no APK)
./gradlew.bat :app:compileDebugKotlin
```

---

## Known Integration Gaps

These components exist but are not yet fully integrated end-to-end:

| Component | Status | Detail |
|---|---|---|
| `LocalBrain.runInference()` | 🔧 Scaffold | Currently returns mock JSON placeholder output |
| `RAGManager` | 🔧 Scaffold | Exists but not wired into orchestrator runtime flow |
| `Diagnostics` | 🔧 Scaffold | Helpers exist but not broadly instrumented across execution paths |
| `ObjectDetectionControl` | 🔧 Dead code | Single-image detector never invoked from orchestrator (BlindAidManager is used instead) |
| Bounding-box overlay | 🔧 Planned | Detection results are computed but no Compose overlay is drawn over camera preview |

**Fully resolved** (previously listed as gaps):

| Component | Previous Status | Current Status |
|---|---|---|
| `VoiceService` callback wiring | ❌ Not wired | ✅ End-to-end via `ACTION_VOICE_COMMAND` broadcast |
| Accessibility node recycling | ⚠️ Partial leaks | ✅ All methods use `try/finally` patterns |
| `AndroidSttEngine` double-destroy | ⚠️ Race condition | ✅ `synchronized safeDestroyRecognizer()` guard |
| Compound command parsing | ❌ Broke skill steps | ✅ Domain-specific guard prevents incorrect splits |
| Compound safety checks | ❌ Bypassed safety | ✅ Per-part safety classification and permission checks |
| `BlindAidManager` initialization | ⚠️ Race on factory | ✅ `remember{}` eager init, only composed when active |
| VoiceModule lifecycle | ❌ Multiple instances | ✅ Single shared instance across all consumers |
| Camera binding flicker | ⚠️ Rebind on recomposition | ✅ One-time `factory` block binding |

This README is intentionally explicit about remaining gaps to keep architecture and hardware guidance accurate.