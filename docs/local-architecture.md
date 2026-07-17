# UnoOne Local Architecture

## Overview

UnoOne is a local-first Android AI agent. Every core capability runs on the device. Cloud is optional and only used for model downloads, never for inference or data storage.

## Core Principles

1. **Privacy first** — voice, text, and actions never leave the device.
2. **Offline capable** — after models are installed, airplane mode is a valid state.
3. **Safe by default** — every action passes through a 4-tier risk classifier.
4. **Transparent** — every step of the agent loop is visible in the timeline UI.
5. **Modular** — each capability is a discrete Gradle module with clear interfaces.

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                         UI Layer                             │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐      │
│  │  Agent   │ │  Notes   │ │  Skills  │ │  Logs    │      │
│  │  Screen  │ │  Screen  │ │  Screen  │ │  Screen  │      │
│  └────┬─────┘ └──────────┘ └────┬─────┘ └──────────┘      │
│       │         Compose UI        │                        │
│  ┌────┴──────────────────────────┴─────────────────────┐   │
│  │  FloatingAgentService (bubble overlay)               │   │
│  │  WaveformVisualizer + ConfirmationDialog             │   │
│  └────────────────────┬───────────────────────────────┘   │
└────────────────────────┼──────────────────────────────────┘
                         │
┌────────────────────────┼──────────────────────────────────┐
│              Agent Orchestrator                            │
│  ┌──────────────────────────────────────────────────────┐ │
│  │  1. Skill Match → 2. Parse → 3. Permission → 4. Safety│ │
│  │  → 5. Confirm? → 6. Execute → 7. Verify → 8. Speak  │ │
│  └──────────────────────────────────────────────────────┘ │
└───────┬──────────┬──────────┬──────────┬─────────────────┘
        │          │          │          │
   ┌────┴────┐ ┌───┴───┐ ┌───┴────┐ ┌───┴────────────┐
   │  Voice  │ │Local  │ │Safety  │ │ Accessibility   │
   │ Module  │ │Brain  │ │Guard   │ │    Control       │
   │         │ │       │ │        │ │                  │
   │• Sherpa │ │•Rule  │ │•4-tier │ │•Tap/Type/Fill   │
   │  STT    │ │ based │ │ risk   │ │•Scroll/Swipe    │
   │• Sherpa │ │ parser│ │ class. │ │•Go Back/Home    │
   │  TTS    │ │•ONNX  │ │•Block  │ │•Read Screen     │
   │• Keyword│ │  LLM  │ │•Confirm│ │•Find+Click      │
   │  Spotter│ │•Memory│ │        │ │•Context Track   │
   │•Android│ │  context│        │ │•Notifications   │
   │  STT   │ │       │ │        │ │•Long Press      │
   └─────────┘ └───────┘ └────────┘ └──────────────────┘
        │          │          │          │
   ┌────┴────┐                                                  │
   │ Phone   │                                                  │
   │ Control │  ┌────────┐  ┌──────┐  ┌──────┐  ┌───────┐    │
   │•Chrome  │  │ Agent  │  │Memory│  │Skills │  │Observ- │    │
   │•URL     │  │ Router │  │Module│  │Module│  │ability │    │
   │•Apps    │  │        │  │      │  │      │  │        │    │
   │•Email   │  │10+ tool│  │Keywrd│  │CRUD  │  │Metrics │    │
   │•WhatsApp│  │registry│  │context│  │trigger│  │        │    │
   │•Calendar│  └────────┘  └──────┘  └──────┘  └───────┘    │
   │•Camera  │                                                  │
   │•Dialer  │                                                  │
   │•OCR     │  ┌──────────────────────────────────────────────┐│
   └─────────┘  │              Storage Layer                    ││
                │  ┌──────────┐ ┌──────────┐ ┌──────────────┐  ││
                │  │   Room   │ │  Local   │ │ Action Logs  │  ││
                │  │    DB    │ │  Files   │ │ & Diagnostics │  ││
                │  └──────────┘ └──────────┘ └──────────────┘  ││
                └──────────────────────────────────────────────┘│
```

## Module Descriptions

### 1. app (Application Shell)
- **MainActivity**: Permission requests with "Go to Settings" for permanent denials, battery optimization, MIUI autostart, re-execute after grant
- **FloatingAgentService**: Bubble overlay with chat card, voice input toggle, timeline display
- **AgentOrchestrator**: 8-step pipeline with skill matching, confirmation flow, pending command re-execution
- **PermissionManager**: Runtime + system + permanent denial handling, battery optimization, MIUI support
- **Compose UI**: AgentScreen (waveform + progress), SkillsScreen (CRUD), NotesScreen, LogsScreen, SettingsScreen
- **ConfirmationDialog**: CONFIRM (allow/deny) and STRONG_CONFIRM (type "confirm")

### 2. core
- `Result<T>` — Success/Error sealed class
- `ToolCall` — Structured tool invocation (tool name + JSON args)
- `TimelineStep` — Agent execution step with status, label, detail, timestamp
- `RiskLevel` — DIRECT / CONFIRM / STRONG_CONFIRM / BLOCK
- `InputType` — TEXT / VOICE
- `AgentStatus` — LISTENING / TRANSCRIBING / UNDERSTANDING / TOOL_SELECTED / SAFETY_CHECK / EXECUTING / VERIFYING / SPEAKING / DONE / FAILED

### 3. storage
- Room database with 5 entities: NoteEntity, SkillEntity, MemoryEntity, ActionLogEntity, ModelMetadataEntity
- 5 DAOs with Flow-based reactive queries
- Thread-safe singleton via DatabaseProvider

### 4. modelmanager
- Detects model folders under app-private storage
- Verifies SHA-256 checksums
- Reports model status (missing/present/loaded/error) and storage usage

### 5. localbrain
- **RuleBasedParser**: 20+ command patterns including scroll, swipe, go back/home, read screen, find+click, fill, compound commands
- **PromptBuilder**: Generates structured prompts with tool list and memory context
- **LocalBrain**: ONNX session creation with NNAPI hardware acceleration, rule-based fallback, mock inference (real inference requires model files + tokenizer)

### 6. voice
- **VoiceService**: Foreground service with continuous audio pipeline: AudioRecorder → KeywordSpotter → VAD → STT → command → TTS
- **SherpaSttEngine**: Real Sherpa-ONNX OfflineRecognizer, PCM-to-float conversion
- **SherpaTtsEngine**: Real Sherpa-ONNX OfflineTts (Piper), feeds audio to TtsPlayer
- **KeywordSpotterEngine**: Wake word detection via Sherpa-ONNX KeywordSpotter
- **AndroidSttEngine**: Fallback using Android SpeechRecognizer (requires internet)
- **AudioRecorder**: 16kHz PCM capture with RMS amplitude reporting for waveform visualization
- **TtsPlayer**: PCM float → 16-bit → AudioTrack playback
- **VoiceModule**: Unified API with STT/TTS init, Android fallback, amplitude callback

### 7. agentrouter
- Tool registry with 10+ built-in tools
- Command classification and routing
- Extensible `register()` pattern for new tools

### 8. safetyguard
- 4-tier risk classification: DIRECT, CONFIRM, STRONG_CONFIRM, BLOCK
- Maps tool names to risk levels
- CONFIRM → simple allow/deny dialog
- STRONG_CONFIRM → type "confirm" to proceed
- BLOCK → reject immediately

### 9. phonecontrol
- **PhoneControl**: Intent-based actions for Chrome, URLs, apps, calendar, camera, dialer, share
- **CalendarControl**: Read events via ContentProvider, insert via intent
- **OcrControl**: Google ML Kit on-device text recognition
- **PackageResolver**: Friendly name → package name mapping

### 10. memory
- **MemoryModule**: Store preferences, corrections, patterns
- **getRelevantContext()**: Keyword matching over preferences/corrections/patterns for prompt enrichment
- Room-backed via MemoryDao

### 11. skills
- **SkillsModule**: Full CRUD with kotlinx-serialization for step storage
- Trigger matching via comma-separated phrases
- Recursive step execution via AgentOrchestrator.processCommand()
- SkillsScreen: Create dialog with name + steps (one per line), toggle enable/disable, delete

### 12. observability
- Local diagnostics only (no network)
- Latency tracking per action
- Success/failure rates
- Model load times

### 13. accessibilitycontrol
- **UnoOneAccessibilityService**: Android AccessibilityService with singleton pattern
  - `clickAt(x, y)` — coordinate-based tap via GestureDescription
  - `clickNodeWithText(text)` — find and click by accessibility text
  - `typeTextIntoFocused(text)` — type into focused input
  - `fillFieldWithText(hint, text)` — find editable by hint and fill
  - `scrollDown()` / `scrollUp()` — gesture-based scrolling
  - `swipe(startX, startY, endX, endY, duration)` — gesture swipe
  - `longPress(x, y)` — 1000ms gesture hold
  - `goBack()` / `goHome()` / `openNotifications()` / `openRecents()` — global actions
  - `captureVisibleText()` — reads all text from accessibility tree
  - `onAccessibilityEvent()` — tracks current foreground package and activity
- **AccessibilityControl**: High-level `Result<Unit>` API wrapping the service
  - `findAndClick(text, maxScrolls)` — scrolls to find text, then taps
  - `getCurrentContext()` — returns "package/activity" of foreground app

## Data Flow (Agent Loop)

```
1. User speaks or types a command
2. VoiceService → AudioRecorder → SherpaSttEngine (or Android fallback)
3. Text sent to AgentOrchestrator.processCommand()
4. Step 1: SkillsModule.findSkillByTrigger() — check for saved skill
5. Step 2: RuleBasedParser.parse() → LocalBrain.runInference() fallback → ToolCall JSON
6. Step 3: Check runtime permissions, request missing ones
7. Step 4: SafetyGuard.classify() → risk level
8. Step 5: If CONFIRM → dialog; if STRONG_CONFIRM → type "confirm"; if BLOCK → reject
9. Step 6: executeTool() dispatches to PhoneControl, AccessibilityControl, etc.
10. Step 7: Log result and verify
11. Step 8: If voice input → SherpaTtsEngine speaks response; otherwise → timeline update
```

## Security Model

- All model files stored in app-private storage (`Android/data/com.unoone.agent/files/models/`)
- No network permission required for core loop
- No data leaves device unless user explicitly exports logs
- AccessibilityService requires explicit user enablement in Settings
- SafetyGuard blocks destructive actions; CONFIRM/STRONG_CONFIRM require explicit approval
- Permissions requested with "Go to Settings" for permanently denied permissions
- Service restart on task removal (Xiaomi battery optimization resilience)

## Offline-First Stack

| Capability            | On-Device Technology                      | Fallback           |
|----------------------|------------------------------------------|--------------------|
| Speech-to-Text       | Sherpa-ONNX (English transducer; Indic Omnilingual CTC) | Locale-pinned Android SpeechRecognizer when explicitly enabled |
| Text-to-Speech       | Sherpa-ONNX VITS (English Coqui; Indic MMS) | Silent (no output) |
| Command Parsing       | RuleBasedParser (20+ patterns)          | Same (primary)     |
| LLM Inference        | LiteRT-LM with Gemma 3n E4B            | CPU inference / Rule-based parser |
| Screen Reading       | Accessibility tree text capture          | ML Kit OCR         |
| Phone Actions        | Android Intents + ContentProvider        | N/A                |
| Gesture Control      | AccessibilityService + GestureDescription | N/A                |
| Audio Recording      | Android AudioRecord API (16kHz PCM)     | N/A                |
| Keyword Spotting     | Sherpa-ONNX KeywordSpotter               | Manual activation  |
| Memory/Context       | Room SQLite keyword matching             | N/A                |

## Extensibility

- **Cloud expansion**: add optional cloud LLM module behind feature flag
- **New tools**: implement Tool interface + register in AgentRouter
- **New models**: drop-in replacement via ModelManager
- **Localization**: prompt templates and TTS voice packs per language
- **New skills**: users create skills via voice ("Teach you a skill called X to Y then Z")

## Device Adaptation

The app automatically adapts to each Android manufacturer:

- **NNAPI hardware acceleration**: Enabled when available (Snapdragon, Exynos, Dimensity, Tensor), with CPU fallback for devices without an NPU
- **Autostart/battery settings**: Automatically opens the correct manufacturer settings screen:
  - Xiaomi/Redmi → MIUI Security Center autostart
  - Huawei/Honor → System Manager power optimization
  - Oppo → ColorOS Safe Center autostart
  - Vivo → iManager power management
  - OnePlus → Security autolaunch
  - Asus → Mobile Manager power saver
  - Samsung/Pixel/Motorola → Standard battery optimization dialog
- **Battery optimization exemption**: Requested via standard Android intent on all devices
- **Service restart on `onTaskRemoved`**: Ensures VoiceService survives background kills on all manufacturers
- **Foreground service with persistent notification**: Required for continuous wake word detection
