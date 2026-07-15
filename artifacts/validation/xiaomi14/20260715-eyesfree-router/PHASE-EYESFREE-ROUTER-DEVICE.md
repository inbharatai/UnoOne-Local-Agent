# Phase — Eyes-free + router device run (2026-07-15)

Branch: `fix/unoone-router-eyesfree` (off `main@a788915`), 11 commits (A1-A7 + B1-B6).
Device: Xiaomi 14 `23127PN0CG` (houji), Android 15 / API 35, serial `7f8cafef`.
Gemma 4 E2B + Sherpa speech packs carried over from the 2026-07-14 validation (model data
preserved across the `-r` reinstall).

## Automated gate (all green)

- `:app:lintDebug` — green.
- `testDebugUnitTest` (all modules: app, core, localbrain, securebrowser, observability,
  safetyguard, voice, languagepacks, modelmanager) — green. New JVM logic tests pass:
  `IntentClassifierTest`, `BlindAidNarratorTest`, `ApprovedOriginPolicyTest`, extended
  `RuleBasedParserTest`, `ContextSnapshotTest`, `DiagnosticsTest`, `CanonicalToolRegistryTest`
  (28 tools), `SafetyGuardToolCoverageTest`, `ActionExecutorToolCoverageTest`.
- `:app:assembleDebug` + `:app:assembleDebugAndroidTest` — green.
- Instrumented suite on Xiaomi 14 via `am instrument`: **OK (42 tests)**, 129.4 s.
  Evidence: `instrumented-suite.txt`. Includes `CameraAccessHeadlessTest` (5, incl. the
  B1 CAMERA-only assertion), `SecureBrowserPolicyHeadlessTest`, `SafetyGuardHeadlessTest`,
  `AgentSafetyPipelineHeadlessTest`, `GemmaPlannerAccuracyTest`, speech fallback tests.

## Live device evidence (adb text logs — no screenshots read)

- APK install via `adb push` + `pm install -r` → `Success` (model data preserved).
- `am start -W -n com.unoone.agent/.MainActivity` → `Status: ok`, WaitTime ~3.0 s, **no
  FATAL/AndroidRuntime crash**. Evidence: `launch-logcat.txt`.
- TTS engine bound to `com.google.android.tts`.
- Sherpa STT initialized offline (streaming transducer, en, 4 threads).
- Sherpa TTS initialized offline (en-IN, espeak frontend, 2 threads).
- **Gemma 4 E2B loaded on CPU backend, "conversation ready"** (~6 s after process start;
  GPU not selected — CPU is the safe fallback). XNNPack weight cache reused.
- Mic capture path live: `AudioRecordImpl` 16 kHz mono recording confirmed.
- Front-panel UI renders correctly with all TalkBack `contentDesc` strings (B6) and the 2x2
  capability surface (B3), and the text field's floating **"Command"** label (B6) is present.
  Evidence: `front-panel-uiautomator.xml` (text XML hierarchy, not an image).

## Known non-blocking observation (NOT a regression)

- `ForegroundServiceStartNotAllowedException` auto-starting `VoiceService` from
  `UnoOneApplication.onCreate`. The `VoiceService.start(this)` call is pre-existing and already
  wrapped in try/catch (logged "Failed to auto-start VoiceService", app continues). This is the
  documented Android 14+ restriction for FGS starts from an `am start` (adb) cold launch
  (`mAllowStartForeground false`); a real user tap of the launcher icon grants FGS-start
  eligibility. The branch added only the `onWakeWord` hook *above* this existing call; the
  start path is unchanged. Net: the wake-word hands-free service is not auto-started under an
  adb launch — a human launch + live wake test is the gate for B2.

## Still ☐ — human gates (cannot be verified without eyes/voice/ears)

These require a human at the device and are deliberately left unchecked:
- A typed/spoken command producing the expected spoken answer; calendar actually opening
  (FAST_ACTION); the CHAT lane answering in one inference with no confirmation popup and the
  full answer on the Done card (A1/A3/A4 live UX).
- English-in → English-out on a real turn (A6).
- "listen" wake accuracy + audible "Yes, I'm listening" + step narration (B2).
- Blind Aid live camera scene narration + close-obstacle warnings, quiet mode (B5).
- Secure Browser voice task to an approved origin via PageAgent, read-aloud, spoken confirm
  (B4).
- TalkBack live-region announcements of "Listening"/"Processing"/"Blind Aid active"/"Done"
  (B6) — needs the screen reader enabled.
- Visual UI aesthetics judgment (no image capability — text-only verification only).

## Cleanup

Runaway mic recording from blind `input` tapping was ended by `am force-stop com.unoone.agent`.