# Phase 7 — Headless function verification on Xiaomi 14 (DEVICE evidence, 25 tests, 0 failures)

Date: 2026-07-14 (run completed 20:12 IST)
Device: Xiaomi 14 (`23127PN0CG` / houji), Android 15 / API 35, SM8650, 12 GB RAM
App: `com.unoone.agent` v0.4.0-alpha-v2 (versionCode 4)
App APK (P0 Sherpa-fix build): `app/build/outputs/apk/debug/app-debug.apk`
  SHA-256 `9230bcdbbbf5fb1ec729b854351d812c934546b09a77c43a3b70123619a0d9fd`
  (distinct from the Phase-2 pre-fix baseline `4e72d981…` — the four-file `assetManager=null`
   Sherpa-ONNX fix from Phase 6 changes the APK; identical 372 MB size, no regression.)
Test APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
  SHA-256 `e492e02d3001834457cee6c306cdc45b7c32a70f06eff7b7e15e0651a7540277`
Runner: `com.unoone.agent.test/androidx.test.runner.AndroidJUnitRunner`
Method: NO image input / NO screenshots (model has no vision). All evidence is `am instrument`
stdout, on-disk `ls`, sha256 — text-verifiable.

## What this phase proves

DEVICE_VERIFICATION.md distinguishes "compilation, lint, JVM tests and Playwright tests" from
physical-device evidence. The functions below were exercised **headlessly on the device's ART with
real Room/SQLite and the real production classes** (not Robolectric, not JVM) — the parts of the
A→Z matrix that can be proven without a live screen/mic/camera. Pure-logic classes that also have
JVM unit tests are re-proven here on-device because the repo rule forbids marking a feature verified
from JVM tests alone.

## Results

```
com.unoone.agent.safetyguard.SafetyGuardHeadlessTest   OK (10 tests)   0.036s
com.unoone.agent.storage.NotesCrudHeadlessTest          OK (2 tests)    [in batch]
com.unoone.agent.memory.MemoryStoreHeadlessTest        OK (3 tests)    [in batch]
com.unoone.agent.securebrowser.SecureBrowserPolicyHeadlessTest OK (3 tests) [in batch]
com.unoone.agent.languagepacks.SpeechNoCloudFallbackTest OK (3 tests)  [in batch]
  ─ batch of 4 classes above: OK (11 tests) 0.337s
com.unoone.agent.safety.AgentSafetyPipelineHeadlessTest OK (3 tests)   0.226s
com.unoone.agent.languagepacks.LanguagePackRepairRetainTest OK (1 test) 9.943s

TOTAL: 7 classes, 25 tests, 0 failures.
```

## 1. SafetyGuard + CanonicalToolRegistry + SafetyPipeline (10 tests)

Pure logic on device ART. `SafetyGuard()` no-arg; `CanonicalToolRegistry` object; `SafetyPipeline(ctx, guard)`.

- Tool-name risk tiers: `create_note`/`open_chrome`/`check_calendar` → DIRECT;
  `open_url`/`read_screen`/`share_text` → CONFIRM; `delete_notes`/`delete_all_notes`/`describe_scene`/
  `system_control` → STRONG_CONFIRM; `make_payment`/`send_message`/`access_passwords`/`install_app`/
  `silent_control` → BLOCK; unknown `totally_bogus_xyz` → STRONG_CONFIRM (never auto-allowed).
- Input escalation (`classifyFromInput`): "pay my credit card bill" / "saved passwords" /
  "enter the otp 123456" / "check my bank account balance" → BLOCK; "delete all my notes now" →
  STRONG_CONFIRM; "open chrome" → DIRECT.
- Registry: `isKnown("create_note")`=true, `isKnown("delete_all_notes")`=true;
  `make_payment`/`send_message`/`access_passwords`/`install_app`/`bogus_xyz` → NOT canonical
  (the brain rejects these tool names before SafetyGuard — a dual-layer block); `schemaFor("bogus")`=null.
- Missing-argument detection against the schema: `create_note` required = [title, content];
  empty args → [title, content] missing; {title} → [content] missing; {title, content} → [];
  {title, content, tags} → [] (extra optional ignored); `delete_all_notes` has no required params.
- `SafetyPipeline`: `isBlocked(BLOCK)`=true (only BLOCK); `requiresConfirmation(CONFIRM|STRONG_CONFIRM)`=
  true, (BLOCK|DIRECT)=false; `classifyRisk("open_url","open the bank payment page")`=BLOCK
  (input payment mention escalates an ordinary tool).

## 2. Notes CRUD + search + persistence (2 tests)

`Room.inMemoryDatabaseBuilder(ctx, UnoOneDatabase).allowMainThreadQueries().build().noteDao()`.
- create (2 notes) → read (getById) → search across title/content/tag (searchOnce, LIKE) → negative
  search empty → update (content changed, title intact) → delete(entity) → deleteAll → getAll empty.
- Persistence: file-backed DB `unoone_headless_notes_test` → insert → close → rebuild same name →
  getById survives → `context.deleteDatabase()` cleanup. Proves Room data survives DB reopen on device
  (DEVICE_VERIFICATION §8 "app update preserves Room data").

## 3. Agent memory store + persistence (3 tests)

`MemoryModule(db.memoryDao())`. `getRelevantContext` semantics verified against source (matches query
words against preference key OR value, correction value, pattern key).
- `storePreference` upsert by key: write "Alice" → read "Alice"; re-write "Bob" → read "Bob", exactly
  one preference row (no duplicate).
- `storeCorrection`/`storePattern`/`storeOutcome` + `getRelevantContext("name")` surfaces preference
  "user_name: Bob" (word "name" ⊂ key "user_name"); `getRelevantContext("bengaluru")` surfaces
  "Bengaluru" (value match); `getRelevantContext("chrome")` surfaces correction "open chrome"; a
  no-match query does NOT surface the unrelated preference (no false positive).
- Persistence: file-backed DB `unoone_headless_memory_test` → storePreference("fact","sky is blue") →
  close → rebuild → getPreference = "sky is blue" → deleteDatabase cleanup.

## 4. Secure Browser policy + PageAgent asset on device (3 tests)

`SecureWebViewController` itself hard-requires a live `WebView` (constructor calls
`configureWebView()` + `WebViewCompat.addWebMessageListener` in `init`) and
`readRuntimeBundle()`/`validateRequest()` are private + WebView-bound, so the full live-WebView
gates are manual/visual (see "still manual" below). The headless-provable parts:
- `BrowserDomainPolicy.evaluate` (device ART): approved exact HTTPS origin allowed; subdomain,
  unapproved origin, cleartext HTTP, localhost, IPv4 `192.168.1.1`, IPv6 `[::1]`, `javascript:`
  fragment, userinfo credentials → all Block. `isAllowedOrigin` mirror verified.
- `BrowserSafetyPolicy.evaluate` (device ART): `make_payment` → Block; `enter_password`/`enter_otp`/
  `solve_captcha`/`accept_terms` → UserTakeover; `submit_form`/`upload_file` → Confirm;
  `input_text` → Allow; unknown action → Confirm (never silent allow).
- PageAgent runtime asset in the INSTALLED app: read `page-agent/unoone-page-agent.js` from the app
  AssetManager → non-blank, exact size **193488 B**, exact SHA-256
  **d434912a15ebaac5434cbaf847291d0cdec1cb8054594cd537b4245c02ade71e** (matches the Phase-2 laptop
  bundle byte-for-byte), contains the `UnoOnePageAgentRuntime` entry symbol. The runtime the WebView
  would inject is packaged and byte-authentic.

## 5. Speech no-cloud-fallback (3 tests)

Construct each Sherpa-ONNX engine against a non-existent model dir; `initialize()` must return
`Result.Error` citing "missing" — never `Success`, never a silent cloud/Android-SpeechRecognizer
fallback. Proves the speech path is offline-only. No files touched, no native runtime loaded
(the engines check file existence before any native construction).
- `SherpaSttEngine(TRANSDUCER)` → Error "missing" ✓
- `SherpaSttEngine(WHISPER, "hi")` → Error "missing" ✓
- `SherpaTtsEngine` → Error "missing" ✓

## 6. Agent safety pipeline end-to-end (3 tests)

`AgentOrchestrator(ctx, noteDao, actionLogDao, memoryDao, skillDao)` with in-memory Room — NO Hilt,
NO Gemma (the skill trigger + rule path do not load the brain; 0.226s confirms no model load). Drives
`processCommand` for a user-created skill and reads `timelineSteps`. Proves the FULL
permission→risk→block→confirm→execute→audit pipeline on device ART + real Room:
- Skill with a `delete all notes` step → timeline shows `Risk: STRONG_CONFIRM`; when the confirmation
  callback grants → `Skill Complete` AND the notes are actually deleted (recent count 0).
- When the confirmation callback denies → no `Skill Complete` AND the notes survive (recent count 1).
- Skill step `open google to check my bank balance` → `open_url` (CONFIRM at tool level) but input
  mentions "bank" → `classifyFromInput` BLOCK → timeline `Security Block`, no `Skill Complete`.
  (This is the §3 "blocked payment/credential/OTP request" path + "skills execution through the same
  safety path".)

## 7. Language-pack uninstall / shared-ASR retention / repair (1 test)

`LanguagePackManager(ctx)` on the real on-device pack store (prerequisite: Phase-6 installed packs).
- Uninstall `ml-IN-standard` → Success; `state(ml)` installed=false, missingModelIds contains the
  pack-specific `sherpa-tts-mal`, does NOT list the shared `sherpa-asr-whisper` (retained);
  `state("hi-IN-standard")` stays installed+healthy (shared ASR retained while another language
  depends on it — DEVICE_VERIFICATION §5).
- Reinstall `ml-IN-standard` → Success; `state(ml)` installed+healthy+verified (remove/reinstall/repair
  works; activation-gate-blocked-before-health via `state`).
- On-disk proof of the re-download (not a silent assumeTrue skip): `model.onnx` for ml is present,
  app-owned (`u0_a998`), 114,052,280 B, timestamp 20:12:
  `/storage/emulated/0/Android/data/com.unoone.agent/files/models/speech/languages/ml-IN/tts/model.onnx`

## DEVICE_VERIFICATION matrix — what is now ✅ vs still ☐ (manual)

Verified headlessly on this device (✅ with this evidence path):
- §3 create/read/search/delete note workflows (Notes DAO CRUD + search) ✅
- §3 unknown-tool rejection, missing-argument rejection, destructive-action confirmation,
  blocked payment/credential/OTP request (SafetyGuard + registry + pipeline) ✅
- §3 skills execution through the same safety path (AgentOrchestrator skill step) ✅
- §4 PageAgent runtime initializes from the packaged asset (asset-level: present + sha-verified) ✅
- §4 unapproved origin / subdomain / HTTP / localhost / IP-literal navigation blocked (policy) ✅
- §4 payment blocked / credential-OTP-CAPTCHA-legal takeover / final-submit+file confirm (policy) ✅
- §5 pack downloads/installs (Phase 6) + activation-blocked-before-health + shared-ASR-retained +
  remove/reinstall/repair + no system/cloud speech fallback ✅
- §8 Room data + agent memory survive DB reopen (persistence) ✅

Still manual / visual (cannot prove headlessly; require a human at the screen or a live mic/camera —
NO screenshots available to this runner). Left ☐ in the matrix until driven manually:
- §4 live WebView: approved-origin page load, text-field fill, dropdown/checkbox/radio/date, scroll,
  file-upload Android takeover, final submission confirmation, password/OTP/CAPTCHA/legal takeover,
  audit-log (origin/action/decision without form values), repeated open/close no WebView/engine leak.
- §5 STT recognition accuracy on real mic audio; TTS pronunciation intelligibility; airplane-mode.
- §6 Accessibility service enable/disable, visible text capture, tap/type/fill, scroll/swipe/long-press,
  back/home/recents, screen-off behavior, permission-denial recovery, confirmation-timeout cancel.
- §7 Blind Aid: CameraX preview, object detection, haptic, spoken guidance, low-light/denied states.
- §9 30-minute mixed workload performance/thermal run.
- Secondary device (Phase 11).

## Files added (validation branch, working tree — NOT committed unless requested)

- app/src/androidTest/java/com/unoone/agent/storage/NotesCrudHeadlessTest.kt
- app/src/androidTest/java/com/unoone/agent/memory/MemoryStoreHeadlessTest.kt
- app/src/androidTest/java/com/unoone/agent/securebrowser/SecureBrowserPolicyHeadlessTest.kt
- app/src/androidTest/java/com/unoone/agent/languagepacks/SpeechNoCloudFallbackTest.kt
- app/src/androidTest/java/com/unoone/agent/languagepacks/LanguagePackRepairRetainTest.kt
- app/src/androidTest/java/com/unoone/agent/safety/AgentSafetyPipelineHeadlessTest.kt
- app/src/androidTest/java/com/unoone/agent/safetyguard/SafetyGuardHeadlessTest.kt

Compile + assemble green (`:app:compileDebugAndroidTestKotlin`, `:app:assembleDebugAndroidTest`).
One initial defect found+fixed during this phase: the two persistence tests returned `Boolean`
(last expr `context.deleteDatabase()`) → JUnit "should be void"; fixed by appending `Unit`.