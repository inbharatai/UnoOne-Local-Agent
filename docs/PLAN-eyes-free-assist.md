# UnoOne Eyes-Free Assist — Structured Plan (for 2026-07-15)

Goal: make UnoOne genuinely usable by a blind user eyes-free. The device should **listen** on a
spoken word, **do** what is asked, and **speak** what it is doing and what is in front of the
camera — with Blind Aid, voice command, read-screen/OCR, and the Secure Browser (PageAgent) all
reachable from the **main page**, not buried in Settings.

This is a **plan only** — no code changed yet. Every task below is anchored to real file:line
findings from the 2026-07-14 review (four read-only traces: Blind Aid, voice, PageAgent, main-screen UX).

---

## Current-state findings (why each workstream exists)

### Blind Aid / camera
- `detect_objects` is gated on **CAMERA + Accessibility** (`ToolPermissionRegistry.kt:69-72`), but
  `BlindAidManager` **never uses Accessibility** — it is pure CameraX `ImageAnalysis` + ML Kit ODT +
  vibrator/tone/TTS (`BlindAidManager.kt:78-230`). The Accessibility requirement is **vestigial** and
  is exactly what produces the dead-end **"needs system access to detect objects"** message
  (`AgentOrchestrator.kt:874-881`).
- `onSystemPermissionRequired` (the callback that would let the app offer a one-tap grant) is
  **never wired** anywhere (`AgentOrchestrator.kt:206`) — only runtime perms are wired
  (`MainActivity.kt:78`). So a missing system access = failed timeline step + stashed
  `pendingCommand`, with no way to recover from inside the app.
- The main-screen "Blind Aid" quick button routes through the text-command pipeline
  (`AgentScreen.kt:327` → `onTextCommand("activate blind aid")`), so it hits the safety + system-access
  gate and fails the same way. A direct toggle exists (`AgentViewModel.kt:143 setBlindAidActive`) but
  the button doesn't use it.
- Blind Aid speaks only an activation disclaimer + sparse obstacle warnings ("Stop! X" / "X ahead"
  when fillRatio > 0.15, throttled 3500 ms, `BlindAidManager.kt:140-202`). There is **no continuous
  "what's in front" narration**.
- Camera preview is Activity-bound (Compose `BlindAidCameraPreview`, `AgentScreen.kt:354-460`); it
  only runs while `MainActivity` is foregrounded.

### Voice command
- The wake phrase is **hardcoded to `"uno one"`** (`VoiceService.kt:155`). It is a **runtime keyword
  file** (`KeywordSpotter.kt:118-127` writes `cacheDir/unoone_keywords.txt`), NOT baked into the
  model — so adding `"listen"` is a **code edit**, no model retrain, provided the word tokenizes via
  `tokens.txt` in `speech/shared/vad` (English BPE — almost certainly yes).
- The round-trip **wake → command → act → speak-final-result** already works for `InputType.VOICE`
  (`AgentOrchestrator.kt:583-586, 699, 831-832`). But **intermediate steps are visual only**
  (`addStep` at `AgentOrchestrator.kt:1038-1044` never speaks) — there is no "I'm doing X" narration.
- The TTS API to speak arbitrary strings already exists (`SherpaTtsEngine.speak`,
  `VoiceModule.speak` `VoiceModule.kt:234-245`) and is used outside `speak_response` (blind-aid,
  final responses, VoiceTest). We just need to call it at step milestones.
- Dead hooks: `onWakeWordDetected`/`onCommandReceived` are never assigned; only the static
  `voiceCommandCallback` is live (`UnoOneApplication.kt:99`). `ACTION_VOICE_COMMAND`/`EXTRA_COMMAND`
  are declared but unhandled in `onStartCommand` (`VoiceService.kt:60-61, 99-110`).
- Without the KWS model installed → **push-to-talk only**, no always-on listening
  (`VoiceService.kt:183-185`). Wake word stays English even for Indic command language; language is
  chosen in Settings (visual) — a gap for a blind user.

### PageAgent / Secure Browser
- **Buried 2 taps deep**: Agent → Settings → "Secure Browser (PageAgent)" (`SettingsScreen.kt:103`
  → `UnoOneNavHost.kt:118`). Not on the main screen, not in the bottom nav (`Screen.kt:18-24`).
- **Disconnected from the agent**: there is **no tool that launches the Secure Browser**. `open_url`/
  `open_chrome` open the **system Chrome** (`ActionExecutor.kt:149-163`), an unaudited path that
  bypasses the PageAgent safety machinery entirely. So "open unigurus and fill the form" lands in
  Chrome, not the secure WebView.
- **No voice input** on the browser screen — the task is a text field (`SecureBrowserScreen.kt:134-142`).
  **No TTS narration** of PageAgent actions (`SecureBrowserViewModel.activitySummary` is visual-only,
  `SecureBrowserViewModel.kt:321-327`).
- Human-in-the-loop prompts (CONFIRM/ASK/TAKEOVER) are visual `AlertDialog`s
  (`SecureBrowserScreen.kt:177-232`) — **not spoken, not voice-answerable**.
- Approved origins are hard-coded (`SecureBrowserViewModel.kt:342-351`: unigurus, uniassist,
  testsprep, inbharat). No picker; a blocked origin is a silent status-card error with no list of
  what *is* approved.

### Main-screen UX/UI
- Start destination = `AgentScreen` (`UnoOneNavHost.kt:103`). Layout: header, offline/blind-aid
  badge, blind-aid camera preview (only when active), progress, waveform, mic FAB, text input + Go,
  4 quick actions (Create Note / Open Chrome / Calendar / Blind Aid), timeline.
- Bottom nav: Agent, Notes, Skills, Logs, Settings. **Secure Browser, Voice Test, Model Status,
  Language Packs, Audit, Privacy all live under Settings → Manage.**
- **OCR / read-screen has NO UI surface at all** — `ScreenshotPermissionActivity` exists but is not
  wired to any route or button.
- TalkBack is partial: mic FAB + quick actions are labeled; but **state changes (listening,
  processing, blind-aid active, done) are not announced** as live regions, the **floating overlay is
  largely unlabeled** (`contentDescription = null` at `FloatingAgentService.kt:265,298,302,363,379`),
  and text fields lack proper labels.

---

## Design principles

1. **Listen → Speak → Act.** Eyes-free means the device must speak what it is doing at every step,
   not just the final answer.
2. **One-tap / one-word reachability.** Blind Aid, Listen mode, Read Screen, and Secure Browser are
   primary capabilities → they live on the main page, not behind Settings.
3. **No vestigial gates.** Don't block a capability on a permission it doesn't use. Don't dead-end
   on a missing permission — offer a one-tap grant and resume.
4. **Safety stays.** The BrowserSafetyPolicy and the per-action authorization/confirm/takeover gates
   are not weakened. Hands-free means *spoken* confirmations, not *skipped* confirmations.
5. **Honesty gates.** No fake URLs/origins, no dummy narration, no marking a device item ✅ without
   phone evidence. Build/lint/test green before any commit; don't merge to main without owner sign-off.

---

## Workstreams

### WS1 — Fix Blind Aid activation (P0, unblocks the demo)
The smallest, highest-impact fix. After this, "Blind Aid" works with just the CAMERA permission.

1. **Drop the vestigial Accessibility requirement from `detect_objects`** —
   `ToolPermissionRegistry.kt:69-72` → `listOf(RuntimePerm(CAMERA))` only. `BlindAidManager` does not
   use Accessibility (confirmed). Keep `Accessibility` on `read_screen`/`system_control` (those
   genuinely use it). Update the misleading comment at `ToolPermissionRegistry.kt:68`.
2. **Wire `onSystemPermissionRequired` in `MainActivity`** (and `FloatingAgentService`) so any
   *remaining* system-access need offers a one-tap grant: `Settings.ACTION_ACCESSIBILITY_SETTINGS`
   deep-link for Accessibility, the existing `ScreenshotPermissionActivity` for MediaProjection,
   `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` for Overlay. After the grant, resume the stashed
   `pendingCommand` (`AgentOrchestrator.kt:518`). Mirror the runtime-perm wiring at `MainActivity.kt:78`.
3. **Make the main-screen "Blind Aid" button a direct toggle**, not a text command:
   `AgentScreen.kt:327` → `viewModel.setBlindAidActive(true)` (setter at `AgentViewModel.kt:143`),
   requesting CAMERA first if missing. The voice/text command "activate blind aid" path stays (now
   unblocked by task 1).
4. **Tests**: extend `CameraAccessHeadlessTest` to assert `detect_objects` requires **only CAMERA**
   (no Accessibility). On-device: tapping Blind Aid starts the preview with no "needs system access"
   when CAMERA is granted (manual gate, ☐ until phone evidence).

### WS2 — "Listen" hands-free voice mode (P0) — the core ask
1. **Add wake phrases** `"listen"` and `"listen to me"` (keep `"uno one"`) at `VoiceService.kt:155` →
   `listOf("uno one", "listen", "listen to me")`. **Verify tokenization** of "listen" via the KWS
   `tokens.txt` in `speech/shared/vad` — add a unit/device test that `KeywordSpotterEngine.initialize`
   succeeds with the new list; if "listen to me" fails to tokenize, keep the single word `"listen"`.
2. **Speak on wake**: when the wake word fires, call `voiceModule.speak("Yes, I'm listening")` (and
   the first time per session, a short capability hint: "I can read the screen, scan what's in front
   of you, open the secure browser, or take a note. Tell me what to do."). Wire the dead
   `onWakeWordDetected` hook or do it inside the KWS loop before capturing the command.
3. **Step narration ("say what it is doing")**: for `InputType.VOICE` (and an "Announce mode" toggle
   for text input too), call `voiceModule.speak(...)` at key `addStep` milestones in
   `AgentOrchestrator` — "Checking safety", "Creating a note", "Opening Chrome", "Done". Throttle
   (e.g. min 1.2 s between speaks) and keep safety-block/deny messages spoken ("That looks like a
   payment — I won't do that"). Reuse `VoiceModule.speak`; do not double-speak the final result.
4. **Handle `ACTION_VOICE_COMMAND`/`EXTRA_COMMAND`** in `onStartCommand` (`VoiceService.kt:99-110`)
   so the main-page Listen button and floating bubble can inject a command through the same
   orchestrator path (`processCommand(..., VOICE)`) instead of each reimplementing record/transcribe.
5. **(P1) Voice-driven language switch**: spoken "speak in Hindi" / "switch to Tamil" →
   `setVoiceLanguage` so a blind user need not open Settings. Wake word stays English.
6. **Tests**: JVM — wake-phrase list contains "listen"; orchestrator emits a spoken step for VOICE
   input. Device — KWS initializes with the new phrase (☐ live mic wake-accuracy).

### WS3 — Spoken "what's in front" (Blind Aid narration + read-screen) (P0/P1)
1. **Blind Aid continuous scene narration**: in `BlindAidManager.processDetections`
   (`BlindAidManager.kt:140-202`), add a periodic spoken summary of detected labels ("In front of you:
   a chair, a desk, a person") in addition to the existing close-obstacle warnings. Throttle to a
   sensible cadence (e.g. every ~6 s or on significant label-set change) and respect a quiet mode.
   Extract the throttle/summary logic into a pure, JVM-testable function.
2. **Voice-invocable, spoken `describe_scene`/read-screen**: ensure a spoken "describe what's on the
   screen" / "read the screen" routes to `describe_scene`/`ocr_screen` and that for `InputType.VOICE`
   the resulting scene text is **spoken** (it currently only writes the timeline). Add a main-page
   "Read Screen" affordance (see WS5) that requests MediaProjection via `ScreenshotPermissionActivity`
   and speaks the result.
3. **Tests**: JVM — BlindAid summary throttle logic. Device — describe_scene result spoken for VOICE
   input (☐ live screen).

### WS4 — Voice-driven PageAgent + narration (P0/P1 — the blind user's web interface)
PageAgent is the built browser-assist: it **checks/reads pages, fills forms, and submits**. For a
blind user this is their primary window onto the web — so it must **read the page aloud**, narrate
every action, and be drivable + confirmable by voice. (The audited path already exists
(`SecureWebViewController` + `PageAgentGemmaPlanner` + `BrowserSafetyPolicy`); WS4 makes it eyes-free.)
1. **Voice input on `SecureBrowserScreen`**: add a mic button that records → transcribes (via the
   shared `VoiceModule`) → `viewModel.executeTask(transcript)`; and a spoken URL/"open unigurus" →
   `navigate`. Reuse WS2's narration hook.
2. **Read the page aloud**: add a spoken page-read/summary capability — after load and on request
   ("read this page" / "what does this page say"), speak the page title + main text (the PageAgent
   action set already has `extract_text`/`done`, `SecureBrowserViewModel.kt:321-327`; surface the
   extracted text through TTS). This is the "check browsers" half made eyes-free.
3. **Narrate PageAgent actions**: in `SecureBrowserViewModel`, speak `activitySummary` changes and the
   per-action AUTHORIZE_ACTION/TASK_RESULT outcomes ("I'm filling the name field", "Clicking
   Continue", "Done — I booked the appointment"). Wire a TTS speak into the browser path (currently
   none). This is the "fill forms" half narrated.
4. **Voice-answerable human-in-the-loop prompts**: when a CONFIRM/ASK/TAKEOVER prompt opens
   (`SecureBrowserScreen.kt:177-232`), **speak** it aloud and accept a spoken yes/no/answer (record →
   transcribe → `respondToPrompt`). This is the hands-free takeover — safety is preserved, the user
   just answers by voice instead of tapping a dialog.
5. **Connect the agent to the Secure Browser**: add a canonical tool `secure_browser_task` (recommended
   over rerouting `open_url`, to avoid breaking open_url semantics) that navigates the Secure WebView
   to an approved origin and runs a task — so "open unigurus and fill the form" uses the **audited**
   PageAgent path, not system Chrome. Gate on `APPROVED_ORIGINS`; reject non-approved origins with a
   spoken explanation.
6. **Approved-origin picker**: show the approved origins as speakable chips/list on the browser screen
   so a blind user hears them instead of typing a URL blindly.
7. **Tests**: headless — `secure_browser_task` is canonical + permission-gated + origin-checked;
   `BrowserSafetyPolicy` unchanged (already tested). Device — voice → executeTask + spoken page read
   (☐ live browser).

### WS5 — Main-page capability surface (P0)
Redesign the top of `AgentScreen` into a clear, large-button capability surface (eyes-friendly, large
touch targets, full TalkBack labels):

- **Listen** — starts hands-free voice mode (WS2). Primary, biggest target.
- **Blind Aid** — direct camera toggle (WS1), with CAMERA request if missing.
- **Read Screen** — MediaProjection → OCR/describe_scene → spoken result (WS3).
- **Secure Browser** — navigates to `SecureBrowserScreen` (one tap, not two).
- Keep the mic FAB, text input, and the timeline below; keep the 4 existing quick actions or fold
  them into the new surface.
- Decision (open): add Secure Browser as a 6th bottom-nav tab, or keep it as a main-page quick action?
  **Recommend**: main-page quick action (avoids nav clutter; bottom nav stays the 5 core tabs).
- Tests: headless — each new button routes to the correct handler. Device — each works live (☐).

### WS6 — TalkBack / eyes-free UI polish (P1, parallel)
- Add Compose `semantics` **live regions** so state changes announce: "Listening", "Processing",
  "Blind Aid active", "Done" (`AgentScreen.kt`, `FloatingAgentService.kt`).
- `contentDescription` on all floating-overlay icons (currently `null` at `FloatingAgentService.kt:265,
  298, 302, 363, 379`).
- Proper `label`s on all text fields (not placeholders only).
- Ensures the visual UI itself is navigable by TalkBack, complementing the voice mode.

### WS7 — Honesty, docs, tests (cross-cutting)
- No fake URLs/origins; the approved-origin list stays real.
- Update `DEVICE_VERIFICATION.md` + `README.md` as each WS lands; mark device items ☐ until phone
  evidence; flip to ✅ only with on-device proof.
- Build/lint/test gate (`:app:lintDebug :app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest`) green before any commit; full instrumented suite on the Xiaomi 14
  (currently OK 42 tests) must stay green.
- Do **not** merge to main without owner sign-off.

---

## Recommended sequencing for tomorrow

1. **WS1** (Blind Aid gate + direct toggle + system-perm grant flow) — smallest, unblocks the demo.
2. **WS2** (listen wake + step narration) — the core "listen and speak" ask; also the narration hook WS4 reuses.
3. **WS5** (main-page surface) — makes Listen / Blind Aid / Read Screen / Secure Browser discoverable in one tap.
4. **WS4** (voice-driven PageAgent — read page aloud + fill forms + spoken confirm) — the blind user's web interface; start once WS2's narration hook lands. This is core, not optional — it's how a blind user "checks browsers and fills forms".
5. **WS3** (Blind Aid scene narration + spoken read-screen) — "what's in front".
6. **WS6** (TalkBack polish) — parallel / last.

Each WS is independently shippable and testable; stop and verify on the device after each.

---

## Open decisions (confirm in <1 min tomorrow, defaults shown)

1. Wake phrases: **keep `"uno one"` + add `"listen"` (and `"listen to me"` if it tokenizes)**. (Default yes.)
2. Agent→browser: **new `secure_browser_task` tool** (recommended) vs rerouting `open_url` for approved origins.
3. Secure Browser placement: **main-page quick action** (recommended) vs 6th bottom-nav tab.
4. Step narration scope: **VOICE input always narrates** + an "Announce mode" toggle for text input. (Default.)

---

## Non-goals (explicitly out of scope)

- No new KWS model training (adding "listen" is a code edit if it tokenizes).
- No weakening of `BrowserSafetyPolicy` or the per-action confirm/takeover gates.
- No always-on camera foreground service in this pass (Blind Aid stays Activity-bound).
- No live-mic STT accuracy benchmarking (stays a manual device gate).
- No `vad` manifest dedup (needs a real silero VAD URL — don't invent).