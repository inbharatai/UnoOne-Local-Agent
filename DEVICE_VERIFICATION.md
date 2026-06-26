# Device Verification — UnoOne

**Status:** Template / not yet populated. This file is the single place where real-device
end-to-end verification results are recorded. Until the matrix below is filled in with actual
device logs, **UnoOne is an alpha, not production-verified** (see `STATUS.md`).

> CI verifies build, unit tests (203), lint, and `assembleDebug` on a host JVM — it cannot run
> native Sherpa (Whisper/MMS) or LiteRT-LM (Gemma 3n E4B) inference, Accessibility, or camera.
> Those require a real device with models installed. This file captures that verification.

## Device matrix

| Device | Android | SoC | RAM | Build/install/UI smoke | Sherpa STT | Sherpa TTS | Wake word | Gemma planning | Accessibility control | Blind Aid | Date | Log file |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Xiaomi 14 | 15 (35) | Snapdragon 8 Gen 3 | 12 GB | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | — | — |
| Samsung Galaxy S24 | 15 (35) | Exynos 2400 / SD8G3 | 8/12 GB | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | — | — |
| Pixel 8 | 15 (35) | Tensor G3 | 8/12 GB | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | — | — |

Legend: ✅ verified · ❌ failed · ☐ not yet run.

## Per-device test procedure

For each device, install the debug APK, install the needed models (Model Status screen or ADB),
grant all permissions, then run:

### 1. Build / install / UI smoke
- [ ] `assembleDebug` APK installs without error.
- [ ] App launches; Model Status screen lists models with correct presence/health.
- [ ] Floating bubble toggles on/off; persistent notification shows.

### 2. Sherpa STT (offline)
- [ ] English: "create a note buy milk" → transcribed → note saved. (model: `sherpa-asr-en`)
- [ ] One Indic language (hi/bn/ta/te/kn/ml): same command in that language → transcribed. (model: `sherpa-asr-whisper`, language selected in Settings)

### 3. Sherpa TTS (offline)
- [ ] English response spoken. (`sherpa-tts-en`)
- [ ] One Indic TTS spoken. (`sherpa-tts-<lang>`)

### 4. Wake word
- [ ] Say "UnoOne" → detection fires → command captured. (`vad`)

### 5. Gemma 3n E4B planning
- [ ] Load `.litertlm` → `GemmaPlanner` initializes (GPU, falls back to CPU if needed).
- [ ] A complex command produces a correct tool call, parsed + safety-classified + executed.
- [ ] Run `GemmaPlannerAccuracyTest` instrumented: `./gradlew connectedDebugAndroidTest`.

### 6. Accessibility control
- [ ] `read_screen` returns visible text (after CONFIRM).
- [ ] `find_and_click Login` / `fill …` / scroll / back / home execute (after STRONG_CONFIRM).
- [ ] Service survives screen-off; stops cleanly when disabled.

### 7. Blind Aid
- [ ] `detect_objects` starts camera + overlay + haptics + spoken guidance.
- [ ] Failure states handled: low light / motion blur / camera blocked → spoken warning.
- [ ] `deactivate_blind_aid` stops cleanly.

### 8. Sensitive-surface confirmations
- [ ] `send_whatsapp` draft → STRONG_CONFIRM → WhatsApp opens with pre-filled message (user presses send).
- [ ] `web_search` with Online tools OFF → explicit offline message; with ON → attributed results.
- [ ] "send OTP" / "auto send" → BLOCK.

## How to record results

1. Replace each ☐ with ✅/❌ and fill Date + Log file (path to the captured `adb logcat` or
   `Diagnostics` export for that run).
2. Attach the Diagnostics latency export (Settings → Logs) for the Gemma + Sherpa runs.
3. Commit the completed table. Once all cells are ✅, bump `versionName` to `1.0.0` and remove the
   alpha disclaimer from `STATUS.md` / README.

## Known device-specific notes (populate during runs)

- Manufacturer autostart prompts (Xiaomi/Huawei/Oppo/Vivo/OnePlus/Asus) — record any extra step.
- GPU backend availability for LiteRT-LM per SoC — record GPU vs CPU fallback.
- Sherpa native `.so` load success per ABI (arm64-v8a expected).