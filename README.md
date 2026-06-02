# UnoOne Local Agent

UnoOne Local Agent is an Android-first, on-device assistant built for private automation, screen interaction, and sensory navigation workflows.

This repository contains two major documentation layers:

- Root overview (this file): product and architecture summary for the full workspace.
- Android implementation details: `android-app/UnoOneAgent/README.md`.

## Current Reality Snapshot

The codebase currently delivers:

- 13 Gradle modules in a modular Android architecture.
- On-device screen interaction through AccessibilityService.
- Blind Aid camera mode with continuous CameraX + ML Kit object detection feedback.
- Voice input with unified start/stop transcription APIs.
- Rule-based command parsing and safety classification before execution.
- Room-backed persistence for notes, skills, memory, logs, and model metadata.

Important runtime note:

- In-app mic interactions currently default to Android SpeechRecognizer unless explicit Sherpa model initialization is wired for the `VoiceModule` path.
- `VoiceService` contains keyword spotting and Sherpa loading logic, but command callback wiring to orchestrator is still incomplete.

## Architecture

### Module Map (13 modules)

| Module | Role | Key points |
|---|---|---|
| `:app` | UI and orchestration | Compose screens, overlay service, orchestrator, permission flow |
| `:core` | Shared primitives | `Result`, `ToolCall`, timeline models, logger |
| `:storage` | Persistence | Room DB, DAOs, entity models |
| `:modelmanager` | Model asset management | Folder detection, checksum verification, storage usage |
| `:localbrain` | Intent and inference layer | Rule parser, ONNX wrapper, prompt builder, RAG utilities |
| `:voice` | Speech stack | Recorder, Android STT, Sherpa wrappers, TTS players, foreground voice service |
| `:agentrouter` | Tool dispatch | Registry/handler routing for supported tools |
| `:safetyguard` | Policy enforcement | `DIRECT`, `CONFIRM`, `STRONG_CONFIRM`, `BLOCK` classifier |
| `:phonecontrol` | Device integrations | Calendar, app launch, OCR, object detection, Blind Aid manager |
| `:memory` | User memory | Preference/correction/pattern retrieval and storage |
| `:skills` | User automation | Skill CRUD, trigger matching, step execution |
| `:observability` | Diagnostics | Local metric recording helpers |
| `:accessibilitycontrol` | Screen control | Gestures, click/type/fill, text capture, context tracking |

### Orchestrator Flow

`AgentOrchestrator` executes commands through this sequence:

1. skill trigger lookup
2. parser/inference selection
3. permission gate
4. safety classification
5. optional user confirmation
6. tool execution
7. verification/logging
8. voice/text response

## Blind Aid Vision Deep Dive

The current blind aid path is implemented across parser, orchestrator, UI, and phonecontrol modules:

- Activation/deactivation intents:
  - parser routes `activate blind aid`/`detect objects` to `detect_objects`
  - parser routes `deactivate blind aid`/`stop blind aid` to `deactivate_blind_aid`
- Safety behavior:
  - unlisted tools default to `STRONG_CONFIRM` in `SafetyGuard`, so blind aid activation is confirmation-gated.
- UI behavior:
  - `AgentScreen` shows a live `BlindAidCameraPreview` card when blind aid is active.
  - preview binds `Preview` + `ImageAnalysis` with `ProcessCameraProvider`.
  - camera resources are explicitly unbound on teardown.
- Analyzer behavior (`BlindAidManager`):
  - processes approx 1/6 frames (about 5 FPS from a 30 FPS feed).
  - runs ML Kit object detection on-device.
  - loads custom local model if found at:
    `Android/data/com.unoone.agent/files/models/gemma-local/custom_yolov8.tflite`
  - emits haptic + tone feedback when obstacle fill ratio crosses threshold.
  - emits throttled spoken feedback by object label.

Current limitation to document clearly:

- The live camera card currently does not render visual bounding boxes in Compose; feedback is voice/haptic/tone driven.

## Hardware Requirements

### Baseline (Runs app)

- Android 9+ (API 28+)
- ARM64 device
- 4 GB RAM
- 1 GB free storage
- Microphone

### Recommended (Smooth blind aid + voice)

- Android 12+ (API 31+)
- 6 to 8 GB RAM
- Mid/high-tier SoC with CameraX/ML Kit capable ISP
- 2+ GB free storage
- Rear camera + vibration motor

### Expert (Large local model workflows)

- 8+ GB RAM (12+ GB preferred)
- Modern NPU-capable chipset (Snapdragon 8-class / equivalent)
- 5+ GB free storage for optional larger model packs

## Permissions and Security

Manifest-declared permissions include:

- `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`
- `CAMERA`, `VIBRATE`
- `READ_CONTACTS`
- `READ_CALENDAR`, `WRITE_CALENDAR`
- `SYSTEM_ALERT_WINDOW`
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`
- `POST_NOTIFICATIONS`
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- storage compatibility permissions for model directories

Security model highlights:

- Unknown or sensitive tools default to strong confirmation.
- Accessibility actions require explicit user enablement.
- Screen capture path now filters for visible nodes and truncates oversized context payloads.

## Model and Storage Notes

Model folders expected under app external files:

- `gemma-local`
- `sherpa-asr`
- `sherpa-tts`
- `vad`
- `punctuation`
- `ocr-optional`

Workspace optimization file:

- `android-app/UnoOneAgent/.aiexclude` excludes heavy build/cache/model paths from AI/IDE scanning.

## Validation Commands

From `android-app/UnoOneAgent`:

```bash
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:compileDebugKotlin
```

## Canonical Technical README

For implementation-level details, see:

- `android-app/UnoOneAgent/README.md`
