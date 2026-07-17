# UnoOne Step-by-Step Implementation Order

## Current Status

| Step | Module | Status |
|------|--------|--------|
| 1 | Android project skeleton (Kotlin + Compose) | **DONE** |
| 2 | Basic UI shell (Home, Timeline, Settings, Logs) | **DONE** |
| 3 | Room database (5 tables, DAOs, database) | **DONE** |
| 4 | ModelManager (detect, verify, storage) | **DONE** |
| 5 | Phone setup test + docs | **DONE** |
| 6 | Sherpa STT wrapper + AudioRecorder | **DONE** (needs models) |
| 7 | Local TTS wrapper + TtsPlayer | **DONE** (needs models) |
| 8 | LocalBrain (prompt builder, rule-based fallback) | **DONE** (needs Gemma model) |
| 9 | AgentRouter (tool registry, validation) | **DONE** |
| 10 | SafetyGuard (risk classifier) | **DONE** |
| 11 | First tools (create_note, search_notes, speak_response) | **DONE** |
| 12 | Phone action tools (Chrome, URL, apps, calendar, camera) | **DONE** |
| 13 | Full voice loop wired (mic → orchestrator → result) | **DONE** |
| 14 | Agent timeline logs for every step | **DONE** |
| 15 | Airplane mode test | PENDING |
| 16 | Skill storage | PENDING |
| 17 | Local memory | PENDING |
| 18 | Diagnostics (latency, battery) | PENDING |
| 19 | Accessibility module design | PENDING |

---

## How to Continue

### Option A: Get it running on your phone NOW (Recommended)

1. Follow `docs/setup-guide.md` to install Android Studio and connect your Xiaomi 14.
2. Open the project in Android Studio.
3. Click Run (▶).
4. The app will install and open.
5. Type a command like **"Create a note: buy milk tomorrow"** and tap **Go**.
6. Go to the **Notes** tab. You will see the note.
7. Type **"Open Chrome"** and tap **Go**. Chrome will open.
8. Check the **Agent** tab — the timeline shows every step.
9. Check the **Logs** tab — action logs are saved.
10. Check **Settings** → tap refresh to see model status.

### Option B: Add Sherpa-ONNX models for real STT/TTS

1. Use the exact Sherpa-ONNX ASR artifacts pinned in `models_manifest.json`:
   `https://github.com/k2-fsa/sherpa-onnx/releases`
2. Download Sherpa-ONNX TTS model (e.g., Coqui en-ljspeech VITS for English, or MMS VITS for Indic languages).
3. Install them through the app into the normalized folders: `speech/shared/sherpa-asr-en/` (English ASR), `speech/shared/sherpa-asr-indic/` (shared Indic Omnilingual ASR), and `speech/languages/<locale>/tts/` (one TTS voice per language). See `models_manifest.json` for exact artifacts and hashes.
4. Run `scripts/adb-push-models/push-models.bat`.
5. Add Sherpa-ONNX AAR to `voice/build.gradle.kts`:
   ```kotlin
   implementation("com.github.k2-fsa:sherpa-onnx:v1.13.3")
   ```
6. The Kotlin bindings (`com.k2fsa.sherpa.onnx.*`) are already wired in `SherpaSttEngine.kt` and `SherpaTtsEngine.kt` — no JNI code to uncomment.
7. Rebuild and test voice commands.

### Option C: Add Gemma local LLM for real inference

1. Download Gemma 2B IT TFLite model from Kaggle or Hugging Face.
2. Place it in `models/gemma-local/`.
3. Push to phone via ADB script.
4. Implement `ModelLoader.kt` in `localbrain` using `org.tensorflow.lite.Interpreter`.
5. Implement tokenizer using `sentencepiece` or `tiktoken`.
6. Update `LocalBrain.runInference()` to use the real model.
7. The rule-based parser will still work as fallback when the model is not loaded.

### Option D: Add Skills and Memory

1. Wire `SkillsModule` to `SkillDao`.
2. Add "Create Skill" UI in Skills tab.
3. Wire `MemoryModule` to `MemoryDao`.
4. Inject memory context into `PromptBuilder`.
5. Add memory storage for corrections and repeated patterns.

### Option E: Run the full test plan

1. Follow `docs/phone-test-plan.md`.
2. Execute each test manually on the Xiaomi 14.
3. Record pass/fail for each test.
4. File bugs or fix issues as you find them.

---

## File Map

| What you want to change | Where to go |
|------------------------|-------------|
| App theme colors | `app/src/main/java/com/unoone/agent/ui/theme/Color.kt` |
| Home screen UI | `app/src/main/java/com/unoone/agent/ui/screens/AgentScreen.kt` |
| Bottom navigation | `app/src/main/java/com/unoone/agent/ui/navigation/UnoOneNavHost.kt` |
| Agent loop logic | `app/src/main/java/com/unoone/agent/AgentOrchestrator.kt` |
| Add a new tool | `agentrouter/src/main/java/com/unoone/agent/agentrouter/AgentRouter.kt` |
| Change safety rules | `safetyguard/src/main/java/com/unooone/agent/safetyguard/SafetyGuard.kt` |
| Add a new phone action | `phonecontrol/src/main/java/com/unoone/agent/phonecontrol/PhoneControl.kt` |
| STT integration | `voice/src/main/java/com/unoone/agent/voice/stt/SherpaSttEngine.kt` |
| TTS integration | `voice/src/main/java/com/unoone/agent/voice/tts/SherpaTtsEngine.kt` |
| LLM loading | `localbrain/src/main/java/com/unoone/agent/localbrain/LocalBrain.kt` |
| Database schema | `storage/src/main/java/com/unoone/agent/storage/entity/*.kt` |
| Model detection | `modelmanager/src/main/java/com/unoone/agent/modelmanager/ModelManager.kt` |
| Add dependencies | `app/build.gradle.kts` or `*/build.gradle.kts` |

---

## Next Immediate Actions

1. **Open Android Studio** → File → Open → `C:\Users\reetu\Desktop\UnoOne-Local-Agent\android-app\UnoOneAgent`
2. **Sync Gradle** — wait for it to finish.
3. **Connect Xiaomi 14** via USB.
4. **Click Run** (▶) — the app installs and opens.
5. **Test text commands** like "Create a note: test" and "Open Chrome".
6. **Report back** what works and what breaks.
