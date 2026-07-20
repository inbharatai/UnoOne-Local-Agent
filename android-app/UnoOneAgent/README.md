# UnoOneAgent Android

Android implementation for UnoOne V2.

> The authoritative architecture, implementation checklist and release gates are in the repository [README](../../../README.md). This document is intentionally limited to Android build, model layout and device-validation instructions so it cannot drift into a second product specification.

## Current state

- Android API 28+.
- AGP 8.10.0, Gradle 8.11.1, Kotlin 2.2.21.
- 15 Gradle modules.
- One planning brain: Gemma 4 E2B.
- Offline Sherpa-ONNX speech baseline.
- Downloadable language-pack manager.
- Accessibility-based phone control.
- CameraX/MediaPipe Blind Aid using the bundled EfficientDet-Lite2 COCO detector.
- English and native-Hindi Blind Aid object/status narration, with gender-neutral Hindi confirmations, multi-frame filtering, repeat cooldowns and stale-state cleanup on stop.
- Page Agent Secure Browser using local Gemma planning.
- Landing-screen Agent activity panel with a persistent latest-step summary, bounded expansion, and direct Skills access.
- Safety-routed built-in/custom skills plus disabled, review-first suggestions learned only from repeated successful low-risk routines.
- Offline Document Agent for verified save-as-copy filling of PDF AcroForms and DOCX content-control/placeholder templates. It is available from the landing screen or the built-in PDF/DOCX voice skills; ordinary DOCX files are also supported by Load Document.
- FAST_ACTION, CHAT and AGENT_ACTION command routing, with deterministic parsing before model inference.
- Eyes-free operation through hands-free listening, selected-language wake cues, native one-breath core commands, spoken execution steps, Blind Aid scene narration, voice-driven Page Agent commands and TalkBack live regions.
- Read Screen through MediaProjection and bundled ML Kit Latin OCR; PDF, image, XLSX, DOCX, HTML, CSV and text loading; legacy `.xls` is unsupported.
- A persistent master-disable mode that synchronously closes the runtime gate, stops voice, inference, camera, OCR, accessibility actions, Page Agent work and services, persists across restart/reboot, and never replays old work after explicit re-enable.
- The July 17 Xiaomi 14 validation recorded 55 connected-device tests plus a 20-test no-network subset. On July 20, Android lint, 519 JVM tests, debug/release assembly, Android-test compilation, repository invariants, and all Page Agent tests passed. The latest instrumentation APK installation was rejected by Xiaomi with `INSTALL_FAILED_USER_RESTRICTED`; the older device suite is therefore historical evidence, not qualification of the current revision. This is an alpha, not production qualification.

## Modules

```text
:app
:core
:storage
:modelmanager
:languagepacks
:localbrain
:voice
:agentrouter
:safetyguard
:phonecontrol
:memory
:skills
:observability
:accessibilitycontrol
:securebrowser
```

## Model filesystem

The app-private model root is divided by purpose:

```text
models/
├── brain/
│   └── gemma-4-e2b/
│       └── gemma-4-E2B-it.litertlm
├── speech/
│   ├── shared/
│   │   ├── sherpa-asr-en/
│   │   ├── sherpa-asr-indic/
│   │   ├── vad/
│   │   └── punctuation/
│   └── languages/
│       ├── en-IN/tts/
│       └── hi-IN/tts/
├── vision/
│   └── blind-aid/
├── ocr/
│   └── optional/
└── staging/
```

There is no active `gemma-local` or Gemma 3n compatibility folder in V2.

## Gemma 4 E2B integrity metadata

| Field | Value |
|---|---|
| File | `gemma-4-E2B-it.litertlm` |
| Size | `2,588,147,712` bytes |
| SHA-256 | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |
| Context | 32,768 tokens |
| Minimum RAM product gate | 6 GB |
| Recommended RAM product gate | 8 GB |
| Prototype device result | loaded successfully on Xiaomi 14; production qualification pending |

The manifest may use the verified upstream file for engineering acquisition. Production must distribute the same bytes from UnoOne-controlled storage through a signed catalogue.

## Build prerequisites

- JDK 17.
- Android SDK 35.
- Node.js 24 for the Page Agent bundle.
- Android device or emulator for installation; physical device required for Gemma, Sherpa, Accessibility and Blind Aid qualification.

## Build the Page Agent asset first

```bash
cd ../../../web-runtime/page-agent-unoone
npm install --no-audit --no-fund
npm run typecheck
npm test
npm run test:e2e
npm run bundle:android
```

The generated asset must exist at:

```text
securebrowser/src/main/assets/page-agent/unoone-page-agent.js
```

The Android build must not silently substitute an empty or dummy runtime.

## Android validation

```bash
cd android-app/UnoOneAgent
chmod +x gradlew
./gradlew :app:lintDebug --stacktrace
./gradlew testDebugUnitTest --stacktrace
./gradlew assembleDebug --stacktrace
```

Run instrumented tests only on a configured Android device:

```bash
./gradlew connectedDebugAndroidTest --stacktrace
```

## Secure Browser behavior

The Secure Browser uses an exclusive Gemma lease:

1. reserve the process-wide Gemma owner;
2. unload the phone-agent conversation when required;
3. load the Page Agent planner using the same model artifact;
4. block phone self-heal from allocating a second engine;
5. restore the phone brain after the browser session closes.

Standard uses exact approved HTTPS origins; explicit Prototype/Off admits arbitrary public HTTPS.
The first screen is an offline Page Agent home rather than a blank remote page. It explains URL entry,
offline HTML form loading, typed/voice tasks, Read Page and hands-free command examples.
Navigation-triggered tasks wait for the requested page and a fresh runtime before execution, so a
voice command is not run against the previously open page.
The bridge still validates the main frame, session id, nonce and exact source/declared/active origin,
and Page Agent cannot execute arbitrary JavaScript.
The guarded runtime supports text, email, number, textarea, select, checkbox, radio, date, file and
explicit-submit controls in automated browser tests. The local planner rejects missing required
arguments, repairs a bounded set of malformed small-model outputs and verifies visible changes.
Changing public sites and every possible form are not qualified.
In the default **Standard** security level, payments are blocked and credentials, OTPs, CAPTCHA and
legal acceptance require manual takeover. The explicit **Off — prototype (agent + browser)** setting
removes those per-action browser blocks for local prototyping and displays a persistent warning.
HTTP, executable URLs, embedded credentials, localhost, `.local`, IP literals and invalid bridge
sessions remain blocked in every mode.

## Language packs

The active voice language can be changed from the landing-screen selector or **Settings → Voice language**. **Settings → Offline Languages** manages pack installation and health. Activation is allowed only when every required model dependency is healthy; planned packs remain non-downloadable.

Current enabled voice profiles:

- English
- Hindi

Other language packs are deferred and are not exposed while English and Hindi are being hardened.

Priority planned pack:

- Assamese

## Device verification

Use the root [DEVICE_VERIFICATION.md](../../../DEVICE_VERIFICATION.md). Do not mark a feature as verified from compilation or JVM tests alone.

Required physical-device evidence includes:

- Gemma load and backend;
- first-token and total planning latency;
- peak RAM and temperature;
- repeated phone-agent and Page Agent tasks;
- English and Hindi STT/TTS;
- Accessibility gestures and text capture;
- Blind Aid camera, haptic and spoken feedback;
- model repair and process restart;
- Secure Browser takeover and blocked-action paths.

## Release rules

The Android release must not proceed until:

- latest Android CI is green;
- Page Agent Playwright tests are green;
- device matrix is populated;
- model and speech artefacts have exact checksums and licences;
- release APK is signed with the production key;
- APK size and SHA-256 are published in the signed distribution catalogue;
- installer PWA verifies the catalogue and APK locally.
