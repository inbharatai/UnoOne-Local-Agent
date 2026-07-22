# UnoOne Architecture

UnoOne is a 15-module Kotlin/Jetpack Compose Android application. Its current migration branch has one installable planning brain: Gemma 4 E4B through LiteRT-LM 0.13.1. Physical E4B qualification is still pending; older E2B measurements are historical evidence only.

## Request pipeline

```text
voice / text / accessibility input
  → language and command normalisation
  → deterministic parser (common phone and Blind Aid commands)
  → E4B proposal only when ambiguity or bounded planning needs it
  → canonical tool and required-argument validation
  → permissions, risk and confirmation policy
  → deterministic Android/Page Agent executor
  → outcome verification
  → offline spoken result
```

The model never invokes Android APIs directly. WhatsApp and email remain reviewable drafts; Calendar insertion stops before Save. Credentials, OTPs, payments, CAPTCHA, legal acceptance and unapproved final submission remain blocked or require native takeover.

## E4B runtime ownership

`E4bRuntimeCoordinator` serializes verification, load, inference, cancellation, phone/browser transition, unload and disable operations with one process-wide mutex and visible states. `SecureBrowserModelLease` retains its exclusive owner until the browser engine is confirmed closed and any phone-engine restoration completes. Blind Aid cancels and unloads model work before starting CameraX.

Both phone and Page Agent engines receive `EngineConfig.maxNumTokens = 2048`. Planning conversations exist only for bounded ReAct work; chat and safety-judge conversations are lazy and close after their operation. Callback inference uses LiteRT-LM `cancelProcess()` on timeout, stop, disable or mode change, waits for the native callback, and refuses unsafe close/restoration when native work has not stopped. Production `AUTO` uses CPU; GPU is an explicit developer qualification choice, not a load-success fallback.

## Model integrity and installation

The manifest pins the exact Android E4B artifact, immutable upstream revision, byte size and SHA-256. The installer performs storage preflight, trusted redirect handling, resumable Range validation, fsync, exact size/hash verification and atomic activation. A foreground WorkManager job owns downloads, defaults to unmetered networking and survives Activity recreation. A metadata-bound verification record prevents repeated 3.66 GB hashes without weakening invalidation; explicit Verify always hashes the full file.

## Voice and Blind Aid

Offline wake matching accepts conservative English, Hindi and Hinglish variants and permits wake plus command in one breath. There is one command callback route. Speech detection adapts to per-session ambient energy with bounded thresholds. The KWS support folder is labelled `sherpa-kws-en`; it is not represented as a VAD model.

Blind Aid is independent of the planning-model folder. CameraX and the bundled detector produce live object labels and concise narration; start/stop clears session detections, narration and camera ownership so stale descriptions cannot continue. The bundled detector recognizes its trained label set, not arbitrary product brands.

## Modules

```text
:app                  UI, orchestration, settings, foreground model worker
:core                 tool schemas, result types, safety primitives, eval, E4B coordinator/budgets
:modelmanager         manifest, installer, verification cache, cleanup qualification
:localbrain           deterministic parser, LiteRT-LM planners, prompts and tool decoding
:voice                offline STT/TTS/KWS, wake routing and adaptive speech detection
:phonecontrol         intents, Calendar, OCR and Blind Aid
:accessibilitycontrol screen reading and bounded UI actions
:securebrowser        guarded WebView/Page Agent bridge and browser policy
:storage              Room persistence
:languagepacks        speech-pack catalogue and health
:skills               built-in/user routines
:memory                local preferences, context and corrections
:agentrouter          plugin/tool routing
:safetyguard          risk classification and confirmation policy
:observability        privacy-safe diagnostics
```

## Evidence boundaries

JVM/CI tests prove deterministic policies, parsing, integrity and build behavior. They do not prove E4B accuracy, backend, latency, memory, thermal stability, speech accuracy or camera behavior on a phone. Those claims require the exact model hash and the physical matrix in [E4B_XIAOMI14_HANDOFF.md](E4B_XIAOMI14_HANDOFF.md).
