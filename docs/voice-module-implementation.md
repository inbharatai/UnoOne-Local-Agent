# Voice Module Implementation Plan

## Overview

The `voice` module handles all audio I/O: microphone recording, Voice Activity Detection (VAD), Speech-to-Text (STT) via Sherpa-ONNX, and Text-to-Speech (TTS) via Sherpa-ONNX / Piper / Kokoro.

## Architecture

```
┌─────────────────────────────────────────┐
│            VoiceModule.kt               │
│  (public API — start, stop, speak)      │
└─────────────────────────────────────────┘
                   │
    ┌──────────────┼──────────────┐
    ▼              ▼              ▼
┌────────┐   ┌──────────┐   ┌──────────┐
│ Audio  │   │ Sherpa   │   │ Sherpa   │
│Record  │   │ STT      │   │ TTS      │
│(PCM)   │   │(ONNX)    │   │(ONNX)    │
└────────┘   └──────────┘   └──────────┘
    │              │              │
    ▼              ▼              ▼
┌────────┐   ┌──────────┐   ┌──────────┐
│  VAD   │   │ Model    │   │ Audio    │
│(silero)│   │ Files    │   │Track     │
└────────┘   └──────────┘   └──────────┘
```

## Dependencies

```kotlin
// In voice/build.gradle.kts
implementation("com.github.k2-fsa:sherpa-onnx:v1.13.3")  // Kotlin bindings (com.k2fsa.sherpa.onnx.*)
```

## Step 1: Audio Recording

**File:** `voice/src/main/java/com/unoone/agent/voice/recorder/AudioRecorder.kt`

```kotlin
class AudioRecorder {
    private var audioRecord: AudioRecord? = null
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    fun start(): Result<Unit> {
        // Request RECORD_AUDIO permission first
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
        audioRecord?.startRecording()
        return Result.Success(Unit)
    }

    fun stop(): ByteArray {
        audioRecord?.stop()
        audioRecord?.release()
        // Return accumulated PCM bytes
        return byteArrayOf()
    }
}
```

**Testing:**
- Tap mic button → visual waveform animates.
- Speak → PCM buffer non-empty.
- Stop → buffer size > 0.

## Step 2: Sherpa-ONNX STT Integration

**File:** `voice/src/main/java/com/unoone/agent/voice/stt/SherpaSttEngine.kt`

```kotlin
class SherpaSttEngine(modelPath: String) {
    private val recognizer: SherpaOnnxRecognizer

    init {
        val config = OnlineRecognizerConfig(
            featConfig = FeatureExtractorConfig(sampleRate = 16000f, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(encoder = "", decoder = "", joiner = ""),
                tokens = "$modelPath/tokens.txt",
                numThreads = 4
            ),
            decodingMethod = "greedy_search"
        )
        recognizer = OnlineRecognizer(config)
    }

    fun transcribe(pcmBytes: ByteArray): String {
        val stream = recognizer.createStream()
        stream.acceptWaveform(pcmBytes, sampleRate = 16000)
        recognizer.decode(stream)
        return recognizer.getResult(stream).text
    }
}
```

**Model files needed (English — `sherpa-asr-en/`):**
- `sherpa-asr-en/tokens.txt`
- `sherpa-asr-en/encoder.onnx`
- `sherpa-asr-en/decoder.onnx`
- `sherpa-asr-en/joiner.onnx`

> Indic languages (hi/bn/ta/te/kn/ml) use `sherpa-asr-whisper/` instead — a shared multilingual whisper-tiny int8 model extracted to `sherpa-asr-whisper/sherpa-onnx-whisper-tiny/tiny-{encoder,decoder}.int8.onnx` + `tiny-tokens.txt`, decoded one-shot via Sherpa's offline-Whisper path with the `language` field selecting the language. See `SherpaSttEngine.kt` (`SttMode.WHISPER`).

**Testing:**
- Push model via ADB.
- Speak: "Create a note to call Ramesh tomorrow."
- Expected transcription appears within 1 second.

## Step 3: Sherpa-ONNX TTS Integration

**File:** `voice/src/main/java/com/unoone/agent/voice/tts/SherpaTtsEngine.kt`

```kotlin
class SherpaTtsEngine(modelPath: String) {
    private val tts: OfflineTts

    init {
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(model = "$modelPath/model.onnx", lexicon = "", dataDir = "", dictDir = ""),
                numThreads = 4
            ),
            ruleFsts = "",
            maxNumSentences = 1
        )
        tts = OfflineTts(config)
    }

    fun synthesize(text: String): FloatArray {
        val audio = tts.generate(text, sid = 0, speed = 1.0f)
        return audio.samples
    }
}
```

**Playback:**
- Convert FloatArray to PCM 16-bit.
- Write to `AudioTrack` for playback.

**Model files needed (English — `sherpa-tts-en/`):**
- `sherpa-tts-en/model.onnx`
- `sherpa-tts-en/tokens.txt`
- `sherpa-tts-en/espeak-ng-data/` (phoneme table, bundled as an app asset)

> Indic languages use `sherpa-tts-hin/` (and `-ben`/`-tam`/`-tel`/`-kan`/`-mal`) — MMS VITS `model.onnx` + `tokens.txt` each, character frontend (no espeak). `SherpaTtsEngine` auto-detects the frontend from the presence of `espeak-ng-data/`.

**Testing:**
- Tap TTS test in Settings.
- Expected: "UnoOne is ready." plays audibly.

## Step 4: VAD Integration

**File:** `voice/src/main/java/com/unoone/agent/voice/vad/SileroVad.kt`

Use built-in sherpa-onnx VAD or Silero VAD ONNX model.

- Detect voice start → begin buffering.
- Detect voice end → stop buffering and run STT.
- Prevents half-second of silence from being transcribed.

## Step 5: Permission Handling

**Flow:**
1. User taps mic.
2. Check `ContextCompat.checkSelfPermission(RECORD_AUDIO)`.
3. If denied → show rationale dialog.
4. Request permission launcher.
5. If granted → start `AudioRecorder`.
6. If permanently denied → deep-link to App Settings.

## Acceptance Criteria

- [ ] Audio records at 16kHz mono PCM.
- [ ] STT returns transcription within 1s for short phrases.
- [ ] TTS synthesizes and plays audio within 500ms.
- [ ] VAD trims silence from start/end.
- [ ] All errors show user-readable messages.
- [ ] Works in airplane mode after models are installed.
