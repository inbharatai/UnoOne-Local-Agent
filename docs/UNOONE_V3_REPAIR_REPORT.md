# UnoOne V3 repair pass — development candidate, main unchanged

## Current summary — 2026-10-05 (Qwen integration worktree)

**Host-verified development build 0.5.0-alpha-v3, not phone-qualified. Final host gates passed: 770 JVM tests with zero failures/errors/skips, app lint with its existing baseline, and both APK assemblies. See [current receipts](evidence/phone-delivery/results.json). The older repair4 717-test record remains historical.**

- Qwen3.5-2B MNN is an explicit experimental selection, not the default; E2B default and retained E4B rollback are unchanged. All nine pinned files were independently SHA-256 verified outside the repo. NDK arm64 compilation passed; pristine pinned MNN also compiled on the Linux host and produced actual toy outputs `4`, `{"sum":4}`, and `red`. [Text-only host evidence](evidence/qwen-host/SUMMARY.md) distinguishes source/derived config hashes, original/redacted logs and binary identity. HOST tests are not Android inference or comparative winner evidence.
- Selected Qwen uses the MNN runtime plane for shared-brain chat/device advice and browser planning. Browser uses the existing native protocol/controller and `secure-browser` owner; no cloud, silent Gemma fallback, second engine or model-granted execution authority. Native postconditions—not model Done—determine outcomes.
- Settings reviewed image interpretation is reachable: explicit consent for each capture, shared selected brain, a native byte/snapshot-bound privacy review receipt, full-screen aspect-preserving size <=768, and advice-only output. No image result dispatches an action. Known-sensitive masking is not proof of unknown/canvas secret exclusion; legacy strict observation remains fail-closed. Android capture/inference/privacy qualification is pending.
- Skills V2 list/import/review/export/run UI is reachable from Agent → Skills → Reviewed native workflows. Exact digest and per-step approval, installed app versions, master/accessibility admission and native runner guards apply. Candidate fixture approval, manual supervised capture/learning and parameter learning remain unsupported; no fabricated fixture or success receipt.
- Device commands now include reviewed Click Search (ClickNode, not focus), scoped unique-list Scroll with changed-container evidence, Back with action-only changed-screen evidence, and raw filtered Read screen. Exact-state scope, ambiguity handover, unknown semantics and sensitive-action exclusions remain. These are bounded source paths, not universal phone automation.

The repair4 report below is a frozen older checkpoint, including its 717 tests, APK hash and then-missing features. Those outcomes remain true only for that checkpoint. New Qwen, vision and Skills work invalidates use of repair4 as current acceptance; current acceptance is recorded separately in docs/evidence/phone-delivery. This historical section does not override those newer receipts.

---

## Historical checkpoint detail — preserved verbatim, NOT current status

The following text records the earlier checkpoint and its limitations/results. Present-tense wording inside this historical section describes that checkpoint only; use the dated current summary above for current implementation status.

Date: 2026-10-05. Baseline main: 69bf5e097b85a1bc40da14c07609dde8ca765cbd. The V3 changes remain uncommitted. This report supersedes the old deep-review verdict only for the specific repaired source paths and evidence below; it does not erase historical failures or establish device qualification.

## Implemented repairs

| Area | Current source change | Evidence boundary |
|---|---|---|
| Speech endpoint | Signed PCM16 RMS, explicit quiet/odd-tail/full-scale tests | Pure arithmetic/policy regression, not acoustic accuracy |
| Speech loading | Manifest-bound STT/TTS/KWS integrity checks, explicit canonical English-ASR KWS fallback | Source and descriptor tests; installed phone files not checked here |
| Playback | Player errors and timeouts propagate; cancellation cleanup and utterance ownership; no success from silently truncated playback | Host policy tests; audible playback/device routes pending |
| Calls and mic ownership | Call-mode checks, buffer discard, recorder ownership and lifecycle cleanup | Device transitions still pending |
| Emergency Stop | Central ingress before busy lock; stop-only inference monitoring; optional AEC-enabled, wake-qualified stop-only capture during TTS | OS effect-enabled is NOT proof of echo suppression or measured stop latency; UI Stop fallback retained |
| Parsing | Negated/reported commands rejected; payload case preserved; compounds reject unhandled steps; explicit legacy create-note chains retained | JVM/text tests, not microphone recognition or contact resolution |
| Browser isolation | Native-owned planning/confirmation loop; website bundle is DOM-only; no page-visible model/consent bridge | Real bundled JS boundary tests pass; native Android E2E pending |
| Browser contracts | Typed arguments, date/checkbox/scroll correctness, exact target fingerprints, native epoch checks and untrusted JSON context | Actual browser fixture tests pass; not arbitrary website correctness |
| Browser cancellation | Native cancel/drain hookup, prompt/file cancellation, queued handoff generation revoked on Stop; active-task replacement rejected before navigation | Native helper ordering tests; JNI/device races pending |
| Outcomes | Authorization is not execution; page claims/dispatch are not independently verified task success | Generic browser completion remains explicitly unverified |
| Model lifecycle | Allocation/residency gate inside process mutex, owner tokens, sticky quarantine, exact model restoration identity | Fake-engine/barrier tests; hung JNI/native close still requires device testing |
| Android controller | Reachable sequential native goals, shared budget/deadline/epoch; slow proposals reobserve and rebind only on exact native equivalence | Narrow native goals, NOT universal natural-language phone control |
| Local diagnostics | Explicit local masked preview/OCR separated from inference-admission types; lifecycle/generation/bitmap ownership cleanup | No inference approval flag fabricated; production model screenshot provider still absent |
| Speech evaluation | Executable WER/CER/intent/slot/wake/stop-latency scorer and pending human-corpus plan | Synthetic scorer tests only; zero collected acoustic accuracy evidence |

## Validation actually completed

- Locked npm dependency installation succeeded after network approval.
- Page runtime TypeScript typecheck and unit suite: 10 tests passed in the repair pass.
- Orchestrator rerun of Playwright against the rebuilt production DOM bundle: **15/15 passed**, none skipped, 19.2 seconds. Fixtures include semantic fields, date/checkbox/scroll, target replacement, hostile page bridge spoofing, and dispatch-versus-verification. These are host/script fixtures, not real-model Android authorization tests.
- Python repository invariants and six mutation/schema tests passed. Evaluation schema validation does not execute phone tasks.
- Independent narrow recheck reports B1/B2 queued-task/replacement-task races and B3 canonical KWS fallback source-resolved, plus direct 79-test parser/core/handoff/confirmation and 3-test descriptor passes. These overlap other suites and must not be summed into a fabricated independent total.
- The complete stable Android gate `repair4` finished **exit 0 in 11m 6s**: clean, all debug JVM tests, app lint, debug APK assembly and Android-test APK assembly. XML totals: **717 tests, 0 failures, 0 errors, 0 skips**. Lint: 0 reported errors/warnings, 2 hints; 15 historical errors remain filtered by the existing baseline. Earlier concurrent-edit runs are not stable-source acceptance; raw logs are preserved separately.
- Debug APK: 392414616 bytes, SHA256 `c65283f1bbdb8aac96597e30ca1f9bbe2715f9f859d7b923d448c897a860854e`. APK signature verification passed (debug signer, not a production signature). Its 4,296-byte DOM asset matches the generated bundle byte-for-byte; asset SHA256 `695f15c52c003cbe30f26cb7caf6ba9739c978fb8ac075705e182f9b91c5648c`.
- Production npm audit reports 0 known vulnerabilities. A limited current-text-tree secret-pattern scan found no matches in 534 text files; this is not a comprehensive secret audit or Git-history scan.
- No emulator or physical device is attached (`adb devices -l` empty). No microphone, real model image inference, native cancellation timing, battery/thermal or real-app benchmark pass exists.

## Independent review scope

The scoped final recheck traced current production code rather than accepting implementer claims. It found no definite remaining blocker in its requested narrow repair scope. This is NOT approval of all V3 features or release quality. Broad product gaps below remain explicit. Reviewed-source hashes and detailed observations are included with the execution handoff/evidence archive; later source edits invalidate a snapshot's signoff.

## Still incomplete / release hold

1. Host gates now pass for this checkpoint. Any further production edits invalidate that evidence and require a new run; preserve exact commands, counts, hashes and suppressed lint baseline issues.
2. Screenshot-driven model inference/visual action is not wired for arbitrary apps. Local preview success must not be represented as multimodal controller success.
3. General mail-to-calendar and arbitrary natural-language multi-app workflows are not complete. Reviewed search selectors, explicit sequences and native postconditions have bounded coverage and may hand over.
4. Skills V2 review/editor/approved execution UI and an actual partner UnoBridge provider are not complete. AppFunctions and Qwen are explicitly unavailable experimental boundaries, not working features.
5. Browser file/rich-control behavior may require explicit human takeover; final arbitrary-goal completion is not independently verified from page-provided claims. Frames/shadow/control support is not universal.
6. English/Hindi/Hinglish human-audio recognition, names/recipient accuracy, wake false accepts, AEC stop behavior, and audible confirmation must be measured on the Xiaomi. No claims of perfect speech or universal echo-safe interruption.
7. All 100 device tasks and the 50-task sustained thermal/battery subset remain pending. Tests and collectors are not results.
8. Final delivery remains main only after agreed gates pass. No force push, archive deletion, or automatic old-model removal. The temporary V3 branch can be removed after verified delivery; pre-existing archive preservation is unchanged.

## Reproduce

Use JDK 17 and Android SDK 35. Install locked Page Agent dependencies, then run typecheck, unit tests, Playwright tests and `npm run bundle:android` before Gradle. The Android browser build now rejects a missing or obsolete privileged-runtime asset instead of silently producing an APK without the agent adapter. Follow the exact commands in README and the Xiaomi/speech qualification documents. On Windows use `python`, not the Microsoft Store `python3` alias.
