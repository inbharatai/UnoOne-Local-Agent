# Qwen 3.5 2B MNN artifact contract

## Current summary — 2026-10-05 (Qwen integration worktree)

**Host-verified development build 0.5.0-alpha-v3, not phone-qualified. Final host gates passed: 770 JVM tests with zero failures/errors/skips, app lint with its existing baseline, and both APK assemblies. See [current receipts](evidence/phone-delivery/results.json). The older repair4 717-test record remains historical.**

- Qwen3.5-2B MNN is an explicit experimental selection, not the default; E2B default and retained E4B rollback are unchanged. All nine pinned files were independently SHA-256 verified outside the repo. NDK arm64 compilation passed; pristine pinned MNN also compiled on the Linux host and produced actual toy outputs `4`, `{"sum":4}`, and `red`. [Text-only host evidence](evidence/qwen-host/SUMMARY.md) distinguishes source/derived config hashes, original/redacted logs and binary identity. HOST tests are not Android inference or comparative winner evidence.
- Selected Qwen uses the MNN runtime plane for shared-brain chat/device advice and browser planning. Browser uses the existing native protocol/controller and `secure-browser` owner; no cloud, silent Gemma fallback, second engine or model-granted execution authority. Native postconditions—not model Done—determine outcomes.
- Settings reviewed image interpretation is reachable: explicit consent for each capture, shared selected brain, a native byte/snapshot-bound privacy review receipt, full-screen aspect-preserving size <=768, and advice-only output. No image result dispatches an action. Known-sensitive masking is not proof of unknown/canvas secret exclusion; legacy strict observation remains fail-closed. Android capture/inference/privacy qualification is pending.
- Skills V2 list/import/review/export/run UI is reachable from Agent → Skills → Reviewed native workflows. Exact digest and per-step approval, installed app versions, master/accessibility admission and native runner guards apply. Candidate fixture approval, manual supervised capture/learning and parameter learning remain unsupported; no fabricated fixture or success receipt.
- Device commands now include reviewed Click Search (ClickNode, not focus), scoped unique-list Scroll with changed-container evidence, Back with action-only changed-screen evidence, and raw filtered Read screen. Exact-state scope, ambiguity handover, unknown semantics and sensitive-action exclusions remain. These are bounded source paths, not universal phone automation.

The artifact table below preserves the original metadata-inspection checkpoint. Its historical “payload not downloaded” cells are superseded ONLY by the new [independent nine-file disk receipts](evidence/qwen-host/artifact-verification.json), not silently relabeled. Source config stays immutable; host-derived configs and their original/archive SHA-256 are recorded separately. Runtime receipt and tokenizer-budget enforcement are source integrations, not proven by the upstream host demo.

---

## Historical checkpoint detail — preserved verbatim, NOT current status

The following text records the earlier checkpoint and its limitations/results. Present-tense wording inside this historical section describes that checkpoint only; use the dated current summary above for current implementation status.

EXPERIMENTAL / opt-in / NOT the default. E2B remains default; existing E4B IDs, files and rollback remain unchanged. Native/device validation is not established by metadata.

## Provenance

Repository: https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN
API: https://huggingface.co/api/models/taobao-mnn/Qwen3.5-2B-MNN?blobs=true
Pinned revision: `35781816d7b6a9dcb273a6765ac9563401951c3c` (upstream last modified 2026-03-04).
License: Apache-2.0 per pinned README model card (no separate LICENSE file in export). Base model: Qwen/Qwen3.5-2B.

All 9 export artifacts are installed together. `llm.mnn.json` is exported graph diagnostic JSON and `export_args.json` is export provenance, not an alternate runtime config; retained in the complete pinned set rather than guessing that they may be deleted. `.gitattributes` and README are repository metadata, not installation inputs.

| File | Bytes | SHA-256 | Evidence |
|---|---:|---|---|
| [.gitattributes](https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/resolve/35781816d7b6a9dcb273a6765ac9563401951c3c/.gitattributes) | 1642 | `200966173edc40adcf63cfc6b016dad6bd8a8447893ec636a914363d188d8f82` | SHA-256 computed from pinned response bytes |
| [README.md](https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/resolve/35781816d7b6a9dcb273a6765ac9563401951c3c/README.md) | 1171 | `d484d35e51037538596096a34bc32e9918978fed1589e506e346c147581d0d72` | SHA-256 computed from pinned response bytes |
| [config.json](https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/resolve/35781816d7b6a9dcb273a6765ac9563401951c3c/config.json) | 652 | `92853033efe602f95efca3e1c05cd8b108f973c8beed417843a9671f8147ed8d` | SHA-256 computed from pinned response bytes |
| [export_args.json](https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/resolve/35781816d7b6a9dcb273a6765ac9563401951c3c/export_args.json) | 1040 | `a5b3a7d2c45e53c887e539259696d5fcec793637c9a3e9fbc7cd0dd008af6a87` | SHA-256 computed from pinned response bytes |
| [llm.mnn](https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/resolve/35781816d7b6a9dcb273a6765ac9563401951c3c/llm.mnn) | 2148136 | `23df98f8b341b277365e0bbca025c1d192939e3d32d7f79776352c6f32e77960` | Hugging Face API LFS SHA-256 (payload not downloaded) |
| [llm.mnn.json](https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/resolve/35781816d7b6a9dcb273a6765ac9563401951c3c/llm.mnn.json) | 5344018 | `7131ff4f1a441add1039d371815ad94652ae6801f579591cb2f9ad90b5954025` | SHA-256 computed from pinned response bytes |
| [llm.mnn.weight](https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/resolve/35781816d7b6a9dcb273a6765ac9563401951c3c/llm.mnn.weight) | 1176647702 | `c93f71a2dbecf9328782bd38861656d8faa82e95e7f99607350074768a482054` | Hugging Face API LFS SHA-256 (payload not downloaded) |
| [llm_config.json](https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/resolve/35781816d7b6a9dcb273a6765ac9563401951c3c/llm_config.json) | 8692 | `a88234b36c2af0eff8e5c89667011badf71c15e30459eb0e21030a8f3f9ed240` | SHA-256 computed from pinned response bytes |
| [tokenizer.txt](https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/resolve/35781816d7b6a9dcb273a6765ac9563401951c3c/tokenizer.txt) | 6465727 | `7e75de1f279a10b65bd9dc1a5207205cb8993823861c4c42bbbd74e48e1c23a4` | SHA-256 computed from pinned response bytes |
| [visual.mnn](https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/resolve/35781816d7b6a9dcb273a6765ac9563401951c3c/visual.mnn) | 488096 | `88fc40a7b676e90eb2cb86d854db15cb90b9eb1f34087ab0f48c5e43572c8dac` | Hugging Face API LFS SHA-256 (payload not downloaded) |
| [visual.mnn.weight](https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/resolve/35781816d7b6a9dcb273a6765ac9563401951c3c/visual.mnn.weight) | 195587264 | `8f90e106f5b9ae9a939faed240305cfdd5c6740ae91d3fc418a990bee0cce36b` | Hugging Face API LFS SHA-256 (payload not downloaded) |

## Immutable source vs derived native config

Upstream config is CPU / 4 threads, low precision+memory, max_new_tokens=8192, enable_thinking=true, mixed stochastic samplers (penalty/topK/topP/min_p/temperature), temperature=1.0, topP=.95, topK=20. Do not rewrite this hash-bound file. The loader must derive a separate runtime config (or in-memory override) AFTER all files verify: CPU, max_new_tokens<=256, jinja.context.enable_thinking=false. Context is application-enforced at 2048 by default, 4096 explicit upper bound; it is not proven that an arbitrary MNN JSON context key enforces this. Native adapter must count/truncate/reject at the tokenizer boundary and return a receipt of the resolved overrides. Receipt policy data is not evidence native code honored it.

`QwenRuntimePolicy` describes override JSON and receipt; native integration must apply and acknowledge it before reporting ready. Source config hash/revision and derived config hash should be included in native load evidence. No GPU auto-fallback or automatic Gemma migration.

Upstream README CPU build flags: MNN_LOW_MEMORY=true, MNN_CPU_WEIGHT_DEQUANT_GEMM=true, MNN_BUILD_LLM=true, MNN_SUPPORT_TRANSFORMER_FUSE=true; invocation llm_demo /path/config.json prompt.txt. This is upstream documentation, not an Android validation result.

## Verification boundary

Non-LFS files were downloaded at the exact revision and hashed from response bytes; LFS hashes/sizes come from API blob metadata, not Git blob SHA-1. No model weight payload was downloaded or committed. API/byte evidence is archived outside the repository at /agent/workspace/qwen-artifact-evidence. Install-time verification still hashes every payload. Full native inference, image processing, memory, thermal, cancellation, and accuracy evidence remains required before promotion.


## Reviewed host JNI follow-up — 2026-10-05

[Text-only reviewed JNI evidence](evidence/qwen-jni/SUMMARY.md) now records actual production Kotlin → JNI → MNN on Linux x86_64/amd64 (not the static arm64 runtime label). Controlled BEFORE produced real text `{"action":"wait"}`, red32→white (FAIL), red256→red; AFTER preserved expected red and returned red for both fixtures. Full outputs equal callbacks. CPU/greedy/2-thread policy, vision minimum65536, diagnostic context512 versus app default2048, original failure/guarded retry history, and distinct original/archive hashes are retained. This is 3 toy cases / 13 host harness assertions, not broad accuracy. Physical Android NOT RUN; main delivery gates remain PENDING. No README aggregate test count is changed.
