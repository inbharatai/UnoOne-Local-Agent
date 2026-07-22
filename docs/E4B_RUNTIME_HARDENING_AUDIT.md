# E4B Runtime Hardening Audit

**Audit date:** 2026-07-22

**Source:** `feat/e4b-agentic-runtime` at `6e5f8a7e931a29ecc6b0e6d691b21bb4bb8776e1`

**Hardening branch:** `codex/e4b-runtime-hardening`

## Findings and implemented controls

1. **Native lifecycle race — critical.** Phone planning, self-test, browser leasing, Blind Aid, memory pressure and disable could overlap, and browser ownership could be released before phone restoration. A process-wide E4B coordinator now serializes native operations. Browser ownership remains held through confirmed close and restoration; if cancellation is not acknowledged, restoration is refused and the exclusive owner remains held.
2. **Kotlin timeout was not native cancellation — critical.** LiteRT-LM 0.13.1 exposes callback inference and `Conversation.cancelProcess()`. Both planners now use the callback API, request native cancellation for timeout/stop/disable/mode changes, await callback completion, and refuse concurrent close while native work remains active.
3. **2,048-token budget was declarative only — high.** Both engine constructors now receive `EngineConfig.maxNumTokens = 2048`. Phone output is capped at 256 decoded tokens and chat/Page Agent output at 384 using runtime benchmark decode counts plus native cancellation. Page prompts retain bounded per-step compaction.
4. **Three eager KV caches — high.** Engine load no longer creates planning, judge and chat conversations. Planning is recreated for each initial bounded flow; judge/chat are lazy and close after their operation.
5. **Repeated full-artifact hashing — high.** A locally serialized verification record is bound to model id, expected hash/size, canonical path, actual size, timestamp, manifest/model version and result time. Metadata or manifest drift invalidates it. The installer seeds the record from its mandatory activation hash; explicit Verify forces a full hash.
6. **Mutable acquisition and fragile download — high.** E4B uses immutable upstream revision `28299f30ee4d43294517a4ac93abd6163412f07f`. Download now performs free-space preflight, trusted manual redirects, Range preservation, exact `Content-Range` validation, oversized-part rejection, fsync and atomic activation. It runs in foreground WorkManager, defaults to unmetered networking, supports explicit metered approval and preserves ordinary interrupted partial data.
7. **Self-test false pass and weak scoring — high.** Self-test now requires three exact read-only outcomes: exact summary tool/text, exact WhatsApp app proposal, and recipient clarification without invented email. Exact-value eval fields no longer use substring scoring. The prompt set covers English, Hindi/Hinglish, accessibility, Blind Aid, drafting, Calendar, ambiguity, malformed input, sensitive data and injection cases.
8. **Backend selected by load success — high.** Production `AUTO` now uses CPU pending evidence. CPU and GPU are explicit developer choices and the instrumentation harness runs the same fixed dataset for each. No GPU or NPU claim is made without device evidence.
9. **Integrity-only legacy deletion — high.** The old API refuses deletion. Cleanup requires an exact-hash qualification record, successful load, strict self-test, evaluation, sustained run, recorded backend/device/build, zero crash/ANR/OOM and explicit user approval. Deletion is restricted to the canonical legacy folder and verifies completion.
10. **Duplicate voice routes and fixed energy threshold — medium.** The duplicate callback path was removed. Speech detection uses bounded ambient adaptation; error/empty recognition speaks an English/Hindi retry cue and returns to wake listening. The ASR/KWS alias is labelled KWS, not VAD.
11. **Stale active documentation — medium.** Current architecture, status and model docs now describe E4B, enforced limits, conservative backend policy and pending physical qualification. Dated historical E2B evidence remains labelled historical.

## Model contract

| Field | Exact value |
|---|---|
| Id | `gemma-4-e4b` |
| Folder | `brain/gemma-4-e4b` |
| File | `gemma-4-E4B-it.litertlm` |
| Bytes | `3,659,530,240` |
| SHA-256 | `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0` |
| Revision | `28299f30ee4d43294517a4ac93abd6163412f07f` |
| Runtime | LiteRT-LM `0.13.1` |
| Engine context | `2,048` tokens |

## Evidence boundary

Automated test results belong in the child pull request for its exact commit. On the connected Xiaomi 14 (model `23127PN0CG`, Android 15/API 35), accessibility and runtime permissions were present and storage was sufficient, but the app-private model folder contained only the historical E2B artifact. The exact E4B file was absent. Therefore CPU/GPU inference, E4B self-test/eval, E4B Page Agent planning, memory, thermal and sustained stability remain blocked and must not be marked passed.
