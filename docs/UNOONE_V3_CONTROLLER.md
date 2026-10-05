# V3 controller contract and supported scope

## Current summary — 2026-10-05 (Qwen integration worktree)

**Host-verified development build 0.5.0-alpha-v3, not phone-qualified. Final host gates passed: 770 JVM tests with zero failures/errors/skips, app lint with its existing baseline, and both APK assemblies. See [current receipts](evidence/phone-delivery/results.json). The older repair4 717-test record remains historical.**

- Qwen3.5-2B MNN is an explicit experimental selection, not the default; E2B default and retained E4B rollback are unchanged. All nine pinned files were independently SHA-256 verified outside the repo. NDK arm64 compilation passed; pristine pinned MNN also compiled on the Linux host and produced actual toy outputs `4`, `{"sum":4}`, and `red`. [Text-only host evidence](evidence/qwen-host/SUMMARY.md) distinguishes source/derived config hashes, original/redacted logs and binary identity. HOST tests are not Android inference or comparative winner evidence.
- Selected Qwen uses the MNN runtime plane for shared-brain chat/device advice and browser planning. Browser uses the existing native protocol/controller and `secure-browser` owner; no cloud, silent Gemma fallback, second engine or model-granted execution authority. Native postconditions—not model Done—determine outcomes.
- Settings reviewed image interpretation is reachable: explicit consent for each capture, shared selected brain, a native byte/snapshot-bound privacy review receipt, full-screen aspect-preserving size <=768, and advice-only output. No image result dispatches an action. Known-sensitive masking is not proof of unknown/canvas secret exclusion; legacy strict observation remains fail-closed. Android capture/inference/privacy qualification is pending.
- Skills V2 list/import/review/export/run UI is reachable from Agent → Skills → Reviewed native workflows. Exact digest and per-step approval, installed app versions, master/accessibility admission and native runner guards apply. Candidate fixture approval, manual supervised capture/learning and parameter learning remain unsupported; no fabricated fixture or success receipt.
- Device commands now include reviewed Click Search (ClickNode, not focus), scoped unique-list Scroll with changed-container evidence, Back with action-only changed-screen evidence, and raw filtered Read screen. Exact-state scope, ambiguity handover, unknown semantics and sensitive-action exclusions remain. These are bounded source paths, not universal phone automation.

Current budgets are QUICK 4, STANDARD 12, EXTENDED 32, with session aggregate maximum 32. Click Search needs an exact unique reviewed native search button and verifies a reviewed search field. Scroll accepts a unique reviewed android:id/list ListView and checks changed descendant contents/bounds. Read screen reports filtered accessibility text as untrusted data. Back is ACTION_VERIFIED, not task completion. Unsupported controls, duplicate matches, unknown mutations and draft/send operations hand over.

---

## Historical checkpoint detail — preserved verbatim, NOT current status

The following text records the earlier checkpoint and its limitations/results. Present-tense wording inside this historical section describes that checkpoint only; use the dated current summary above for current implementation status.

## Contract

`core/device/DeviceAgentLoop.kt` defines UnoBrain: capabilities, plan, chat, interpretScreen, groundTarget, verifyOutcome. Model output is advice. DeviceActionCodec accepts one strict typed JSON action; unknown keys/actions, invalid types, stale snapshots and unknown targets fail closed. Native validation is repeated even for typed constructor callers.

Loop: authorized observation → fresh native predicate check → bounded proposal → validation and native policy → exact confirmation if required → guarded dispatch → settle/reobserve → native postcondition. Accepted dispatch is not success. Done/OutcomeAdvice and UI change are not native completion. Uncertain side effects must not be blindly retried.

Budgets: QUICK 4, STANDARD 12, EXTENDED 24; absolute maximum 32 steps, at most two proposal retries, three no-progress events, default 60-second deadline (validated upper bound 180 seconds). Bounds do not create permission or imply those workloads work.

## Actual app entry points

Source: `app/.../NativeDeviceGoal.kt`, `DeviceAgentSession.kt`, `AgentOrchestrator.kt`.

| Goal | Current meaning | Explicit limit |
|---|---|---|
| Open/launch installed app | Foreground package predicate | Intent dispatch alone never succeeds |
| `device: find <text> in <app>` | Exact visible text in requested package | No inferred semantic search or offscreen navigation |
| `device: click <text> in <app>` | Focus a uniquely resolved reviewed editable search field | Uses FocusNode; not generic tap or search submission |
| `device: set <package>:id/<id> to <text>` | Confirmed bounded edit; fresh exact field equality | Only source-reviewed field semantics; no final send |
| Other explicit device goal | NEEDS_USER | No invented native postcondition or fallback CHAT execution |

AppRegistry resolves visible installed launchers at use time; ambiguous/unavailable packages hand over. Current reviewed candidates are Android search_src_text, Gmail search_view and WhatsApp search_src_text with EditText class checks. **Source-reviewed candidates, not device-qualified selectors.** App updates/localization/custom widgets may invalidate them.

Single goals are deterministic. The shared real GemmaE2BBrain is available to native `runNativeGoals`, which requires nonempty native predicates and explicit authorization and checks their conjunction. This API seam is not a general user-facing arbitrary multi-app workflow planner. Search-button/navigation/close semantics remain unsupported where no authorized native action/postcondition exists.

## Outcomes and cancellation

Core statuses: VERIFIED, NEEDS_USER, FAILED, LIMIT_REACHED. Cancellation propagates separately and must never become success. Current focus success is still enum VERIFIED with an ACTION_VERIFIED reason; consumers must not interpret it as a wider task success. A distinct action-only status/UI mapping remains follow-up work.

One retained epoch owner and mutex serialize runs, including queued runs. Stop/disable invalidates epoch before job cancellation and rejects pending approvals. Each approval binds epoch, snapshot and action digest; expiry after a slow confirmation fails closed. Fresh observation is required before completion. Android callers inject elapsedRealtime-compatible clocks, never a mismatched wall clock or arbitrary JVM epoch.

Legacy generic click/type/fill/swipe/long-press routes are retired/denied; safe retained navigation is not a bypass into V3 target authorization. Safety policy cannot be disabled by OFF/RELAXED for V3.

## Remaining execution work

Implement and review additional native semantics/postconditions before extending task scope; wire deliberate supported multi-goal UX rather than accepting arbitrary prose; separate action-only completion reporting; instrument Stop/disable/confirmation races in the real orchestrator; qualify stale-node/event handling on physical devices. Visual coordinates/swipes lack native semantic proof and remain blocked. SkillsV2 fixture replay is offline evidence, not live Android success. See [status](UNOONE_V3_DEVELOPMENT_STATUS.md) for release blockers and [safety](UNOONE_V3_SAFETY.md) for hard exclusions.
