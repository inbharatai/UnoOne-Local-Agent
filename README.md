<div align="center">

# 🤖 UnoOne Agent

### **Your Phone. Your Intelligence. Your Privacy.**

> A fully offline Android AI companion that lives on your device —  
> understanding voice, reading screens, controlling apps, and automating tasks.  
> **Zero cloud. Zero accounts. Zero data leaves your phone.**

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green?style=for-the-badge&logo=android" alt="Android">
  <img src="https://img.shields.io/badge/Language-Kotlin-purple?style=for-the-badge&logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/UI-Compose%20%2B%20Material%203-blue?style=for-the-badge&logo=jetpackcompose" alt="Compose">
  <img src="https://img.shields.io/badge/Privacy-100%25%20Offline-critical?style=for-the-badge&logo=privacyguides" alt="Offline">
  <img src="https://img.shields.io/badge/License-Proprietary-red?style=for-the-badge" alt="License">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/API-28%2B%20(Android%209)-success?style=flat-square" alt="API 28+">
  <img src="https://img.shields.io/badge/Modules-13-9cf?style=flat-square" alt="13 Modules">
  <img src="https://img.shields.io/badge/Architecture-MVVM-informational?style=flat-square" alt="MVVM">
  <img src="https://img.shields.io/badge/Safety-4%20Tier%20Classifier-orange?style=flat-square" alt="Safety">
  <img src="https://img.shields.io/badge/Voice-Sherpa--ONNX-blueviolet?style=flat-square" alt="Sherpa-ONNX">
</p>

---

</div>

## ✨ What UnoOne Does

<table>
<tr>
<td width="50%">

### 🎙️ Voice-First
Say **"UnoOne"** and speak naturally. Wake word detection, offline STT, and spoken responses — no internet needed.

</td>
<td width="50%">

### 📱 Deep Control
Tap, scroll, swipe, type, read screens — all through Android's Accessibility Service. Your phone, automated.

</td>
</tr>
<tr>
<td width="50%">

### 🧠 Local Intelligence
Rule-based parser handles 20+ command patterns instantly. ONNX LLM fallback for complex requests. Memory that learns your preferences.

</td>
<td width="50%">

### 🔒 Privacy by Design
Every byte stays on your device. No cloud APIs, no telemetry, no accounts. Your data never leaves your phone.

</td>
</tr>
</table>

---

## 🚀 Capabilities

| 🎯 Capability | ⚙️ How It Works | 📡 Offline? |
|:-------------|:----------------|:----------:|
| **Voice wake word** | Sherpa-ONNX Keyword Spotter ("UnoOne") | ✅ |
| **Speech-to-text** | Sherpa-ONNX offline transducer model | ✅ |
| **Command parsing** | Rule-based parser + on-device LLM fallback | ✅ |
| **Text-to-speech** | Sherpa-ONNX Piper TTS | ✅ |
| **Screen reading** | Accessibility tree capture + ML Kit OCR | ✅ |
| **App control** | Accessibility gestures (tap, scroll, type, swipe) | ✅ |
| **Skill automation** | Record multi-step workflows, trigger by voice | ✅ |
| **Safety guard** | 4-tier risk classifier with confirmation dialogs | ✅ |
| **Notes & Calendar** | Android intents + ContentProvider queries | ✅ |

---

## 💻 Supported Hardware

| ⚙️ Requirement | 🔻 Minimum | ✅ Recommended |
|:--------------|:-----------|:-------------|
| Android version | Android 9 (API 28) | Android 12+ (API 31+) |
| RAM | 4 GB | 8 GB+ |
| Storage (app) | 50 MB | 50 MB |
| Storage (models) | 120 MB | 200 MB |
| SoC | Snapdragon 660 / Exynos 9611 | Snapdragon 8 Gen 2+ / Dimensity 9200+ |
| Microphone | Required | — |

> **Works on any Android 9+ device.** UnoOne adapts automatically:
> - **NNAPI** hardware AI acceleration when available, CPU fallback otherwise
> - **Manufacturer-specific autostart** prompts for Xiaomi (MIUI), Huawei, Oppo, Vivo, OnePlus, Asus — gracefully skip on other devices
> - **Standard Android APIs** (AccessibilityService, GestureDescription, intents) work identically across all manufacturers

---

## 🏗️ Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                            ✨ UI LAYER                                │
│                                                                      │
│   ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐ ┌────────┐│
│   │  🤖 Agent│  │  📝 Notes│  │  ⚡ Skills│  │  📋 Logs │ │ ⚙️ Set ││
│   │  Screen  │  │  Screen  │  │  Screen  │  │  Screen  │ │ tings  ││
│   └────┬─────┘  └──────────┘  └────┬─────┘  └──────────┘ └────────┘│
│        │        Jetpack Compose UI      │                            │
│   ┌────┴───────────────────────────────┴─────┐                      │
│   │  💬 FloatingAgentService (bubble overlay) │                      │
│   │  🌊 WaveformVisualizer · ✅ Confirmation  │                      │
│   └─────────────────┬────────────────────────┘                      │
└──────────────────────┼───────────────────────────────────────────────┘
                       │
┌──────────────────────┼───────────────────────────────────────────────┐
│              🧠 AGENT ORCHESTRATOR (8-Step Pipeline)                  │
│                                                                      │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │  ① Skill Match → ② Parse → ③ Permission → ④ Safety          │  │
│   │  → ⑤ Confirm? → ⑥ Execute → ⑦ Verify → ⑧ Speak             │  │
│   └──────────────────────────────────────────────────────────────┘  │
└──────┬──────────┬──────────┬──────────┬──────────┬────────────────┘
       │          │          │          │          │
  ┌────┴────┐ ┌───┴───┐ ┌───┴────┐ ┌───┴───┐ ┌───┴────────┐
  │  🎙️    │ │ 🧠    │ │ 🛡️    │ │ 📱   │ │ ♿        │
  │  Voice  │ │ Local │ │ Safety │ │ Phone│ │ Access-  │
  │  Module │ │ Brain │ │ Guard  │ │Ctrl  │ │ ibility  │
  │         │ │       │ │        │ │      │ │ Control  │
  │ • STT   │ │ •Rule │ │ •4-tier│ │ •Open│ │ •Tap/Type│
  │ • TTS   │ │ parser│ │  risk  │ │ •Mail│ │ •Scroll │
  │ • KWS   │ │ •ONNX │ │  class │ │ •WA  │ │ •Swipe  │
  │ •Android│ │  LLM  │ │ •Block │ │ •Cal │ │ •Back   │
  │  fallback│ │ •Mem  │ │ •Conf. │ │ •OCR │ │ •Screen │
  └─────────┘ └───────┘ └────────┘ └──────┘ └──────────┘
       │          │          │          │          │
┌──────┴──────────┴──────────┴──────────┴──────────┴──────────────────┐
│                     💾 STORAGE LAYER (Room DB)                       │
│                                                                      │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌──────────┐  ┌──────────┐ │
│  │  Notes  │  │ Skills  │ │Memories │  │ActionLogs│  │ModelMeta │ │
│  │  DAO    │  │  DAO    │  │  DAO    │  │   DAO    │  │   DAO    │ │
│  └─────────┘  └─────────┘  └─────────┘  └──────────┘  └──────────┘ │
└──────────────────────────────────────────────────────────────────────┘
```

### Module Dependency Graph

```
app ─┬─ core ──────── Result, ToolCall, TimelineStep, Logger
     ├─ storage ───── Room entities, DAOs, UnoOneDatabase
     ├─ modelmanager ─ Model folder detection, checksums
     ├─ localbrain ─── RuleBasedParser, PromptBuilder, LocalBrain (ONNX)
     ├─ voice ─────── AudioRecorder, SherpaSttEngine, SherpaTtsEngine,
     │                KeywordSpotterEngine, AndroidSttEngine, TtsPlayer,
     │                VoiceService (foreground), VoiceModule
     ├─ agentrouter ── Tool registry, 10+ built-in tools
     ├─ safetyguard ── RiskLevel classifier (DIRECT/CONFIRM/STRONG_CONFIRM/BLOCK)
     ├─ phonecontrol ─ PhoneControl, CalendarControl, OcrControl, PackageResolver
     ├─ memory ─────── MemoryModule (preferences, corrections, keyword context)
     ├─ skills ─────── SkillsModule (CRUD, trigger matching, JSON step storage)
     ├─ observability ─ Diagnostics (latency, success rates)
     └─ accessibilitycontrol ─ UnoOneAccessibilityService, AccessibilityControl
```

### 🔄 Agent Loop — 8 Steps

```
  🎤 Voice / ⌨️ Text Input
         │
         ▼
  ① 🔍 Skill Match ────── Is this a saved skill trigger? → Execute each step
         │
         ▼
  ② 🧩 Parse ──────────── RuleBasedParser → ONNX LLM fallback → ToolCall JSON
         │
         ▼
  ③ 🔑 Permission Check ── Missing runtime permissions? → Request & pause
         │
         ▼
  ④ 🛡️ Safety Classify ─── DIRECT / CONFIRM / STRONG_CONFIRM / BLOCK
         │
         ▼
  ⑤ ⚠️ Confirmation ────── CONFIRM: allow/deny dialog
         │                  STRONG_CONFIRM: type "confirm" to proceed
         │                  BLOCK: reject immediately
         ▼
  ⑥ ⚡ Execute ─────────── Dispatch to PhoneControl, AccessibilityControl, etc.
         │
         ▼
  ⑦ ✅ Verify ──────────── Log result, check success
         │
         ▼
  ⑧ 🔊 Speak / Display ── TTS for voice input, timeline update for text
```

---

## 🛠️ Tech Stack

| Layer | Technology |
|:------|:-----------|
| **Language** | Kotlin 1.9.23 |
| **UI** | Jetpack Compose + Material 3 |
| **Architecture** | MVVM (manual DI, no Hilt/Koin) |
| **Database** | Room 2.6.1 (5 entities, 5 DAOs) |
| **Serialization** | kotlinx-serialization 1.6.3 |
| **Coroutines** | kotlinx-coroutines 1.8.0 |
| **LLM Inference** | ONNX Runtime 1.18.0 |
| **Speech** | Sherpa-ONNX 1.10.30 (STT, TTS, KWS) |
| **OCR** | Google ML Kit Text Recognition 19.0.0 |
| **Accessibility** | Android AccessibilityService + GestureDescription |
| **Build** | AGP 8.5.0 · Gradle 8.7 · KSP 1.9.23-1.0.20 |
| **Target** | Android 9 (API 28) → Android 14 (API 34) |

---

## 📂 Project Structure

```
UnoOne-Local-Agent/
├── android-app/UnoOneAgent/           # 📱 Android Studio project root
│   ├── app/                           # 🚀 Application shell
│   │   └── src/main/java/com/unoone/agent/
│   │       ├── AgentOrchestrator.kt       # 8-step agent pipeline
│   │       ├── FloatingAgentService.kt    # Floating bubble + chat overlay
│   │       ├── MainActivity.kt            # Permissions, battery optimization
│   │       ├── PermissionManager.kt       # Permission + manufacturer autostart
│   │       ├── UnoOneApplication.kt       # App entry, orchestrator init
│   │       └── ui/
│   │           ├── screens/               # AgentScreen, NotesScreen, SkillsScreen
│   │           ├── components/            # WaveformVisualizer, ConfirmationDialog
│   │           ├── viewmodel/             # AgentViewModel, SkillsViewModel
│   │           ├── navigation/            # UnoOneNavHost, Screen
│   │           └── theme/                 # UnoOneTheme, Color, Type
│   ├── core/                          # 📦 Result, ToolCall, TimelineStep, Logger
│   ├── storage/                       # 🗄️ Room entities, DAOs, UnoOneDatabase
│   ├── modelmanager/                  # 📂 Model folder detection, checksums
│   ├── localbrain/                    # 🧠 RuleBasedParser, PromptBuilder, LocalBrain
│   ├── voice/                         # 🎙️ VoiceModule, VoiceService, AudioRecorder
│   ├── agentrouter/                   # 🔀 AgentRouter, tool registry
│   ├── safetyguard/                   # 🛡️ SafetyGuard, 4-tier risk classification
│   ├── phonecontrol/                  # 📱 PhoneControl, CalendarControl, OcrControl
│   ├── memory/                        # 💭 MemoryModule (keyword context matching)
│   ├── skills/                        # ⚡ SkillsModule (JSON step storage)
│   ├── observability/                 # 📊 Diagnostics (latency, success rates)
│   └── accessibilitycontrol/         # ♿ UnoOneAccessibilityService, AccessibilityControl
├── models/                            # 🧠 On-device model files (pushed via ADB)
│   ├── gemma-local/                   # Intent classifier or Gemma 2B ONNX
│   ├── sherpa-asr/                    # Sherpa-ONNX ASR transducer model
│   ├── sherpa-tts/                    # Piper TTS model + espeak-ng-data
│   └── vad/                           # Keyword spotting model
├── scripts/                           # 🔧 ADB push, checksum, test scripts
└── docs/                              # 📖 Architecture, test plans, setup guides
```

---

## 🏁 Getting Started

### Prerequisites

| Tool | Version | Purpose |
|:-----|:--------|:--------|
| Android Studio | Latest stable | IDE, build, debug |
| Android SDK | API 34 | Compile target |
| JDK | 17 | Kotlin compilation |
| ADB | Latest | Device deployment, model push |

### Build & Run

```bash
# 1. Open in Android Studio
#    File → Open → UnoOne-Local-Agent/android-app/UnoOneAgent

# 2. Wait for Gradle sync (5-10 min first time)

# 3. Connect your Android device (USB Debugging enabled)

# 4. Build and install:
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk

# 5. On first launch, grant:
#    🎙️ Microphone, 📇 Contacts, 📅 Calendar, 📷 Camera
#    🖼️ Display over other apps (overlay)
#    ♿ Accessibility Service (for deep control)
#    🔋 Disable battery optimization
#    ▶️ Autostart permission (Xiaomi/Huawei/Oppo/Vivo/OnePlus/Asus)
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

> ⚠️ **Without model files**, the app falls back to: Android `SpeechRecognizer` for STT (requires internet), rule-based command parsing for NLU, and no TTS output.

---

## 🎤 Voice Commands

### Quick Commands (No Models Required)

| 🗣️ Command | ⚡ Action |
|:-----------|:---------|
| `"Create a note: buy milk"` | Saves note to Room DB |
| `"Open Chrome"` | Launches Chrome |
| `"Open WhatsApp"` | Launches WhatsApp |
| `"Open calendar"` | Opens calendar insert |
| `"Read screen"` | Reads all visible text via accessibility |
| `"Scroll down"` | Scrolls current app down |
| `"Go back"` | Presses back button |
| `"Go home"` | Presses home button |
| `"Send whatsapp to 1234567890 saying hello"` | Opens WhatsApp with message |

### Skill Commands

| 🗣️ Command | ⚡ Action |
|:-----------|:---------|
| `"Teach you a skill called Morning to open Chrome then read screen"` | Creates a reusable skill |
| `"Morning"` | Triggers the saved skill's steps |

### Deep Control (Accessibility Required)

| 🗣️ Command | ⚡ Action |
|:-----------|:---------|
| `"Find and click Login"` | Scrolls to find "Login" text and taps it |
| `"Fill username with john@example.com"` | Types into the field with "username" hint |
| `"Swipe left"` | Performs left swipe gesture |
| `"Open notifications"` | Opens notification shade |

---

## 📋 Module Deep Dive

### 🎙️ Voice Pipeline

```
AudioRecorder (16kHz PCM, continuous)
       │
       ▼
KeywordSpotterEngine ("UnoOne" wake word)
       │ detected
       ▼
VAD Silence Detection (energy-based RMS)
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

### ♿ Accessibility Control

All actions go through `UnoOneAccessibilityService` which requires explicit user enablement:

| Method | What It Does |
|:-------|:------------|
| `clickNodeWithText(text)` | Finds and clicks a node by its text |
| `clickAt(x, y)` | Clicks at exact coordinates |
| `typeTextIntoFocused(text)` | Types into the currently focused input |
| `fillFieldWithText(hint, text)` | Finds editable by hint and fills it |
| `scrollDown()` / `scrollUp()` | Scrolls via gesture swipe |
| `swipe(direction)` | Swipes left / right / up / down |
| `longPress(x, y)` | Long press at coordinates |
| `goBack()` / `goHome()` | Global navigation actions |
| `openNotifications()` / `openRecents()` | System UI actions |
| `findAndClick(text)` | Scrolls to find text, then clicks it |
| `captureVisibleText()` | Reads all text from the accessibility tree |

### 🛡️ Safety Guard

Every action is classified before execution:

| ⚠️ Risk Level | 🔒 Behavior | 📋 Example |
|:-------------|:-----------|:----------|
| **DIRECT** | Execute immediately | Create note, open Chrome |
| **CONFIRM** | Show allow/deny dialog | Send WhatsApp, open camera |
| **STRONG_CONFIRM** | Type "confirm" to proceed | Draft email, fill passwords |
| **BLOCK** | Reject immediately | Destructive actions |

---

## 🔐 Permissions

| Permission | Purpose | Required |
|:----------|:--------|:--------:|
| `RECORD_AUDIO` | Voice commands, wake word | ✅ |
| `READ_CONTACTS` | Email/WhatsApp contact resolution | ✅ |
| `READ_CALENDAR` / `WRITE_CALENDAR` | Calendar event queries | ✅ |
| `CAMERA` | Open camera intent | ✅ |
| `POST_NOTIFICATIONS` | Foreground service notification (API 33+) | ✅ |
| `SYSTEM_ALERT_WINDOW` | Floating bubble overlay | ✅ |
| Accessibility Service | Deep app control (tap, scroll, type) | ✅ |
| `MANAGE_EXTERNAL_STORAGE` | Model file management (API 30+) | Optional |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prevent background service kill | Recommended |
| `FOREGROUND_SERVICE_MICROPHONE` | Background wake word detection | ✅ |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Floating bubble service | ✅ |

> **Manufacturer-specific prompts** work on Xiaomi (MIUI), Huawei, Oppo, Vivo, OnePlus, and Asus — each targets the correct settings screen. On Pixel/Samsung/Motorola, standard battery optimization dialog is sufficient.

---

## 🧠 Models

| Model | Size | Purpose | Source |
|:------|:-----|:-------|:-------|
| Sherpa-ONNX ASR | ~70 MB | Offline speech-to-text | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/releases) |
| Sherpa-ONNX TTS (Piper) | ~30-60 MB | Offline text-to-speech | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/releases) |
| Keyword Spotter | ~10-30 MB | Wake word "UnoOne" | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/releases) |
| Intent Classifier | ~5-20 MB | On-device command classification | Custom-trained ONNX |
| Gemma 2B (optional) | ~1.5-5 GB | Full generative LLM | [Kaggle](https://www.kaggle.com/models/google/gemma-2b) |

> All models are stored in app-private storage and **never leave the device**.

---

## 📑 Key Files Quick Reference

| File | Purpose |
|:-----|:--------|
| `AgentOrchestrator.kt` | 8-step agent pipeline, skill matching, confirmation flow |
| `FloatingAgentService.kt` | Floating bubble + chat overlay with voice input |
| `MainActivity.kt` | Permissions, battery optimization, manufacturer autostart |
| `PermissionManager.kt` | Runtime, system, permanent denial, manufacturer handling |
| `RuleBasedParser.kt` | 20+ command patterns with compound command support |
| `LocalBrain.kt` | ONNX inference engine (rule fallback when no model) |
| `VoiceService.kt` | Foreground service with KWS → VAD → STT → command pipeline |
| `UnoOneAccessibilityService.kt` | Screen automation (tap, scroll, type, read) |
| `SafetyGuard.kt` | 4-tier risk classification engine |
| `SkillsModule.kt` | Skill CRUD with kotlinx-serialization |
| `MemoryModule.kt` | Keyword-based context retrieval |
| `WaveformVisualizer.kt` | Animated waveform during voice input |
| `ConfirmationDialog.kt` | CONFIRM/STRONG_CONFIRM safety dialogs |

---

<div align="center">

### Built with ❤️ by [InBharatAI](https://github.com/inbharatai)

**UnoOne** — *One agent. One device. Zero compromises.*

</div>