# UnoOne Phone Test Plan — Xiaomi 14

## Environment Setup

1. Android Studio latest stable installed and opened.
2. Xiaomi 14 connected via USB.
3. `adb devices` shows device.
4. scrcpy running for screen mirroring (optional but recommended).
5. Airplane mode switchable for offline tests.

## Test Matrix

### Test 1: Blank App Launch
**Objective:** Verify app installs and opens without crash.

**Steps:**
1. Build debug APK.
2. `adb install app-debug.apk`
3. Tap UnoOne icon on Xiaomi 14.

**Expected:**
- App opens.
- Home screen visible with UnoOne branding.
- Bottom tabs: Agent, Notes, Skills, Logs, Settings.
- No crash.

**Pass / Fail:**

---

### Test 2: Model Detection
**Objective:** Verify ModelManager detects pushed model folders.

**Steps:**
1. Run `scripts/adb-push-models/push-models.bat` (or .sh).
2. Open app → Settings → Model Status.

**Expected:**
- Gemma model shows "Present" or "Loaded".
- Sherpa ASR shows "Present".
- Sherpa TTS shows "Present".
- VAD shows "Present".
- Storage usage displayed in MB.

**Pass / Fail:**

---

### Test 3: Speech-to-Text
**Objective:** Verify Sherpa-ONNX STT transcribes voice correctly.

**Steps:**
1. Press mic button.
2. Speak: "Create a note to call Ramesh tomorrow."
3. Wait for transcription.

**Expected:**
- Transcription appears on screen.
- Text is reasonably accurate ("Create a note to call Ramesh tomorrow").
- Timeline shows "Listening" then "Transcribing" then text.

**Pass / Fail:**

---

### Test 4: Text-to-Speech
**Objective:** Verify local TTS speaks a test sentence.

**Steps:**
1. Go to Settings → TTS Test.
2. Tap "Speak Test Sentence."

**Expected:**
- Audio plays: "UnoOne is ready."
- No crash.
- Voice status shows "Speaking" then "Idle."

**Pass / Fail:**

---

### Test 5: Local Model Text Command
**Objective:** Verify LocalBrain returns valid JSON tool call.

**Steps:**
1. Type: "Create a note: buy milk tomorrow."
2. Press send.

**Expected:**
- Agent timeline shows "Understanding."
- JSON preview visible (debug mode).
- Tool call contains `create_note` with title/content.

**Pass / Fail:**

---

### Test 6: Create Note Tool
**Objective:** Verify create_note saves to Room DB.

**Steps:**
1. Run Test 5 command.
2. Approve if confirmation shown.
3. Go to Notes tab.

**Expected:**
- Note appears with title "Buy milk" or similar.
- Created_at timestamp is recent.
- Searchable in Notes tab search bar.

**Pass / Fail:**

---

### Test 7: Full Voice Agent Loop
**Objective:** Verify end-to-end voice → action → TTS.

**Steps:**
1. Press mic.
2. Speak: "Create a note: call doctor tomorrow."
3. Wait for full loop.

**Expected:**
- STT transcribes correctly.
- Model returns `create_note` JSON.
- Note saves locally.
- TTS says: "Note created: call doctor tomorrow."
- Timeline shows all 9 steps ending in Done.

**Pass / Fail:**

---

### Test 8: Open Chrome
**Objective:** Verify open_chrome intent works.

**Steps:**
1. Speak or type: "Open Chrome."

**Expected:**
- Chrome browser opens.
- App stays in background.
- Timeline shows Done.

**Pass / Fail:**

---

### Test 9: Open URL
**Objective:** Verify open_url with confirmation.

**Steps:**
1. Speak or type: "Open google dot com."

**Expected:**
- Confirmation dialog appears.
- Tap "Open."
- Chrome opens https://www.google.com.

**Pass / Fail:**

---

### Test 10: Open App — WhatsApp
**Objective:** Verify open_app resolves package names.

**Steps:**
1. Speak or type: "Open WhatsApp."

**Expected:**
- WhatsApp opens if installed.
- If not installed, timeline shows Failed with reason.

**Pass / Fail:**

---

### Test 11: Open App — Gmail
**Objective:** Verify Gmail intent.

**Steps:**
1. Speak or type: "Open Gmail."

**Expected:**
- Gmail opens if installed.

**Pass / Fail:**

---

### Test 12: Calendar Draft
**Objective:** Verify open_calendar_insert with fields.

**Steps:**
1. Speak: "Book calendar tomorrow at 5 PM meeting with Ramesh."
2. Confirm if dialog shown.

**Expected:**
- Calendar insert screen opens.
- Title pre-filled: "meeting with Ramesh."
- Start time pre-filled: tomorrow 5 PM.

**Pass / Fail:**

---

### Test 13: Airplane Mode
**Objective:** Verify local features work without network.

**Steps:**
1. Enable airplane mode.
2. Repeat:
   - Voice note creation
   - TTS test
   - Open Chrome
   - Search local notes

**Expected:**
- All local features succeed.
- No network-dependent errors.
- Timeline completes.

**Pass / Fail:**

---

### Test 14: Safety Layer
**Objective:** Verify risk classification works.

**Steps:**
1. Try: "Delete all notes." → Expect block or strong confirmation.
2. Try: "Send a message." → Expect block.
3. Try: "Make a payment." → Expect block.
4. Try: "Create a note." → Expect direct execute.

**Expected:**
- Risk 3 commands blocked with clear reason.
- Risk 1+ commands show confirmation.
- Risk 0 commands execute immediately.
- All decisions logged.

**Pass / Fail:**

---

### Test 15: Heat / Battery Stress
**Objective:** Verify stability under sustained load.

**Steps:**
1. Run 30-minute session:
   - 20 voice commands
   - 20 local tool calls
   - 10 TTS responses
2. Note heat and battery drain.
3. Check for crashes.

**Expected:**
- No crashes.
- App remains responsive.
- Heat acceptable (not burning).
- Battery drain documented.

**Pass / Fail:**

---

## Sign-off

| Tester | Date | Result |
|--------|------|--------|
|        |      |        |

**Required for milestone:** Tests 1–14 must pass. Test 15 is informational.
