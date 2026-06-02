# UnoOneAgent Android Technical README

This document describes what is implemented in the Android codebase today, including architecture, hardware requirements, and the current Blind Aid vision stack.

## Implementation Status

The Android workspace builds as a 13-module project and includes:

- Compose UI with timeline-driven orchestration UX.
- Floating overlay chat bubble service.
- Accessibility-driven deep control (tap, type, fill, scroll, swipe, global actions).
- Blind Aid camera mode with continuous CameraX + ML Kit analyzer feedback.
- Unified voice start/stop transcription APIs with waveform amplitude updates.
- Rule parser updates for blind aid activation/deactivation and note creation phrasing.
- Unit coverage for parser blind aid triggers.

## Module Architecture (13 modules)

| Module | Primary responsibility | Core files |
|---|---|---|
| `:app` | App shell, orchestration, permissions, UI | `MainActivity.kt`, `AgentOrchestrator.kt`, `AgentScreen.kt`, `FloatingAgentService.kt` |
| `:core` | Shared primitives and logging | `Result.kt`, `ToolCall.kt`, `TimelineStep.kt`, `Logger` |
| `:storage` | Room persistence layer | `UnoOneDatabase.kt`, DAOs, entities |
| `:modelmanager` | Model folder management and checksum verification | `ModelManager.kt` |
| `:localbrain` | Parsing/inference utilities | `RuleBasedParser.kt`, `LocalBrain.kt`, `PromptBuilder.kt`, `RAGManager.kt` |
| `:voice` | Recorder, STT/TTS engines, voice service | `VoiceModule.kt`, `AndroidSttEngine.kt`, `VoiceService.kt` |
| `:agentrouter` | Tool registry and routing fallback | `AgentRouter.kt` |
| `:safetyguard` | Risk classification policy | `SafetyGuard.kt` |
| `:phonecontrol` | Device intents, OCR, object detection, blind aid analyzer | `PhoneControl.kt`, `OcrControl.kt`, `ObjectDetectionControl.kt`, `BlindAidManager.kt` |
| `:memory` | Preference/correction/pattern memory | `MemoryModule.kt` |
| `:skills` | Skill CRUD and trigger execution | `SkillsModule.kt` |
| `:observability` | Local diagnostics helper | `Diagnostics.kt` |
| `:accessibilitycontrol` | Accessibility service wrappers | `UnoOneAccessibilityService.kt`, `AccessibilityControl.kt` |

## Blind Aid Vision Capabilities (Deep Dive)

### Command and state flow

- `RuleBasedParser` maps activation phrases to `detect_objects`.
- `RuleBasedParser` maps stop phrases to `deactivate_blind_aid`.
- `AgentOrchestrator` toggles `isBlindAidActive` state and speaks activation/deactivation status.
- `AgentScreen` listens to `isBlindAidActive` and mounts/unmounts the camera preview card.

### Camera pipeline

`BlindAidCameraPreview`:

- obtains `ProcessCameraProvider`
- binds `Preview` + `ImageAnalysis` to lifecycle owner
- uses `STRATEGY_KEEP_ONLY_LATEST`
- unbinds camera providers on dispose
- releases `BlindAidManager` on dispose

### Analyzer and detection logic

`BlindAidManager`:

- throttles to 1 of every 6 frames (roughly 5 FPS from a 30 FPS stream)
- uses ML Kit object detection in single-image mode
- attempts custom model load from:
  - `Android/data/com.unoone.agent/files/models/gemma-local/custom_yolov8.tflite`
- falls back to default ML Kit detector if custom model is not present
- computes obstacle proximity from bounding-box fill ratio
- emits feedback via:
  - vibration intensity scaling
  - tone beeps
  - throttled spoken prompts (`voiceModule.speak` callback)

### What is missing today

- no visual bounding-box overlay rendering in Compose preview
- blind aid activation currently goes through strong confirmation (default safety behavior for non-whitelisted tools)

## Voice Pipeline and Runtime Behavior

### In-app mic flow (AgentScreen and overlay)

Current code path uses:

- `VoiceModule.startRecording(context, scope)`
- `VoiceModule.stopAndTranscribe()`

Behavior:

- if Sherpa STT is not initialized, falls back to Android SpeechRecognizer path
- Android STT now streams `onRmsChanged` amplitude to waveform UI
- Android path avoids recorder contention by running recognizer-first capture flow

### Background VoiceService flow

`VoiceService` includes:

- wake word loop scaffolding
- Sherpa STT/TTS init attempts
- keyword spotting and RMS VAD loop

Current integration gap:

- service callbacks (`onWakeWordDetected`, `onCommandReceived`) are not wired into app orchestrator in current runtime path

## Accessibility and Context Safety Updates

Implemented hardening:

- `captureVisibleText()` now filters by `isVisibleToUser`
- traversal recycles child nodes to reduce leak pressure
- `AccessibilityControl.captureScreenText()` truncates payloads above 100,000 chars before passing onward

## Safety and Permissions

### Safety classification

`SafetyGuard` maps known tools to explicit risk levels.

Important default behavior:

- any unknown tool name defaults to `STRONG_CONFIRM`

### Manifest permissions (current)

- audio: `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`
- camera and haptics: `CAMERA`, `VIBRATE`
- calendar/contacts: `READ_CALENDAR`, `WRITE_CALENDAR`, `READ_CONTACTS`
- overlay/control: `SYSTEM_ALERT_WINDOW`
- background execution: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`
- power/storage compatibility permissions for model and service reliability

## Hardware Requirements

### Minimum (functional baseline)

- Android 9+ (API 28)
- 4 GB RAM
- 1 GB free storage
- microphone

### Recommended (smooth blind aid and voice UX)

- Android 12+
- 6 to 8 GB RAM
- rear camera with stable autofocus
- vibration motor
- 2+ GB free storage

### Expert local model tier

- 8+ GB RAM (12+ GB preferred)
- modern NPU-capable chipset
- additional storage depending on model packs (several GB for larger local models)

## Model Folder Expectations

App-managed model directories under app external files include:

- `gemma-local`
- `sherpa-asr`
- `sherpa-tts`
- `vad`
- `punctuation`
- `ocr-optional`

## Testing and Verification

Validated commands:

```bash
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:compileDebugKotlin
```

Current parser unit tests include:

- blind aid activation phrase routing
- blind aid deactivation phrase routing
- note creation trigger routing

## Documentation Accuracy Notes

The following components exist in code but are not fully integrated end-to-end yet:

- `LocalBrain.runInference()` still returns a mock JSON output placeholder (tokenizer/inference integration is incomplete).
- `RAGManager` exists but is not currently wired into orchestrator runtime flow.
- `Diagnostics` helpers exist but are not broadly instrumented across execution paths.

This README intentionally describes shipped behavior and these gaps explicitly, so architecture and hardware guidance remain trustworthy.
