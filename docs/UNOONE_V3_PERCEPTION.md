# V3 perception — native tree and reviewed advisory image lane

## Current summary — 2026-10-05 (Qwen integration worktree)

**Host-verified development build 0.5.0-alpha-v3, not phone-qualified. Final host gates passed: 770 JVM tests with zero failures/errors/skips, app lint with its existing baseline, and both APK assemblies. See [current receipts](evidence/phone-delivery/results.json). The older repair4 717-test record remains historical.**

- Qwen3.5-2B MNN is an explicit experimental selection, not the default; E2B default and retained E4B rollback are unchanged. All nine pinned files were independently SHA-256 verified outside the repo. NDK arm64 compilation passed; pristine pinned MNN also compiled on the Linux host and produced actual toy outputs `4`, `{"sum":4}`, and `red`. [Text-only host evidence](evidence/qwen-host/SUMMARY.md) distinguishes source/derived config hashes, original/redacted logs and binary identity. HOST tests are not Android inference or comparative winner evidence.
- Selected Qwen uses the MNN runtime plane for shared-brain chat/device advice and browser planning. Browser uses the existing native protocol/controller and `secure-browser` owner; no cloud, silent Gemma fallback, second engine or model-granted execution authority. Native postconditions—not model Done—determine outcomes.
- Settings reviewed image interpretation is reachable: explicit consent for each capture, shared selected brain, a native byte/snapshot-bound privacy review receipt, full-screen aspect-preserving size <=768, and advice-only output. No image result dispatches an action. Known-sensitive masking is not proof of unknown/canvas secret exclusion; legacy strict observation remains fail-closed. Android capture/inference/privacy qualification is pending.
- Skills V2 list/import/review/export/run UI is reachable from Agent → Skills → Reviewed native workflows. Exact digest and per-step approval, installed app versions, master/accessibility admission and native runner guards apply. Candidate fixture approval, manual supervised capture/learning and parameter learning remain unsupported; no fabricated fixture or success receipt.
- Device commands now include reviewed Click Search (ClickNode, not focus), scoped unique-list Scroll with changed-container evidence, Back with action-only changed-screen evidence, and raw filtered Read screen. Exact-state scope, ambiguity handover, unknown semantics and sensitive-action exclusions remain. These are bounded source paths, not universal phone automation.

The reviewed advisory lane is separate from the strict secret-exclusion observation lane. Consent resets on replacement/cancel/finish; captured bytes, masked preview and snapshot identity are bound by NativeImageReviewReceipt, with best-effort disposal rather than forensic-erasure claims. Advisory freshness is distinct from the stricter action snapshot TTL; an advisory cannot become live action authority.

---

## Historical checkpoint detail — preserved verbatim, NOT current status

The following text records the earlier checkpoint and its limitations/results. Present-tense wording inside this historical section describes that checkpoint only; use the dated current summary above for current implementation status.

## Native observations

`core/device/Perception.kt` carries immutable bounded UiSnapshot/PerceptionState data. AndroidDeviceAdapter currently observes the active window only (not complete multi-window coverage), with depth 24 and total traversal cap 256. Snapshot UUID/event sequence/geometry bind evidence. Sensitive semantics are resolved before reading text; password and SECRET/OTP/PAYMENT/SECURITY/CAPTCHA/LEGAL/FINAL_SEND/DESTRUCTIVE labels and descendants are excluded. Unknown semantics are not proof that pixels contain no secrets.

Stable structural node paths are scoped to fresh snapshots, not permanent selectors. Before operation the adapter re-resolves and refreshes the node, checks capabilities/signature/bounds/event sequence, and consumes its own issued observation once. UiStateHasher/UiDiff detect change/progress, not goal completion. Compacted text and OCR are untrusted observations.

## Pixel envelope and privacy blocker

ScreenshotCapture.captureFrame drains queued frames with a bounded wait, fences requests in elapsedRealtimeNanos and accepts only a producer timestamp after the request and no later than now. Capture carries full display geometry, rotation and monotonic sequence. Rotation changes, stale buffers, incompatible clocks or idle-producer timeout fail closed; never relabel a previous frame as fresh.

Bound ScreenPerceptionProvider.observe requires exact snapshot/event/geometry and trusted native `secretsExcludedOutsideKnownRegions` assurance. Its default is false. **The app currently has no full-screen exclusion authority, so this route blocks before OCR/encoding and hands over.** User consent, no password node, or a model assertion does not establish exclusion for unknown/canvas pixels. Do not turn the Boolean on merely to make a demo run.

Known sensitive rectangles are masked at source resolution before resize, fingerprint, compression or OCR. This masking is necessary but insufficient for unclassified regions. A reviewed screen-specific native exclusion mechanism, adversarial tests and device proof are required before enabling pixels. Legacy captureAuthorized is also fail-closed pending binding/privacy proof.

## Implemented components, not enabled end-to-end capability

ScreenTransform validates crop and maps geometry using enclosing floor/ceil bounds; preprocessing caps the largest dimension at 768 without upscaling, generates JPEG 85 plus SHA-256/tile-change metadata, and preserves caller bitmap ownership. OCR uses bundled ML Kit Latin/Devanagari recognition with bounded line regions. Confidence zero means unknown, not measured certainty. Generic bitmap processing is unbound and cannot be treated as authorized fused evidence.

Fusion requires exact tree/frame binding, event continuity, compatible full-screen geometry and <=5-second ages in the same elapsed clock domain. Image envelopes for Gemma additionally validate snapshot/event, request fence, capture sequence, full-frame image bounds, rotation and native secret assurance. Crop or independent pixel rotation is rejected; whole-frame scaling is allowed. Grounding maps high-confidence boxes only to unique already-issued native targets, never invents executable coordinates.

## UI and retention

Opt-in Settings diagnostics has explicit capture and runtime refresh controls. Capture is not part of ordinary chat. Settings does not supply an interpretation provider. No working screenshot/vision preview should be promised while the privacy gate blocks. Frames are ephemeral, discarded on close/disposal/disable/expiry; export allowlists coarse booleans/safety enum, not pixels, OCR or node text. Background lifecycle disposal beyond composition and provider lifetime/TTL coordination need further work before image interpretation is wired.

## Required validation

Parent integrated compile/unit results remain pending. Source-level/JVM fake tests do not qualify MediaProjection or ML Kit. Run real-device stale-queue, idle display, rotation, clock compatibility, canvas-secret, password/OTP overlay, mask-before-encoding, cancellation, event-churn and lifecycle tests. Strict event checks may reject diagnostics' own changing UI; solve settling without weakening freshness. No perception accuracy, working multimodal run, or privacy qualification is claimed here.
