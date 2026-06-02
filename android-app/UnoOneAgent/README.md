<div align="center">

# UnoOneAgent Android
### Technical Implementation README

<p align="center">
  <img src="https://img.shields.io/badge/Build-Gradle%208.7-1f6feb?style=for-the-badge" alt="Gradle">
  <img src="https://img.shields.io/badge/Min%20SDK-28-success?style=for-the-badge" alt="Min SDK 28">
  <img src="https://img.shields.io/badge/Modules-13-0ea5e9?style=for-the-badge" alt="13 Modules">
  <img src="https://img.shields.io/badge/Vision-CameraX%20%2B%20ML%20Kit-2563eb?style=for-the-badge" alt="Vision">
</p>

</div>

> This document is implementation-aligned and intentionally avoids over-claims.

## Quick Navigation

- [Implementation Snapshot](#implementation-snapshot)
- [Module Architecture](#module-architecture)
- [Blind Aid Vision Deep Dive](#blind-aid-vision-deep-dive)
- [Voice Runtime Behavior](#voice-runtime-behavior)
- [Accessibility and Safety Hardening](#accessibility-and-safety-hardening)
- [Permissions and Hardware](#permissions-and-hardware)
- [Validation Commands](#validation-commands)
- [Known Integration Gaps](#known-integration-gaps)

## Implementation Snapshot

| Capability | Status | Notes |
|---|---|---|
| Compose app shell + overlay | Implemented | Main UI + floating chat bubble |
| Orchestrator pipeline | Implemented | Parse -> permission -> safety -> execute -> verify |
| Blind Aid camera mode | Implemented | Live CameraX preview + analyzer feedback |
| Voice input APIs | Implemented | Unified start/stop transcribe interfaces |
| Parser blind-aid fixes | Implemented | Activation/deactivation collision corrected |
| Parser tests | Implemented | JUnit coverage for blind aid and note triggers |

## Module Architecture

| Module | Primary responsibility | Core files |
|---|---|---|
| `:app` | app shell, orchestration, permissions, UI | `MainActivity.kt`, `AgentOrchestrator.kt`, `AgentScreen.kt`, `FloatingAgentService.kt` |
| `:core` | shared primitives and logging | `Result.kt`, `ToolCall.kt`, `TimelineStep.kt` |
| `:storage` | Room persistence layer | `UnoOneDatabase.kt`, DAOs, entities |
| `:modelmanager` | model folder management and checksum verification | `ModelManager.kt` |
| `:localbrain` | parsing/inference utilities | `RuleBasedParser.kt`, `LocalBrain.kt`, `PromptBuilder.kt`, `RAGManager.kt` |
| `:voice` | recorder, STT/TTS engines, voice service | `VoiceModule.kt`, `AndroidSttEngine.kt`, `VoiceService.kt` |
| `:agentrouter` | tool registry and routing fallback | `AgentRouter.kt` |
| `:safetyguard` | risk classification policy | `SafetyGuard.kt` |
| `:phonecontrol` | intents, OCR, object detection, blind aid analyzer | `PhoneControl.kt`, `OcrControl.kt`, `ObjectDetectionControl.kt`, `BlindAidManager.kt` |
| `:memory` | preference/correction/pattern memory | `MemoryModule.kt` |
| `:skills` | skill CRUD and trigger execution | `SkillsModule.kt` |
| `:observability` | local diagnostics helper | `Diagnostics.kt` |
| `:accessibilitycontrol` | accessibility service wrappers | `UnoOneAccessibilityService.kt`, `AccessibilityControl.kt` |

## Blind Aid Vision Deep Dive

### Command and state flow

- `RuleBasedParser` maps activation phrases to `detect_objects`.
- `RuleBasedParser` maps stop phrases to `deactivate_blind_aid`.
- `AgentOrchestrator` toggles `isBlindAidActive` and emits spoken state feedback.
- `AgentScreen` binds preview visibility to blind aid state.

### Camera pipeline

`BlindAidCameraPreview`:

- obtains `ProcessCameraProvider`
- binds `Preview` and `ImageAnalysis` to lifecycle owner
- uses `STRATEGY_KEEP_ONLY_LATEST`
- unbinds camera providers on dispose
- releases `BlindAidManager` on dispose

### Analyzer behavior

`BlindAidManager`:

- throttles processing to 1 in 6 frames (about 5 FPS at 30 FPS input)
- runs ML Kit object detection in single-image mode
- attempts local custom model load from:
  - `Android/data/com.unoone.agent/files/models/gemma-local/custom_yolov8.tflite`
- falls back to default ML Kit detector when custom model is absent
- computes obstacle proximity from fill ratio
- emits:
  - vibration intensity scaling
  - tone beeps
  - throttled spoken guidance via callback

### Current visual limitation

- no Compose-rendered bounding-box overlay is currently drawn over camera preview

## Voice Runtime Behavior

### In-app mic path (Agent screen and overlay)

Current code path uses:

- `VoiceModule.startRecording(context, scope)`
- `VoiceModule.stopAndTranscribe()`

Runtime behavior:

- falls back to Android SpeechRecognizer when Sherpa STT is not initialized
- Android STT `onRmsChanged` is wired to waveform amplitude updates
- recognizer-first fallback avoids recorder resource contention

### Background VoiceService path

`VoiceService` includes:

- wake-word loop scaffolding
- Sherpa STT/TTS init attempts
- keyword spotting and RMS VAD loop

Current integration gap:

- service callbacks (`onWakeWordDetected`, `onCommandReceived`) are not fully wired into orchestrator command dispatch

## Accessibility and Safety Hardening

Implemented hardening:

- `captureVisibleText()` filters by `isVisibleToUser`
- traversal recycles child nodes to reduce leak pressure
- `AccessibilityControl.captureScreenText()` truncates payloads above 100,000 chars

Safety behavior:

- `SafetyGuard` maps known tools to explicit risk levels
- unknown tools default to `STRONG_CONFIRM`
- blind aid activation currently falls under strong confirmation unless rule table is expanded

## Permissions and Hardware

### Manifest permission profile

- audio: `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`
- camera and haptics: `CAMERA`, `VIBRATE`
- contacts/calendar: `READ_CONTACTS`, `READ_CALENDAR`, `WRITE_CALENDAR`
- overlay control: `SYSTEM_ALERT_WINDOW`
- background execution: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`
- storage compatibility permissions for model access

### Hardware tiers

| Tier | Recommended profile |
|---|---|
| Minimum (functional baseline) | Android 9+ (API 28), 4 GB RAM, 1 GB free storage, microphone |
| Recommended (smooth blind aid + voice UX) | Android 12+, 6 to 8 GB RAM, rear camera with stable autofocus, vibration motor, 2+ GB free storage |
| Expert local model tier | 8+ GB RAM (12+ preferred), modern NPU-capable chipset, additional multi-GB model storage |

### Model folder expectations

App-managed model directories under app external files:

- `gemma-local`
- `sherpa-asr`
- `sherpa-tts`
- `vad`
- `punctuation`
- `ocr-optional`

## Validation Commands

Run from this directory (`android-app/UnoOneAgent`):

```bash
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:compileDebugKotlin
```

## Known Integration Gaps

These components exist but are not yet fully integrated end-to-end:

- `LocalBrain.runInference()` currently returns mock JSON placeholder output.
- `RAGManager` exists but is not wired into orchestrator runtime flow.
- `Diagnostics` helpers exist but are not broadly instrumented across execution paths.
- `VoiceService` callback outputs are not fully wired into orchestrator dispatch.

This README is intentionally explicit about these gaps to keep architecture and hardware guidance accurate.
