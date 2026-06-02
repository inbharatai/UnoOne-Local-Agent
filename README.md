<div align="center">

# UnoOne Local Agent

### Private Android Assistant for On-Device Automation and Sensory Navigation

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-success?style=for-the-badge&logo=android" alt="Android">
  <img src="https://img.shields.io/badge/Language-Kotlin-7f52ff?style=for-the-badge&logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/UI-Compose%20%2B%20Material3-1f6feb?style=for-the-badge&logo=jetpackcompose" alt="Compose">
  <img src="https://img.shields.io/badge/Architecture-13%20Modules-0ea5e9?style=for-the-badge" alt="13 Modules">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Min%20SDK-28%20(Android%209)-22c55e?style=flat-square" alt="Min SDK 28">
  <img src="https://img.shields.io/badge/Mode-Offline--First-f59e0b?style=flat-square" alt="Offline First">
  <img src="https://img.shields.io/badge/Vision-CameraX%20%2B%20ML%20Kit-2563eb?style=flat-square" alt="Vision Stack">
  <img src="https://img.shields.io/badge/Safety-DIRECT%20%7C%20CONFIRM%20%7C%20STRONG__CONFIRM%20%7C%20BLOCK-ef4444?style=flat-square" alt="Safety Levels">
</p>

</div>

## Quick Navigation

- [What It Is](#what-it-is)
- [Current Runtime Truth](#current-runtime-truth)
- [Blind Aid Vision Deep Dive](#blind-aid-vision-deep-dive)
- [Architecture](#architecture)
- [Hardware Requirements](#hardware-requirements)
- [Permissions and Safety](#permissions-and-safety)
- [Validation Commands](#validation-commands)

## What It Is

UnoOne Local Agent is an Android-first assistant for:

- deep app control via AccessibilityService
- voice and text command execution through a safety-gated orchestrator
- on-device visual assistance workflows using CameraX + ML Kit
- local persistence of notes, skills, memory, logs, and model metadata

The workspace is split into two documentation layers:

- this root README for product/architecture overview
- `android-app/UnoOneAgent/README.md` for implementation-level technical details

## Current Runtime Truth

This section intentionally documents current behavior without over-claiming.

| Area | Current behavior in code |
|---|---|
| Voice in app UI | `VoiceModule.startRecording/stopAndTranscribe` is active in UI; if Sherpa STT is not explicitly initialized, Android SpeechRecognizer path is used |
| Background wake-word service | `VoiceService` includes KWS/STT loop scaffolding, but command callback wiring into orchestrator is not fully end-to-end |
| LLM inference path | `LocalBrain` ONNX wrapper exists, but `runInference()` still returns a mock JSON placeholder |
| RAG and diagnostics | `RAGManager` and `Diagnostics` utilities exist but are not broadly integrated in orchestrator runtime flow |

## Blind Aid Vision Deep Dive

### Command and state path

- parser maps `activate blind aid` and related phrases to `detect_objects`
- parser maps `deactivate blind aid` and related phrases to `deactivate_blind_aid`
- orchestrator toggles blind aid state and voice feedback
- UI mounts `BlindAidCameraPreview` when blind aid is active

### Camera and analyzer path

`BlindAidCameraPreview`:

- binds `Preview` + `ImageAnalysis` with `ProcessCameraProvider`
- uses `STRATEGY_KEEP_ONLY_LATEST`
- unbinds providers on dispose
- releases `BlindAidManager` on teardown

`BlindAidManager`:

- processes 1 out of every 6 frames (about 5 FPS on a 30 FPS stream)
- performs on-device ML Kit object detection
- optionally loads local custom model from:
  - `Android/data/com.unoone.agent/files/models/gemma-local/custom_yolov8.tflite`
- computes proximity from bounding box fill ratio
- emits feedback channels:
  - vibration intensity
  - tone beeps
  - spoken obstacle prompts

Current visual limitation:

- no Compose-rendered bounding-box overlay is currently drawn in the camera preview

## Architecture

### Module map (13 modules)

| Module | Responsibility |
|---|---|
| `:app` | Compose UI, overlay service, command orchestration, permission handling |
| `:core` | Shared models and logging primitives |
| `:storage` | Room database entities and DAOs |
| `:modelmanager` | Model folder detection, checksums, storage metrics |
| `:localbrain` | Rule parser, prompt helpers, ONNX wrapper, RAG utility |
| `:voice` | Recorder, STT/TTS engines, foreground voice service |
| `:agentrouter` | Tool registration and fallback routing |
| `:safetyguard` | Risk classification policy |
| `:phonecontrol` | Device intents, OCR, object detection, blind aid analyzer |
| `:memory` | Preference/correction/pattern memory retrieval |
| `:skills` | Skill CRUD and trigger execution |
| `:observability` | Local diagnostics helper functions |
| `:accessibilitycontrol` | Accessibility gestures, input, screen text capture |

### Orchestrator flow

`AgentOrchestrator` processes commands in this order:

1. skill trigger lookup
2. parser/inference selection
3. permission gate
4. safety classification
5. confirmation gate (if required)
6. tool execution
7. verification/logging
8. spoken or textual response

## Hardware Requirements

| Tier | Recommended baseline |
|---|---|
| Baseline (functional) | Android 9+ (API 28), ARM64, 4 GB RAM, 1 GB free storage, microphone |
| Recommended (smooth blind aid + voice UX) | Android 12+, 6 to 8 GB RAM, rear camera, vibration motor, 2+ GB free storage |
| Expert local-model tier | 8+ GB RAM (12+ preferred), modern NPU-capable chipset, additional multi-GB model storage |

## Permissions and Safety

### Manifest-declared capabilities include

- `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`
- `CAMERA`, `VIBRATE`
- `READ_CONTACTS`
- `READ_CALENDAR`, `WRITE_CALENDAR`
- `SYSTEM_ALERT_WINDOW`
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`
- `POST_NOTIFICATIONS`
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- storage compatibility permissions for model directories

### Safety behavior

- explicit tool rules in `SafetyGuard` map to `DIRECT`, `CONFIRM`, `STRONG_CONFIRM`, `BLOCK`
- unknown tool names default to `STRONG_CONFIRM`
- accessibility actions require explicit user enablement
- screen capture now filters to visible nodes and truncates oversized payloads

## Model and Storage Notes

Expected app-managed model directories:

- `gemma-local`
- `sherpa-asr`
- `sherpa-tts`
- `vad`
- `punctuation`
- `ocr-optional`

Workspace optimization:

- `android-app/UnoOneAgent/.aiexclude` excludes heavy build/cache/model paths from AI/IDE scanning

## Validation Commands

Run from `android-app/UnoOneAgent`:

```bash
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:compileDebugKotlin
```

## Canonical Technical README

For implementation-level details, see:

- `android-app/UnoOneAgent/README.md`
