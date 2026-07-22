# UnoOneAgent Android

Native Android implementation for UnoOne V2.

The repository [README](../../../README.md) is the product and architecture source of truth. This document covers Android build, model layout, runtime boundaries and device validation.

## Current branch state

- Android API 28+.
- AGP 8.10.0, Gradle 8.11.1, Kotlin 2.2.21 and JDK 17.
- 15 Gradle modules.
- Gemma 4 E4B is the sole installable planning model.
- LiteRT-LM manual tool calling with CPU `AUTO`; GPU is an explicit developer qualification choice.
- Deterministic command parsing before model inference.
- Canonical tool-name and argument validation before execution.
- Offline Sherpa-ONNX speech with English and Hindi exposed.
- Accessibility-based phone control and screen reading.
- CameraX/MediaPipe Blind Aid independent of the language model.
- Secure Browser Page Agent using an exclusive lease on the same E4B artifact.
- Offline document reading and supported PDF/DOCX template filling.
- Persistent master disable that stops voice, TTS, inference, camera, OCR, accessibility actions, browser work and recovery.

This branch is an alpha. E4B has not yet been physically qualified on the Xiaomi 14. Existing E2B device records are historical evidence only.

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

```text
models/
├── brain/
│   └── gemma-4-e4b/
│       └── gemma-4-E4B-it.litertlm
├── speech/
│   ├── shared/
│   │   ├── sherpa-asr-en/
│   │   ├── sherpa-asr-indic/
│   │   └── sherpa-kws-en/
│   └── languages/
│       ├── en-IN/tts/
│       ├── hi-IN/tts/
│       └── retained language TTS folders
├── vision/
│   └── blind-aid/
└── staging/
```

The app uses app-private storage and does not require all-files access.

## Gemma 4 E4B contract

| Field | Value |
|---|---|
| Manifest id | `gemma-4-e4b` |
| File | `gemma-4-E4B-it.litertlm` |
| Exact size | `3,659,530,240` bytes |
| SHA-256 | `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0` |
| Runtime | LiteRT-LM `0.13.1` |
| Production `AUTO` | CPU until exact device/build/hash qualification approves another backend |
| Enforced engine context | 2,048 tokens |
| Artifact capability ceiling | 32,768 tokens; not configured on phone |
| Minimum RAM gate | 8,192 MB |
| Recommended RAM gate | 12,288 MB |
| Xiaomi 14 qualification | pending |

`ModelManager.getLlmModelPath()` returns only the exact declared file after exact size and SHA-256 verification. It does not select the largest `.litertlm` file and does not accept the web-specific E4B artifact.

Existing E2B files are retained until exact integrity, load, strict self-test, evaluation, sustained stability, recorded device/build/backend evidence and explicit user approval all pass. Cleanup must use the fixed-path qualified migration rather than an unbounded filesystem deletion.

## Agent execution order

```text
voice/text input
      ↓
normalisation and language handling
      ↓
deterministic parser
      ├── direct Android handler
      └── E4B planner for ambiguity or multi-step work
                    ↓
          canonical tool validation
                    ↓
       permission + safety + confirmation
                    ↓
         deterministic action executor
                    ↓
           post-action verification
                    ↓
            offline spoken response
```

The model proposes actions; native code authorises, executes and verifies them. A tool response cannot bypass permissions, safety rules, confirmation requirements or verification.

Common app opening, language changes, Blind Aid control, Home/Back navigation, screen reading and stop commands should bypass the model where deterministic routing exists.

## Voice and wake activation

Wake phrases include:

- Uno
- Uno One
- Hey Uno
- Uno on
- Uno start

Wake and command may be spoken together. The voice service and foreground recorder maintain single microphone ownership, and TTS must not become a new command.

English and Hindi are currently exposed. Assamese remains planned until exact speech artifacts pass licence, integrity, Android load and accuracy qualification.

## Secure Browser model lease

Secure Browser follows this order:

1. acquire the process-wide model lease;
2. verify the exact E4B artifact off the UI thread;
3. unload the phone-agent planner if loaded;
4. load one Page Agent planner using the same file;
5. prevent phone recovery from allocating another engine;
6. close the browser planner on release;
7. restore the phone planner once when required.

Two E4B engines must never be resident simultaneously.

The browser bridge continues to enforce session, nonce, origin and main-frame checks. Arbitrary JavaScript execution is not exposed. Payments remain blocked in Standard mode, while credentials, OTPs, CAPTCHA and legal acceptance require manual takeover.

## Blind Aid memory boundary

Blind Aid can operate without Gemma. Starting Blind Aid may cancel an in-flight brain load or unload the resident E4B engine before sustained camera analysis. Stopping Blind Aid must:

- close camera and detector state;
- stop stale detections and narration;
- release exclusive ownership;
- reload E4B once when the agent is enabled and no other mode owns it.

## Build prerequisites

- JDK 17
- Android SDK 35
- Node.js 24 for Page Agent
- physical Android device for LiteRT-LM, Sherpa, Accessibility, background voice and camera qualification

## Build Page Agent first

```bash
cd ../../../web-runtime/page-agent-unoone
npm install --no-audit --no-fund
npm run typecheck
npm test
npm run bundle:android
```

For the full browser matrix:

```bash
npx playwright install chromium
npm run test:e2e
```

The generated asset must exist at:

```text
securebrowser/src/main/assets/page-agent/unoone-page-agent.js
```

## Android gates

```bash
cd android-app/UnoOneAgent
chmod +x gradlew
./gradlew \
  :app:lintDebug \
  testDebugUnitTest \
  :app:assembleDebug \
  :app:assembleDebugAndroidTest \
  --stacktrace
```

Repository invariants:

```bash
cd ../../..
python3 scripts/ci/check_repo_invariants.py
python3 scripts/verify_unoone_v2_invariants.py
```

## Install on Xiaomi/HyperOS

Preferred:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If streamed installation is blocked:

```bash
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/unoone-e4b-debug.apk
adb shell pm install -r /data/local/tmp/unoone-e4b-debug.apk
```

HyperOS can disable Accessibility after an APK update. Re-enable **UnoOne → Accessibility** before cross-app tests.

## Required device evidence

Follow [E4B Xiaomi 14 Handoff](../../../docs/E4B_XIAOMI14_HANDOFF.md). Record:

- device, Android and HyperOS build;
- physical memory and free storage;
- E4B download and checksum time;
- actual backend and any GPU failure reason;
- load, first-token and total planning latency;
- process memory, heat and battery behaviour;
- deterministic English/Hindi commands;
- exact tool-name and argument accuracy;
- external-app foreground and draft verification;
- Blind Aid unload/reload;
- Secure Browser exclusive lease;
- background/foreground and lock/unlock recovery;
- sustained 50-task stability;
- crash, ANR, native-signal, OOM and low-memory scan.

Compilation alone does not qualify E4B.

## Release rules

Do not merge or release until:

- latest Android and Distribution CI are green on the final head;
- E4B loads and passes the self-test on Xiaomi 14;
- the sustained device matrix passes;
- E2B cleanup occurs only after verified E4B success;
- model and speech licences/notices are reviewed;
- production storage and catalogue signing are configured;
- the release APK is signed and checksum-published;
- security, privacy, dependency and SBOM reviews are complete.
