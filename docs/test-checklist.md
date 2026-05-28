# UnoOne Manual Test Checklist

**Tester:** _______________  
**Date:** _______________  
**Phone:** Xiaomi 14  
**App Version:** 1.0.0-local

---

## Pre-Test Setup

- [ ] Android Studio opened project at `C:\Users\reetu\Desktop\UnoOne-Local-Agent\android-app\UnoOneAgent`
- [ ] Gradle sync completed with no errors
- [ ] Xiaomi 14 connected via USB
- [ ] `adb devices` shows the phone as `device`
- [ ] App installed and opened successfully
- [ ] Battery optimization disabled for UnoOne

---

## Section A: Text Command Tests

### A1. Create a note
**Steps:**
1. On Agent tab, tap the text input.
2. Type: `Create a note: buy milk tomorrow`
3. Tap **Go**.

**Expected:**
- [ ] Timeline shows: Understanding → Tool Selected → Safety Check → Executing → Verifying → Speaking → Done
- [ ] No crash

**Actual result:** _________________________________

---

### A2. Note saved in Room DB
**Steps:**
1. After A1 completes, go to **Notes** tab.

**Expected:**
- [ ] Note titled "buy milk tomorrow" appears in the list
- [ ] Note content is visible

**Actual result:** _________________________________

---

### A3. Action log saved
**Steps:**
1. Go to **Logs** tab.

**Expected:**
- [ ] A log entry appears for the "create_note" command
- [ ] Status shows "SUCCESS"
- [ ] Input text shows "Create a note: buy milk tomorrow"
- [ ] Timestamp is recent

**Actual result:** _________________________________

---

### A4. Search notes
**Steps:**
1. Go to **Notes** tab.
2. Tap the search box.
3. Type: `milk`

**Expected:**
- [ ] The note from A1 appears in filtered results
- [ ] Notes without "milk" are hidden

**Actual result:** _________________________________

---

### A5. Delete note
**Steps:**
1. In Notes tab, tap the **trash icon** on the note from A1.

**Expected:**
- [ ] Note disappears from the list

**Actual result:** _________________________________

---

## Section B: Phone Action Tests

### B1. Open Chrome
**Steps:**
1. On Agent tab, type: `Open Chrome`
2. Tap **Go**.

**Expected:**
- [ ] Timeline shows steps ending in Done
- [ ] Chrome browser opens on the phone
- [ ] UnoOne stays in background

**Actual result:** _________________________________

---

### B2. Open WhatsApp
**Steps:**
1. Type: `Open WhatsApp`
2. Tap **Go**.

**Expected:**
- [ ] Timeline shows Done
- [ ] WhatsApp opens if installed
- [ ] If not installed, timeline shows failed with reason "App not installed"

**Actual result:** _________________________________

---

### B3. Open Gmail
**Steps:**
1. Type: `Open Gmail`
2. Tap **Go**.

**Expected:**
- [ ] Gmail opens if installed

**Actual result:** _________________________________

---

### B4. Open Calendar
**Steps:**
1. Type: `Open Calendar`
2. Tap **Go**.

**Expected:**
- [ ] Calendar app opens if installed

**Actual result:** _________________________________

---

### B5. Open URL
**Steps:**
1. Type: `Open google.com`
2. Tap **Go**.

**Expected:**
- [ ] Timeline shows Safety Check (Risk 1)
- [ ] App may show confirmation (if implemented)
- [ ] Chrome opens with https://www.google.com

**Actual result:** _________________________________

---

### B6. Open Camera
**Steps:**
1. Type: `Open camera`
2. Tap **Go**.

**Expected:**
- [ ] Camera app opens

**Actual result:** _________________________________

---

### B7. Quick action: Create Note
**Steps:**
1. On Agent tab, tap **Create Note** quick action button.

**Expected:**
- [ ] Timeline shows Executing → Done

**Actual result:** _________________________________

---

### B8. Quick action: Open Chrome
**Steps:**
1. Tap **Open Chrome** quick action.

**Expected:**
- [ ] Chrome opens

**Actual result:** _________________________________

---

## Section C: Timeline Tests

### C1. Timeline shows all steps
**Steps:**
1. Run any command (e.g., `Create a note: test`).
2. Watch the **Agent Flow Timeline**.

**Expected:**
- [ ] "Understanding" appears (blue)
- [ ] "Tool Selected" appears (purple)
- [ ] "Safety Check" appears (orange)
- [ ] "Executing" appears (cyan)
- [ ] "Verifying" appears (teal)
- [ ] "Speaking Response" appears (green)
- [ ] "Done" appears (green check)

**Actual result:** _________________________________

---

### C2. Timeline clears on new command
**Steps:**
1. Run one command.
2. Run a second command.

**Expected:**
- [ ] Old timeline is cleared before new steps appear

**Actual result:** _________________________________

---

### C3. Timeline colors
**Steps:**
1. Run a command and observe timeline card colors.

**Expected:**
- [ ] Each step has a distinct color dot and text
- [ ] Done step is green
- [ ] Failed step (if any) is red

**Actual result:** _________________________________

---

## Section D: Permission Tests

### D1. Mic permission appears
**Steps:**
1. On Agent tab, tap the **big mic button**.

**Expected:**
- [ ] Android permission dialog appears: "Allow UnoOne to record audio?"
- [ ] Dialog has "While using the app" and "Don't allow" options

**Actual result:** _________________________________

---

### D2. Grant mic permission
**Steps:**
1. Tap **"While using the app"** on the permission dialog.

**Expected:**
- [ ] Dialog closes
- [ ] Mic button turns red (listening state)

**Actual result:** _________________________________

---

### D3. Stop listening
**Steps:**
1. While listening (red button), tap the button again.

**Expected:**
- [ ] Button returns to normal color
- [ ] Timeline shows "Stopped"

**Actual result:** _________________________________

---

## Section E: Failure Tests

### E1. Unknown command
**Steps:**
1. Type: `Fly to the moon`
2. Tap **Go**.

**Expected:**
- [ ] App does NOT crash
- [ ] Timeline shows "Understanding" → "Failed"
- [ ] User-readable error: "Could not understand command"
- [ ] Logs tab shows failed entry

**Actual result:** _________________________________

---

### E2. App not installed
**Steps:**
1. Type: `Open SomeFakeApp`
2. Tap **Go**.

**Expected:**
- [ ] App does NOT crash
- [ ] Timeline shows failed with reason

**Actual result:** _________________________________

---

### E3. Chrome not installed
**Steps:**
1. If Chrome is not installed, type: `Open Chrome`
2. Tap **Go**.

**Expected:**
- [ ] App does NOT crash
- [ ] Timeline shows failed with "Chrome is not installed"

**Actual result:** _________________________________

---

## Section F: Settings Tests

### F1. Model status refresh
**Steps:**
1. Go to **Settings** tab.
2. Look at **Model Status** section.
3. Tap the **refresh icon**.

**Expected:**
- [ ] All models show "Missing" (red X) since no models pushed yet
- [ ] Each model name is listed: Gemma, ASR, TTS, VAD, Punctuation, OCR
- [ ] No crash

**Actual result:** _________________________________

---

### F2. Storage usage
**Steps:**
1. In Settings, look at **Storage** section.

**Expected:**
- [ ] Shows "0 MB" or a small number (database only)

**Actual result:** _________________________________

---

### F3. Clear logs
**Steps:**
1. Make sure you have some logs from previous tests.
2. In Settings, tap **Clear Local Logs**.
3. Go to **Logs** tab.

**Expected:**
- [ ] Logs tab is empty

**Actual result:** _________________________________

---

## Section G: Navigation Tests

### G1. All tabs accessible
**Steps:**
1. Tap each bottom tab: **Agent, Notes, Skills, Logs, Settings**.

**Expected:**
- [ ] Agent tab: shows home screen
- [ ] Notes tab: shows notes list
- [ ] Skills tab: shows skills screen
- [ ] Logs tab: shows action logs
- [ ] Settings tab: shows settings
- [ ] No crash on any tab switch

**Actual result:** _________________________________

---

### G2. Tab state preserved
**Steps:**
1. In Notes tab, type something in search.
2. Switch to Agent tab.
3. Switch back to Notes tab.

**Expected:**
- [ ] Search text is preserved (or at least screen reopens without crash)

**Actual result:** _________________________________

---

## Summary

| Section | Tests | Passed | Failed |
|---------|-------|--------|--------|
| A: Text Commands | 5 | | |
| B: Phone Actions | 8 | | |
| C: Timeline | 3 | | |
| D: Permissions | 3 | | |
| E: Failure Handling | 3 | | |
| F: Settings | 3 | | |
| G: Navigation | 2 | | |
| **Total** | **27** | | |

## Critical Issues Found

List any crashes, hangs, or completely broken features:

1. _________________________________
2. _________________________________
3. _________________________________

## Files That Need Changes

List any files you had to modify or that need fixing:

1. _________________________________
2. _________________________________
3. _________________________________

## Verdict

- [ ] All 27 tests passed — **READY for Sherpa-ONNX integration**
- [ ] Some tests failed — **FIX issues first**, then re-test
- [ ] App does not build or install — **Report errors immediately**

**Tester's signature:** _______________
