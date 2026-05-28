# UnoOne Agent

> One private AI agent for every phone action.

UnoOne is a fully offline Android AI companion that understands voice commands, reads your screen, controls your apps, and automates repetitive tasks — all running locally on your device. No cloud, no accounts, no data leaves your phone.

---

## What It Does

| Capability | How It Works | Offline? |
|-----------|-------------|----------|
| Voice wake word | Sherpa-ONNX Keyword Spotter ("UnoOne") | Yes |
| Speech-to-text | Sherpa-ONNX offline transducer | Yes |
| Command parsing | Rule-based parser + on-device LLM fallback | Yes |
| Text-to-speech | Sherpa-ONNX Piper TTS | Yes |
| Screen reading | Accessibility tree text capture + ML Kit OCR | Yes |
| App control | Accessibility gestures (tap, scroll, type, swipe) | Yes |
| Skill automation | Record multi-step workflows, trigger by voice | Yes |
| Safety | 4-tier risk classifier with confirmation dialogs | Yes |
| Notes, calendar, browser | Android intents + ContentProvider queries | Yes |

---

## Supported Hardware

| Requirement | Minimum | Recommended |
|------------|---------|-------------|
| Android version | Android 9 (API 28) | Android 12+ (API 31+) |
| RAM | 4 GB | 8 GB+ |
| Storage (app) | 50 MB | 50 MB |
| Storage (models) | 120 MB | 200 MB |
| SoC | Snapdragon 660 / Exynos 9611 | Snapdragon 8 Gen 2+ / Dimensity 9200+ |
| Microphone | Required | — |
| Tested on | — | Xiaomi 14, Samsung Galaxy S24, Pixel 8 |

**Any Android 9+ device works.** The app automatically adapts to each manufacturer:
- **NNAPI** (hardware AI acceleration) is enabled when available, with CPU fallback
- **Autostart/battery prompts** target the correct settings screen for Xiaomi (MIUI), Huawei, Oppo, Vivo, OnePlus, and Asus — and gracefully skip on other devices
- **Standard Android APIs** (AccessibilityService, GestureDescription, intents) work identically across all manufacturers

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                         UI Layer                                │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐          │
│  │  Agent   │ │  Notes   │ │  Skills  │ │  Logs   │ │ Settings│
│  │  Screen  │ │  Screen  │ │  Screen  │ │  Screen  │ │ Screen  │
│  └────┬─────┘ └──────────┘ └────┬─────┘ └──────────┘ └─────────┘
│       │         Compose UI        │
│  ┌────┴──────────────────────────┴─────┐
│  │  FloatingAgentService (bubble)      │
│  │  WaveformVisualizer + Confirmation │
│  └────────────────┬───────────────────┘
└───────────────────┼──────────────────────────────────────────────┘
                    │
┌───────────────────┼──────────────────────────────────────────────┐
│              Agent Orchestrator                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  1. Skill Match → 2. Parse → 3. Permission → 4. Safety   │   │
│  │  → 5. Confirm? → 6. Execute → 7. Verify → 8. Speak     │   │
│  └──────────────────────────────────────────────────────────┘   │
└───────┬──────────┬──────────┬──────────┬──────────┬────────────┘
        │          │          │          │          │
   ┌────┴────┐ ┌───┴───┐ ┌───┴────┐ ┌───┴───┐ ┌───┴────────┐
   │  Voice  │ │Local  │ │Safety  │ │ Phone │ │Accessibility│
   │ Module  │ │Brain  │ │Guard   │ │Control│ │   Control   │
   │         │ │       │ │        │ │       │ │             │
   │• Sherpa │ │•Rule  │ │•4-tier │ │•Intent│ │•Tap/Type    │
   │  STT    │ │ based │ │ risk   │ │  open │ │•Scroll/Swipe│
   │• Sherpa │ │ parser│ │ class. │ │•Email │ │•Go Back/Home│
   │  TTS    │ │•ONNX  │ │•Block  │ │•WA    │ │•Read Screen │
   │• Keyword│ │  LLM  │ │•Confirm│ │•Cal   │ │•Find+Click  │
   │  Spotter│ │  fallback││       │ │•OCR   │ │•Fill Fields │
   │•Android│ │•Memory│ │        │ │       │ │•Context Track│
   │  STT    │ │  context│        │ │       │ │             │
   └─────────┘ └───────┘ └────────┘ └───────┘ └─────────────┘
        │          │          │          │          │
┌───────┴──────────┴──────────┴──────────┴──────────┴─────────────┐
│                        Storage Layer                              │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌──────────┐ ┌───────────┐ │
│  │  Notes  │ │ Skills  │ │Memories │ │ActionLogs│ │ModelMeta  │ │
│  │  DAO    │ │  DAO    │ │  DAO    │ │   DAO    │ │   DAO      │ │
│  └─────────┘ └─────────┘ └─────────┘ └──────────┘ └───────────┘ │
│                        Room Database                              │
└──────────────────────────────────────────────────────────────────┘
```

### Module Dependency Graph

```
app ─┬─ core ──── Result, ToolCall, TimelineStep, Logger
     ├─ storage ─ Room entities, DAOs, UnoOneDatabase
     ├─ modelmanager ─ Model folder detection, checksums
     ├─ localbrain ── RuleBasedParser, PromptBuilder, LocalBrain (ONNX)
     ├─ voice ─────── AudioRecorder, SherpaSttEngine, SherpaTtsEngine,
     │                KeywordSpotterEngine, AndroidSttEngine, TtsPlayer,
     │                VoiceService (foreground), VoiceModule
     ├─ agentrouter ─ Tool registry, 10+ built-in tools
     ├─ safetyguard ─ RiskLevel classifier (DIRECT/CONFIRM/STRONG_CONFIRM/BLOCK)
     ├─ phonecontrol ─ PhoneControl, CalendarControl, OcrControl, PackageResolver
     ├─ memory ────── MemoryModule (preferences, corrections, keyword context)
     ├─ skills ────── SkillsModule (CRUD, trigger matching, JSON step storage)
     ├─ observability ─ Diagnostics (latency, success rates)
     └─ accessibilitycontrol ─ UnoOneAccessibilityService, AccessibilityControl
```

### Agent Loop (8 Steps)

```
Voice/Text Input
       │
       ▼
 1. Skill Match ── Is this a saved skill trigger? → Execute each step
       │
       ▼
 2. Parse ── RuleBasedParser first → ONNX LLM fallback → ToolCall JSON
       │
       ▼
 3. Permission Check ── Missing runtime permissions? → Request & pause
       │
       ▼
 4. Safety Classification ── DIRECT / CONFIRM / STRONG_CONFIRM / BLOCK
       │
       ▼
 5. Confirmation ── CONFIRM: allow/deny dialog
       │              STRONG_CONFIRM: type "confirm" to proceed
       │              BLOCK: reject immediately
       ▼
 6. Execute ── Dispatch to PhoneControl, AccessibilityControl, etc.
       │
       ▼
 7. Verify ── Log result, check success
       │
       ▼
 8. Speak/Display ── TTS for voice input, timeline update for text
```

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 1.9.23 |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM (manual DI, no Hilt/Koin) |
| Database | Room 2.6.1 (5 entities, 5 DAOs) |
| Serialization | kotlinx-serialization 1.6.3 |
| Coroutines | kotlinx-coroutines 1.8.0 |
| LLM Inference | ONNX Runtime 1.18.0 |
| Speech | Sherpa-ONNX 1.10.30 (STT, TTS, KWS) |
| OCR | Google ML Kit Text Recognition 19.0.0 |
| Accessibility | Android AccessibilityService + GestureDescription |
| Build | AGP 8.5.0, Gradle 8.7, KSP 1.9.23-1.0.20 |
| Target | Android 9 (API 28) → Android 14 (API 34) |

---

## Project Structure

```
UnoOne-Local-Agent/
├── android-app/UnoOneAgent/          # Android Studio project root
│   ├── app/                          # Application shell
│   │   └── src/main/java/com/unoone/agent/
│   │       ├── AgentOrchestrator.kt       # 8-step agent pipeline
│   │       ├── FloatingAgentService.kt    # Floating bubble + chat overlay
│   │       ├── MainActivity.kt            # Permissions, battery optimization
│   │       ├── PermissionManager.kt       # Permission + manufacturer autostart handling
│   │       ├── UnoOneApplication.kt       # App entry, orchestrator init
│   │       └── ui/
│   │           ├── screens/               # AgentScreen, NotesScreen, SkillsScreen, etc.
│   │           ├── components/            # WaveformVisualizer, ConfirmationDialog
│   │           ├── viewmodel/             # AgentViewModel, SkillsViewModel, etc.
│   │           ├── navigation/            # UnoOneNavHost, Screen
│   │           └── theme/                  # UnoOneTheme, Color, Type
│   ├── core/                         # Result, ToolCall, TimelineStep, RiskLevel, Logger
│   ├── storage/                       # Room entities, DAOs, UnoOneDatabase
│   ├── modelmanager/                  # Model folder detection, checksums
│   ├── localbrain/                    # RuleBasedParser, PromptBuilder, LocalBrain, SimpleTokenizer
│   ├── voice/                         # VoiceModule, VoiceService, AudioRecorder,
│   │                                  # SherpaSttEngine, SherpaTtsEngine, KeywordSpotterEngine,
│   │                                  # AndroidSttEngine, TtsPlayer
│   ├── agentrouter/                   # AgentRouter, tool registry
│   ├── safetyguard/                   # SafetyGuard, 4-tier risk classification
│   ├── phonecontrol/                  # PhoneControl, CalendarControl, OcrControl, PackageResolver
│   ├── memory/                        # MemoryModule (keyword context matching)
│   ├── skills/                        # SkillsModule (JSON step storage, trigger matching)
│   ├── observability/                 # Diagnostics (latency, success rates)
│   └── accessibilitycontrol/         # UnoOneAccessibilityService, AccessibilityControl
├── models/                            # On-device model files (pushed via ADB)
│   ├── gemma-local/                   # Intent classifier or Gemma 2B ONNX
│   ├── sherpa-asr/                    # Sherpa-ONNX ASR transducer model
│   ├── sherpa-tts/                   # Piper TTS model + espeak-ng-data
│   └── vad/                           # Keyword spotting model
├── scripts/                           # ADB push, checksum, test scripts
├── docs/                              # Architecture, test plans, setup guides
└── tests/                             # Manual test checklists
```

---

## Getting Started

### Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Android Studio | Latest stable | IDE, build, debug |
| Android SDK | API 34 | Compile target |
| JDK | 17 | Kotlin compilation |
| ADB | Latest | Device deployment, model push |
| scrcpy | Latest | Phone screen mirroring (optional) |
| Xiaomi 14 | — | Primary test device |

### Build & Run

```bash
# 1. Open in Android Studio
#    File → Open → UnoOne-Local-Agent/android-app/UnoOneAgent

# 2. Wait for Gradle sync (5-10 min first time)

# 3. Connect your Android device via USB with Developer Options + USB Debugging enabled

# 4. Click Run (▶) or:
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk

# 5. On first launch, grant:
#    - Microphone, Contacts, Calendar, Camera permissions
#    - Display over other apps (overlay)
#    - Accessibility Service (for deep control)
#    - Disable battery optimization
#    - Autostart permission (Xiaomi/Huawei/Oppo/Vivo/OnePlus/Asus)
```

### Push Model Files (Required for Voice)

```bash
# Sherpa-ONNX ASR model (~70 MB)
adb push models/sherpa-asr/ /sdcard/Android/data/com.unoone.agent/files/models/sherpa-asr/

# Sherpa-ONNX TTS (Piper) model (~30-60 MB)
adb push models/sherpa-tts/ /sdcard/Android/data/com.unoone.agent/files/models/sherpa-tts/

# Keyword spotting model (~10-30 MB)
adb push models/vad/ /sdcard/Android/data/com.unoone.agent/files/models/vad/

# Intent classifier or Gemma 2B ONNX (~5 MB - 1.5 GB)
adb push models/gemma-local/ /sdcard/Android/data/com.unoone.agent/files/models/gemma-local/
```

> Without model files, the app falls back to: Android `SpeechRecognizer` for STT (requires internet), rule-based command parsing for NLU, and no TTS output.

---

## Voice Commands

### Quick Commands (No Models Required)

| Command | Action |
|---------|--------|
| "Create a note: buy milk" | Saves note to Room DB |
| "Open Chrome" | Launches Chrome |
| "Open WhatsApp" | Launches WhatsApp |
| "Open calendar" | Opens calendar insert |
| "Read screen" | Reads all visible text via accessibility |
| "Scroll down" | Scrolls current app down |
| "Go back" | Presses back button |
| "Go home" | Presses home button |
| "Send whatsapp to 1234567890 saying hello" | Opens WhatsApp with message |

### Skill Commands

| Command | Action |
|---------|--------|
| "Teach you a skill called Morning to open Chrome then read screen" | Creates a reusable skill |
| "Morning" | Triggers the saved skill's steps |

### Deep Control (Accessibility Required)

| Command | Action |
|---------|--------|
| "Find and click Login" | Scrolls to find "Login" text and taps it |
| "Fill username with john@example.com" | Types into the field with "username" hint |
| "Swipe left" | Performs left swipe gesture |
| "Open notifications" | Opens notification shade |

---

## Module Details

### Voice Pipeline

```
AudioRecorder (16kHz PCM, continuous)
       │
       ▼
KeywordSpotterEngine ("UnoOne" wake word)
       │ detected
       ▼
VAD Silence Detection (energy-based)
       │ pause detected
       ▼
SherpaSttEngine (offline transducer)
       │ or fallback
       ▼
AndroidSttEngine (Google SpeechRecognizer)
       │
       ▼
AgentOrchestrator.processCommand(text, VOICE)
       │
       ▼
SherpaTtsEngine (Piper voice) → TtsPlayer (AudioTrack)
```

### Accessibility Control

All actions go through `UnoOneAccessibilityService` which requires explicit user enablement:

| Method | What It Does |
|--------|-------------|
| `clickNodeWithText(text)` | Finds and clicks a node by its text |
| `clickAt(x, y)` | Clicks at exact coordinates |
| `typeTextIntoFocused(text)` | Types into the currently focused input |
| `fillFieldWithText(hint, text)` | Finds editable by hint and fills it |
| `scrollDown()` / `scrollUp()` | Scrolls via gesture swipe |
| `swipe(direction)` | Swipes left/right/up/down |
| `longPress(x, y)` | Long press at coordinates |
| `goBack()` / `goHome()` | Global navigation actions |
| `openNotifications()` / `openRecents()` | System UI actions |
| `findAndClick(text)` | Scrolls to find text, then clicks it |
| `captureVisibleText()` | Reads all text from the accessibility tree |

### Safety Guard

Every action is classified before execution:

| Risk Level | Behavior | Example |
|-----------|----------|---------|
| DIRECT | Execute immediately | Create note, open Chrome |
| CONFIRM | Show allow/deny dialog | Send WhatsApp, open camera |
| STRONG_CONFIRM | Type "confirm" to proceed | Draft email, fill passwords |
| BLOCK | Reject immediately | Destructive actions |

---

## Permissions

| Permission | Purpose | Required |
|-----------|---------|----------|
| `RECORD_AUDIO` | Voice commands, wake word | Yes |
| `READ_CONTACTS` | Email/WhatsApp contact resolution | Yes |
| `READ_CALENDAR` / `WRITE_CALENDAR` | Calendar event queries | Yes |
| `CAMERA` | Open camera intent | Yes |
| `POST_NOTIFICATIONS` | Foreground service notification (API 33+) | Yes |
| `SYSTEM_ALERT_WINDOW` | Floating bubble overlay | Yes |
| Accessibility Service | Deep app control (tap, scroll, type) | Yes |
| `MANAGE_EXTERNAL_STORAGE` | Model file management (API 30+) | Optional |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prevent battery optimization from killing background service | Recommended |
| `FOREGROUND_SERVICE_MICROPHONE` | Background wake word detection | Yes |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Floating bubble service | Yes |

On Xiaomi devices, the app also requests:
- **Autostart permission** via MIUI Security Center
- **Battery optimization exemption** via system dialog
- Service restart on `onTaskRemoved` for background resilience

The same autostart prompts work on Huawei, Oppo, Vivo, OnePlus, and Asus — each targets the manufacturer's specific settings screen. On Pixel/Samsung/Motorola, the standard battery optimization dialog is sufficient.

---

## Models

| Model | Size | Purpose | Source |
|-------|------|---------|--------|
| Sherpa-ONNX ASR | ~70 MB | Offline speech-to-text | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/releases) |
| Sherpa-ONNX TTS (Piper) | ~30-60 MB | Offline text-to-speech | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/releases) |
| Keyword Spotter | ~10-30 MB | Wake word "UnoOne" | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/releases) |
| Intent Classifier | ~5-20 MB | On-device command classification | Custom-trained ONNX |
| Gemma 2B (optional) | ~1.5-5 GB | Full generative LLM | [Kaggle](https://www.kaggle.com/models/google/gemma-2b) |

All models are stored in app-private storage and never leave the device.

---

## Key Files Quick Reference

| File | Purpose |
|------|---------|
| `app/.../AgentOrchestrator.kt` | 8-step agent pipeline, skill matching, confirmation flow |
| `app/.../FloatingAgentService.kt` | Floating bubble + chat overlay with voice input |
| `app/.../MainActivity.kt` | Permissions, battery optimization, MIUI autostart |
| `app/.../PermissionManager.kt` | Runtime, system, permanent denial, manufacturer-specific autostart handling |
| `localbrain/.../RuleBasedParser.kt` | 20+ command patterns with compound command support |
| `localbrain/.../LocalBrain.kt` | ONNX inference engine (rule fallback when no model) |
| `voice/.../VoiceService.kt` | Foreground service with KWS → VAD → STT → command pipeline |
| `voice/.../SherpaSttEngine.kt` | Real Sherpa-ONNX offline STT |
| `voice/.../SherpaTtsEngine.kt` | Real Piper TTS via Sherpa-ONNX |
| `voice/.../KeywordSpotterEngine.kt` | Wake word detection |
| `accessibilitycontrol/.../UnoOneAccessibilityService.kt` | Screen automation (tap, scroll, type, read) |
| `accessibilitycontrol/.../AccessibilityControl.kt` | High-level Result-based API wrapper |
| `skills/.../SkillsModule.kt` | Skill CRUD with kotlinx-serialization |
| `memory/.../MemoryModule.kt` | Keyword-based context retrieval |
| `phonecontrol/.../OcrControl.kt` | ML Kit on-device OCR |
| `app/.../ui/components/WaveformVisualizer.kt` | Animated waveform during voice input |
| `app/.../ui/components/ConfirmationDialog.kt` | CONFIRM/STRONG_CONFIRM safety dialogs |

---

## License

Proprietary — UnoOne Product.