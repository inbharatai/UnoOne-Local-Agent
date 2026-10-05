# V3 model strategy and evidence provenance

## Current summary — 2026-10-05 (Qwen integration worktree)

**Host-verified development build 0.5.0-alpha-v3, not phone-qualified. Final host gates passed: 770 JVM tests with zero failures/errors/skips, app lint with its existing baseline, and both APK assemblies. See [current receipts](evidence/phone-delivery/results.json). The older repair4 717-test record remains historical.**

- Qwen3.5-2B MNN is an explicit experimental selection, not the default; E2B default and retained E4B rollback are unchanged. All nine pinned files were independently SHA-256 verified outside the repo. NDK arm64 compilation passed; pristine pinned MNN also compiled on the Linux host and produced actual toy outputs `4`, `{"sum":4}`, and `red`. [Text-only host evidence](evidence/qwen-host/SUMMARY.md) distinguishes source/derived config hashes, original/redacted logs and binary identity. HOST tests are not Android inference or comparative winner evidence.
- Selected Qwen uses the MNN runtime plane for shared-brain chat/device advice and browser planning. Browser uses the existing native protocol/controller and `secure-browser` owner; no cloud, silent Gemma fallback, second engine or model-granted execution authority. Native postconditions—not model Done—determine outcomes.
- Settings reviewed image interpretation is reachable: explicit consent for each capture, shared selected brain, a native byte/snapshot-bound privacy review receipt, full-screen aspect-preserving size <=768, and advice-only output. No image result dispatches an action. Known-sensitive masking is not proof of unknown/canvas secret exclusion; legacy strict observation remains fail-closed. Android capture/inference/privacy qualification is pending.
- Skills V2 list/import/review/export/run UI is reachable from Agent → Skills → Reviewed native workflows. Exact digest and per-step approval, installed app versions, master/accessibility admission and native runner guards apply. Candidate fixture approval, manual supervised capture/learning and parameter learning remain unsupported; no fabricated fixture or success receipt.
- Device commands now include reviewed Click Search (ClickNode, not focus), scoped unique-list Scroll with changed-container evidence, Back with action-only changed-screen evidence, and raw filtered Read screen. Exact-state scope, ambiguity handover, unknown semantics and sensitive-action exclusions remain. These are bounded source paths, not universal phone automation.

Qwen evidence is now actual host execution, unlike the still-metadata-only E2B artifact inspection described below. Do not transfer Qwen evidence to E2B or upgrade either model to physical qualification. See [Qwen artifact contract](UNOONE_V3_QWEN35_MNN.md).

---

## Historical checkpoint detail — preserved verbatim, NOT current status

The following text records the earlier checkpoint and its limitations/results. Present-tense wording inside this historical section describes that checkpoint only; use the dated current summary above for current implementation status.

## Selected artifacts

E2B is the new/unknown-selection default; a persisted E4B selection remains E4B. Neither profile is physically qualified by this migration. E4B files are preserved; no automatic removal is allowed. An explicit user uninstall is different from migration cleanup.

| Property | E2B | Retained E4B |
|---|---|---|
| Repository | `litert-community/gemma-4-E2B-it-litert-lm` | `litert-community/gemma-4-E4B-it-litert-lm` |
| Revision | `b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1` | `28299f30ee4d43294517a4ac93abd6163412f07f` |
| File | `gemma-4-E2B-it.litertlm` | `gemma-4-E4B-it.litertlm` |
| Bytes | 2588147712 | 3659530240 |
| SHA-256 expected | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` | `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0` |

Authoritative upstream identity sources: [E2B revision API metadata](https://huggingface.co/api/models/litert-community/gemma-4-E2B-it-litert-lm/revision/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1?blobs=true), [matching E2B LFS pointer](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/raw/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it.litertlm), [pinned model card](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/raw/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/README.md), [E4B pointer](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/raw/28299f30ee4d43294517a4ac93abd6163412f07f/gemma-4-E4B-it.litertlm). The litert-community conversion and its metadata are the authority for these exact packaged bytes; this does not imply independent Google certification of UnoOne.

Immutable E2B download: <https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it.litertlm>.

## What was actually inspected

Cached upstream metadata and LFS pointer agree on size/hash. A 32,768-byte header range was decoded, **not the whole file downloaded and independently hashed**, and no inference was performed as part of that inspection. Header magic is LITERTLM, format 1.5.0; section directory identifies embedded `SP_Tokenizer` at offsets 32768–4721781 and vision encoder/adapter sections. This establishes container metadata, not tokenizer payload validation, model loading, image accuracy or device compatibility. A separate tokenizer download must not be invented. Format references: [pinned header](https://raw.githubusercontent.com/google-ai-edge/LiteRT-LM/d17a52fd5c2b4ce1280959309af175caebaac2c3/schema/core/litertlm_header.h) and [pinned schema](https://raw.githubusercontent.com/google-ai-edge/LiteRT-LM/d17a52fd5c2b4ce1280959309af175caebaac2c3/schema/core/litertlm_header_schema.fbs).

Source cache in the research workspace: `model-research-evidence/sources.json`, `sources-final.json`, `sources-more.json`, `e2b-header-decoded.json`. These are evidence provenance, not required runtime assets. The root Gallery allowlist lists older Gemma 3n and was not used as proof. The versioned [Google AI Edge Gallery 1.0.19 catalogue](https://github.com/google-ai-edge/gallery/blob/c7e9ccd4c75a476dc4e35b41fbac0a40298dadad/model_allowlists/1_0_19.json) explicitly identifies this E2B Android multimodal artifact, size 2588147712, with image/audio support. Its revision 6e5c4f1e395deb959c494953478fa5cec4b8008f has an [LFS pointer](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/raw/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm) matching the exact SHA256 and size selected above. The catalogue also lists newer update revisions; this implementation deliberately pins the inspected compatible bytes instead of silently switching to them. Gallery defaults vision to GPU; UnoOne's conservative CPU-vision configuration is a candidate requiring physical qualification, not an assertion that CPU image execution works.

## Runtime and capabilities

`localbrain/build.gradle.kts` pins `com.google.ai.edge.litertlm:litertlm-android:0.13.1`; do not substitute latest APIs when reproducing. Official version-specific APIs: [Engine.kt](https://raw.githubusercontent.com/google-ai-edge/LiteRT-LM/v0.13.1/kotlin/java/com/google/ai/edge/litertlm/Engine.kt), [Message.kt](https://raw.githubusercontent.com/google-ai-edge/LiteRT-LM/v0.13.1/kotlin/java/com/google/ai/edge/litertlm/Message.kt).

E2B source config enables CPU vision backend and one image. GemmaE2BBrain uses shared LocalBrain, strict text-first plan JSON, and explicit image interpretation/grounding APIs. No second engine is created by the adapter. E4B retains existing text planning/chat; adapter vision is unavailable for E4B. Production app supplies no screenshot provider, so implemented image API is **not working app multimodal support**. Secret exclusion is an additional unresolved gate even if a provider is added.

The 2048-token mobile context cap and inherited 8192/12288 MB application RAM policy are conservative configuration, not measured device requirements or benchmarks. Record actual backend, device/build, APK, artifact hash, load/self-test, latency, sustained RAM/thermal/battery, crashes and cancellation behavior before qualification.

## Alternatives and qualification

Qwen/GUI-Owl/MNN research is documented in [the experiment](UNOONE_V3_QWEN_EXPERIMENT.md). ExperimentalQwenBrain deliberately has all capabilities unavailable and throws on use. No working fallback, Android engine or qualified weights are claimed. AppFunctions is also unavailable; it is not a model feature.

ModelQualificationRecord validates evidence shape but is not yet persisted or connected to qualification UI/backend selection/deletion. A pure validator pass cannot certify a phone. Follow [benchmarks](UNOONE_V3_BENCHMARKS.md) and [Xiaomi procedure](UNOONE_V3_XIAOMI_TEST.md); do not remove E4B after a self-test or host build.
