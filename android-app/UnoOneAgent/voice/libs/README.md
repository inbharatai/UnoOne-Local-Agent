# Sherpa-ONNX speech runtime (optional local AAR)

UnoOne's offline speech (STT / TTS / wake-word) runs on **Sherpa-ONNX**. The primary resolution
path is the Maven dependency declared in `../build.gradle.kts`:

```kotlin
implementation("com.github.k2-fsa:sherpa-onnx:v1.13.3")
```

JitPack builds the native Android AAR (JNI `.so` for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`)
from the [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) source on demand.

## Local AAR fallback

If you prefer a pre-built AAR (faster, deterministic, no JitPack build) — this is the path
recommended by the sherpa-onnx project itself for native artifacts:

1. Download the Android archive from the
   [sherpa-onnx releases page](https://github.com/k2-fsa/sherpa-onnx/releases):
   `sherpa-onnx-v1.13.3-android.tar.bz2` (or the matching release for the pinned version).
2. Extract it and locate the pre-built `sherpa-onnx.aar` (or build it from
   `android/SherpaOnnxAar/` with the NDK).
3. Place the `.aar` file in **this directory** (`voice/libs/`).

The `fileTree("libs")` dependency in `../build.gradle.kts` picks it up automatically:

```kotlin
implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
```

Only one source is needed — keep the Maven coordinate **or** the local AAR. Having both is
harmless (Gradle deduplicates), but the AAR takes precedence on the compile classpath.

## Model files (not included)

The AAR provides the native runtime only. The actual model files go in the device's
`Android/data/com.unoone.agent/files/models/`:

- `sherpa-asr-en/` — `encoder.onnx`, `decoder.onnx`, `joiner.onnx`, `tokens.txt` (English streaming zipformer transducer ASR, int8)
- `speech/shared/sherpa-asr-indic/` — the extracted official Omnilingual 300M CTC int8 archive containing `model.int8.onnx` and `tokens.txt` (shared offline ASR for hi/bn/ta/te/kn/ml)
- `sherpa-tts-en/` — `model.onnx`, `tokens.txt`, `espeak-ng-data/` (Coqui VITS TTS, English, espeak frontend)
- `sherpa-tts-hin/` / `sherpa-tts-ben/` / `sherpa-tts-tam/` / `sherpa-tts-tel/` / `sherpa-tts-kan/` / `sherpa-tts-mal/` — `model.onnx`, `tokens.txt` each (MMS VITS TTS, character frontend, no espeak)
- `vad/` — `encoder.onnx`, `decoder.onnx`, `joiner.onnx`, `tokens.txt` (English online transducer KWS / wake-word — always English; no Indic KWS model exists)

Download models from the sherpa-onnx model zoo. The app's **Model Status** screen verifies each
file's SHA-256 against `models_manifest.json` and reports health/missing state.
