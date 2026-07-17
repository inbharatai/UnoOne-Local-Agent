# UnoOne Quick Start — Run It Now

> **CRITICAL: The project is NOT on your Desktop.**  
> **Open this exact path in Android Studio:**  
> `C:\Users\reetu\Desktop\UnoOne-Local-Agent\android-app\UnoOneAgent`  
> Do NOT open `C:\Users\reetu\Desktop\UnoOne` — that folder is empty.

## Prerequisites
- Android Studio installed
- Xiaomi 14 connected via USB with Developer Options + USB Debugging enabled

## Step 1: Open the Project
1. Open Android Studio.
2. Click **File → Open**.
3. Paste this exact path into the file picker:
   ```
   C:\Users\reetu\Desktop\UnoOne-Local-Agent\android-app\UnoOneAgent
   ```
4. Click **OK**.
5. Wait for Gradle sync to finish (look at the bottom status bar).

## Step 2: Run on Your Phone
1. Make sure your Xiaomi 14 is connected.
2. In Android Studio, check the device dropdown at the top — it should show your Xiaomi 14.
3. Click the green **Run** button (▶) or press `Shift + F10`.
4. The app installs and opens automatically.

## Step 3: Test Immediately (No Models Needed)

### Test 1: Create a note
1. In the text box, type: `Create a note: buy milk tomorrow`
2. Tap **Go**.
3. Watch the **Agent Flow Timeline** fill with steps.
4. Go to the **Notes** tab. The note appears.

### Test 2: Open Chrome
1. Type: `Open Chrome`
2. Tap **Go**.
3. Chrome opens.

### Test 3: Open WhatsApp
1. Type: `Open WhatsApp`
2. Tap **Go**.
3. WhatsApp opens if installed.

### Test 4: Quick Actions
1. Tap **Create Note** quick action.
2. Timeline shows execution steps.

### Test 5: Logs
1. Go to **Logs** tab.
2. Every action you ran is listed with status and timestamp.

### Test 6: Settings
1. Go to **Settings** tab.
2. Tap the refresh icon next to **Model Status**.
3. It shows which model folders are present/missing.

## Step 4: Test Mic (Permission Required)
1. Tap the big **Mic** button.
2. Approve the **Microphone** permission.
3. The button turns red and shows **Listening**.
4. Tap it again to stop.
5. (STT will show an error until Sherpa models are installed — this is expected.)

## Step 5: Push Models (Optional)
1. Download Sherpa ASR + TTS models.
2. Place them in (per-language folders):
   ```
   models/sherpa-asr-en/        # English ASR (streaming zipformer transducer int8)
   models/speech/shared/sherpa-asr-indic/ # Indic ASR (Omnilingual 300M CTC int8; shared)
   models/sherpa-tts-en/        # English TTS (Coqui VITS + espeak-ng-data)
   models/sherpa-tts-hin/       # Hindi TTS (MMS VITS) — also -ben/-tam/-tel/-kan/-mal
   models/vad/                  # English wake-word (keyword spotter)
   ```
3. Run:
   ```powershell
   .\scripts\adb-push-models\push-models.bat
   ```
4. In the app, go to **Settings** → refresh.
5. Models should show as **Present**. Pick the active STT/TTS language in **Settings → Voice language**.

## Troubleshooting

| Problem | Fix |
|---------|-----|
| Gradle sync fails | File → Settings → Gradle → Select JDK 17 |
| Device not showing | Run `adb devices` in PowerShell. If unauthorized, check phone screen. |
| App crashes on open | Check **Logcat** in Android Studio for the error. |
| "STT not yet integrated" | Expected until Sherpa ONNX AAR is added and models pushed. |
| Chrome not opening | Make sure Chrome is installed on the phone. |

## What Works Now vs Later

**Works now (no downloads needed):**
- Text commands
- Note creation and search
- Opening apps via intents
- Calendar insert
- Camera
- Agent timeline
- Action logging
- Model folder detection
- Mic permission flow

**Needs model downloads:**
- Voice-to-text (Sherpa ASR)
- Text-to-speech (Sherpa TTS)
- Local LLM reasoning (Gemma)

## Next Steps
1. Run the app.
2. Test the commands above.
3. Tell me what breaks or what you want next.
