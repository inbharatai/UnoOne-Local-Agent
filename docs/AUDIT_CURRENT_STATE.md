# UnoOne V2 Migration Audit

**Date:** 2026-07-12  
**Working branch:** `feature/unoone-v2-gemma4-pageagent`  
**Source branch:** `feature/gemma-4-e2b-brain`  
**Recovery branch:** `archive/main-pre-unoone-v2-2026-07`

## Fixed product decisions

UnoOne V2 will use:

- Gemma 4 E2B as the only local planning brain
- LiteRT-LM for Android inference
- Sherpa-ONNX-based offline speech with downloadable language packs
- Alibaba PageAgent inside an UnoOne-controlled Secure Browser for DOM-based web automation
- Android Accessibility and OCR for native apps and external-browser fallback
- Existing Blind Aid capabilities independent of the LLM
- A website/PWA as the installer and release portal, not as a replacement for the native Android runtime

Gemma 3n is removed from the product direction. It will not remain as a fallback, selectable profile, model manifest entry or user-facing option.

## Existing functions that must be preserved

### Agent and application

- Compose application shell
- Floating assistant overlay
- Agent flow timeline
- Rule-based fallback parser
- Notes, skills, memory and audit storage
- Compound commands with per-step safety
- Background voice routing
- Model health, repair and self-test surfaces
- Diagnostics and memory-pressure recovery

### Safety

- Canonical tool registry
- Unknown-tool rejection
- Required-argument validation
- Permission gate
- DIRECT / CONFIRM / STRONG_CONFIRM / BLOCK tiers
- Per-step validation for skills and compound commands
- No silent payment, credential access, app installation or message sending

### Voice

- Sherpa-ONNX STT/TTS runtime
- Push-to-talk and background voice service
- Wake-word path
- STT confidence and retry
- Explicit offline/system-fallback status
- Voice self-test screen

### Native control

- Open app, URL, Chrome, camera, dialer and calendar
- Click, type, fill, scroll, swipe and long press
- Back, home, notifications and recents
- Accessibility screen reading
- OCR screen reading

### Blind Aid

- CameraX preview
- Object detection
- Haptics
- Proximity tones
- Spoken guidance
- Bounding-box overlay
- Background activation
- Immediate deactivation

Blind Aid must continue to function when Gemma is missing, unloaded or recovering from memory pressure.

## Current migration hotspots

1. `BrainModelRegistry` was dual-profile and defaulted to Gemma 3n.
2. `BrainSelection` persisted a user-selectable profile.
3. `PromptBuilder` carried separate Gemma 3n and Gemma 4 instructions.
4. `models_manifest.json` contains both LLM entries and direct third-party URLs.
5. Model Status UI presents a brain selector instead of a single brain health card.
6. Tests and documentation describe Gemma 3n as device-verified without a populated physical-device matrix.
7. The optional Blind Aid YOLO path is nested under `gemma-local`, coupling vision to the obsolete brain folder.
8. Voice languages are model-centric and hard-coded rather than represented as signed language packs.
9. Manifest signature verification is scaffolded but the production key and enforcement policy are not active.
10. Browser control is generic Accessibility/OCR control; Alibaba PageAgent Secure Browser integration does not yet exist.

## Migration status

### Completed on this branch

- Created a recovery branch for the previous `main`.
- Created the V2 branch from the existing Gemma 4 branch.
- Made Gemma 4 E2B the sole registered brain.
- Removed runtime profile selection while retaining compatibility method signatures.
- Removed the legacy Gemma 3n prompt path.
- Removed unverified claims that Gemma 4 is faster or production-ready.
- Raised the provisional memory gate to 6 GB minimum / 8 GB recommended pending measurements.

### Next

1. Remove Gemma 3n from the model manifest and documentation.
2. Normalize model storage under `brain/`, `speech/`, `vision/`, `ocr/` and `staging/`.
3. Convert the brain UI to one install/repair/self-test card.
4. Update tests for the single-brain invariant.
5. Add the language-pack catalogue and manager contracts.
6. Add the Secure Browser and PageAgent runtime bridge.
7. Add browser safety, user takeover and form-submission boundaries.
8. Add Playwright only for E2E testing of the web runtime.
9. Add model acquisition, hashing, signing and self-hosted distribution scripts.
10. Rewrite README, status, security, privacy, verification and Codex handoff documentation.

## Honesty boundary

Repository changes can prepare, build and statically test the architecture. They cannot honestly prove the following without the exact artifacts and a physical device:

- Gemma 4 LiteRT-LM load success
- Real tool-call accuracy
- RAM, thermal, battery or latency performance
- Sherpa native speech accuracy
- Android Accessibility behaviour across OEMs
- CameraX / Blind Aid accuracy
- WebView bridge behaviour on a real phone
- Final AI4Bharat model selection or ONNX conversion quality

These remain explicit release gates rather than assumed capabilities.
