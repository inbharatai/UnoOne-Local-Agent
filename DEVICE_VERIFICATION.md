# UnoOne V2 Device Verification

**Status:** not yet populated with V2 physical-device evidence.  
**Release implication:** UnoOne V2 remains alpha until the required matrix is complete.

Compilation, lint, JVM tests and Playwright tests cannot prove LiteRT-LM, Sherpa-ONNX, Android Accessibility, CameraX, haptics, microphone, thermal behavior or real WebView operation on a phone.

## Required target devices

| Target | Android | SoC | RAM | Reason | Assigned device |
|---|---:|---|---:|---|---|
| Primary | 15 / API 35 | Snapdragon 8 Gen 3 | 12 GB | current development target | Xiaomi 14 |
| Secondary | API 28+ | different supported Android hardware | 8 GB+ | catches vendor/backend/device-specific failures | must be identified before release |

A release cannot be marked device-qualified with only one phone.

## Verification matrix

Use ✅ pass, ❌ fail, or ☐ not run. Every ✅ must have a date and attached log/evidence path.

| Device | Build/install | Gemma load | Phone planning | PageAgent | English speech | Indic speech | Accessibility | Blind Aid | Lifecycle | Performance | Date | Evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Xiaomi 14 | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | — | — |
| Secondary device | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | — | — |

## Before testing

- [ ] Latest Android CI is green on the exact commit under test.
- [ ] Debug or release-candidate APK SHA-256 is recorded.
- [ ] Device model, Android build, SoC, RAM and free storage are recorded.
- [ ] Battery is above 60% and device temperature is recorded before the run.
- [ ] Required permissions are granted deliberately, not assumed.
- [ ] Gemma and speech files match the manifest size and SHA-256.
- [ ] No old Gemma 3n or `gemma-local` folder is present.
- [ ] PageAgent Android asset is packaged and non-empty.

## 1. Build, install and basic UI

- [ ] APK installs without package/signature error.
- [ ] App launches without crash.
- [ ] Settings, Model Status, Offline Languages, Voice Test, Audit and Secure Browser routes open.
- [ ] Floating assistant can be enabled and disabled.
- [ ] Foreground-service notification is displayed when required.
- [ ] Rotation/background/foreground does not duplicate services or ViewModels.
- [ ] Clean reinstall starts with correct empty/required model states.

Record:

```text
APK path:
APK SHA-256:
Version name/code:
Install command/result:
Startup log path:
```

## 2. Gemma 4 E2B model integrity and load

Expected artifact:

```text
File: gemma-4-E2B-it.litertlm
Size: 2588147712 bytes
SHA-256: 181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c
Folder: models/brain/gemma-4-e2b/
```

- [ ] Model installation completes or resumes after interruption.
- [ ] Size verification passes.
- [ ] SHA-256 verification passes.
- [ ] Corrupted/partial file is rejected and repair works.
- [ ] LiteRT-LM loads on GPU or records a safe CPU fallback.
- [ ] App remains responsive during load.
- [ ] Main phone planner owns the only Gemma engine.
- [ ] Memory-pressure unload frees the engine.
- [ ] Safe reload works after foreground return.

Record:

```text
Backend:
Cold load ms:
Warm reload ms:
Peak RAM MB:
Failure/retry result:
Log path:
```

## 3. Phone-agent planning

Run at least 50 sequential tasks, including:

- [ ] create/read/search/delete note workflows;
- [ ] open installed and missing apps;
- [ ] read screen;
- [ ] scroll, swipe, back and home;
- [ ] find-and-click and fill-field workflows;
- [ ] compound commands with mixed risk levels;
- [ ] skills execution through the same safety path;
- [ ] unknown-tool rejection;
- [ ] missing-argument rejection;
- [ ] destructive-action confirmation;
- [ ] blocked payment/credential/OTP request;
- [ ] inference timeout and recovery;
- [ ] repeated failures without deadlock or duplicate execution.

Record:

```text
Tasks attempted:
Tasks successful:
Incorrect tool selections:
Unknown tools rejected:
Timeouts:
Median planning latency ms:
P95 planning latency ms:
```

## 4. Secure Browser / Alibaba PageAgent

The Secure Browser must use the same Gemma artifact through an exclusive model lease.

- [ ] Entering Secure Browser unloads/reserves the phone brain correctly.
- [ ] No second Gemma allocation appears in memory.
- [ ] Approved exact HTTPS origin loads.
- [ ] Unapproved origin, subdomain, HTTP, localhost and IP-literal navigation are blocked.
- [ ] PageAgent runtime initializes from the packaged asset.
- [ ] Ordinary text field fill works.
- [ ] Dropdown selection works.
- [ ] Checkbox and radio selection work.
- [ ] Date input works.
- [ ] Scrolling and observation loop work.
- [ ] File upload opens Android user takeover; PageAgent cannot read arbitrary files.
- [ ] Final submission requires explicit confirmation.
- [ ] Password entry requires manual takeover.
- [ ] OTP requires manual takeover.
- [ ] CAPTCHA requires manual takeover.
- [ ] Legal acceptance requires manual takeover.
- [ ] Payment action is blocked and cannot click the payment control.
- [ ] Arbitrary JavaScript execution is unavailable.
- [ ] Page prompt injection cannot bypass the native policy.
- [ ] Audit log records origin/action/decision without typed form values.
- [ ] Closing Secure Browser restores the phone brain safely.
- [ ] 50 sequential browser-planning steps complete without OOM or stale session reuse.

Record:

```text
Origins tested:
Workflows tested:
Blocked actions:
Takeovers completed:
Audit sample path:
Peak RAM MB:
Median step latency ms:
P95 step latency ms:
```

## 5. Offline speech

### English

- [ ] Streaming STT loads and transcribes offline.
- [ ] TTS loads and speaks offline.
- [ ] Wake-word/VAD path works as configured.
- [ ] Airplane-mode test proves no silent network dependency.
- [ ] Low-confidence retry behaves correctly.

### Current Indic baselines

Test Hindi, Bengali, Tamil, Telugu, Kannada and Malayalam separately:

- [ ] pack downloads/installs correctly;
- [ ] activation is blocked before health passes;
- [ ] shared ASR is retained while another language depends on it;
- [ ] STT produces usable output in clean speech;
- [ ] STT is tested in real environmental noise;
- [ ] TTS pronunciation is intelligible;
- [ ] code-mixed terms, names, dates and numbers are tested;
- [ ] remove/reinstall/repair works;
- [ ] no system/cloud speech fallback occurs unless explicitly enabled.

### Assamese

Assamese remains planned until exact artifacts are selected.

- [ ] exact STT artifact and revision recorded;
- [ ] exact TTS artifact and revision recorded;
- [ ] licence and redistribution conditions approved;
- [ ] file size and SHA-256 recorded;
- [ ] Android load passes;
- [ ] benchmark corpus passes agreed quality gates;
- [ ] pack is changed from `planned` only after evidence is committed.

## 6. Accessibility and phone control

- [ ] service enable/disable flow works;
- [ ] visible text capture works;
- [ ] tap/type/fill works;
- [ ] scroll/swipe/long-press works;
- [ ] back/home/notifications/recents work where permitted;
- [ ] find-and-click handles missing targets safely;
- [ ] screen-off/background behavior is understood and logged;
- [ ] disabling the service stops privileged automation cleanly;
- [ ] permission denial and permanent denial recovery work;
- [ ] no action executes after confirmation timeout/cancel.

## 7. Blind Aid and OCR

- [ ] CameraX preview starts and stops cleanly.
- [ ] Object detection produces expected labels/locations.
- [ ] Haptic feedback works.
- [ ] Spoken guidance works with the active offline TTS pack.
- [ ] Low light, motion blur, covered camera and camera-denied states are handled.
- [ ] Background/foreground transition does not leak camera resources.
- [ ] OCR permission and capture flow work.
- [ ] Blind Aid remains functional when Gemma is absent or unloaded.

## 8. Lifecycle, update and recovery

- [ ] process kill and relaunch recover local state;
- [ ] app update preserves Room data and model metadata;
- [ ] model partial download resumes;
- [ ] hash mismatch deletes/quarantines corrupt output;
- [ ] storage-full error is visible and recoverable;
- [ ] microphone/camera/accessibility revocation is handled;
- [ ] battery optimization and manufacturer autostart prompts are documented;
- [ ] repeated Secure Browser open/close does not leak WebViews or model engines;
- [ ] memory pressure during PageAgent closes the lease without immediate reallocation;
- [ ] crash logs and diagnostics can be exported without sensitive form values.

## 9. Performance and thermal run

Run a minimum 30-minute mixed workload:

```text
10 phone-planning tasks
10 STT/TTS interactions
10 Secure Browser planning steps
Blind Aid for 10 continuous minutes
repeat until 30 minutes total
```

Record at 0, 10, 20 and 30 minutes:

```text
Battery percentage:
Device temperature:
Process RSS/peak RAM:
Gemma backend:
Planning latency:
Speech latency:
Dropped frames/ANR/crash:
```

Fail the device gate if there is an OOM, ANR, model duplication, uncontrolled temperature rise, corrupted response loop or safety-policy bypass.

## Evidence requirements

For every completed row, commit or link:

- exact Git commit SHA;
- APK SHA-256;
- device and Android build details;
- model/language artifact versions and hashes;
- `adb logcat`/diagnostics output with sensitive values removed;
- test date and tester;
- failed cases and reproduction steps;
- final pass/fail decision.

Do not replace ☐ with ✅ without evidence. Device qualification and production approval are separate decisions.
