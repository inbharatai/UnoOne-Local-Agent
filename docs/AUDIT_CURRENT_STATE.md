# UnoOne Current-State Audit

**Audit date:** 2026-07-21  
**Development branch:** `feat/e4b-agentic-runtime`  
**Draft pull request:** #2  
**Release state:** Alpha; E4B physical-device qualification pending

This audit records the current design decisions and remaining risks. Historical E2B test records remain valid only for the revisions and model that were actually tested.

## Fixed product decisions

UnoOne uses:

- Gemma 4 E4B as the sole installable local planning model;
- LiteRT-LM for Android inference;
- deterministic routing for common Android commands before model inference;
- manual model tool calling with one proposed canonical tool per turn;
- native permission, safety, confirmation, execution and verification stages;
- Sherpa-ONNX offline speech with English and Hindi currently exposed;
- Android Accessibility and OCR for native screen understanding and actions;
- CameraX/MediaPipe Blind Aid independent of the language model;
- a guarded WebView Page Agent using the same exclusive E4B artifact;
- an installer/distribution portal for release delivery, not as a replacement for the native app.

E2B is not an active downloadable or selectable fallback. Existing E2B phone bytes are temporary migration data until E4B passes physical verification.

## Preserved capabilities

### Agent and application

- Compose application shell;
- floating assistant overlay;
- agent activity timeline;
- deterministic parser;
- notes, Skills, memory and audit storage;
- compound and bounded model-planned tasks;
- background voice routing;
- model health, repair and self-test surfaces;
- diagnostics, master disable and memory-pressure recovery.

### Safety

- canonical tool registry;
- unknown-tool rejection;
- required-argument and runtime-type validation;
- contextual Android permission gates;
- DIRECT / CONFIRM / STRONG_CONFIRM / BLOCK policy;
- validation for every Skill and multi-step action;
- no silent payment, credential access, app installation or external message sending;
- post-action verification before success is announced.

### Voice

- Sherpa-ONNX STT/TTS;
- foreground and background voice services;
- wake phrase and one-breath command flow;
- single microphone ownership;
- STT confidence and retry;
- explicit language selection and offline status;
- developer diagnostics and voice self-test.

### Native control

- open app, URL, Chrome, camera, dialer and Calendar;
- reviewable email, WhatsApp and Calendar hand-offs;
- click, type, fill, scroll, swipe and long press;
- Back, Home, notifications and recents;
- accessibility screen reading;
- screenshot/OCR fallback;
- execution verification where technically available.

### Blind Aid

- CameraX preview and analysis;
- offline object detection;
- haptics and proximity tones;
- English/Hindi spoken guidance;
- bounding-box overlay;
- immediate stop and stale-state cleanup;
- operation while Gemma is missing or unloaded.

### Secure Browser

- Page Agent on an UnoOne-controlled WebView;
- exclusive model lease;
- session/nonce/origin/main-frame bridge checks;
- native action authorisation;
- no arbitrary JavaScript tool;
- payment blocks and credential/CAPTCHA/legal takeover in Standard mode;
- bounded action loop and visible-result verification.

## Current E4B contract

| Field | Value |
|---|---|
| Id | `gemma-4-e4b` |
| Folder | `brain/gemma-4-e4b` |
| File | `gemma-4-E4B-it.litertlm` |
| Exact size | `3,659,530,240` bytes |
| SHA-256 | `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0` |
| Runtime | LiteRT-LM `0.13.1` |
| Backend order | GPU then CPU |
| Default / maximum context | 2,048 / 32,768 tokens |
| Minimum / recommended RAM gate | 8,192 / 12,288 MB |
| Device-qualified | no |

## E4B branch changes completed

- E4B is the only active model descriptor.
- Exact artifact metadata is CI-enforced.
- Android rejects wrong, partial, old, web-specific or arbitrary model files.
- Startup, recovery, model status and Secure Browser target E4B.
- Large integrity work is moved off known UI-thread call sites.
- Legacy E2B cleanup is guarded by E4B verification.
- Prompt and tool descriptions forbid invented recipients, package names, dates, times and success.
- Page Agent identifies the E4B model.
- README, status, model and distribution documentation are aligned.
- A Xiaomi 14 validation handoff is committed.

## Remaining hotspots

1. The final Android CI head must be green after all cleanup commits.
2. A temporary deprecated registry alias still maps old `GEMMA_4_E2B` source references to E4B; remove it after every active reference is converted.
3. `GemmaPlanner` currently creates planning, safety and chat conversations at load time. Device measurement must determine whether auxiliary conversations should become lazy or short-lived to reduce KV-cache pressure.
4. Exact SHA verification of a 3.66 GB file is intentionally strict but costly. Future optimisation may use a securely persisted verified-generation marker, without weakening first-install or post-update verification.
5. Contact-name resolution and direct Calendar-provider creation are not production-qualified; the current external-app workflows remain reviewable and must not claim autonomous completion.
6. Assamese remains planned, not active.
7. GPU/NPU acceleration cannot be claimed until physical logs identify the backend.
8. Distribution still requires UnoOne-controlled production storage, protected signing keys and signed approved catalogues.

## Physical evidence required

Follow [E4B_XIAOMI14_HANDOFF.md](E4B_XIAOMI14_HANDOFF.md) and collect:

- exact device and HyperOS build;
- physical memory and available storage;
- E4B download, checksum and load times;
- actual backend and fallback reason;
- first-token and total inference latency;
- process and peak memory;
- temperature and battery change;
- deterministic English/Hindi command matrix;
- exact tool-name and argument evaluation;
- external-app verification;
- Blind Aid and Secure Browser model transitions;
- background/foreground, lock/unlock and restart behaviour;
- sustained 50-task results;
- crash, ANR, native signal, OOM and low-memory scan.

## Honesty boundary

Source changes and CI can prove architecture consistency, tests and build output. They cannot prove without the physical device:

- E4B load success;
- real tool-call accuracy;
- actual GPU or NPU use;
- RAM, heat, battery or latency performance;
- speech accuracy and audible quality;
- stable HyperOS background voice and Accessibility behaviour;
- Blind Aid real-world accuracy;
- production readiness.

These remain explicit release gates.
