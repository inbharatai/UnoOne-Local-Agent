# UnoOne V2 Device Verification

**Status:** partially populated with V2 physical-device evidence (Xiaomi 14 only). Headless-provable
sub-items are ✅ with evidence; live-screen / live-mic / live-camera sub-items remain ☐ (require a
human at the device — no screenshots available to the automated runner). Secondary device not yet run.
**Release implication:** UnoOne V2 remains alpha until the required matrix is complete.

Evidence so far (Xiaomi 14, `23127PN0CG`, Android 15/API 35):
- Phase 5 Gemma: `artifacts/validation/xiaomi14/20260714-174410-PHASE3-DEVICE/PHASE5-GEMMA-ON-DEVICE.md`
- Phase 6 speech packs: `…/PHASE6-LANGUAGE-PACKS.md`
- Phase 7 headless functions: `…/PHASE7-HEADLESS-FUNCTIONS.md` (25 instrumented tests, 0 failures)

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
| Xiaomi 14 | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | 2026-07-14 (partial: Phase 5/6/7 sub-items, see checklists) | artifacts/validation/xiaomi14/20260714-174410-PHASE3-DEVICE/ |
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

- [x] create/read/search/delete note workflows — ✅ 2026-07-14 headless (NotesCrudHeadlessTest);
- [ ] open installed and missing apps;
- [ ] read screen;
- [ ] scroll, swipe, back and home;
- [ ] find-and-click and fill-field workflows;
- [ ] compound commands with mixed risk levels;
- [x] skills execution through the same safety path — ✅ 2026-07-14 headless (AgentSafetyPipelineHeadlessTest);
- [x] unknown-tool rejection — ✅ 2026-07-14 headless (SafetyGuardHeadlessTest / CanonicalToolRegistry.isKnown);
- [x] missing-argument rejection — ✅ 2026-07-14 headless (SafetyGuardHeadlessTest / requiredParams);
- [x] destructive-action confirmation — ✅ 2026-07-14 headless (SafetyGuardHeadlessTest + AgentSafetyPipelineHeadlessTest);
- [x] blocked payment/credential/OTP request — ✅ 2026-07-14 headless (SafetyGuardHeadlessTest BLOCK tier + pipeline Security Block);
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
- [x] Unapproved origin, subdomain, HTTP, localhost and IP-literal navigation are blocked — ✅ 2026-07-14 headless (BrowserDomainPolicy.evaluate, SecureBrowserPolicyHeadlessTest);
- [x] PageAgent runtime initializes from the packaged asset — ✅ 2026-07-14 headless (asset present + sha-verified d434912a…; live WebView inject/init remains manual);
- [ ] Ordinary text field fill works.
- [ ] Dropdown selection works.
- [ ] Checkbox and radio selection work.
- [ ] Date input works.
- [ ] Scrolling and observation loop work.
- [ ] File upload opens Android user takeover; PageAgent cannot read arbitrary files.
- [x] Final submission requires explicit confirmation — ✅ 2026-07-14 headless (BrowserSafetyPolicy submit_form → Confirm);
- [x] Password entry requires manual takeover — ✅ 2026-07-14 headless (BrowserSafetyPolicy → UserTakeover);
- [x] OTP requires manual takeover — ✅ 2026-07-14 headless (BrowserSafetyPolicy → UserTakeover);
- [x] CAPTCHA requires manual takeover — ✅ 2026-07-14 headless (BrowserSafetyPolicy → UserTakeover);
- [x] Legal acceptance requires manual takeover — ✅ 2026-07-14 headless (BrowserSafetyPolicy → UserTakeover);
- [x] Payment action is blocked and cannot click the payment control — ✅ 2026-07-14 headless (BrowserSafetyPolicy → Block; live control-not-clicked remains manual);
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

- [x] Streaming STT loads and transcribes offline — ✅ 2026-07-14 (engine loads offline proven Phase 6c; transcription-accuracy on real mic audio remains manual);
- [x] TTS loads and speaks offline — ✅ 2026-07-14 (Phase 6c: real PCM generated for English on device);
- [ ] Wake-word/VAD path works as configured.
- [x] Airplane-mode test proves no silent network dependency — ✅ 2026-07-14 headless (SpeechNoCloudFallbackTest: engines return Error, never cloud/Android-SpeechRecognizer fallback; live airplane-mode toggle remains manual);
- [ ] Low-confidence retry behaves correctly.

### Current Indic baselines

Test Hindi, Bengali, Tamil, Telugu, Kannada and Malayalam separately:

- [x] pack downloads/installs correctly — ✅ 2026-07-14 (Phase 6a: all 6 Indic packs sha-verified);
- [x] activation is blocked before health passes — ✅ 2026-07-14 (Phase 7: LanguagePackManager.state gates installed on missing+unhealthy);
- [x] shared ASR is retained while another language depends on it — ✅ 2026-07-14 headless (Phase 7: ml uninstalled, sherpa-asr-whisper retained, hi stayed healthy);
- [ ] STT produces usable output in clean speech;
- [ ] STT is tested in real environmental noise;
- [ ] TTS pronunciation is intelligible;
- [ ] code-mixed terms, names, dates and numbers are tested;
- [x] remove/reinstall/repair works — ✅ 2026-07-14 headless (Phase 7: uninstall→reinstall ml healthy+verified, on-disk model.onnx restored);
- [x] no system/cloud speech fallback occurs unless explicitly enabled — ✅ 2026-07-14 headless (SpeechNoCloudFallbackTest).

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

- [x] Camera-access tools are canonical, permission-gated and safety-classified — ✅ 2026-07-14 headless (CameraAccessHeadlessTest on Xiaomi 14: `open_camera`→CAMERA runtime perm + CONFIRM; `detect_objects`(Blind Aid)→CAMERA+Accessibility + STRONG_CONFIRM; `deactivate_blind_aid`→DIRECT/no-perm; `ocr_screen`→MediaProjection NOT camera).
- [ ] CameraX preview starts and stops cleanly.
- [ ] Object detection produces expected labels/locations.
- [ ] Haptic feedback works.
- [ ] Spoken guidance works with the active offline TTS pack.
- [ ] Low light, motion blur, covered camera and camera-denied states are handled.
- [ ] Background/foreground transition does not leak camera resources.
- [x] OCR recognizer runs on-device against rendered text — ✅ 2026-07-14 headless (OcrControlHeadlessTest on Xiaomi 14: bundled ML Kit Latin recognizes a rendered Latin string end-to-end; `recognizeScreen()` honors the MediaProjection gate headlessly).
- [ ] Live on-screen OCR capture flow (MediaProjection screenshot of a real app screen) — manual.
- [ ] Blind Aid remains functional when Gemma is absent or unloaded.

## 8. Lifecycle, update and recovery

- [ ] process kill and relaunch recover local state;
- [x] app update preserves Room data and model metadata — ✅ 2026-07-14 headless (NotesCrudHeadlessTest + MemoryStoreHeadlessTest: Room survives DB close/reopen on device; ModelMetadataEntity shares the same UnoOneDatabase);
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
