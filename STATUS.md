# UnoOne V2 Status

**Updated:** 2026-07-21  
**Development branch:** `feat/e4b-agentic-runtime`  
**Draft pull request:** #2  
**Target branch:** `main`  
**Release state:** **Alpha / E4B physical-device qualification pending**

The repository [README](README.md) is the product-level source of truth. This file records only the current evidence and release gates. Historical E2B validation remains historical and must not be represented as E4B evidence.

## Current architecture

| Area | Current branch state | Evidence still required |
|---|---|---|
| Android project | Native Kotlin/Compose, API 28+, 15 modules | latest Android CI and Xiaomi 14 installation |
| Planning brain | Gemma 4 E4B is the sole installable model | physical load, latency, memory, thermal and tool-call qualification |
| Model integrity | exact Android filename, byte size and SHA-256 pinned | verify downloaded phone bytes and successful LiteRT-LM initialization |
| Command routing | deterministic handlers before model inference | physical English/Hindi voice matrix and latency measurements |
| Tool execution | canonical schema, permissions, safety, confirmations and verification | real external-app and accessibility regression matrix |
| Offline speech | English and Hindi exposed; shared Indic components retained | controlled accent, names, numbers, distance and noise benchmarks |
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
| Backend order | GPU, then CPU fallback |
| Default context | 2,048 tokens |
| Maximum supported context | 32,768 tokens |
| Minimum product RAM gate | 8,192 MB |
| Recommended product RAM gate | 12,288 MB |
| Xiaomi 14 qualified | **No — pending** |
| Production approved | **No** |

The Android runtime rejects a wrong filename, wrong byte size, wrong checksum, `.part` file, old E2B file, web-specific artifact, or arbitrary larger `.litertlm` file.

## E2B migration policy

E2B is not an active selectable or downloadable production brain on this branch. Existing installed E2B bytes are preserved temporarily until all of the following succeed on the phone:

1. E4B download completes;
2. exact size and SHA-256 verification passes;
3. LiteRT-LM engine initialization succeeds;
4. the on-device tool-calling self-test passes;
5. the sustained Xiaomi 14 validation shows no crash, ANR, OOM or low-memory kill.

Only then may the guarded `ModelManager.removeLegacyE2BIfE4BVerified()` migration remove the old folder and metadata.

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

### Distribution CI

The latest completed Distribution CI for the E4B branch is green. It covers:

- repository invariants;
- distribution API type checking and policy tests;
- Cloudflare Worker bundling;
- installer PWA type checking, catalogue tests and build;
- signing round trip and tamper rejection.

### Android CI

The current branch must pass:

- repository invariants;
- Page Agent type checking, unit tests, bundle generation and Playwright tests;
- Android lint;
- Android JVM tests;
- debug APK assembly.

Only the latest branch head is authoritative. A green run for an older commit does not qualify a newer commit.

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
- [ ] Exact E4B bytes load successfully on Xiaomi 14.
- [ ] Actual GPU or CPU backend is recorded from logs.
- [ ] E4B phone-tool and Page Agent evaluations meet the release threshold.
- [ ] English and Hindi controlled speech matrix is recorded.
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
