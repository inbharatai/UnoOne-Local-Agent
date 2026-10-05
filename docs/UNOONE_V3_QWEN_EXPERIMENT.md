# UnoOne V3 experimental Qwen-family GUI backend

## Current summary — 2026-10-05 (Qwen integration worktree)

**Host-verified development build 0.5.0-alpha-v3, not phone-qualified. Final host gates passed: 770 JVM tests with zero failures/errors/skips, app lint with its existing baseline, and both APK assemblies. See [current receipts](evidence/phone-delivery/results.json). The older repair4 717-test record remains historical.**

- Qwen3.5-2B MNN is an explicit experimental selection, not the default; E2B default and retained E4B rollback are unchanged. All nine pinned files were independently SHA-256 verified outside the repo. NDK arm64 compilation passed; pristine pinned MNN also compiled on the Linux host and produced actual toy outputs `4`, `{"sum":4}`, and `red`. [Text-only host evidence](evidence/qwen-host/SUMMARY.md) distinguishes source/derived config hashes, original/redacted logs and binary identity. HOST tests are not Android inference or comparative winner evidence.
- Selected Qwen uses the MNN runtime plane for shared-brain chat/device advice and browser planning. Browser uses the existing native protocol/controller and `secure-browser` owner; no cloud, silent Gemma fallback, second engine or model-granted execution authority. Native postconditions—not model Done—determine outcomes.
- Settings reviewed image interpretation is reachable: explicit consent for each capture, shared selected brain, a native byte/snapshot-bound privacy review receipt, full-screen aspect-preserving size <=768, and advice-only output. No image result dispatches an action. Known-sensitive masking is not proof of unknown/canvas secret exclusion; legacy strict observation remains fail-closed. Android capture/inference/privacy qualification is pending.
- Skills V2 list/import/review/export/run UI is reachable from Agent → Skills → Reviewed native workflows. Exact digest and per-step approval, installed app versions, master/accessibility admission and native runner guards apply. Candidate fixture approval, manual supervised capture/learning and parameter learning remain unsupported; no fabricated fixture or success receipt.
- Device commands now include reviewed Click Search (ClickNode, not focus), scoped unique-list Scroll with changed-container evidence, Back with action-only changed-screen evidence, and raw filtered Read screen. Exact-state scope, ambiguity handover, unknown semantics and sensitive-action exclusions remain. These are bounded source paths, not universal phone automation.

The unavailable ExperimentalQwenBrain descriptor below concerns earlier GUI-Owl/MAI research and is not the selected Qwen3.5 MNN implementation. Qwen3.5 now has a real runtime/artifact pipeline; GUI-Owl availability claims are not relabeled. Skills native review UI has since been added, while trusted candidate fixture capture/manual learning remains absent.

---

## Historical checkpoint detail — preserved verbatim, NOT current status

The following text records the earlier checkpoint and its limitations/results. Present-tense wording inside this historical section describes that checkpoint only; use the dated current summary above for current implementation status.

## Runtime status: unavailable, not functional

`core/integrations/ExperimentalQwenBrain` implements the existing `UnoBrain` interface as an explicitly unavailable descriptor. `chat`, `devicePlanning` and `vision` capabilities are all false. Every inference method throws `UnsupportedOperationException`; it does not return simulated plans or verification. No runtime, weights, dependency, downloader, arbitrary code evaluator, or model-granted authority has been added. It must not be selected as a production fallback.

## Candidate evidence (cached official upstream sources)

Evidence is in `/agent/workspace/model-research-evidence`; cache manifests record retrieval on 2026-10-05. These are source observations, not local-phone measurements.

| Candidate | Exact source / revision | What is and is not established |
|---|---|---|
| Tongyi-MAI MAI-UI-2B | [model card](https://huggingface.co/Tongyi-MAI/MAI-UI-2B/blob/503050934809558c8dfd2ddedaf9621fa74ac2de/README.md), [config](https://huggingface.co/Tongyi-MAI/MAI-UI-2B/blob/503050934809558c8dfd2ddedaf9621fa74ac2de/config.json) | Card declares Apache-2.0. Config declares `Qwen3VLForConditionalGeneration` / `qwen3_vl`. Family benchmark claims are not a qualified 2B phone deployment. |
| mPLUG GUI-Owl-1.5-2B-Instruct | [model card](https://huggingface.co/mPLUG/GUI-Owl-1.5-2B-Instruct/blob/528ceaec795bbfbe6103bd79e03db849feadfb24/README.md), [config](https://huggingface.co/mPLUG/GUI-Owl-1.5-2B-Instruct/blob/528ceaec795bbfbe6103bd79e03db849feadfb24/config.json) | MIT card; Qwen3-VL derivative. Upstream deployment example says A100 96 GB, not an Android measurement. This is the descriptor's candidate, not an installed model. |
| Alibaba MNN Android runtime | [Android app README](https://github.com/alibaba/MNN/blob/024a946b0b8fcf87c8a418229fadd4cd7858ffba/apps/Android/MnnLlmChat/README.md), [LLM docs](https://github.com/alibaba/MNN/blob/024a946b0b8fcf87c8a418229fadd4cd7858ffba/docs/transformers/llm.md), [export mapper](https://github.com/alibaba/MNN/blob/024a946b0b8fcf87c8a418229fadd4cd7858ffba/transformers/llm/export/utils/model_mapper.py) | Runtime/export research path, not evidence that either candidate is converted and working on this phone. |
| taobao-mnn/GUI-Owl-1.5-2B-Instruct-MNN | [repository revision](https://huggingface.co/taobao-mnn/GUI-Owl-1.5-2B-Instruct-MNN/tree/6d81e4a7854234793de45250787a795bc86eedcc) | Cached API metadata `mnn-owl-meta.txt` lists only `.gitattributes`, `usedStorage: 0`. This is **not an available model artifact**; do not invent download files. |

No claims are made about phone throughput, memory, thermals, battery, navigation reliability or offline readiness. Qualification must pin usable artifacts and hashes/licenses, validate tokenizer/chat template/image preprocessing and coordinate transforms, compile a supported Android ABI runtime, measure real device resource use, replay safety fixtures, and verify native guarded execution. Model output remains advice; native policy and predicates retain authority.

## Bounded phase 9–11 support included

- `SkillsV2`: serialized typed `DeviceAction` steps, stable package/resource selectors, typed native pre/postconditions, exact installed app versions, 32-step and 60-second execution bounds, existing authorization/epoch/safety guards. Persistent coordinates and model control-flow steps are rejected. Runtime guard—not workflow risk metadata—authorizes effects.
- Candidate review compares permissions, risk, selectors, actions and app versions; fixture replay reports regressions and rejects candidate failures. Approval is explicit and bound to the candidate digest. Versions are append-only, and approving a candidate never silently switches an active pointer or modifies the old workflow. Native run records separately retain workflow version, actual installed app versions and device build; counters distinguish verified and failed runs.
- Fixture replay checks recorded before/after states and action validity; it does not simulate Android or establish actual device success. Only the runtime runner's fresh native postconditions can establish completion.
- `AppRegistry` lazily discovers currently visible launchable apps, supports aliases and returns explicit ambiguity/unavailability. Legacy resolver remains unchanged; `resolveLegacyName` is an opt-in visibility-checked compatibility path. No `QUERY_ALL_PACKAGES` permission is added. Main/app wiring owns minimal launcher/bridge package visibility declarations as needed.
- `UnoBridgeContract` is a version-1 pure schema. Android `UnoBridgeClient` uses an explicit package/component, pinned **current signer** SHA-256 digests, exactly one discovered service requiring a signature-level permission, interface-token validation and real Binder `transact` calls. Transaction 1 discovers capabilities; transaction 2 invokes a native-allowlisted capability after per-call native authorization. Both use token + JSON string input, exception header + JSON string output. Version, request ID and 32 KiB parcel/UTF-8 limits fail closed. Binder death invalidates discovery. No retries of uncertain effects, no broad exported service, no fake invocation result. Partner provider implementation and device IPC tests remain out of scope.
- Binding has a 3-second deadline. Synchronous Binder transact runs off-main; Android does not provide a cancellation primitive for an already-blocked synchronous transaction. This scaffold does **not** claim a hard remote-call execution deadline. Do not ship against unqualified providers; an asynchronous bounded provider contract is needed for stronger liveness guarantees.
- AppFunctions has an explicit unavailable optional seam: no guessed alpha dependency or fabricated discovery. Cached Maven metadata alone is insufficient to claim a compatible integration.

## Integration boundary

These support APIs do not automatically replace legacy skills or package resolution, change the manifest, register a bridge provider, or select a model. The application owner must wire native review UI, native permissions/confirmation, app-version collection, stats recording, and approved-version selection. No model JSON should be allowed to supply trust pins, native allowlists, approval booleans, fixture evidence or outcome counters. External capability `accepted` acknowledges dispatch only; it is never native completion proof.
