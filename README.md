# UnoOne V2

**Offline-first Android AI agent with local Gemma 4 planning, offline speech, accessibility-based phone control, Blind Aid, downloadable language packs, and a safety-gated Alibaba PageAgent Secure Browser.**

> **Current status:** UnoOne V2 was merged into `main` through PR #1 on July 13, 2026. Automated Android and Distribution CI are green. It is **not production-ready** until the physical-device, speech, security, signed-release and production-distribution gates in this README are complete. The previous `main` state remains preserved on `archive/main-pre-unoone-v2-2026-07`.

## Non-negotiable engineering policy

UnoOne V2 must not ship with:

- fake or placeholder model URLs;
- invented checksums, file sizes, licences, performance or accuracy claims;
- dummy language packs shown as downloadable;
- silent cloud fallback for speech or model inference;
- unrestricted JavaScript execution from PageAgent;
- autonomous password, OTP, CAPTCHA, payment or legal-declaration handling;
- a second Gemma engine loaded beside the main engine;
- stale Gemma 3n or `gemma-local` runtime paths;
- a release marked `production-approved` without committed device evidence;
- a catalogue accepted without signature, integrity, freshness and rollback checks.

Development-only values must be visibly labelled and must fail closed in production.

---

## What UnoOne V2 contains

### Android agent

- Kotlin + Jetpack Compose application, Android API 28+.
- 15 Gradle modules.
- Local Gemma 4 E2B planning through LiteRT-LM.
- Rule-based command parsing before LLM inference.
- Canonical tool registry and per-action safety validation.
- Android intents, AccessibilityService automation and phone controls.
- Room-based notes, memory, skills, logs and browser audit records.
- Offline Sherpa-ONNX STT/TTS with explicit model health checks.
- CameraX/ML Kit Blind Aid preserved independently of the Gemma model folder.
- Camera access via the `open_camera` tool (system camera capture intent, CONFIRM + CAMERA permission) and CameraX Blind Aid (`detect_objects`, STRONG_CONFIRM + CAMERA + Accessibility); `deactivate_blind_aid` stops it. The CAMERA runtime permission is requested on first use.
- On-device OCR via bundled ML Kit Latin text recognition (no model download, no network); used by the `ocr_screen` tool and the always-available OCR fallback of `describe_scene` (the multimodal-vision path is wired but inactive until a vision-capable Gemma artifact is provided).
- Floating assistant and background voice service.
- In-app **Security Level** chooser (Standard / Relaxed / Off) and **Voice Language** chooser (English, Hindi, Bengali, Tamil, Telugu, Kannada, Malayalam) — see below.

### Secure Browser

- Alibaba PageAgent headless core embedded in an origin-restricted Android WebView.
- PageAgent model calls are redirected to local Gemma; there is no cloud LLM endpoint.
- Exact HTTPS origin allow-list.
- AndroidX WebKit origin-scoped message bridge; no unrestricted `addJavascriptInterface` object.
- Native authorization before each DOM click, input, dropdown, upload or submission.
- JavaScript execution tool disabled.
- Payments blocked.
- Passwords, OTPs, CAPTCHA and legal acceptance require manual takeover.
- File transfer and final submission require explicit confirmation.
- Browser audit logs store origin, action class and decision, not typed form values.

### Offline language packs

- Typed language-pack manifest and dependency graph.
- Shared ASR artifacts are installed once and retained while referenced. The wake-word (keyword-spotter) model lives in the shared `speech/shared/vad` folder and is optional — it is not required for any language pack, so it is not downloaded by default (the "red" indicator on the Model Status screen means "optional wake-word model not installed", not a pack failure).
- Pack activation is blocked until every required model passes health checks.
- English is the required base language.
- Hindi, Bengali, Tamil, Telugu, Kannada and Malayalam are current baseline packs.
- Assamese is the priority planned language and remains disabled until both STT and TTS are qualified.
- Marathi, Gujarati, Punjabi, Odia and Urdu remain planned until exact artifacts are verified.

### Distribution system

- Installable PWA for verified APK distribution.
- Read-only Cloudflare Worker API backed by R2/object storage.
- Signed stable and beta catalogues.
- Ed25519 catalogue verification in the browser.
- Catalogue freshness, future-time, installer-version, rollback and equivocation protection.
- Local APK SHA-256 verification; selected APK bytes are not uploaded.
- No inference, prompt, voice, screenshot or form-data endpoint in the distribution API.

---

## Architecture

```text
User voice / text / overlay / accessibility input
                     │
                     ▼
             AgentOrchestrator
                     │
          Rule parser ──► local Gemma 4
                     │
                     ▼
          CanonicalToolRegistry   (unknown tools rejected, required args validated)
                     │
                     ▼
        SafetyGuard + permission policy   ◄── SecurityLevel (Standard / Relaxed / Off)
                     │                          sets judge on/off, block on/off, confirm on/off
                     ▼
   [Standard only] Gemma safety judge (2nd pass, escalate-only)
                     │
                     ▼
        block gate ── confirm gate ──►   phone tools / notes / memory / skills / Blind Aid

Secure Browser WebView
        │
        ▼
Alibaba PageAgent DOM controller
        │
        ├── local Gemma planning through secured native bridge (exclusive model lease)
        ├── native action authorization before DOM execution
        └── Room audit trail without form values
```

### Android modules

| Module | Responsibility |
|---|---|
| `:app` | Compose UI, orchestration, services, navigation and ViewModels |
| `:core` | Shared types, canonical tools, model registry and exclusive brain lease state |
| `:storage` | Room database, notes, memory, skills, model metadata and audit logs |
| `:modelmanager` | Manifest loading, downloads, integrity checks, health and uninstall |
| `:languagepacks` | Language catalogue, dependency-aware install and pack health |
| `:localbrain` | Gemma planner, PageAgent planner, prompts and inference lifecycle |
| `:voice` | Offline STT/TTS, wake-word (keyword spotter), recording and background voice service |
| `:agentrouter` | Tool and plugin routing |
| `:safetyguard` | Risk classification and approval policy |
| `:phonecontrol` | Intents, OCR, object detection and Blind Aid |
| `:memory` | Local preference and outcome memory |
| `:skills` | Skill storage and execution |
| `:observability` | Diagnostics and health reporting |
| `:accessibilitycontrol` | Android UI reading, gestures and input |
| `:securebrowser` | Domain policy, WebView bridge, PageAgent protocol and browser safety |

---

## Gemma 4 E2B contract

UnoOne V2 has **one planning brain only**: Gemma 4 E2B.

| Field | Current verified metadata |
|---|---|
| Manifest id | `gemma-4-e2b` |
| File | `gemma-4-E2B-it.litertlm` |
| Runtime | LiteRT-LM |
| File size | `2,588,147,712` bytes |
| SHA-256 | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |
| Context limit | 32,768 tokens |
| Minimum product RAM gate | 6 GB |
| Recommended product RAM gate | 8 GB |
| Licence | Apache-2.0 upstream artifact |
| Device qualification | **Primary device qualified 2026-07-14** (Xiaomi 14, Android 15 / API 35): bytes authenticated, loads on CPU backend (GPU delegate fails on SM8650 → safe CPU fallback), 18/18 canonical tool-match. Secondary device still pending. Full 50-task planning/PageAgent benchmark + thermal run still pending. |

The development manifest can acquire the exact upstream bytes for engineering tests. A public release must mirror the same verified bytes to UnoOne-controlled storage and publish them through a signed production catalogue.

### Exclusive model ownership

The phone agent and Secure Browser must never load separate Gemma copies simultaneously. `SecureBrowserModelLease`:

1. records whether the main phone brain was loaded;
2. reserves the process-wide Gemma owner state;
3. unloads the phone conversation;
4. loads the same artifact in the PageAgent planner;
5. blocks phone self-heal from allocating a second engine;
6. closes the browser planner at session end;
7. restores the phone brain when safe.

Memory-pressure release does not immediately reload the model.

---

## Language plan

| Language | Current state | Required next action |
|---|---|---|
| English | required baseline | physical-device STT/TTS verification |
| Hindi | baseline | accuracy, noise and latency benchmark |
| Bengali | baseline | accuracy, noise and latency benchmark |
| Tamil | baseline | accuracy, noise and latency benchmark |
| Telugu | baseline | accuracy, noise and latency benchmark |
| Kannada | baseline | accuracy, noise and latency benchmark |
| Malayalam | baseline | accuracy, noise and latency benchmark |
| Assamese | planned, priority | qualify exact Assamese STT + TTS artifacts and benchmark |
| Marathi | planned | select and qualify STT/TTS artifacts |
| Gujarati | planned | select and qualify STT/TTS artifacts |
| Punjabi | planned | select and qualify STT/TTS artifacts |
| Odia | planned | select and qualify STT/TTS artifacts |
| Urdu | planned | select and qualify STT/TTS artifacts |

Whisper having an Assamese language token is **not** treated as proof of acceptable Assamese recognition. AI4Bharat models are evaluated as direct mobile candidates, export candidates or teacher models based on licence, format, size and measured phone performance.

See [Speech Model Qualification](docs/SPEECH_MODEL_QUALIFICATION.md).

---

## Secure Browser safety policy

| Action | Policy |
|---|---|
| Read, wait, scroll | allowed |
| Ordinary form input | allowed after native classification |
| Unknown or sensitive action | explicit confirmation |
| File upload/download | explicit confirmation and Android picker takeover |
| Final form submission | explicit confirmation |
| Login credentials/passwords | manual takeover |
| OTP/verification code | manual takeover |
| CAPTCHA | manual takeover |
| Legal declaration/terms acceptance | manual takeover |
| Payment, banking, card, UPI PIN | blocked |
| Arbitrary JavaScript execution | unavailable |

Only approved exact HTTPS origins may use the PageAgent bridge. Subdomains are not implicitly trusted.

See [SAFETY](docs/SAFETY.md) and [Architecture](docs/ARCHITECTURE.md).

---

## User-selectable security level and voice language

Both are exposed in **Settings** (no rebuild needed; the change takes effect on the next command).

### Security Level

| Level | Judge | BLOCK tier | Confirm tap | Use |
|---|---|---|---|---|
| Standard (default) | on | enforced | required | real use / production posture |
| Relaxed | off | enforced | auto-approved | everyday testing — benign commands like "add a calendar event" are no longer over-blocked by the judge, but payments / credentials / install stay blocked |
| Off (demo) | off | bypassed | auto-approved | demo / developer — every module can be exercised |

Off is safe only because the BLOCK-tier tool names (`make_payment`, `send_message`, `access_passwords`, `install_app`, `silent_control`) have **no `ActionExecutor` handlers** — they fall through to the plugin router (a no-op error) — so unblocking them triggers no real payment / SMS / credential / install action. Standard is the default and the production posture; the app never silently weakens safety on first launch.

### Voice Language

The offline STT/TTS language is chosen in **Settings → Voice language**. English uses the streaming zipformer transducer (`speech/shared/sherpa-asr-en`); every Indic language uses the multilingual Whisper model (`speech/shared/sherpa-asr-whisper`) pinned to that language code. **Speak in the language you have selected** — the English-only transducer cannot transcribe Hindi, so Hindi speech with English selected will not transcribe correctly. There is no automatic language detection today. Changing the language rebuilds the Sherpa engines live (no restart).

---

## Repository layout

```text
.
├── android-app/UnoOneAgent/          Android application and 15 modules
├── web-runtime/page-agent-unoone/    Headless Alibaba PageAgent runtime
├── installer-pwa/                    Signed-catalogue APK installer PWA
├── distribution/api/                 Read-only Cloudflare Worker API
├── distribution/catalog/             Catalogue schemas and examples
├── scripts/models/                   Artifact measurement/qualification scripts
├── scripts/catalog/                  Catalogue signing and verification scripts
├── scripts/ci/                       Repository invariant checks
└── docs/                              Architecture, privacy, security and handoff guides
```

---

## Build and test

### 1. PageAgent runtime

```bash
cd web-runtime/page-agent-unoone
npm install --no-audit --no-fund
npm run typecheck
npm test
npm run build
npx playwright install chromium
npm run test:e2e
npm run bundle:android
```

`bundle:android` copies the generated `unoone-page-agent.js` into the Android `:securebrowser` assets. Android builds must fail if this asset is missing.

### 2. Android

```bash
cd android-app/UnoOneAgent
chmod +x gradlew
./gradlew :app:lintDebug --stacktrace
./gradlew testDebugUnitTest --stacktrace
./gradlew assembleDebug --stacktrace
```

### 3. Distribution API

```bash
cd distribution/api
npm install --no-audit --no-fund
npm run typecheck
npm test
npx wrangler deploy --dry-run --outdir dist
```

### 4. Installer PWA

```bash
cd installer-pwa
npm install --no-audit --no-fund
npm run typecheck
npm test
npm run build
```

### 5. Repository invariants

```bash
python scripts/ci/check_repo_invariants.py
```

The invariant checker must reject legacy Gemma 3n identifiers, `gemma-local`, incorrect Gemma metadata and other prohibited runtime regressions.

---

## Model acquisition and self-hosting

Do not commit multi-gigabyte weights to Git.

1. Acquire the exact approved upstream artifact and immutable revision.
2. Confirm the model licence and redistribution conditions.
3. Generate exact size and SHA-256 using:

```bash
python scripts/models/qualify_artifact.py \
  /absolute/path/to/gemma-4-E2B-it.litertlm \
  --id gemma-4-e2b \
  --version <immutable-version> \
  --runtime litertlm \
  --license Apache-2.0 \
  --provider Google \
  --repository litert-community/gemma-4-E2B-it-litert-lm \
  --revision <immutable-revision> \
  --upstream-file gemma-4-E2B-it.litertlm \
  --minimum-ram-mb 6144 \
  --recommended-ram-mb 8192 \
  --output artifacts/qualification/gemma-4-e2b.json
```

4. Run LiteRT-LM load tests on target devices.
5. Run canonical phone-tool and PageAgent tests.
6. Record RAM, latency, thermal and battery evidence.
7. Mirror the unchanged bytes to UnoOne-controlled object storage.
8. Publish licence, notice, size, SHA-256 and qualification state.
9. Sign the stable/beta catalogue with an offline Ed25519 release key.
10. Verify the installer rejects tampering and rollback before release.

See [Model Acquisition and Distribution](docs/MODEL_ACQUISITION_AND_DISTRIBUTION.md).

---

# Mandatory implementation and release plan

The following checklist is the source of truth. A phase is complete only when its evidence is committed and CI/device gates are green.

## Phase A — Repository and architecture cleanup

- [x] Preserve the previous main branch as an archive branch.
- [x] Base V2 on the existing Gemma 4 E2B work.
- [x] Remove Gemma 3n from the runtime contract.
- [x] Remove `gemma-local` filesystem fallbacks.
- [x] Normalize model folders under `brain/`, `speech/`, `vision/` and `staging/`. (OCR is bundled ML Kit Latin in the APK — it is not a downloaded model and has no model folder; the unused `ModelType.ocr` enum value is retained only for catalogue forward-compat.)
- [x] Keep Blind Aid independent of the LLM folder.
- [x] Add invariant checks preventing legacy model paths from returning.
- [ ] Confirm the current branch contains no dead, unreachable or duplicate V1 implementation after full static review.

**Acceptance gate:** no legacy brain identifier in active Android source; invariant script and all compilation gates pass.

## Phase B — Gemma 4 E2B integration

- [x] Make Gemma 4 E2B the sole typed brain profile.
- [x] Use exact generic Android artifact filename, size and SHA-256.
- [x] Correct the context limit to 32K.
- [x] Preserve GPU-to-CPU backend fallback.
- [x] Add exclusive phone/PageAgent model ownership.
- [x] Add timeout and error handling for browser planning.
- [x] Download the exact artifact on an engineering device. (Xiaomi 14, 2026-07-14; bytes authenticated sha256 `181938105e…`.)
- [x] Prove LiteRT-LM load on Xiaomi 14. (Loads on CPU backend; GPU delegate fails on SM8650 → safe CPU fallback by design.)
- [x] Prove CPU fallback on a supported non-GPU path. (CPU backend is the running path; cold load ~7.5s, warm ~0.9s.)
- [ ] Measure cold load, first token, tokens/second, peak RAM, temperature and battery (full benchmark).
- [ ] Run 50 sequential phone-planning tasks and 50 PageAgent planning tasks without memory duplication.
- [x] Record device evidence and update qualification status. (Phase 5 evidence: `artifacts/validation/xiaomi14/20260714-174410-PHASE3-DEVICE/PHASE5-GEMMA-ON-DEVICE.md`; 18/18 tool-match.)

**Acceptance gate:** device-qualified artifact record; no OOM, corrupt output or duplicate engine allocation.

## Phase C — Offline speech and language packs

- [x] Add typed language-pack catalogue.
- [x] Add dependency-aware install/uninstall.
- [x] Protect shared models from premature removal.
- [x] Block activation until model health checks pass.
- [x] Replace direct language selection with the Offline Languages screen.
- [x] Mark unqualified languages non-downloadable.
- [ ] Validate every current baseline checksum and extraction path on a clean device.
- [ ] Run STT/TTS tests for English, Hindi, Bengali, Tamil, Telugu, Kannada and Malayalam.
- [ ] Select exact Assamese STT artifact.
- [ ] Select exact Assamese TTS artifact.
- [ ] Verify licence and redistribution for Assamese artifacts.
- [ ] Benchmark Assamese clean, noisy, accented, code-mixed, names, dates and numbers.
- [ ] Repeat qualification for Marathi, Gujarati, Punjabi, Odia and Urdu.
- [ ] Replace any baseline model that fails accuracy, latency or thermal gates.

**Acceptance gate:** each downloadable pack has exact artifacts, hashes, licence, Android load evidence and language benchmark results.

## Phase D — Alibaba PageAgent Secure Browser

- [x] Use Alibaba headless core and DOM controller packages.
- [x] Route model calls to local Gemma instead of a remote API.
- [x] Disable generated JavaScript execution.
- [x] Add exact-origin HTTPS policy.
- [x] Add session id, nonce, origin and payload validation.
- [x] Add native pre-action authorization.
- [x] Block payment automation.
- [x] Require takeover for credentials, OTP, CAPTCHA and legal acceptance.
- [x] Require confirmation for file transfer and final submission.
- [x] Add PageAgent form-fill and payment-block Playwright tests.
- [x] Add privacy-bounded Room audit records.
- [ ] Test navigation, form fill, dropdown, checkbox, date, upload and final confirmation on controlled fixtures.
- [ ] Test login, OTP and CAPTCHA takeover on controlled fixtures.
- [ ] Test every approved production domain separately.
- [ ] Remove any domain from the allow-list that is not required for launch.
- [ ] Run WebView/PageAgent tests on Xiaomi 14 and a second Android device.

**Acceptance gate:** all controlled workflows pass; no sensitive value is logged; blocked actions cannot be bypassed.

## Phase E — Android functions that must not regress

- [ ] Voice command input and spoken response.
- [ ] Floating assistant lifecycle.
- [ ] Background VoiceService routing.
- [x] Notes CRUD and search — ✅ 2026-07-14 headless (NotesCrudHeadlessTest on Xiaomi 14).
- [x] Skills execution through the same safety path — ✅ 2026-07-14 headless (AgentSafetyPipelineHeadlessTest; skill CRUD live-UI remains manual).
- [x] Memory retrieval and correction — ✅ 2026-07-14 headless (MemoryStoreHeadlessTest).
- [x] Safety routing (unknown-tool rejection, missing-argument rejection, destructive confirmation, blocked payment/credential/OTP, user-selectable Security Level) — ✅ 2026-07-14 headless (SafetyGuardHeadlessTest + SecurityLevelHeadlessTest).
- [ ] Accessibility tap, type, swipe, long press and visible-text capture.
- [ ] Android intent launching and system actions.
- [ ] CameraX Blind Aid startup, object detection, haptic and spoken guidance.
- [x] OCR path — ✅ 2026-07-14 headless (OcrControlHeadlessTest on Xiaomi 14: bundled ML Kit Latin recognizes rendered text end-to-end; `recognizeScreen()` honors the MediaProjection gate headlessly). Live on-screen screenshot OCR remains a manual MediaProjection item.
- [ ] Permission denial and permanent-denial recovery.
- [ ] Memory-pressure unload and safe reload.
- [x] Process restart and app update preserve Room data + model repair — ✅ 2026-07-14 headless (Room survives DB reopen; language-pack uninstall/reinstall/repair proven on device).

The granular per-item ✅/☐ matrix with evidence paths is in [DEVICE_VERIFICATION.md](DEVICE_VERIFICATION.md). Headless-provable items are ✅; live-screen / live-mic / live-camera items remain ☐ until driven manually on the device.

**Acceptance gate:** committed device verification matrix with pass/fail evidence; no old function silently removed.

## Phase F — Distribution API and installer PWA

- [x] Add read-only Cloudflare Worker API.
- [x] Restrict catalogue channels to stable/beta.
- [x] Add artifact path policy and no write endpoint.
- [x] Add installable PWA.
- [x] Verify Ed25519 catalogue signatures.
- [x] Add catalogue freshness, future-time and installer-version checks.
- [x] Add rollback and same-version equivocation protection.
- [x] Add stable/beta channel selection.
- [x] Add local APK size and SHA-256 verification.
- [ ] Add a real production catalogue public key to the PWA build environment.
- [ ] Create production R2 buckets and least-privilege bindings.
- [ ] Upload a real signed APK and verify R2 metadata.
- [ ] Mirror production-approved model and language artifacts.
- [ ] Publish signed stable and beta catalogues.
- [ ] Test cache headers, range requests, CORS, content type and download interruption/resume.
- [ ] Deploy installer PWA on an UnoOne/InBharat-controlled HTTPS domain.
- [ ] Perform Android install/update tests from the downloaded APK.

**Acceptance gate:** production PWA unlocks only a valid signed catalogue and verifies the exact APK; tampered/stale/rollback catalogues remain locked.

## Phase G — Security, privacy and compliance

- [x] Document threat model and browser boundaries.
- [x] Document privacy architecture.
- [x] Record third-party components and licences.
- [ ] Complete Android exported-component and intent review.
- [ ] Complete WebView hardening review against current Android guidance.
- [ ] Confirm no sensitive logs in release builds.
- [ ] Test corrupt model, partial download, hash mismatch and malicious archive handling.
- [ ] Test malicious PageAgent page content and prompt injection.
- [ ] Complete dependency and licence scan.
- [ ] Generate release SBOM.
- [ ] Sign release APK with protected production signing key.
- [ ] Review privacy notice and consent text for every optional network feature.

**Acceptance gate:** security review has no unresolved high-severity issue; release artefacts and notices are complete.

## Phase H — CI and release cutover

- [x] Android lint, unit-test and APK workflow exists.
- [x] PageAgent typecheck, unit, bundle and Playwright workflow exists.
- [x] Distribution API and installer workflow exists.
- [x] Diagnostic artifacts are uploaded when a gate fails.
- [x] Automated Android and Distribution CI passed on the merged V2 head.
- [x] Final automated evidence and known limitations are recorded in the repository.
- [ ] Complete Xiaomi 14 and secondary-device gates. (Xiaomi 14: Phases 5/6/7 done 2026-07-14 — Gemma load, language packs, 28 headless instrumented tests green; live-UI / live-mic / live-camera / 30-min thermal / secondary device remain.)
- [ ] Freeze model/catalogue versions for release candidate.
- [ ] Build, sign and verify the release candidate.
- [x] Merge PR #1 into `main` after automated repository gates passed; physical-device and production-release gates remain blocked.
- [x] Keep the archived pre-V2 branch; do not destroy recoverable history.
- [ ] Tag the release and publish checksums, licences, known limitations and rollback instructions.

**Acceptance gate:** green CI, green device matrix, signed release, verified installer and documented rollback.

---

## Known limitations today

- Gemma 4 E2B is device-qualified on the primary Xiaomi 14 (loads on CPU, 18/18 tool-match) but not yet on a secondary device; the full 50-task planning/PageAgent benchmark and 30-minute thermal run are not yet recorded.
- Assamese and several other Indian languages are planned, not downloadable.
- Baseline speech model presence does not imply production accuracy.
- Secure Browser is restricted to approved domains and intentionally cannot automate payments, credentials, OTPs, CAPTCHA or legal acceptance.
- Optional web search or Android system speech fallbacks may use network-dependent services only when explicitly enabled; the core offline path must not silently invoke them.
- The installer PWA must remain download-locked until a real production public key and signed catalogue are configured.

---

## Documentation

- [Implementation status](STATUS.md)
- [Device verification matrix](DEVICE_VERIFICATION.md)
- [Architecture](docs/ARCHITECTURE.md) and [detailed module walkthrough](docs/local-architecture.md)
- [Agent flow](docs/agent-flow.md)
- [Safety](docs/SAFETY.md) and [permissions](docs/permissions.md)
- [Model acquisition and distribution](docs/MODEL_ACQUISITION_AND_DISTRIBUTION.md) and [models](docs/MODELS.md)
- [Speech model qualification](docs/SPEECH_MODEL_QUALIFICATION.md) and [voice module](docs/voice-module-implementation.md)
- [Privacy policy](docs/play-review/privacy-policy.md) and [data safety](docs/play-review/data-safety.md)
- [Tool schema registry](docs/tool-schema-registry.md)

---

## Ownership and licensing

UnoOne is developed under Uni Guru Technologies LLP / InBharat.ai. Repository code and third-party model/runtime artefacts may have different licences. Always follow the licence and notice attached to each component and model artifact. Do not redistribute an artifact merely because it can be downloaded.
