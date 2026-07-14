# Phase 6 — Offline Language Packs on Xiaomi 14 (install + functional VERIFIED, P0 fixed)

Date: 2026-07-14 (run completed 19:36 IST)
Device: Xiaomi 14 (houji), SM8650, arm64-v8a, WiFi "Ramborj5g" (unmetered, 866 Mbps)
App: com.unoone.agent v0.4.0-alpha-v2; native runtimes bundled: libonnxruntime.so (25.8MB),
libsherpa-onnx-{c,cxx,jni}.so, libLiteRt.so + liblitertlm_jni.so, assets/espeak-ng-data.zip (9MB).

## 6a. Install + checksum + extraction gate (README Phase C)

Instrumented `LanguagePackInstallTest` drives the app's OWN `ModelInstaller` over real WiFi for
every downloadable baseline pack. Every artifact is created app-owned (the app downloads it), so the
shell-ownership/SELinux gotcha from Phase 5 does NOT apply. `state.verified=true` means `modelHealth`
ran sha256+size on every file and they matched the manifest.

```
Time: 124.768   OK (1 test)   failures=0
<<< en-IN-base       Success "English installed and verified."      healthy/verified=true
<<< hi-IN-standard   Success "Hindi installed and verified."        healthy/verified=true
<<< bn-IN-standard   Success "Bengali installed and verified."      healthy/verified=true
<<< ta-IN-standard   Success "Tamil installed and verified."       healthy/verified=true
<<< te-IN-standard   Success "Telugu installed and verified."       healthy/verified=true
<<< kn-IN-standard   Success "Kannada installed and verified."     healthy/verified=true
<<< ml-IN-standard   Success "Malayalam installed and verified."   healthy/verified=true
<<< as-IN-standard   Failure "Assamese is listed as planned; no qualified downloadable models are configured."
                       state.installed=false  (correctly disabled — NOT silently active)
```

Total downloaded ~987 MB (en ~187MB + whisper 116MB shared across 6 Indic packs + 6×~114MB Indic TTS)
in ~124 s on WiFi. Whisper tar.bz2 archive extracted idempotently (downloaded once, reused by 6 packs).
On-disk: app-owned (u0_a998) TTS model.onnx (~114MB) per language, espeak-ng-data extracted for en,
shared ASR (sherpa-asr-en, sherpa-asr-whisper) + vad all present and sha-verified.

## 6b. P0 DEFECT FOUND — speech packs crash app at boot (Sherpa-ONNX assetManager bug)

After installing the speech packs, the app entered a **boot crash loop** (even on a normal
`am start MainActivity` launch), rendering it unbootable:

```
UnoOne: UnoOne V2 starting
ActivityManager: Background started FGS: Allowed ... VoiceService
UnoOne: SherpaSttEngine: Checking transducer files in .../sherpa-asr-en
ActivityManager: Process com.unoone.agent has died: fg FGS
Bringing down service while still waiting to start foreground: VoiceService
(restart → repeat)
```

`startForeground()` IS called before engine init (VoiceService.kt:90), so this is NOT a startForeground
ANR. The native Sherpa-ONNX lib logged the fatal abort:
```
W sherpa-onnx: You are using an absolute path '.../decoder.onnx', but assetManager is NOT set to null.
W sherpa-onnx: Please set assetManager to null when you load model files from the SD card
F sherpa-onnx: Read binary file: Load '.../decoder.onnx' failed        ← FATAL, aborts process
(see k2-fsa/sherpa-onnx#2562)
```

**Root cause:** models are downloaded to external storage (absolute paths), but all 4 Sherpa-ONNX
construction sites passed a NON-NULL `context.assets` AssetManager. Sherpa then resolves the absolute
path through the APK AssetManager, fails, and fatally aborts the process (no Java exception, no
tombstone — a Sherpa `F`-level abort). Affected sites:
- `voice/.../stt/KeywordSpotter.kt:69`   `KeywordSpotter(context.assets, config)`
- `voice/.../stt/SherpaSttEngine.kt:121` `OnlineRecognizer(context.assets, config)` (transducer)
- `voice/.../stt/SherpaSttEngine.kt:153` `OfflineRecognizer(context.assets, config)` (whisper)
- `voice/.../tts/SherpaTtsEngine.kt:63` `OfflineTts(context.assets, config)`

**FIX (applied on validation branch, NOT committed):** pass `null` instead of `context.assets` at
all 4 sites. Sherpa's native API has both `newFromAsset(AssetManager, config)` and
`newFromFile(config)`; a null assetManager selects the file-based path, which reads absolute
filesystem paths directly — exactly correct for external-storage models. (Binding constructor
signature is `AssetManager?` nullable; verified via javap on sherpa-onnx-v1.13.3-runtime.jar.)

Build + voice/app unit tests pass after the fix (EXIT=0); APK 372,355,017 B (identical size — no
regression). After reinstall + re-grant perms, boot is STABLE with speech packs installed:
```
SherpaSttEngine: Online STT initialized (streaming transducer, 4 threads)
VoiceModule: Using Sherpa-ONNX for STT (offline, TRANSDUCER/en)
SherpaTtsEngine: Offline TTS initialized (espeak frontend, 2 threads)
VoiceModule: Using Sherpa-ONNX for TTS (offline)
```
No more "has died" / boot loop.

**Secondary P2 (not a crash):** `MIUIScout ANR` warning (~5.8s) — `VoiceService.onCreate` runs the
Sherpa STT+TTS+KWS init and Gemma load synchronously on the MAIN thread (lines 97-100, after
startForeground). Process survives but the main thread blocks ~6s (potential ANR). Recommend moving
engine init off the main thread (a coroutine/HandlerThread) before production.

## 6c. Functional speech gate — STT/TTS actually RUN (post-fix)

Instrumented `SpeechEngineFunctionalTest`: `OK (1 test)`, `ttsFailures=0 sttFailures=0`, Time 31.1s.
`speak()` returns Success only when the VITS model produces a non-empty PCM buffer, so Success proves
real ONNX inference end-to-end (model load → inference → PCM samples).

```
TTS en  speak=Success  Generated 91575 samples @ 22050Hz  (espeak frontend)
TTS hi  speak=Success  Generated 50504 samples @ 16000Hz  (MMS/character)
TTS bn  speak=Success  Generated 51512 samples @ 16000Hz
TTS ta  speak=Success  Generated 49173 samples @ 16000Hz
TTS te  speak=Success  Generated 51601 samples @ 16000Hz
TTS kn  speak=Success  Generated 71449 samples @ 16000Hz
TTS ml  speak=Success  Generated 43769 samples @ 16000Hz
STT en  transducer init=Success  (online recognizer loaded)
STT    whisper init=Success      (offline whisper recognizer loaded, lang=en)
```

**Open (manual gate):** actual STT transcription requires microphone PCM capture, which is a
physical/manual gate (no automated audio fixture). STT engine load is proven; recognition-accuracy
on real speech is NOT asserted here and remains a manual/visual gate per README Phase C.

## Verdict — README Phase C

- [x] Validate every baseline checksum + extraction on clean device — 7/7 packs sha-verified,
     archives extracted (whisper), app-owned files.
- [x] Assamese correctly disabled (downloadable=false → install refuses, not silently active).
- [x] TTS runs for en/hi/bn/ta/te/kn/ml (real PCM generated on-device).
- [x] STT engines load (English transducer + Whisper).
- [~] STT recognition accuracy on real mic audio — manual/visual gate (not automated).
- [x] P0 boot-crash defect FOUND + FIXED + re-verified (Sherpa assetManager=null).
- [~] P2 main-thread-blocking ANR warning on VoiceService boot — noted, recommend off-main init.

**Phase 6 PASSED with device evidence** (install + functional), and one P0 defect fixed (4 files,
validation branch, not committed). Evidence files: phase6-install-logcat.log,
phase6b-speech-logcat-postfix.log, phase6b-speech-instrument-postfix.log.