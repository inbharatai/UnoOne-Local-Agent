# UnoOne V2

UnoOne is an offline-first Android AI assistant for blind and sighted users. It combines hands-free speech, deterministic Android actions, an on-device planning model, Blind Aid, document tools, reusable Skills, and a guarded Page Agent browser.

> **Current development status — E4B hardening:** the exact Gemma 4 E4B Android artifact is installed and hash-verified on the Xiaomi 14. It loads on CPU and the post-fix physical planner evaluation scored 43/43 tool and argument cases. UnoOne is still an alpha: sustained back-to-back CPU inference warmed the process to about 3.82 GB PSS and reached severe CPU thermal status, while the strict in-app self-test, E4B-backed browser planning, 50-task thermal/battery gate, second device and release supply chain remain open. Historical E2B evidence remains historical.

## Product design

UnoOne does not treat the language model as an unrestricted phone controller.

```text
wake phrase / text / accessibility input
                    │
                    ▼
        language and command normalisation
                    │
                    ▼
          deterministic command router
             │                 │
             │ direct action   │ ambiguous or multi-step task
             ▼                 ▼
   Android intent/provider   Gemma 4 E4B planner
             │                 │
             └───────┬─────────┘
                     ▼
           canonical tool validation
                     ▼
       permissions + safety + confirmation
                     ▼
          deterministic action executor
                     ▼
             result verification
                     ▼
             offline spoken response
```

Common commands such as opening WhatsApp or Gmail, starting Blind Aid, reading the screen, changing language, going Home or Back, and stopping speech should be handled without model inference whenever a deterministic route exists. Gemma 4 E4B is reserved for conversation, ambiguity, summarisation, and bounded agent planning.

## Local model contract

UnoOne has one production model profile.

| Field | Source-of-truth value |
|---|---|
| Model id | `gemma-4-e4b` |
| File | `gemma-4-E4B-it.litertlm` |
| Runtime | LiteRT-LM |
| Exact size | `3,659,530,240` bytes |
| SHA-256 | `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0` |
| Artifact capability ceiling | 32,768 tokens (not used on phone) |
| Enforced phone context | 2,048 tokens |
| Minimum RAM gate | 8,192 MB |
| Recommended RAM gate | 12,288 MB |
| Production `AUTO` backend | CPU until a device/hash/build qualification record approves another backend |
| Device qualification | Partial: Xiaomi 14 CPU load and 43/43 planner evaluation passed; sustained/release gates remain |

The Android app must load only the exact manifest-declared filename after exact size and SHA-256 verification. It must never select an arbitrary `.litertlm` file based on filename similarity or file size. The smaller web-specific E4B artifact is not valid for the Android runtime.

A legacy E2B installation is preserved until exact E4B integrity, load, strict self-test, evaluation, sustained stability, device/build/backend evidence, and explicit user approval are all recorded. It is neither selectable nor loaded by this branch.

## Agent accuracy rules

UnoOne uses the following reliability controls:

- deterministic handlers before model inference;
- one canonical tool proposal per model turn;
- exact tool-name allow-list;
- required-argument and runtime-type validation;
- no invented contacts, addresses, phone numbers, package names, dates, times, permissions, screen nodes, or success results;
- short clarification instead of guessing a missing required value;
- native permission and safety checks before execution;
- explicit confirmation for sensitive or irreversible actions;
- post-execution verification before success is announced;
- bounded observe-plan loops for multi-step work;
- separate tool-less conversational replies from phone-action planning;
- concise responses in the active voice language.

WhatsApp and email tools prepare reviewable drafts. They do not silently press the external app's Send button. Calendar insertion remains reviewable unless a separately verified provider-based creation flow is implemented and qualified.

## Voice and eyes-free use

Wake phrases include **“Uno,” “Uno One,” “Hey Uno,” “Uno on,”** and **“Uno start.”** Wake and command may be spoken in one breath, for example:

- “Uno, start blind mode.”
- “Uno on, open WhatsApp.”
- “Uno, speak in Hindi and start blind mode.”
- “Uno, add a meeting tomorrow at 5 PM.”

Enabled voice baselines are English and Hindi. Assamese remains a priority language but must not be represented as production-ready until exact STT and TTS artifacts pass licensing, integrity, accuracy, Android loading, and physical-device qualification.

Blind Aid uses CameraX and an offline object detector. Starting Blind Aid releases the resident language model when needed to reduce memory pressure; stopping it closes camera/detector state before a guarded model reload. Read Screen uses Accessibility text and the existing screenshot/OCR fallback.

## Android model storage

Models are stored below the app-private models root. Large downloads run as a foreground WorkManager job, default to unmetered networking, survive Activity recreation, expose progress/cancellation, preserve ordinary interrupted `.part` files, validate redirects and `Content-Range`, perform storage preflight, fsync, exact size/SHA-256 verification, and only then atomically activate the final file. The immutable E4B upstream revision is `28299f30ee4d43294517a4ac93abd6163412f07f`.

Expected E4B path on the phone:

```text
<app-private-model-root>/brain/gemma-4-e4b/gemma-4-E4B-it.litertlm
```

After successful E4B device qualification, this legacy directory should no longer exist:

```text
<app-private-model-root>/brain/gemma-4-e2b/
```

## Main Android capabilities

- Native Kotlin and Jetpack Compose application for Android API 28+.
- LiteRT-LM on-device planning with a 2,048-token engine cap, cancellable callback inference, and conservative CPU `AUTO`; CPU/GPU developer qualification uses the same evaluation set.
- Offline Sherpa-ONNX STT/TTS with explicit model-health checks.
- Background foreground-service voice listening with microphone ownership controls.
- Android application opening and system navigation.
- Reviewable WhatsApp, Gmail, and Calendar hand-offs.
- Accessibility-based screen reading and UI interaction.
- Offline notes, memory, Skills, logs, and preferences.
- Camera-based Blind Aid and object narration.
- Offline PDF, DOCX, XLSX, image, HTML, CSV, and text reading workflows.
- Offline fillable PDF and DOCX template workflows.
- Guarded WebView Page Agent with native action authorisation.
- Persistent master disable that stops listening, inference, TTS, accessibility actions, browser automation, and recovery work.

## Local Skills

UnoOne seeds six reviewable, safety-routed routines: Read Screen Aloud, Start Blind Aid Guidance, Fill an Offline PDF Form, Fill an Offline DOCX Template, Open Calendar and Open WhatsApp. Their triggers include English, Hindi and common Hinglish forms. User-created skills remain visible and editable. Repeated successful low-risk use may create a disabled suggestion, but UnoOne never auto-enables it and never learns recipients, message/email bodies or form values.

## Security boundaries

UnoOne must never:

- enter or expose passwords, OTPs, card data, banking credentials, or authentication secrets;
- invent a recipient, phone number, email address, package name, date, or time;
- claim an external action succeeded without verification;
- silently send messages, make payments, install applications, bypass CAPTCHA, or accept legal declarations;
- execute arbitrary JavaScript or native code through the Page Agent;
- weaken native permission, safety, confirmation, or origin checks because the model requested it.

## Build and test

Use JDK 17.

### Android

```bash
cd android-app/UnoOneAgent
chmod +x gradlew
./gradlew \
  :app:lintDebug \
  testDebugUnitTest \
  :app:assembleDebug \
  :app:assembleDebugAndroidTest
```

### Repository invariants

```bash
python3 scripts/ci/check_repo_invariants.py
```

### Page Agent

```bash
cd web-runtime/page-agent-unoone
npm install --no-audit --no-fund
npm run typecheck
npm test
npx playwright install chromium
npm run test:e2e
npm run bundle:android
```

GitHub Actions runs repository invariants, Page Agent type checking/tests/browser tests, Android lint, JVM tests, and debug APK assembly for pull requests targeting `main`.

## Xiaomi 14 handoff

The E4B branch is not complete until the physical phone test proves:

1. exact E4B bytes are present and verified;
2. the model initializes without crash or low-memory kill;
3. the actual backend is recorded from logs;
4. common deterministic commands work without waiting for E4B;
5. tool calls pass exact tool/argument checks;
6. Hindi and English responses use the selected language;
7. Blind Aid unloads and restores the brain safely;
8. Secure Browser leases one model engine at a time;
9. a sustained task loop produces no crash, ANR, OOM, or false success announcement;
10. legacy E2B files remain until every qualification gate passes and the user explicitly approves removal.

See [`docs/E4B_XIAOMI14_HANDOFF.md`](docs/E4B_XIAOMI14_HANDOFF.md) for tomorrow's exact pull, build, install, logging, and verification steps.

## Repository layout

```text
.
├── android-app/UnoOneAgent/          Android application and modules
├── web-runtime/page-agent-unoone/    Page Agent runtime and browser tests
├── installer-pwa/                    Verified APK installer PWA
├── distribution/api/                 Read-only distribution service
├── distribution/catalog/             Catalogue schemas and fixtures
├── scripts/                           Model, catalogue, and CI utilities
└── docs/                              Architecture, safety, privacy, and validation records
```

## Documentation

- [Xiaomi 14 E4B handoff](docs/E4B_XIAOMI14_HANDOFF.md)
- [Android build and validation](android-app/UnoOneAgent/README.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Safety](docs/SAFETY.md)
- [Model acquisition and distribution](docs/MODEL_ACQUISITION_AND_DISTRIBUTION.md)
- [Speech model qualification](docs/SPEECH_MODEL_QUALIFICATION.md)
- [Connected-device validation](docs/DEVICE_VALIDATION_2026-07-17.md)
- [Device verification matrix](DEVICE_VERIFICATION.md)

Historical validation documents describe the model and source revision that were actually tested. They must not be rewritten as E4B evidence.

## Ownership and licensing

UnoOne is developed under Uni Guru Technologies LLP / InBharat.ai. Repository code, libraries, model weights, speech artifacts, and other dependencies may use different licences or usage terms. Review and preserve the notice attached to every component before redistribution.
