# Gemma 4 E4B — Xiaomi 14 Device Handoff

This document is the exact next-day procedure for validating the `feat/e4b-agentic-runtime` branch on the Xiaomi 14.

Do not merge the branch into `main` and do not delete the existing E2B files until E4B has passed integrity, load, tool-calling, memory, and sustained-run checks on the phone.

## Current connected-device preflight (2026-07-22)

- Device: Xiaomi `23127PN0CG` (Xiaomi 14 / `houji`), Android 15, API 35.
- UnoOne accessibility service: enabled.
- Camera, microphone and Calendar runtime permissions: granted.
- `/data` available space observed: approximately 382 GB.
- Exact E4B artifact: **absent** from the app-private model root.
- Historical E2B artifact: present and intentionally preserved.
- Debug app and instrumentation APKs: installed with `adb install -r`; app data retained.
- Debug APK SHA-256: `b08be9ef5fc0442fffeef53524de5c608545472ac866bd8756a14e88270b84b2`.
- Instrumentation result: `OK (56 tests)`; focused voice, master-disable, Blind Aid,
  OCR/camera, DOCX/PDF and Page Agent matrix: `OK (22 tests)`.
- English and Hindi language-pack installation/verification passed. Acoustic wake-word and
  real-room recognition accuracy still require a human-spoken test; an injected offline transcript
  passed through the private hands-free command route.
- Installed app launched successfully after the test run, Accessibility remained enabled, and the
  current log buffer contained no matching UnoOne fatal exception or ANR.
- Result: E4B load, CPU/GPU evaluation, Page Agent model accuracy, memory, thermal and 50-task claims remain pending.

## 1. Update the local clone

```bash
git fetch origin
git checkout feat/e4b-agentic-runtime
git pull --ff-only origin feat/e4b-agentic-runtime
git status --short
git log -1 --oneline
```

The working tree must be clean before building.

## 2. Verify the model contract in source

```bash
python3 - <<'PY'
import json
from pathlib import Path
p = Path('android-app/UnoOneAgent/modelmanager/src/main/assets/models_manifest.json')
data = json.loads(p.read_text())
llms = [m for m in data['models'] if m['type'] == 'llm']
assert len(llms) == 1, llms
m = llms[0]
f = m['files'][0]
assert m['id'] == 'gemma-4-e4b'
assert m['folder'] == 'brain/gemma-4-e4b'
assert f['name'] == 'gemma-4-E4B-it.litertlm'
assert f['sizeBytes'] == 3659530240
assert f['sha256'] == '0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0'
print('E4B source contract OK')
PY
```

## 3. Run repository and Android gates

Use JDK 17.

```bash
python3 scripts/ci/check_repo_invariants.py

cd web-runtime/page-agent-unoone
npm install --no-audit --no-fund
npm run typecheck
npm test
npm run bundle:android
cd ../..

cd android-app/UnoOneAgent
chmod +x gradlew
./gradlew \
  :app:lintDebug \
  testDebugUnitTest \
  :app:assembleDebug \
  :app:assembleDebugAndroidTest \
  --stacktrace
```

Stop on the first failure. Do not hide warnings that indicate broken model paths, manifest mismatches, deprecated E2B runtime selection, duplicate services, or missing native libraries.

## 4. Connect the Xiaomi 14

Enable Developer options and USB debugging, unlock the phone, and confirm the host computer.

```bash
adb kill-server
adb start-server
adb devices -l
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.incremental
adb shell cat /proc/meminfo | head
adb shell df -h /data
```

Record the output in a new dated file under `artifacts/validation/xiaomi14/`.

## 5. Install the APK

Preferred:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If HyperOS blocks streamed installation:

```bash
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/unoone-e4b-debug.apk
adb shell pm install -r /data/local/tmp/unoone-e4b-debug.apk
```

After an APK update, HyperOS may disable Accessibility. Re-enable **UnoOne → Accessibility** before testing cross-app actions.

## 6. Install E4B through UnoOne

In UnoOne Model Status:

1. Select the sole `gemma-4-e4b` model.
2. Start the download on stable Wi-Fi.
3. Do not let the phone enter aggressive battery-saving mode.
4. Confirm progress resumes after one controlled app restart.
5. Wait for exact size and SHA-256 verification.
6. Do not remove E2B manually yet.

The expected file is:

```text
<app-private-model-root>/brain/gemma-4-e4b/gemma-4-E4B-it.litertlm
```

The expected exact values are:

```text
size    3659530240
sha256  0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0
```

## 7. Capture model-load logs

Before loading:

```bash
adb logcat -c
adb shell am force-stop com.unoone.agent
adb shell monkey -p com.unoone.agent -c android.intent.category.LAUNCHER 1
```

In another terminal:

```bash
adb logcat -v threadtime | tee unoone-e4b-logcat.txt
```

Filter important lines:

```bash
adb logcat -d | grep -iE \
  'UnoOne|GemmaPlanner|LiteRT|OpenCL|GPU|CPU|NPU|AndroidRuntime|FATAL|ANR|lowmemory|lmkd|OutOfMemory|SIGSEGV'
```

Record:

- model initialization duration;
- first successful backend;
- GPU failure reason, if CPU fallback is used;
- first-token latency;
- process memory before and after load;
- temperature and battery change;
- any crash, ANR, native signal, or low-memory kill.

Do not claim NPU use unless the runtime explicitly reports an NPU backend.

## 8. Run the brain self-test

The self-test must cover at least:

- ordinary short conversation;
- `open_app` for WhatsApp;
- `open_app` for Gmail;
- `open_calendar_insert` for a fully specified event;
- `send_whatsapp` as a reviewable draft only;
- `draft_email` with recipient, subject, and body;
- unknown tool rejection;
- missing required argument rejection;
- wrong argument type rejection;
- concise Hindi response when Hindi is active.

Any malformed or invented recipient, package, date, time, or success claim is a failure.

## 9. Deterministic command matrix

These commands should work without waiting for E4B planning:

```text
Uno
Uno on
Uno start
Uno start blind mode
Start blind
Stop blind
Hindi mein bolo
Open WhatsApp
Open Gmail
Open Calendar
Read screen
Go home
Go back
Stop speaking
Disable UnoOne
```

For each command record:

- raw STT;
- normalized command;
- selected deterministic intent;
- whether model inference was bypassed;
- executed action;
- verification evidence;
- spoken language;
- total latency.

## 10. Agentic action matrix

Test:

1. Draft an email to a full email address with subject and body.
2. Prepare a WhatsApp message with an explicit number and exact message.
3. Add a calendar event with exact title, date, start, and end.
4. Read the current screen and summarize it in Hindi.
5. Open Secure Browser and complete a safe local form task, stopping before submission.
6. Ask an ambiguous request that should produce one clarification question instead of guessed arguments.
7. Give a compound request and confirm the bounded observe-plan loop does not exceed its step limit.

UnoOne must never announce success before native verification.

## 11. Blind Aid and memory test

1. Load E4B.
2. Start Blind Aid.
3. Confirm the E4B engine is released before camera analysis reaches steady state.
4. Detect supported objects for at least three minutes.
5. Stop Blind Aid.
6. Confirm camera callbacks and narration stop.
7. Confirm E4B reloads once, not multiple times.
8. Run a normal agent command after reload.

Scan logs for duplicate engine loads, duplicate foreground services, OOM, LMK, ANR, or stale narration.

## 12. Secure Browser lease test

1. Confirm the main E4B brain is loaded.
2. Open Secure Browser.
3. Confirm the main planner unloads before the Page Agent planner loads.
4. Run a safe browser task.
5. Close Secure Browser.
6. Confirm the browser planner closes and the main brain restores once.

Two E4B engines must never be resident simultaneously.

## 13. Sustained run

Run at least 50 mixed tasks:

- 20 deterministic commands;
- 15 E4B planning commands;
- 5 Hindi/Hinglish tasks;
- 5 screen-reading tasks;
- 5 Blind Aid or Secure Browser transitions.

Pass criteria:

- no crash;
- no ANR;
- no OOM or low-memory kill;
- no duplicate service or engine;
- no wrong-recipient action;
- no unconfirmed external send;
- no false success announcement;
- acceptable heat and battery behaviour;
- model remains usable after background/foreground and screen lock/unlock.

## 14. Remove legacy E2B only after success

After E4B integrity, load, self-test, and sustained-run checks pass, run the guarded migration from the app or call the code path that invokes:

```text
ModelManager.removeLegacyE2BIfQualified(userApproved = true)
```

Then verify:

```text
brain/gemma-4-e4b/gemma-4-E4B-it.litertlm    present
brain/gemma-4-e2b/                            absent
*.part E2B files                              absent
legacy E2B metadata                           absent
```

Do not use an unguarded recursive shell deletion against an interpolated path.

## 15. Final Git workflow

After fixing any device-specific errors and rerunning all gates:

```bash
git status --short
git diff --check
python3 scripts/ci/check_repo_invariants.py

cd android-app/UnoOneAgent
./gradlew :app:lintDebug testDebugUnitTest :app:assembleDebug --stacktrace
cd ../..

git add -A
git commit -m "feat: qualify UnoOne Gemma 4 E4B agent runtime on Xiaomi 14"
git push origin feat/e4b-agentic-runtime
```

Merge only when the pull request CI is green and the new physical-device evidence is committed.
