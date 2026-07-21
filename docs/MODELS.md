# UnoOne Models — Installation, Integrity and Runtime Contract

The active model catalogue is:

```text
android-app/UnoOneAgent/modelmanager/src/main/assets/models_manifest.json
```

## Integrity rules

Every downloadable artifact must declare:

- an HTTPS engineering-acquisition URL or a bundled asset;
- exact `sizeBytes`;
- exact lowercase SHA-256;
- an app-private destination folder;
- an immutable model id and version label.

`ModelInstaller` downloads to a `.part` file, supports HTTP range resume where available, verifies the final size and SHA-256, and atomically renames the file only after verification. A wrong or incomplete file must never appear healthy.

`ModelManager.getLlmModelPath()` accepts only the exact filename declared by the active brain specification. It rejects:

- a missing file;
- a zero-byte or partial file;
- a `.part` file;
- a wrong filename;
- a wrong byte size;
- a wrong SHA-256;
- an old E2B model;
- the web-specific E4B build;
- an unrelated larger `.litertlm` file.

## Current planning model

UnoOne has one installable language-model profile:

| Field | Value |
|---|---|
| Model id | `gemma-4-e4b` |
| Folder | `brain/gemma-4-e4b` |
| File | `gemma-4-E4B-it.litertlm` |
| Runtime | LiteRT-LM |
| Backend order | GPU, then CPU fallback |
| Exact size | `3,659,530,240` bytes |
| SHA-256 | `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0` |
| Initial context | 2,048 tokens |
| Maximum supported context | 32,768 tokens |
| Minimum RAM gate | 8,192 MB |
| Recommended RAM gate | 12,288 MB |
| Physical-device qualification | pending |

The file above is the Android LiteRT-LM artifact. Do not substitute the smaller web-specific artifact.

The model is the planner for ambiguous, conversational and bounded multi-step tasks. Deterministic Android commands should not invoke E4B when native routing can resolve them safely.

## Legacy E2B handling

E2B is not present in the active install catalogue and is not a user-selectable fallback. Existing devices may still contain:

```text
brain/gemma-4-e2b/
```

That folder must be retained until E4B has:

1. downloaded fully;
2. passed exact size and SHA-256 verification;
3. initialized successfully in LiteRT-LM;
4. passed the phone self-test;
5. completed the Xiaomi 14 sustained validation without crash, ANR, OOM or low-memory kill.

After those gates pass, the guarded migration may call:

```text
ModelManager.removeLegacyE2BIfE4BVerified()
```

The cleanup uses a fixed historical relative folder and canonical-path checks. Generic uninstall refuses unknown model ids rather than guessing a path.

## Speech and voice models

| Model | Type | Backend | Approximate role | Status |
|---|---|---|---|---|
| `sherpa-asr-en` | ASR | CPU | English streaming recognition | integrity metadata present |
| `sherpa-asr-indic` | ASR | CPU | shared Indic Omnilingual recognition | integrity metadata present |
| `sherpa-tts-en` | TTS | CPU | English offline speech | integrity metadata present |
| `sherpa-tts-hin` | TTS | CPU | Hindi offline speech | integrity metadata present |
| `sherpa-tts-ben` | TTS | CPU | Bengali offline speech | retained catalogue component |
| `sherpa-tts-tam` | TTS | CPU | Tamil offline speech | retained catalogue component |
| `sherpa-tts-tel` | TTS | CPU | Telugu offline speech | retained catalogue component |
| `sherpa-tts-kan` | TTS | CPU | Kannada offline speech | retained catalogue component |
| `sherpa-tts-mal` | TTS | CPU | Malayalam offline speech | retained catalogue component |
| `vad` | VAD/KWS support | CPU | wake-listening support | shares verified English ASR bytes where identical |

English and Hindi are the currently exposed voice profiles. Other retained speech artefacts are not automatically production-qualified merely because files exist in the catalogue.

Assamese remains a priority planned pack. It must not be enabled until exact STT and TTS files pass licence review, integrity checks, Android loading, controlled accuracy testing and physical-device qualification.

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
│   │   └── vad/
│   └── languages/
│       ├── en-IN/tts/
│       ├── hi-IN/tts/
│       └── other retained language TTS folders
├── vision/
│   └── blind-aid/
└── staging/
```

Models live under `getExternalFilesDir("models")`, falling back to internal app files when external app-private storage is unavailable. This does not require all-files storage permission.

## Runtime memory policy

- Only one E4B engine may be resident.
- Secure Browser acquires an exclusive lease, unloads the phone planner, and restores it after release.
- Blind Aid may release E4B before sustained camera analysis and reload it after clean shutdown.
- GPU is attempted first; CPU is the supported fallback.
- NPU use must not be claimed unless runtime logs prove an NPU backend was selected.
- The operating context starts at 2,048 tokens. A larger default requires measured memory, latency and thermal evidence.

## Installation and repair

The Model Status screen should show:

- download progress;
- resume state;
- exact integrity verification;
- installed/healthy/verified status;
- active backend after load;
- self-test result;
- a clear failure reason and retry action.

A production build must eventually acquire the same approved bytes through UnoOne-controlled storage and a signed catalogue. The current upstream URL is an engineering acquisition source, not the final production distribution architecture.

## Qualification

Compilation and checksum verification do not prove model quality. Follow [E4B Xiaomi 14 Handoff](E4B_XIAOMI14_HANDOFF.md) and record:

- actual model-load backend;
- load and first-token latency;
- process memory and peak memory;
- heat and battery behaviour;
- tool-name and required-argument accuracy;
- English/Hindi response-language compliance;
- Blind Aid and Secure Browser model transitions;
- sustained 50-task stability;
- crash, ANR, OOM and low-memory scan.
