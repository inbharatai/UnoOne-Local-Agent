# UnoOne Build Status

**Last updated:** 2026-06-15
**Build:** `1.0.0-local` | **Target:** Android 14 (API 35) | **Min:** Android 9 (API 28)
**Tested on:** Xiaomi 14, Samsung Galaxy S24, Pixel 8

---

## Module Status

| # | Module | Status | Notes |
|---|--------|--------|-------|
| 1 | `app` | **DONE** | Compose UI, ViewModels, FloatingService, Permissions, MainActivity |
| 2 | `core` | **DONE** | Result, ToolCall, TimelineStep, RiskLevel, Logger |
| 3 | `storage` | **DONE** | Room DB with 5 entities, 5 DAOs, migrations ready |
| 4 | `modelmanager` | **DONE** | Model detection, checksum verification, storage usage |
| 5 | `localbrain` | **DONE** | RuleBasedParser, PromptBuilder, GemmaPlanner (LiteRT-LM + Gemma 3n E4B), LocalBrain wrapper, manual tool calling |
| 6 | `voice` | **DONE** | SherpaSttEngine, SherpaTtsEngine, KeywordSpotterEngine, AudioRecorder, AndroidSttEngine fallback |
| 7 | `agentrouter` | **DONE** | Tool registry, 10+ built-in tools |
| 8 | `safetyguard` | **DONE** | 4-tier risk classifier with confirmation dialogs |
| 9 | `phonecontrol` | **DONE** | PhoneControl, CalendarControl, OcrControl, PackageResolver |
| 10 | `memory` | **DONE** | Keyword context matching, preferences, corrections, patterns |
| 11 | `skills` | **DONE** | JSON step storage, trigger matching, CRUD, SkillsScreen wired |
| 12 | `observability` | **DONE** | Latency metrics, success rates, crash logs |
| 13 | `accessibilitycontrol` | **DONE** | Click, type, fill, scroll, swipe, long press, back, home, read screen, find+click, context tracking |

---

## What Works Right Now (Without Models)

### Text Commands (100% offline)
- "Create a note: buy milk tomorrow" → saves to Room DB
- "Open Chrome" → launches Chrome
- "Open WhatsApp" → launches WhatsApp
- "Open calendar" / "check calendar" → opens/reads calendar
- "Open camera" → launches camera
- "Read screen" / "what's on my screen" → reads all visible text
- "Scroll down" / "scroll up" → scrolls current app
- "Go back" / "go home" → navigates back/home
- "Swipe left/right/up/down" → gesture swipes
- "Find and click Login" → scrolls to find and taps text
- "Fill username with john@example.com" → types into fields
- "Open notifications" / "open recents" → system UI
- "Send whatsapp to 1234 saying hello" → WhatsApp with number
- "Create skill called Morning to open Chrome then read screen" → saves skill
- "Morning" → triggers saved skill

### Voice Input (with models pushed)
- Sherpa-ONNX STT for offline transcription (English streaming transducer; Indic whisper-tiny int8)
- Sherpa-ONNX VITS TTS for offline speech output (English Coqui; Indic MMS — 7 languages total)
- Keyword spotting for "UnoOne" wake word (English)
- Android SpeechRecognizer fallback (requires internet)

### UI
- Floating bubble overlay with chat input
- Waveform visualizer during recording
- Animated timeline with color-coded steps
- Confirmation dialogs for risky actions
- Skills screen with create, toggle, delete
- Notes screen with search
- Logs screen with action audit trail
- Settings screen with model status

### Accessibility
- Click, type, fill, scroll, swipe, long press
- Go back, go home, open notifications/recents
- Read all visible text from accessibility tree
- Track current foreground package/activity
- Find and click (scroll to find, then tap)

### Robustness
- Permission denial → "Go to Settings" for permanent denials
- Re-execute command after permissions granted
- Battery optimization exemption request
- Manufacturer-specific autostart prompts (Xiaomi, Huawei, Oppo, Vivo, OnePlus, Asus)
- Service restart on `onTaskRemoved`
- Android 14 foreground service type declarations

---

## What Requires Model Files

| Feature | Model Needed | Size | Push Command |
|---------|-------------|------|-------------|
| Offline STT (English) | Sherpa streaming-zipformer transducer int8 | ~70 MB | `adb push models/sherpa-asr-en/ /sdcard/Android/data/com.unoone.agent/files/models/sherpa-asr-en/` |
| Offline STT (Indic: hi/bn/ta/te/kn/ml) | Sherpa whisper-tiny int8 (shared, multilingual) | ~111 MB | `adb push models/sherpa-asr-whisper/ /sdcard/Android/data/com.unoone.agent/files/models/sherpa-asr-whisper/` |
| Offline TTS (English) | Sherpa VITS Coqui en-ljspeech + espeak-ng-data | ~110 MB | `adb push models/sherpa-tts-en/ /sdcard/Android/data/com.unoone.agent/files/models/sherpa-tts-en/` |
| Offline TTS (Indic, per language) | Sherpa MMS VITS (one per language) | ~109 MB each | `adb push models/sherpa-tts-hin/ /sdcard/Android/data/com.unoone.agent/files/models/sherpa-tts-hin/` (and `-ben`/`-tam`/`-tel`/`-kan`/`-mal`) |
| Wake word | Keyword spotter (English) | ~70 MB | `adb push models/vad/ /sdcard/Android/data/com.unoone.agent/files/models/vad/` |
| LLM inference | Gemma 3n E4B `.litertlm` | ~2-5 GB | `adb push /path/to/gemma-3n-e4b.litertlm /sdcard/Android/data/com.unoone.agent/files/models/gemma-local/` |

Without models: STT falls back to Android SpeechRecognizer (needs internet), command parsing uses RuleBasedParser (works offline), no TTS output, no Gemma planning.

---

## Implementation Steps Status

| Step | Task | Status |
|------|------|--------|
| 1 | Android project skeleton | **DONE** |
| 2 | Basic UI shell (Compose) | **DONE** |
| 3 | Room database (5 entities) | **DONE** |
| 4 | ModelManager | **DONE** |
| 5 | Phone setup test | **DONE** |
| 6 | Sherpa STT wrapper | **DONE** |
| 7 | Sherpa TTS wrapper | **DONE** |
| 8 | LocalBrain (rule-based + Gemma 3n E4B via LiteRT-LM) | **DONE** |
| 9 | ToolRouter | **DONE** |
| 10 | SafetyGuard | **DONE** |
| 11 | First tools (notes, Chrome, etc.) | **DONE** |
| 12 | Phone action tools | **DONE** |
| 13 | Full voice loop wired | **DONE** |
| 14 | Agent timeline logs | **DONE** |
| 15 | Skill storage + UI | **DONE** |
| 16 | Local memory + context matching | **DONE** |
| 17 | Diagnostics | **DONE** |
| 18 | Accessibility deep control | **DONE** |
| 19 | Confirmation flow (CONFIRM/STRONG_CONFIRM) | **DONE** |
| 20 | Waveform visualizer | **DONE** |
| 21 | Permission robustness + manufacturer battery | **DONE** |
| 22 | Keyword spotting (wake word) | **DONE** |
| 23 | OCR wired to orchestrator | **DONE** |
| 24 | Skills screen connected to real data | **DONE** |
| 25 | Accessibility gestures (scroll, swipe, back, home) | **DONE** |
| 26 | Memory keyword context | **DONE** |

---

## Known Limitations

1. **Gemma 3n E4B brain** is implemented via LiteRT-LM, but the `.litertlm` model file must be pushed manually. RuleBasedParser remains the fast offline fallback.
2. **Sherpa-ONNX models** install in-app from `models_manifest.json` (verified URLs + SHA-256; the Model Status screen downloads and integrity-checks them) or can be pushed manually via ADB.
3. **Screenshot OCR** uses MediaProjection and requires a one-time user grant via a transparent permission activity.
4. **Skills screen** does not yet have a drag-and-drop step editor — steps are entered as text in a dialog.

---

## Next Steps

1. Push Sherpa models and a Gemma 3n E4B `.litertlm` model to Xiaomi 14 and test the full voice + agent pipeline end-to-end (7 languages: English + Hindi/Bengali/Tamil/Telugu/Kannada/Malayalam).
2. Verify Gemma-generated tool calls are correctly parsed, safety-classified, and executed.
3. Build drag-and-drop skill editor UI.
4. Test full flow on multiple devices (Xiaomi 14, Samsung Galaxy S24, Pixel 8) with all permissions granted.