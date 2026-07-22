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
11. **Stale active documentation — medium.** Current architecture, status and model docs now describe E4B, enforced limits, conservative backend policy and measured physical qualification. Dated historical E2B evidence remains labelled historical.
12. **Oversized planner context — critical device finding.** Registering all verbose reflection schemas produced about 3,000 input tokens, so the enforced 2,048-token engine rejected even basic commands before inference. Planning now uses a compact instruction, bounded user context and command-relevant minimal OpenAPI schemas. Native canonical validation and SafetyGuard remain authoritative. The exact post-fix CPU set scored 43/43.
13. **Native callback completion race — critical device finding.** LiteRT could wake the response coroutine before publishing native idleness, leaving the next sequential voice command permanently rejected as already in flight. Native completion is now published before the response waiter resumes; the full sequential device evaluation completed without recurrence.

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

Automated results belong to the exact child-branch commit. On the connected Xiaomi 14 (`23127PN0CG`, Android 15/API 35), the exact 3,659,530,240-byte E4B file is app-private and SHA-256 verified. LiteRT-LM loaded it on CPU, direct load/basic probes passed, and the post-fix 43-case phone planner scored 43/43 fully correct and 43/43 tool-match. Real English/Hindi TTS, English/Indic STT initialization and a Hindi TTS-to-STT round trip passed. Android Page Agent form/read tests use a controlled BrowserModelPort, so E4B-backed browser-plan accuracy is still pending.

The continuous CPU evaluation also established a limit: warmed PSS plateaued around 3.73–3.82 GB with about 0.77 GB swap, battery reached 40.8–41.6 °C, skin about 41.5 °C, and several CPU sensors reported thermal status 3 near 95 °C. The run completed without an UnoOne exception, ANR or low-memory kill, but this is not a sustained-production pass. GPU/NPU, strict in-app self-test, E4B Page Agent lease/accuracy, 50-task battery/thermal, second-device and legacy-cleanup claims remain blocked.
