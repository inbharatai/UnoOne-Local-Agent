# UnoOne V2 Status

> **Historical V2/E4B snapshot — 2026-07-22. Banner added 2026-10-07.** The branch evidence and present-tense policies below are frozen historical context, not current main policy. E4B-only selection and E2B deletion instructions are superseded; do not use them to remove models or data.
>
> Current main entry points: [README](README.md) → [Floating voice guide](docs/FLOATING_VOICE_GUIDE.md) → [0.8 artifact/build receipts](docs/evidence/floating-voice-delivery/results.json). Existing 0.8.0-alpha-voice / versionCode 8 host gates record **1006 JVM tests passed**, lint and both APK assemblies; physical qualification remains **PENDING**. These are existing main receipts, not a new cleanup validation run.

**Updated:** 2026-07-22

**Development branch:** `codex/e4b-runtime-hardening` (child of `feat/e4b-agentic-runtime`)

**Existing parent draft:** #2

**E4B hardening draft:** #3

**Child target:** `feat/e4b-agentic-runtime`
**Release state:** **Alpha / E4B CPU accuracy passed; sustained release qualification pending**

The repository [README](README.md) is the product-level source of truth. This file records only the current evidence and release gates. Historical E2B validation remains historical and must not be represented as E4B evidence.

## Current architecture

| Area | Current branch state | Evidence still required |
|---|---|---|
| Android project | Native Kotlin/Compose, API 28+, 15 modules; Xiaomi install passed | latest final-head Android CI |
| Planning brain | exact Gemma 4 E4B loads on Xiaomi CPU; 43/43 fixed cases passed | strict in-app self-test, latency policy, thermal and second-device qualification |
| Model integrity | exact phone filename, byte size and SHA-256 verified | production acquisition/mirror release evidence |
| Command routing | deterministic handlers before model inference; sequential English/Hindi/Hinglish planner probes passed | real-room acoustic wake/recognition matrix and latency measurements |
| Tool execution | canonical schema, permissions, safety, confirmations and verification | real external-app and accessibility regression matrix |
| Offline speech | real English/Hindi PCM synthesis, ASR initialization and Hindi round trip passed | controlled accent, names, numbers, distance and noise benchmarks |
| Assamese | priority planned language | exact STT/TTS artifacts, licence, integrity and device qualification |
| Blind Aid | independent CameraX/object-detection flow | E4B unload/reload, camera stability and sustained-use test |
| Secure Browser | local Page Agent with exclusive Gemma lease | E4B browser-plan accuracy and one-engine-at-a-time proof |
| Distribution | signed-catalogue/API/PWA workflows implemented | production storage, protected signing keys and release artefacts |

## Gemma 4 E4B contract

| Field | Value |
|---|---|
| Manifest id | `gemma-4-e4b` |
| Model folder | `brain/gemma-4-e4b` |
| Android file | `gemma-4-E4B-it.litertlm` |
| Exact size | `3,659,530,240` bytes |
| SHA-256 | `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0` |
| Runtime | LiteRT-LM `0.13.1` |
| Production `AUTO` backend | CPU (GPU is developer-qualification only) |
| Enforced engine context | 2,048 tokens |
| Artifact capability ceiling | 32,768 tokens; not configured on phone |
| Minimum product RAM gate | 8,192 MB |
| Recommended product RAM gate | 12,288 MB |
| Xiaomi 14 qualified | **Partial — CPU load and planner accuracy passed; sustained/release gates pending** |
| Production approved | **No** |

The Android runtime rejects a wrong filename, wrong byte size, wrong checksum, `.part` file, old E2B file, web-specific artifact, or arbitrary larger `.litertlm` file.

## E2B migration policy

E2B is not an active selectable or downloadable production brain on this branch. Existing installed E2B bytes are preserved temporarily until all of the following succeed on the phone:

1. E4B download completes;
2. exact size and SHA-256 verification passes;
3. LiteRT-LM engine initialization succeeds;
4. the on-device tool-calling self-test passes;
5. the sustained Xiaomi 14 validation shows no crash, ANR, OOM or low-memory kill.

Only then, after strict evaluation and sustained stability evidence plus explicit user approval, may `ModelManager.removeLegacyE2BIfQualified(true)` remove the fixed legacy folder and matching metadata. The older integrity-only method always refuses deletion.

## Agent accuracy policy

- Common commands bypass Gemma when a deterministic route exists.
- Gemma proposes one canonical tool per turn.
- Unknown tools and malformed required arguments are rejected before execution.
- Missing recipients, addresses, phone numbers, dates or times must trigger clarification rather than invention.
- WhatsApp and email actions create reviewable drafts; they do not silently press Send.
- A success response requires native verification evidence.
- Multi-step work is bounded and re-enters safety and verification for every step.
- The phone agent and Secure Browser must never hold two E4B engines simultaneously.

## Automated gates

### Automated evidence

Evidence must be recorded for the exact child-branch head. Prior parent-branch CI remains useful history but does not qualify new changes. Required gates cover:

- repository invariants;
- distribution API type checking and policy tests;
- Cloudflare Worker bundling;
- installer PWA type checking, catalogue tests and build;
- signing round trip and tamper rejection.
- Page Agent type checking, unit tests, bundle generation and Playwright tests;
- Android lint;
- Android JVM tests;
- debug APK assembly.

Only the latest branch head is authoritative. Local results and GitHub Actions results are reported separately.

Local child-branch evidence on 2026-07-22:

- repository invariants: passed;
- Page Agent: type check, 8 unit tests, bundle, and 5 Playwright scenarios passed;
- distribution API: type check and 3 tests passed;
- installer PWA: type check, 8 tests, and production build passed;
- Android: lint found no new issues, JVM tests passed, and app plus instrumentation APKs assembled;
- connected Xiaomi 14/API 35: exact E4B bytes are present and hash-verified; LiteRT-LM CPU load and
  direct basic probes passed;
- post-fix E4B phone-planner evaluation: `43/43` fully correct and `43/43` tool-match;
- physical voice/master-disable/Blind Aid/OCR/camera/document/Page Agent matrix: `OK (27 tests)`;
- physical storage/memory/Skills/safety/language-pack matrix: `OK (23 tests)`;
- real English/Hindi TTS generated PCM, English/Indic STT initialized, and Hindi TTS→STT returned
  a non-empty Devanagari transcript;
- continuous CPU evaluation completed without an UnoOne exception or ANR, but warmed PSS was about
  `3.73–3.82 GB` with about `0.77 GB` swap and CPU thermal status reached 3 near `95 °C`.

## Xiaomi 14 gate

Follow [docs/E4B_XIAOMI14_HANDOFF.md](docs/E4B_XIAOMI14_HANDOFF.md). Record:

- exact phone/HyperOS build and physical memory;
- E4B download and checksum duration;
- actual LiteRT-LM backend;
- model load and first-token latency;
- process memory, heat and battery behaviour;
- deterministic English/Hindi command results;
- E4B tool and argument accuracy;
- Blind Aid unload/reload behaviour;
- Secure Browser exclusive lease behaviour;
- a sustained 50-task run;
- crash, ANR, native-signal and low-memory scan.

## Release blockers

- [ ] Latest Android CI is green on the final branch head.
- [x] Latest Distribution CI is green on the current E4B branch head at the time recorded.
- [x] Exact E4B bytes load successfully on Xiaomi 14.
- [x] Actual CPU backend is recorded from logs.
- [x] E4B phone-tool evaluation meets the fixed-set threshold (43/43).
- [ ] E4B-backed Page Agent planning meets its release threshold and proves exclusive leasing.
- [x] English and Hindi engine/round-trip speech matrix is recorded.
- [ ] Real-room wake-word, accent, distance, names and numbers speech matrix is recorded.
- [ ] Blind Aid and Secure Browser transition tests pass without duplicate engines or services.
- [ ] Sustained memory, thermal, battery and 50-task test passes.
- [ ] Legacy E2B cleanup is verified after E4B success.
- [ ] Secondary Android device passes the same core gates.
- [ ] Model and speech licences/notices are reviewed for redistribution.
- [ ] Production model mirror, signed catalogues and protected release keys are configured.
- [ ] Signed release APK, SBOM, security review and rollback process are complete.

## Do not claim yet

Until the blockers above are complete, do not claim that UnoOne is:

- production-ready;
- physically qualified with E4B;
- running on the Snapdragon NPU;
- universally accurate at autonomous tool use;
- accurate in Assamese or any other unbenchmarked language;
- thermally stable under sustained E4B/Page Agent use;
- able to send messages, perform payments, enter credentials, solve CAPTCHA or accept legal declarations autonomously;
- available as a final public installer.
