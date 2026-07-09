# UnoOne Build Status

**Last updated:** 2026-07-09
**Build:** `0.3.0-alpha-local` | **Target:** Android 14 (API 35) | **Min:** Android 9 (API 28)
**Build/install/UI smoke-tested on:** Xiaomi 14, Samsung Galaxy S24, Pixel 8
**Brain:** Default **Gemma 3n E4B** (`gemma-3n-e4b`, device-verified fallback). **Gemma 4 E2B** (`gemma-4-e2b`) is an **Experimental opt-in, not device-verified** — selectable + self-testable in Settings → Model Status → Brain Model.
**Not yet verified end-to-end:** Gemma 3n E4B + Sherpa + Accessibility + Blind Aid on all of the above devices; Gemma 4 E2B specifically not yet loaded/planned on any device (see Next Steps). **ReAct loop, safety judge, eval-harness, streaming, self-heal reload, outcome-memory benefit, multimodal vision, and manifest-signature enforcement are implemented but not device-run / partly inactive** — their control/scoring logic is JVM-tested (289 unit tests pass), but no physical device has run `planNext`, `judgeSafety`, `BrainEvalHarnessTest`, `planStreaming`, or the `describe_scene` vision path, and the Ed25519 manifest signature is wired but the signing key is intentionally blank (no fabricated keys) — so no ReAct outcome, judge verdict, Gemma accuracy, streaming behavior, or vision-understanding claim is made.

---

## Module Status

| # | Module | Status | Notes |
|---|--------|--------|-------|
| 1 | `app` | **DONE** | Compose UI, ViewModels, FloatingService, Permissions, MainActivity |
| 2 | `core` | **DONE** | Result, ToolCall, TimelineStep, RiskLevel, Logger |
| 3 | `storage` | **DONE** | Room DB with 5 entities, 5 DAOs, migrations ready |
| 4 | `modelmanager` | **DONE** | Model detection, checksum verification, storage usage |
| 5 | `localbrain` | **DONE** | RuleBasedParser, PromptBuilder, model-profile-aware GemmaPlanner (LiteRT-LM; Gemma 3n E4B default / Gemma 4 E2B experimental), LocalBrain wrapper, manual tool calling, inference Mutex + 30s timeout. **New:** ReAct `planNext` (multi-turn observe), `judgeSafety` (dedicated judge conversation), eval-harness runner, `planStreaming` (LiteRT-LM `sendMessageAsync` `Flow<Message>`), and `describeSceneWithVision` (`Content.ImageBytes`, gated `VISION_MODEL_ENABLED=false`) — control/scoring JVM-tested, LiteRT-LM inference device-time-only |
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
| LLM inference (default) | Gemma 3n E4B `.litertlm` | ~2-5 GB | `adb push /path/to/gemma-3n-e4b.litertlm /sdcard/Android/data/com.unoone.agent/files/models/gemma-local/` |
| LLM inference (Experimental) | Gemma 4 E2B `.litertlm` (128K ctx) | ~2.58 GB | `adb push /path/to/gemma-4-e2b-it.litertlm /sdcard/Android/data/com.unoone.agent/files/models/gemma-4-e2b/` — **opt-in, not device-verified**; select it in Settings → Model Status → Brain Model |

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

1. **Gemma brain** is implemented via LiteRT-LM with two selectable profiles, but the `.litertlm` model file(s) must be pushed manually (or installed from the Model Status screen). The **default is Gemma 3n E4B**; **Gemma 4 E2B** is an Experimental opt-in that is **not yet device-verified** — it loads through the same safe code path, but no physical device has confirmed it loads + performs a tool call. RuleBasedParser remains the fast offline fallback. Every model-proposed tool call is checked against the **CanonicalToolRegistry** (26 tools, incl. the new `describe_scene`: unknown tools rejected, required args validated) before safety/execution.
2. **Sherpa-ONNX models** install in-app from `models_manifest.json` (verified URLs + SHA-256; the Model Status screen downloads and integrity-checks them) or can be pushed manually via ADB.
3. **Screenshot OCR** uses MediaProjection and requires a one-time user grant via a transparent permission activity.
4. **Skills screen** does not yet have a drag-and-drop step editor — steps are entered as text in a dialog.

---

## Next Steps

1. Push Sherpa models and a Gemma 3n E4B `.litertlm` model to Xiaomi 14 and test the full voice + agent pipeline end-to-end (7 languages: English + Hindi/Bengali/Tamil/Telugu/Kannada/Malayalam).
2. Verify Gemma-generated tool calls are correctly parsed, safety-classified, and executed, and that unknown/non-canonical tools are rejected by `GemmaPlanner`.
3. **Verify Gemma 4 E2B on-device** (Experimental): install `gemma-4-e2b`, run the Brain Self-Test (Settings → Model Status → Brain Model), confirm it loads + proposes a canonical tool on each test device. Passing this **does not** make Gemma 4 the default — the default stays Gemma 3n E4B until Gemma 4 is verified across the full matrix **and** the calibration eval harness (step 5c in DEVICE_VERIFICATION) shows it matching/beating Gemma 3n on the prompt set.
4. **Run the agent capabilities on a device.** The ReAct agentic loop, LLM safety judge, calibration eval harness, streaming inference, self-heal reload, outcome-learned memory, multimodal-vision `describe_scene`, and Ed25519 manifest-signature enforcement are implemented with their control/scoring logic JVM-tested (289 unit tests pass), but the LiteRT-LM inference (`planNext`, `judgeSafety`, `planStreaming`) and the `BrainEvalHarnessTest` runner are device-time-only and **have not been run on a physical device**. The vision path and manifest-signature enforcement are additionally wired-but-INACTIVE (shipped Gemma models are text-only; signing key intentionally blank). Run `BrainEvalHarnessTest` on a device with each profile pushed to get real Gemma 3n vs Gemma 4 accuracy numbers — no accuracy numbers are claimed until then.
5. Build drag-and-drop skill editor UI.
6. **Real-device end-to-end verification** (the open item above): run the full flow — Gemma 3n E4B planning + Sherpa STT/TTS (7 languages) + Accessibility control + Blind Aid — on Xiaomi 14, Samsung Galaxy S24, and Pixel 8 with all permissions granted, and record results + logs in [`DEVICE_VERIFICATION.md`](DEVICE_VERIFICATION.md). Until this matrix is populated, the app is an alpha, not production-verified.