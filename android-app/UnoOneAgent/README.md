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
- CameraX/ML Kit Blind Aid.
- Alibaba PageAgent Secure Browser using local Gemma planning.
- Command-path three-lane router (FAST_ACTION / CHAT / AGENT_ACTION) plus eyes-free assist
  (Listen wake mode, step narration, Blind Aid scene narration, voice-driven Secure Browser,
  TalkBack live regions), merged to `main`. Automated gate green; live hands-free/voice/visual
  UX remains device-gated (see root `DEVICE_VERIFICATION.md` §10).
- Alpha branch; physical-device qualification remains pending.

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
│   │   ├── sherpa-asr-whisper/
│   │   ├── vad/
│   │   └── punctuation/
│   └── languages/
│       ├── en-IN/tts/
│       ├── hi-IN/tts/
│       ├── bn-IN/tts/
│       ├── ta-IN/tts/
│       ├── te-IN/tts/
│       ├── kn-IN/tts/
│       └── ml-IN/tts/
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
| Device qualification | pending |

The manifest may use the verified upstream file for engineering acquisition. Production must distribute the same bytes from UnoOne-controlled storage through a signed catalogue.

## Build prerequisites

- JDK 17.
- Android SDK 35.
- Node.js 24 for the PageAgent bundle.
- Android device or emulator for installation; physical device required for Gemma, Sherpa, Accessibility and Blind Aid qualification.

## Build PageAgent asset first

```bash
cd ../../../web-runtime/page-agent-unoone
npm install --no-audit --no-fund
npm run typecheck
npm test
npm run build
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
3. load the PageAgent planner using the same model artifact;
4. block phone self-heal from allocating a second engine;
5. restore the phone brain after the browser session closes.

The browser bridge is exact-origin HTTPS only. PageAgent cannot execute arbitrary JavaScript. Payments are blocked; credentials, OTPs, CAPTCHA and legal acceptance require manual takeover.

## Language packs

Language selection must occur through **Settings → Offline Languages**.

The old direct selector is intentionally removed. Activation is allowed only when every required model dependency is healthy. Planned packs remain non-downloadable.

Current baseline packs:

- English
- Hindi
- Bengali
- Tamil
- Telugu
- Kannada
- Malayalam

Priority planned pack:

- Assamese

## Device verification

Use the root [DEVICE_VERIFICATION.md](../../../DEVICE_VERIFICATION.md). Do not mark a feature as verified from compilation or JVM tests alone.

Required physical-device evidence includes:

- Gemma load and backend;
- first-token and total planning latency;
- peak RAM and temperature;
- repeated phone-agent and PageAgent tasks;
- English and Indic STT/TTS;
- Accessibility gestures and text capture;
- Blind Aid camera, haptic and spoken feedback;
- model repair and process restart;
- Secure Browser takeover and blocked-action paths.

## Release rules

The Android release must not proceed until:

- latest Android CI is green;
- PageAgent Playwright tests are green;
- device matrix is populated;
- model and speech artefacts have exact checksums and licences;
- release APK is signed with the production key;
- APK size and SHA-256 are published in the signed distribution catalogue;
- installer PWA verifies the catalogue and APK locally.
