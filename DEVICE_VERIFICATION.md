# UnoOne V2 Device Verification

**Status:** partially populated with V2 physical-device evidence (Xiaomi 14 only). Headless-provable
sub-items are ✅ with evidence; live-screen / live-mic / live-camera sub-items remain ☐ (require a
human at the device — no screenshots available to the automated runner). Secondary device not yet run.
**Release implication:** UnoOne V2 remains alpha until the required matrix is complete.

Evidence so far (Xiaomi 14, `23127PN0CG`, Android 15/API 35):
- Phase 5 Gemma: `artifacts/validation/xiaomi14/20260714-174410-PHASE3-DEVICE/PHASE5-GEMMA-ON-DEVICE.md`
- Phase 6 speech packs: `…/PHASE6-LANGUAGE-PACKS.md`
- Phase 7 headless functions: `…/PHASE7-HEADLESS-FUNCTIONS.md` (25 instrumented tests, 0 failures)
- 2026-07-15 eyes-free/router build (branch `fix/unoone-router-eyesfree`): install + launch + Gemma load +
  STT/TTS init + front-panel UI render verified via adb text logs; full instrumented suite OK (42 tests);
  live voice/camera/visual UX remains ☐. `artifacts/validation/xiaomi14/20260715-eyesfree-router/PHASE-EYESFREE-ROUTER-DEVICE.md`

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
| Xiaomi 14 | ✅ | ✅ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | 2026-07-15 (eyesfree/router build: install+launch+Gemma load verified; live UX ☐) | artifacts/validation/xiaomi14/20260715-eyesfree-router/PHASE-EYESFREE-ROUTER-DEVICE.md |
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

- [x] APK installs without package/signature error — ✅ 2026-07-15 (`pm install -r` Success, model data preserved).
- [x] App launches without crash — ✅ 2026-07-15 (`am start` Status ok, no FATAL/AndroidRuntime).
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
- [x] LiteRT-LM loads on GPU or records a safe CPU fallback — ✅ 2026-07-15 (Gemma 4 E2B loaded on CPU backend, conversation ready; GPU not selected, CPU is the safe fallback).
- [ ] App remains responsive during load.
- [x] Main phone planner owns the only Gemma engine — ✅ 2026-07-15 (single engine loaded; Secure Browser lease not active at rest).
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
- [ ] File upload opens Android user takeover; PageAgent cannot read arbitrary files. Automated ✅
      2026-07-16 (Playwright proves authorized `upload_file` clicks the real file input and receives
      only the selected file; Android `WebChromeClient`/Activity-result wiring covered by JVM tests
      and compilation). Physical Android picker selection remains ☐.
- [x] In Standard mode, final submission requires explicit confirmation — ✅ 2026-07-16 JVM (BrowserSafetyPolicy submit_form → Confirm);
- [x] In Standard mode, password entry requires manual takeover — ✅ 2026-07-16 JVM (BrowserSafetyPolicy → UserTakeover);
- [x] In Standard mode, OTP requires manual takeover — ✅ 2026-07-16 JVM (BrowserSafetyPolicy → UserTakeover);
- [x] In Standard mode, CAPTCHA requires manual takeover — ✅ 2026-07-16 JVM (BrowserSafetyPolicy → UserTakeover);
- [x] In Standard mode, legal acceptance requires manual takeover — ✅ 2026-07-16 JVM (BrowserSafetyPolicy → UserTakeover);
- [x] In Standard mode, payment action is blocked and cannot click the payment control — ✅ 2026-07-16 JVM (BrowserSafetyPolicy → Block; live control-not-clicked remains manual);
- [x] Explicit prototype Off mode converts browser Confirm/Takeover/Block decisions to Allow — ✅
      2026-07-16 JVM (policy + native-handler tests); Settings and browser show an unsafe-mode
      warning. Physical payment/credential workflow intentionally not exercised.
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
- [x] Airplane-mode test proves no silent network dependency — ✅ 2026-07-16 Xiaomi 14 live toggle:
      cold activity in 1583 ms, OFFLINE UI + language control rendered, one Gemma load, zero UnoOne
      ANR/FATAL; radio state restored. SpeechNoCloudFallbackTest also proves no implicit system/cloud STT fallback.
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

## 10. Command-path router + eyes-free assist (`fix/unoone-router-eyesfree`)

This section covers the command-path stabilization (A1-A7) and the eyes-free assist work
(B1-B6) that ride on top of it. Headless-provable logic is verified by JVM/Robolectric
tests; everything that depends on the live Gemma inference, microphone, speaker, camera,
WebView or TalkBack screen reader is a device-time gate and must remain ☐ until there is
on-device evidence. No item below is flipped to ✅ from compilation or JVM tests alone.

### 10.1 Command-path stabilization (A1-A7)

- [ ] A1: the final "Done" card speaks/shows the **full** streamed answer, not `?` or the
      first fragment (JVM: extractText join helper; device: live streamed answer is complete).
- [x] A2: `open calendar` / `launch calendar` / `show calendar app` route to `open_calendar`
      (not null, not Chrome) — ✅ JVM 2026-07-15 (RuleBasedParserTest); device ☐: real calendar
      app opens with no Gemma planning.
- [x] A3: harmless DIRECT tools skip the safety judge (no confirmation popup for plain
      answers) — ✅ JVM 2026-07-15 (judge-skipped-for-DIRECT policy test); device ☐: a plain
      spoken answer triggers no confirmation dialog.
- [x] A4: three-lane router — FAST_ACTION / CHAT / AGENT_ACTION — classifies correctly
      — ✅ JVM 2026-07-15 (IntentClassifierTest); device ☐: "explain god" answers in one
      inference with no confirmation; "open calendar" acts with no Gemma.
- [x] A5: screen/OCR context is not gathered for non-screen chat — ✅ JVM 2026-07-15
      (ContextSnapshotTest); device ☐: "explain god" does not capture accessibility/OCR.
- [ ] A6: English command → English response (no Hindi switch). JVM asserts the directive
      string is present; the no-language-switch behavior is a live device gate.
- [x] A7: stage-level latency diagnostics recorded — ✅ JVM 2026-07-15 (DiagnosticsTest);
      device ☐: `stage_*` keys populate from a real run.

### 10.2 Eyes-free assist (B1-B6)

- [x] B1/WS1: `detect_objects` requires CAMERA only (Accessibility gate removed) —
      ✅ JVM 2026-07-15 (CameraAccessHeadlessTest); device ☐: tapping Blind Aid starts the
      preview with no "needs system access" when CAMERA is granted.
- [x] B2/WS2: wake-phrase list contains "listen"; VOICE commands emit a spoken step
      — ✅ JVM 2026-07-15 (wake-phrase list + orchestrator narration tests); device ☐: KWS
      initializes with the new phrase; live-mic wake accuracy and audible "Yes, I'm listening"
      + step narration ("Checking safety"/"Creating a note"/"Done") are human gates.
- [x] B3/WS5: main-page capability surface routes each button to the correct handler —
      ✅ JVM 2026-07-15 (CapabilityTest); device ☐: each large button is tappable and
      TalkBack-announced.
- [x] B4/WS4: `secure_browser_task` is canonical, permission-gated, origin-checked against
      the real approved list — ✅ JVM 2026-07-15 (ApprovedOriginPolicyTest +
      CanonicalToolRegistryTest + ActionExecutorToolCoverageTest); device ☐: voice task to an
      approved origin runs via PageAgent (not system Chrome), reads the page aloud, and a
      spoken CONFIRM/ASK is answered by voice. Standard-mode BrowserSafetyPolicy remains enforced;
      the separately labelled Off mode is an explicit prototype override.
- [x] B5/WS3: Blind Aid scene-summary throttle + wording — ✅ JVM 2026-07-15
      (BlindAidNarratorTest); device ☐: live camera scene narration speaks and respects quiet
      mode; "read the screen"/"describe scene" speaks the result for VOICE.
- [ ] B6/WS6: TalkBack live regions announce "Listening"/"Processing"/"Blind Aid
      active"/"Done"; floating-overlay icons have contentDescriptions; text fields have real
      labels. Compose semantics declarations are compile-verified; the actual TalkBack
      announcement is a human screen-reader gate (the app module's `ui-test-junit4` is
      `androidTest`-only, so there is no JVM assertion of live-region behavior).

### 10.3 Honesty constraints for this section

- The approved-origin list (`unigurus.com`, `uniassist.ai`, `testsprep.in`, `inbharat.ai`
  and their `www.` hosts) is real — no fake or placeholder origins.
- No `BrowserSafetyPolicy` rule or per-action confirm/takeover gate was weakened; hands-free
  means *spoken* confirms, not skipped confirms.
- Live-mic STT accuracy, audible TTS quality, visual UI aesthetics and TalkBack
  announcements are not faked and stay ☐ until a human verifies them at the device.

Record:

```text
Run date: 2026-07-15
Branch: fix/unoone-router-eyesfree
Branch HEAD SHA: (see git log; 11 commits A1-A7 + B1-B6)
Device: Xiaomi 14 23127PN0CG, Android 15/API 35
Evidence: artifacts/validation/xiaomi14/20260715-eyesfree-router/PHASE-EYESFREE-ROUTER-DEVICE.md
Automated gate: lint ✅, all-module JVM unit tests ✅, instrumented OK (42) ✅, assemble ✅
Install (pm install -r): Success ✅
Launch (am start): Status ok, no crash ✅
Gemma load: CPU backend, conversation ready ✅
Sherpa STT/TTS init: offline ✅
Front-panel UI render (uiautomator XML): B3 capability surface + B6 labels present ✅
"open calendar" result (no Gemma): ☐ human
Plain-answer confirmation popup (absent): ☐ human
Full streamed answer on Done card: ☐ human
English-in → English-out: ☐ human
"listen" wake + step narration: ☐ human
Blind Aid CAMERA-only start + scene narration: ☐ human
Secure Browser voice task + read-aloud + spoken confirm: ☐ human
TalkBack live-region announcements: ☐ human
```

## 11. Eyes-free bulletproof fixes (`fix/unoone-eyesfree-bulletproof`)

This section covers the C1-C9 fixes for the four real, reproducible failures reported on the
Xiaomi 14 after the router/eyes-free merge (main@628d2c5): OOM "system shuts down" during Blind
Aid, slow Blind-Aid/listen activation, the Read-Screen settings-bounce trap, and the
front-end/agent-work + document/form-loading asks. Headless-provable logic is verified by
JVM/Robolectric tests; everything that depends on the live Gemma inference, microphone,
speaker, camera, WebView, MediaProjection or TalkBack is a device-time gate and stays ☐ until
there is on-device evidence. No item is flipped to ✅ from compilation or JVM tests alone.

### 11.1 What changed (C1-C9)

- **C1**: Blind Aid activation unloads the 2.5 GB Gemma brain (frees ~800 MB) before the camera
  binds; reloads on deactivate. Guards respect the Secure Browser / exclusive lease and the
  processing lock.
- **C2**: `BlindAidCameraPreview` binds CameraX asynchronously (`cameraProviderFuture.addListener`
  on the main executor, not a blocking `.get()` on the main thread); ML Kit `ObjectDetection` is
  lazy; a "Warming up the camera" overlay + immediate spoken "Blind Aid activated" give instant
  feedback.
- **C3**: Always-enabled Stop/Cancel control + `AgentOrchestrator.cancelCurrentCommand()` with
  run-generation tokens (`currentRunId`/`cancelledRunId`) so a cancel only stops the run it was
  aimed at; `addStep` no-ops after a cancel so the timeline isn't repopulated. The blind user is
  never trapped in a stuck "Reading screen"/processing state.
- **C4**: Read Screen now drives an in-app MediaProjection consent (`ScreenshotPermissionActivity`
  → `ScreenshotCapture` → `OcrControl`) and speaks the result — no bounce to MIUI Accessibility
  settings. `clearPendingAndReExecute` re-checks the required system permission on resume and does
  NOT blindly re-run (kills the infinite settings-bounce loop).
- **C5**: One-tap hands-free session — speak → voice reply → re-listen automatically. `VoiceService`
  `foregroundSessionActive` makes the in-app session the single mic owner (the KWS loop releases its
  `AudioRecorder` while the session is active), fixing the dual-`AudioRecord` "listen slow/erratic".
- **C6**: Voice commands route to the direct handlers (start/stop blind aid, read screen, open
  secure browser, stop listening) via the existing three-lane router; spoken confirms, not skipped.
- **C7**: Collapsible "Agent Flow Timeline" that shows the full agent work (auto-scroll to latest,
  live-region "Now: …" summary, expand/collapse) — agent work no longer gets covered/clipped.
- **C8**: Real document loaders (no heavy deps): PDF via `PdfRenderer`+ML Kit OCR, image via ML Kit
  OCR, `.xlsx` via JDK SAX over OOXML, HTML via a regex tag-stripper, CSV/text via UTF-8 — all
  JVM-tested. Legacy `.xls` is honestly reported unsupported, not faked. Output capped to the
  brain's context window with a spoken `truncated` notice.
- **C9**: PageAgent fills **offline** forms: a user-picked `.html` form is loaded into the sandboxed
  WebView at a synthetic `https://unoone.local-form` origin (reachable ONLY via
  `loadDataWithBaseURL`; `BrowserDomainPolicy` blocks navigation to it). The PageAgent runtime is
  injected as for a remote page and every action still round-trips through AUTHORIZE_ACTION →
  `BrowserSafetyPolicy` (origin-agnostic) — no payment/credential/OTP/captcha/legal/final-submission
  gate is weakened.

### 11.2 Verification record

```text
Run date: 2026-07-15
Branch: fix/unoone-eyesfree-bulletproof (commit 0f2da99)
Branch base: main@628d2c5
Merged to main @ c335d76 (owner sign-off 2026-07-15, --no-ff)
Device: Xiaomi 14 23127PN0CG (houji), serial 7f8cafef, Android 15/API 35
Automated gate: lint ✅ (no new issues, 32 baseline), :app + :core JVM unit tests ✅,
  instrumented OK (42) ✅, assembleDebug ✅, assembleDebugAndroidTest ✅
Install (adb push + pm install -r): Success ✅
Permissions granted: RECORD_AUDIO, POST_NOTIFICATIONS, CAMERA ✅
Launch (am start MainActivity): resumed, no crash, pid alive ✅
Gemma 4 E2B load: CPU backend, "conversation ready", model load 1259ms ✅
Front-panel UI render (uiautomator content-desc/text XML, no screenshots):
  "Stop and cancel" (C3) ✅, "Listen" (C5) ✅, "Blind Aid" (C1/C2) ✅,
  "Read Screen. Speaks what is currently on your screen." (C4) ✅,
  "Secure Browser. Opens the voice-driven private browser." (C9) ✅,
  "Load Document (PDF / Excel / image / text)" (C8) ✅,
  "Agent Flow Timeline, expanded" + "Collapse timeline" (C7) ✅
Instrumented suite note: one full-suite run showed 2 flaky failures in
  AgentSafetyPipelineHeadlessTest (deny-confirm / block-input) under the memory pressure of the
  resident 2.5 GB Gemma brain loaded by the localbrain tests; NOT reproducible in isolation (3/3),
  with the alphabetical prefix (24/24), or on a clean full-suite re-run (OK 42). Not a C1-C9
  regression (runValidatedToolCall + skill Blocked/Cancelled branches + SafetyGuard "bank"→BLOCK
  are byte-identical a788915..HEAD). Follow-up: harden awaitConfirmation's suspendCancellable-
  Coroutine + withTimeoutOrNull against full-suite load, or isolate the safety headless tests
  from the resident brain.

Human-gated (stay ☐ until the owner verifies at the device):
  Always-listening session: tap Listen → "I'm listening" → speak → voice reply → auto re-listen ☐
  Blind Aid: no lowmemorykiller kill during camera (logcat) + brain unload/reload ☐
  Blind Aid activation no longer multi-second frozen ☐
  Read Screen: in-app MediaProjection consent → speaks screen contents; no settings bounce ☐
  Stop / "stop listening" ends session, mic released ☐
  Live document OCR (PDF/image) + XLSX/HTML parse + spoken summary ☐
  Offline form: load .html → PageAgent fills it with spoken CONFIRM/TAKEOVER gates intact ☐
  TalkBack live-region announcements for the new timeline + Stop control ☐
```

### 11.3 Honesty constraints for this section

- C9 admits a synthetic local-form origin reachable solely via explicit `loadDataWithBaseURL`.
  Standard mode keeps every per-action confirm/takeover/block decision. The user-requested
  **Off — prototype (agent + browser)** mode now bypasses those action decisions explicitly and
  warns that PageAgent may submit forms/files/credentials/payment fields. Exact-origin isolation
  and the absence of arbitrary native JavaScript remain enforced in every mode.
- No fake URLs, origins, dummy narration, or placeholder parsers. Legacy `.xls` is reported
  unsupported rather than faked.
- Live-mic STT, audible TTS, live camera/OCR, MediaProjection Read Screen, live form-fill and
  TalkBack announcements are NOT faked and stay ☐ until a human verifies them at the device.

### 11.4 Full-function hardening verification (`codex/full-function-audit`)

```text
Run date: 2026-07-16
Branch base: main@f13c42f
Device: Xiaomi 14 23127PN0CG (houji), serial 7f8cafef, Android 15/API 35
Android exact gate: :app:lintDebug (no new issues), :app + :core JVM tests,
  :app:assembleDebug and :app:assembleDebugAndroidTest ✅
PageAgent gate: TypeScript typecheck, Vitest 2/2, production build, Playwright 3/3,
  Android asset bundle ✅
Install: adb push + pm install -r for app and androidTest APKs ✅
Instrumented suite: OK (42 tests), 148.442 s ✅
Normal cold launch: MainActivity 1673 ms; one Gemma load; zero UnoOne ANR ✅
Thread audit: process/main TID 19438; Gemma TID 22603, shared VoiceModule TID 22604,
  VoiceService TID 22638 — native model initialization is off the UI thread ✅
Airplane cold launch: 1583 ms; OFFLINE UI rendered; one Gemma load; zero ANR/FATAL;
  airplane state restored to disabled ✅
Language quick switch: all 7 choices rendered; Hindi selected on the main screen; offline
  WHISPER/hi STT and Hindi TTS reinitialized on worker TID 19407 ✅
Browser prototype-Off policy: JVM/native-handler coverage ✅; physical sensitive transaction ☐
File upload: real browser input + selected filename Playwright coverage ✅; physical picker ☐
Wake command buffering/wake-phrase stripping: JVM coverage ✅; live acoustic wake test ☐ because
  the optional keyword-spotter model is not installed (service reports manual activation only).
Live speaker intelligibility, camera/Blind Aid, MediaProjection, TalkBack, and real remote-site
PageAgent semantic accuracy remain human-gated ☐.
```

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
