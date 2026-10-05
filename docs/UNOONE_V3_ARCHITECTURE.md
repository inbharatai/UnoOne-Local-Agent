# UnoOne V3 architecture — bounded implementation, not release qualification

## Current summary — 2026-10-05 (Qwen integration worktree)

**Host-verified development build 0.5.0-alpha-v3, not phone-qualified. Final host gates passed: 770 JVM tests with zero failures/errors/skips, app lint with its existing baseline, and both APK assemblies. See [current receipts](evidence/phone-delivery/results.json). The older repair4 717-test record remains historical.**

- Qwen3.5-2B MNN is an explicit experimental selection, not the default; E2B default and retained E4B rollback are unchanged. All nine pinned files were independently SHA-256 verified outside the repo. NDK arm64 compilation passed; pristine pinned MNN also compiled on the Linux host and produced actual toy outputs `4`, `{"sum":4}`, and `red`. [Text-only host evidence](evidence/qwen-host/SUMMARY.md) distinguishes source/derived config hashes, original/redacted logs and binary identity. HOST tests are not Android inference or comparative winner evidence.
- Selected Qwen uses the MNN runtime plane for shared-brain chat/device advice and browser planning. Browser uses the existing native protocol/controller and `secure-browser` owner; no cloud, silent Gemma fallback, second engine or model-granted execution authority. Native postconditions—not model Done—determine outcomes.
- Settings reviewed image interpretation is reachable: explicit consent for each capture, shared selected brain, a native byte/snapshot-bound privacy review receipt, full-screen aspect-preserving size <=768, and advice-only output. No image result dispatches an action. Known-sensitive masking is not proof of unknown/canvas secret exclusion; legacy strict observation remains fail-closed. Android capture/inference/privacy qualification is pending.
- Skills V2 list/import/review/export/run UI is reachable from Agent → Skills → Reviewed native workflows. Exact digest and per-step approval, installed app versions, master/accessibility admission and native runner guards apply. Candidate fixture approval, manual supervised capture/learning and parameter learning remain unsupported; no fabricated fixture or success receipt.
- Device commands now include reviewed Click Search (ClickNode, not focus), scoped unique-list Scroll with changed-container evidence, Back with action-only changed-screen evidence, and raw filtered Read screen. Exact-state scope, ambiguity handover, unknown semantics and sensitive-action exclusions remain. These are bounded source paths, not universal phone automation.

Current ownership: localbrain dispatches selected LiteRT/MNN under shared residency; Settings invokes AgentOrchestrator.analyzeReviewedScreen; SkillsV2Screen/ViewModel use the native runner; QwenPageAgentPlanner reuses the native browser protocol and secure-browser lease. No universal workflow or arbitrary visual-coordinate executor is introduced.

---

## Historical checkpoint detail — preserved verbatim, NOT current status

The following text records the earlier checkpoint and its limitations/results. Present-tense wording inside this historical section describes that checkpoint only; use the dated current summary above for current implementation status.

This describes the current source working tree, not a shipped or device-qualified product. Baseline is `69bf5e097b85a1bc40da14c07609dde8ca765cbd`; preserve [the baseline audit](UNOONE_V3_BASELINE_AUDIT.md) as historical evidence. Integrated build/test results are pending the parent final report. The full 66-point mission is **not complete**: physical qualification and remaining integration work are release gates. See [development status](UNOONE_V3_DEVELOPMENT_STATUS.md).

## Ownership and data flow

Android remains a modular application rooted at `android-app/UnoOneAgent`, not a fleet of independent agents:

- `core/device`: immutable observations, strict single-action codec/validator, native safety, bounded loop, epochs, native goal predicates and `UnoBrain` interface.
- `accessibilitycontrol`: real active-window traversal and guarded native Android actions. No live Accessibility node escapes into planner context.
- `phonecontrol`: current installed-app resolution, MediaProjection envelopes, ephemeral preprocessing and OCR. Pixel processing is presently blocked without trusted secret-exclusion proof.
- `localbrain`: existing shared LocalBrain/GemmaPlanner engine plus GemmaE2BBrain adapter. Text-first controller requests; actual image-content API exists but no production screenshot provider is wired.
- `app`: AgentOrchestrator command ownership, DeviceAgentSession, explicit native command descriptors, confirmation and diagnostics UI.
- `skills`: immutable reviewed SkillsV2 definitions/replay/store and optional bridge client; review UI and production workflow integration remain missing.
- `modelmanager`: pinned profiles, verification/staging and non-destructive migration; `storage`, `voice`, `securebrowser` and existing tool lanes remain separate responsibilities.

Flow: explicit user command → native scope/goal → fresh observation → deterministic proposal or one model proposal → strict validation → immutable native policy/confirmation → guarded dispatch → fresh observation → native postcondition. A model's Done, plausible explanation or accepted Android dispatch cannot prove completion. Screen/OCR text is untrusted data, never authority.

## Actual app surface

NativeDeviceCommands and DeviceAgentSession currently support installed-app open, exact visible-text find, source-reviewed search-field focus and bounded field edits. `device: find <text> in <app>`, `device: click <text> in <app>` and `device: set <package>:id/<id> to <text>` are scoped syntax, not arbitrary GUI instructions. The click descriptor currently dispatches **FocusNode**, not a generic tap; its success reason is ACTION_VERIFIED and does not mean a search or external task completed. Unknown, unavailable, ambiguous or unreviewed targets hand over. No automatic navigation to find offscreen text is promised.

A `runNativeGoals` seam accepts native predicates/authorization and 12/24-step budgets; it is not a universal natural-language multi-app entry point. Ordinary chat remains separate and does not capture the screen. Existing browser PageAgent is a separate lane with separate lifecycle/policy; its presence does not qualify Android computer use.

## Runtime boundaries

One retained epoch/session owner serializes device execution. Stop and master-disable invalidate epoch before cancellation and deny pending confirmation. Confirmation binds epoch + snapshot ID + action digest and is revalidated after suspension. Android captures and loop validation use elapsedRealtime/elapsedRealtimeNanos-compatible clocks.

Phone and browser must not allocate over uncertain native residency. Unload now returns proof; idle timeout retains handles and blocks reuse. A throwing native close quarantines ownership and may require process restart. These source contracts still require JNI/device stress validation.

## Not yet delivered

No universal multi-app automation, qualified visual coordinate executor, secret-safe full-screen OCR, working app multimodal interpretation, approved SkillsV2 editor/execution UI, deployed partner UnoBridge provider, AppFunctions invocation or Qwen inference. Diagnostics show unavailable metrics honestly. See [controller](UNOONE_V3_CONTROLLER.md), [perception](UNOONE_V3_PERCEPTION.md), [safety](UNOONE_V3_SAFETY.md), [model strategy](UNOONE_V3_MODEL_STRATEGY.md) and [rollback](UNOONE_V3_MIGRATION_ROLLBACK.md) for exact limits.
