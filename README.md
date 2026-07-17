# UnoOne V2

UnoOne is an offline-first Android AI assistant for blind and sighted users. It combines on-device planning, hands-free speech, phone controls, Blind Aid, document tools, reusable Skills, and a Page Agent browser in one app.

> **Current status — July 17, 2026:** Android lint, JVM tests, debug builds, Page Agent browser tests, and the connected-device suite pass. The current prototype has been exercised on a Xiaomi 14 running Android 15. UnoOne remains an alpha: second-device qualification, controlled speech and vision accuracy benchmarks, signed-release testing, and production distribution are not complete.

## What works today

### Android assistant

- Native Kotlin and Jetpack Compose app for Android 9 and later (API 28+), organized into 15 Gradle modules.
- One local Gemma 4 E2B planning engine through LiteRT-LM. Deterministic rules handle common commands before model inference.
- A canonical 29-tool registry rejects unknown tools and validates required arguments.
- Direct commands, compound tasks, model-planned actions, and Skills use the same permission, risk, confirmation, execution, verification, and audit pipeline.
- Offline Sherpa-ONNX speech recognition and speech output, with explicit model-health checks. English uses the streaming transducer; enabled Indian languages share the official Omnilingual 300M CTC int8 recognizer and use one MMS VITS voice per language.
- Selectable English, Hindi, Bengali, Tamil, Telugu, Kannada, and Malayalam speech profiles. The selection controls STT routing, native-script chat instructions, deterministic tool-status replies, and TTS; it does not silently fall back to English.
- One-tap hands-free sessions that listen, run the command, speak the result, and re-arm. The foreground session and background wake service coordinate ownership of the microphone.
- Background activation uses a low-latency offline keyword spotter plus an independent bounded offline-STT fallback for one-breath commands. The fallback accepts native wake prefixes and core Blind Aid, screen-reading, camera, Calendar, WhatsApp, Chrome, and Secure Browser commands in Hindi, Bengali, Tamil, Telugu, Kannada, and Malayalam; its spoken wake cue follows the selected language.
- Phone actions for opening apps, Calendar, Chrome, WhatsApp, the dialer, URLs, and system screens.
- Calendar events, WhatsApp messages, and emails are prepared as reviewable drafts. UnoOne does not press the external app's final Send or Save control.
- Local notes, memory, Skills, activity logs, browser audit records, and preferences.
- A floating assistant and background voice service.
- A collapsible **Agent activity** panel that shows what UnoOne understood, which checks ran, what is executing, and whether it succeeded.
- A persistent **Disable UnoOne** master control on the Agent and Settings screens. Disabled mode stops and blocks microphone capture, STT, TTS, inference, Blind Aid, screen reading, accessibility actions, browser work, floating services, pending recovery, and network-backed page activity until the user explicitly enables the app.

### Blind Aid and screen understanding

- CameraX preview and analysis run independently of the Gemma model folder.
- Blind Aid uses an offline MediaPipe EfficientDet-Lite2 detector with COCO object labels, normalized bounding boxes, confidence filtering, multi-frame confirmation, scene narration, proximity tones, and haptics.
- The bundled detector can recognize supported classes such as people, cars, bicycles, chairs, and mobile phones. It is not a fine-grained product or brand recognition model.
- Starting Blind Aid releases the resident Gemma engine to reduce memory pressure. Stopping Blind Aid closes the detector, clears detection and narration state, and permits a guarded brain reload.
- Repeated unchanged warnings are cooldown-limited. Stopped or closed Blind Aid does not continue speaking cached detections.
- **Read Screen** uses Android MediaProjection plus bundled ML Kit Latin OCR and offline speech output.
- `ocr_screen` and document/image reading use the same on-device OCR path. The current Gemma artifact is text-only, so general screen description falls back to visible text and OCR rather than claiming visual understanding.

### Offline documents

The landing screen provides separate workflows for reading a document and filling an editable template.

**Load Document** supports:

- PDF pages rendered with Android `PdfRenderer` and read with OCR;
- images read with OCR;
- `.xlsx` spreadsheets parsed from OOXML;
- `.docx` documents parsed from OOXML;
- HTML, CSV, and UTF-8 text files.

Legacy `.xls` is explicitly unsupported. Extracted content is bounded before it is supplied to the local brain.

**Fill PDF / DOCX Offline** supports:

- PDF AcroForm text, checkbox, radio, list, and combo-box fields;
- DOCX content controls identified by tag or title;
- DOCX placeholders written as `{{field_name}}`, `${field_name}`, or `<<field_name>>`, including placeholders split across Word runs;
- document, header, and footer template fields;
- save-as-copy output with an exact read-back verification step.

The source is never overwritten. Encrypted PDFs, digital signing, scanned or flat PDF editing, legacy `.doc`, macros, and arbitrary free-position document editing are outside this workflow. See [Offline Document Skills](docs/OFFLINE_DOCUMENT_SKILLS.md).

### Skills

Four enabled built-in Skills are seeded locally:

1. Read Screen Aloud
2. Start Blind Aid Guidance
3. Fill an Offline PDF Form
4. Fill an Offline DOCX Template

Users can create, enable, disable, and delete their own Skills. Every Skill step re-enters the normal tool and safety pipeline.

Repeated successful low-risk usage can create a disabled suggestion for review from a small allow-list of routines. UnoOne never auto-enables a learned Skill and does not learn recipients, phone numbers, message bodies, email contents, or form values.

### Page Agent browser

- Page Agent runs inside an Android WebView and uses the same local Gemma artifact; there is no cloud LLM endpoint.
- The first screen is an offline help page rather than a blank WebView. It explains navigation, form filling, file selection, voice commands, and Read Page.
- Page Agent can read supported pages and work with text, email, number, textarea, select, checkbox, radio, date, file, and submit controls.
- Web file inputs use Android's Storage Access Framework with `content://` URIs, single or multiple selection, a 50 MB per-file limit, and explicit PDF, DOCX, PPTX, XLSX, TXT, JPEG, and PNG support.
- Tasks are single-flight, have a native timeout, and return results through a session-bound authenticated bridge.
- The bridge validates the main frame, session id, 256-bit nonce, declared origin, source origin, active origin, and navigation scope.
- Arbitrary JavaScript and native-code execution are not exposed as agent tools.
- Browser audit records store the origin, action class, and decision—not typed form values.
- Remote pages require internet access. The local Page Agent home and controlled local-form workflow remain available offline.

## Security modes

Security level is selected in the app and applies to phone tools and the Page Agent browser.

| Mode | Phone agent | Page Agent browser | Intended use |
|---|---|---|---|
| **Standard** (default) | Judge, confirmations, and blocks enforced | Confirm, takeover, and block decisions enforced; approved exact HTTPS origins only | Normal testing and production posture |
| **Relaxed** | Judge disabled; blocks enforced; confirmations auto-approved | Standard browser action decisions remain enforced | Lower-friction benign phone testing |
| **Off — prototype** | Judge, confirmations, and UnoOne block tiers bypassed | Page Agent action confirmations, takeovers, and blocks bypassed; public HTTPS permitted | Explicit local prototype testing only |

Off mode does **not** disable Android runtime permissions, MediaProjection consent, the Storage Access Framework picker, external-app review screens, HTTPS transport checks, main-frame isolation, bridge authentication, or origin consistency checks. HTTP URLs, executable schemes, embedded credentials, localhost, `.local` hosts, IP literals, subframe bridge calls, incorrect nonces, and origin mismatches remain rejected.

In Standard mode:

| Browser action | Policy |
|---|---|
| Read, wait, scroll | Allow |
| Ordinary form input | Allow after native classification |
| Unknown or sensitive action | Confirm |
| File transfer | Confirm and use the Android picker |
| Final submission | Confirm |
| Password, OTP, CAPTCHA, legal acceptance | Manual takeover |
| Payment, banking, card, UPI PIN | Block |
| Arbitrary JavaScript execution | Unavailable |

See [Safety](docs/SAFETY.md) for the tool-level risk model and prototype-mode boundaries.

## Offline behavior

The local brain, rule parser, notes, memory, Skills, Blind Aid, OCR, downloaded speech packs, document reading/filling, and local Page Agent fixtures can operate in airplane mode.

Remote websites, optional web search, app downloads, model downloads, and actions that depend on an external app or network service naturally require that service to be available. UnoOne does not silently send prompts, voice, screenshots, documents, or form values to a cloud inference endpoint.

### Master disabled mode

**Disable UnoOne** is separate from Android Airplane Mode and from the prototype browser safety setting. Its state is stored locally and survives app restart, process death, and phone reboot. Disable closes the runtime gate before cleanup starts, cancels the current command without speaking, discards transient audio/document selections, stops live WebViews and services, releases the local brain, and prevents recovery or queued work from restarting. Re-enabling never replays the old request.

While disabled, the landing screen shows **APP OFF** and a privacy status confirming that microphone, speech recognition, TTS, model inference, accessibility actions, browser automation, and network activity are inactive. Pressing a primary action explains that UnoOne is disabled and offers an explicit Enable button.

## Architecture

```text
voice / text / floating assistant / accessibility input
                         │
                         ▼
                  AgentOrchestrator
                         │
            deterministic parser ──► local Gemma
                         │
                         ▼
              CanonicalToolRegistry
                         │
                         ▼
       permissions + SafetyGuard + security mode
                         │
                         ▼
 phone tools / notes / memory / Skills / Blind Aid / documents

Page Agent WebView
        │
        ├── local Gemma planner with exclusive model lease
        ├── authenticated native bridge
        ├── action authorization before DOM execution
        └── privacy-bounded Room audit record
```

### Android modules

| Module | Responsibility |
|---|---|
| `:app` | Compose UI, orchestration, services, navigation, and ViewModels |
| `:core` | Shared types, canonical tools, document parsers, and model contracts |
| `:storage` | Room database, notes, memory, Skills, model metadata, and audit logs |
| `:modelmanager` | Model manifest, download, integrity, health, and uninstall handling |
| `:languagepacks` | Speech-language catalogue, dependency-aware installation, and health checks |
| `:localbrain` | Gemma planning, prompts, deterministic parser, tool declarations, and inference lifecycle |
| `:voice` | Offline STT/TTS, recording, optional wake-word support, and background voice service |
| `:agentrouter` | FAST_ACTION, CHAT, and AGENT_ACTION routing |
| `:safetyguard` | Tool/input risk classification and approval policy |
| `:phonecontrol` | Android intents, Calendar, OCR, Blind Aid, and document operations |
| `:memory` | Local preference and outcome memory |
| `:skills` | Skill matching, storage, execution, built-ins, and review-first suggestions |
| `:observability` | Diagnostics, logging, and health reporting |
| `:accessibilitycontrol` | Android UI reading, gestures, and text input |
| `:securebrowser` | WebView, Page Agent bridge, domain policy, action safety, and browser audit |

## Local model contract

UnoOne has one planning-brain profile.

| Field | Current source-of-truth value |
|---|---|
| Model id | `gemma-4-e2b` |
| File | `gemma-4-E2B-it.litertlm` |
| Runtime | LiteRT-LM |
| Exact size | `2,588,147,712` bytes |
| SHA-256 | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |
| Maximum context | 32,768 tokens |
| Default configured context | 4,096 tokens |
| Minimum RAM gate | 6,144 MB |
| Recommended RAM gate | 8,192 MB |
| Tested backend on Xiaomi 14 | CPU fallback |

The exact bytes loaded successfully on the primary Xiaomi test device. The source registry deliberately remains marked as not production-qualified until the full device, thermal, accuracy, release, and distribution gates are complete. Model and runtime licences must be reviewed from their upstream notices before redistribution; the Android model manifest does not declare a licence field.

The phone agent and Page Agent browser use an exclusive model lease. UnoOne unloads one planner before loading the other and prevents a second Gemma conversation from being allocated concurrently.

## Speech-language status

| Language | Current state | Remaining qualification |
|---|---|---|
| English | enabled baseline | controlled accent, noise, names, numbers, latency, and audible-quality benchmark |
| Hindi | enabled baseline | same controlled benchmark |
| Bengali | enabled baseline | same controlled benchmark |
| Tamil | enabled baseline | same controlled benchmark |
| Telugu | enabled baseline | same controlled benchmark |
| Kannada | enabled baseline | same controlled benchmark |
| Malayalam | enabled baseline | same controlled benchmark |
| Assamese | planned priority | select and qualify exact STT and TTS artifacts |
| Marathi, Gujarati, Punjabi, Odia, Urdu | planned | select, license-check, and qualify exact artifacts |

All seven enabled engines initialize in connected-device tests. Hindi, Bengali, Tamil, Telugu, Kannada, and Malayalam additionally pass an offline native-script TTS→STT round-trip gate and native one-breath command-routing tests on the primary Xiaomi 14. These deterministic tests do not replace a controlled acoustic benchmark; broader accents, short ambiguous utterances, background noise, microphone distance, and a second device still require qualification. See [Speech Model Qualification](docs/SPEECH_MODEL_QUALIFICATION.md).

## Build and test

### Page Agent runtime

```bash
cd web-runtime/page-agent-unoone
npm install --no-audit --no-fund
npm run typecheck
npm test
npx playwright install chromium
npm run test:e2e
npm run bundle:android
```

`bundle:android` creates the generated Android asset under `securebrowser/src/main/assets/page-agent/`. The generated asset is not committed.

### Android

Use JDK 17. From `android-app/UnoOneAgent`:

```bash
./gradlew \
  :app:lintDebug \
  :app:testDebugUnitTest \
  :core:testDebugUnitTest \
  :skills:testDebugUnitTest \
  :localbrain:testDebugUnitTest \
  :securebrowser:testDebugUnitTest \
  :voice:testDebugUnitTest \
  :app:assembleDebug \
  :app:assembleDebugAndroidTest
```

Connected-device suite:

```bash
adb shell am instrument -w \
  com.unoone.agent.test/androidx.test.runner.AndroidJUnitRunner
```

On Xiaomi/HyperOS, stream installation may be blocked. The validated path is `adb push` followed by `adb shell pm install -r`.

### Distribution projects

```bash
cd distribution/api
npm install --no-audit --no-fund
npm run typecheck
npm test

cd ../../installer-pwa
npm install --no-audit --no-fund
npm run typecheck
npm test
npm run build
```

Repository invariant check:

```bash
python scripts/ci/check_repo_invariants.py
```

## Latest verified results

Validated on July 17, 2026 against the Xiaomi 14 (`7f8cafef`), Android 15:

| Gate | Result |
|---|---|
| Android lint and JVM unit tests | Pass |
| Debug APK and Android-test APK | Pass |
| Connected-device instrumentation | `OK (55 tests)` in 206.218 seconds |
| No-network device subset | `OK (20 tests)` in 77.202 seconds with Wi-Fi and mobile data disabled |
| PDF and DOCX Android round trips | `OK (2 tests)`; exact values persisted and original bytes unchanged |
| Page Agent TypeScript and unit tests | Pass; 4 unit tests |
| Page Agent Playwright | Pass; 5 browser scenarios |
| Page Agent physical WebView fixture | Pass for authenticated form filling and Read Page extraction |
| Blind Aid regression | Representative mobile-phone fixture detected; stale-cache lifecycle test passed |
| Crash/ANR/OOM scan | No UnoOne crash, ANR, OOM, missing crypto class, or UnoOne low-memory kill in the final run |

Detailed evidence and honest boundaries are recorded in [Connected-device validation](docs/DEVICE_VALIDATION_2026-07-17.md) and [Device verification](DEVICE_VERIFICATION.md).

## Not production-ready yet

The following gates remain open:

- a second Android device and broader OEM/API matrix;
- repeatable recorded-speech accuracy tests for every enabled language and multiple accents/noise levels;
- a controlled Blind Aid corpus covering lighting, distance, people, vehicles, phones, and product classes;
- fine-grained product recognition beyond the bundled COCO detector;
- a sustained thermal, memory, battery, and 50-task planning/Page Agent benchmark;
- human verification of audible speech quality and TalkBack announcements;
- final visual reruns of Android document/file pickers while the physical device is unlocked;
- approved-site-by-site Page Agent qualification and prompt-injection testing;
- release dependency/licence review, SBOM, protected signing key, and signed release APK;
- production object storage, catalogue signing key, signed catalogues, deployment, update, and rollback testing.

The installer PWA is implemented but intentionally keeps downloads locked when a production catalogue public key is not configured. No production deployment or production-approved release is claimed.

## Repository layout

```text
.
├── android-app/UnoOneAgent/          Android app and 15 modules
├── web-runtime/page-agent-unoone/    Page Agent runtime and browser tests
├── installer-pwa/                    Verified APK installer PWA
├── distribution/api/                 Read-only distribution Worker
├── distribution/catalog/             Catalogue schemas and examples
├── scripts/                           Model, catalogue, and CI utilities
└── docs/                              Architecture, safety, privacy, and validation records
```

## Documentation

- [Android build and validation](android-app/UnoOneAgent/README.md)
- [Phone-control implementation](android-app/UnoOneAgent/phonecontrol/README.md)
- [Offline Document Skills](docs/OFFLINE_DOCUMENT_SKILLS.md)
- [Connected-device validation](docs/DEVICE_VALIDATION_2026-07-16.md)
- [Device verification matrix](DEVICE_VERIFICATION.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Safety](docs/SAFETY.md)
- [Model acquisition and distribution](docs/MODEL_ACQUISITION_AND_DISTRIBUTION.md)
- [Speech model qualification](docs/SPEECH_MODEL_QUALIFICATION.md)
- [Privacy policy](docs/play-review/privacy-policy.md)
- [Data safety](docs/play-review/data-safety.md)

## Ownership and licensing

UnoOne is developed under Uni Guru Technologies LLP / InBharat.ai. Repository code, libraries, model weights, and speech artifacts may use different licences or usage terms. Review and preserve the notice attached to every component before redistribution.
