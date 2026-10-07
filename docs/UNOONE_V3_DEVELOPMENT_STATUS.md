# V3 development status — mission incomplete

> **Historical development snapshots — banner added 2026-10-07.** The “Current summary” dated 2026-10-05 below is the historical **0.5 / 770-test** checkpoint, not current 0.8 status. Every “current” reference in the preserved body (including “dated current summary above”) is scoped to those earlier checkpoints. The failed checkpoint, 148 dependency-download failures and all original matrices remain unchanged.
>
> Current main entry points: [README](../README.md) → [Floating voice guide](FLOATING_VOICE_GUIDE.md) → [0.8 artifact/build receipts](evidence/floating-voice-delivery/results.json). Existing 0.8.0-alpha-voice / versionCode 8 host gates record **1006 JVM tests passed**, lint and both APK assemblies; physical qualification remains **PENDING**. These are existing main receipts, not a new cleanup validation run.

## Current summary — 2026-10-05 (Qwen integration worktree)

**Host-verified development build 0.5.0-alpha-v3, not phone-qualified. Final host gates passed: 770 JVM tests with zero failures/errors/skips, app lint with its existing baseline, and both APK assemblies. See [current receipts](evidence/phone-delivery/results.json). The older repair4 717-test record remains historical.**

- Qwen3.5-2B MNN is an explicit experimental selection, not the default; E2B default and retained E4B rollback are unchanged. All nine pinned files were independently SHA-256 verified outside the repo. NDK arm64 compilation passed; pristine pinned MNN also compiled on the Linux host and produced actual toy outputs `4`, `{"sum":4}`, and `red`. [Text-only host evidence](evidence/qwen-host/SUMMARY.md) distinguishes source/derived config hashes, original/redacted logs and binary identity. HOST tests are not Android inference or comparative winner evidence.
- Selected Qwen uses the MNN runtime plane for shared-brain chat/device advice and browser planning. Browser uses the existing native protocol/controller and `secure-browser` owner; no cloud, silent Gemma fallback, second engine or model-granted execution authority. Native postconditions—not model Done—determine outcomes.
- Settings reviewed image interpretation is reachable: explicit consent for each capture, shared selected brain, a native byte/snapshot-bound privacy review receipt, full-screen aspect-preserving size <=768, and advice-only output. No image result dispatches an action. Known-sensitive masking is not proof of unknown/canvas secret exclusion; legacy strict observation remains fail-closed. Android capture/inference/privacy qualification is pending.
- Skills V2 list/import/review/export/run UI is reachable from Agent → Skills → Reviewed native workflows. Exact digest and per-step approval, installed app versions, master/accessibility admission and native runner guards apply. Candidate fixture approval, manual supervised capture/learning and parameter learning remain unsupported; no fabricated fixture or success receipt.
- Device commands now include reviewed Click Search (ClickNode, not focus), scoped unique-list Scroll with changed-container evidence, Back with action-only changed-screen evidence, and raw filtered Read screen. Exact-state scope, ambiguity handover, unknown semantics and sensitive-action exclusions remain. These are bounded source paths, not universal phone automation.

Current matrix: Qwen artifacts VERIFIED; arm64 native compile PASSED (compile-only); actual host text/JSON/image smoke PASSED (3 toy cases); reviewed image UI WIRED; Skills V2 bounded UI WIRED; browser selected-MNN source WIRED; current host gates PASSED (770 JVM tests; lint and APK assembly); Android/physical inference, capture/privacy, cancellation stress, speech accuracy, 100 device tasks and sustained thermal/battery qualification PENDING. AppFunctions and a deployed partner UnoBridge provider remain unavailable. Do not sum earlier overlapping suite counts.

---

## Historical checkpoint detail — preserved verbatim, NOT current status

The following text records the earlier checkpoint and its limitations/results. Present-tense wording inside this historical section describes that checkpoint only; use the dated current summary above for current implementation status.

Current source repairs and newer browser evidence are tracked in [UNOONE_V3_REPAIR_REPORT.md](UNOONE_V3_REPAIR_REPORT.md). The following failed-checkpoint matrix is retained as historical integration context; it does not override that repair report. The stable clean Android rerun passed 717 JVM tests, lint (with the existing baseline), debug APK and Android-test APK assembly; see the repair report for exact hashes and limits. No physical qualification is claimed.

This records an unfinished development worktree, not final acceptance. **The clean integrated Gradle run has now completed with exit 1: 518 unit tests passed, 148 failed during Robolectric runtime dependency downloads, and lint found one new error. APK packaging completed, but the Page Agent JavaScript asset is absent. Physical-device qualification is pending. The full 66-point mission is NOT complete.** See [the deep review](UNOONE_V3_DEEP_REVIEW.md) for current findings and exact evidence. The provisional owner-reported matrix below is historical integration context and does not override that review. Historical validation reports must not be relabeled as V3 proof.

## Stage matrix

Implemented = source exists; compiled = scoped compiler evidence only if stated; tested = limited owner evidence, not independent rerun here; wired = actual application entry, not merely API existence. Parent must supersede provisional rows with exact commands/logs and frozen source identity.

| Area | Implemented | Compiled | Tested | Wired | Physical |
|---|---|---|---|---|---|
| Core typed loop/policy/epochs | Yes | Owner reports direct cached Kotlin subset | Owner reports pure JVM/fake regressions | Device session | Pending |
| Accessibility adapter | Yes, active window | Owner reports direct Android-35-stub compile | Core fakes; no Android effect proof | Scoped native goals | Pending |
| Open/find/search-focus/edit | Bounded only | Owner reports descriptor/session subset; large orchestrator not certified | Owner reports 9 subset tests | Explicit app commands | Pending |
| Gemma E2B brain/text requests | Yes | Full integrated compile pending | Owner reports 6 pure envelope/grounding tests | Shared real brain/native-goal seam | Pending |
| Multimodal image request API | Yes | Integrated compile pending | Envelope tests only | No app image provider | Pending/blocked |
| Screenshot/OCR preprocessing | Yes | Integrated compile pending | Robolectric/device execution pending | Diagnostics gate blocks without exclusion proof | Pending/blocked |
| Diagnostics | Partial UI | Integrated compile pending | UI/lifecycle pending | Settings opt-in; interpretation unavailable | Pending |
| E2B/E4B manifest/qualification | Yes | Integrated compile pending | Metadata/host validation reported; generic qualification not device evidence | Selection/catalog; record persistence/UI absent | Pending |
| Installer/native lifecycle repairs | Yes | Integrated compile pending | Latest repair tests authored, execution pending | Existing install/runtime paths | Pending |
| SkillsV2 | Core/store/replay | Owner reports direct subset compile | Owner reports 11 safety-updated JVM tests | Review/editor/approved execution UI missing | Pending |
| UnoBridge | Contract/client | Owner reports direct subset compile | Pure contract cases only | No partner provider/production flow | Pending |
| AppFunctions | Unavailable stub | Not feature completion | Unavailable behavior only | No invocation | Unavailable |
| Qwen | Unavailable experimental stub | Not inference implementation | Unavailable behavior only | No runtime/weights pipeline | Unavailable |
| Evaluation | 100-task corpus/schema/collectors | Python schema validation only | Host/negative checks reported | Native fixture host/task executor missing | 100 pending |

These owner reports are provenance, not a consolidated test total. Different handoffs describe different source snapshots. Do not sum overlapping tests or treat cached compilation as a full Gradle build.

## Explicit residual blockers

1. **Integrated verification:** freeze source state; run full module/app compile, unit tests, lint and APK assembly once concurrent edits cease; preserve complete logs and artifact hashes. Resolve failures before claiming compiled or tested.
2. **Physical E2B loading:** hash the complete installed file, load exact E2B with LiteRT-LM 0.13.1, record actual backend/tokenizer/runtime behavior and run strict self-test. Header metadata is not full-file hash or inference evidence.
3. **Image privacy:** implement reviewed native full-screen secret exclusion for unknown/canvas pixels. Current false-default blocker is intentional; consent/no password nodes is not proof.
4. **Image wiring:** only after privacy proof, connect exact-bound ephemeral screenshot envelopes to GemmaE2BBrain; test image input, unique grounding, stale/rotation/crop rejection, provider lifetime and no hidden retention. No current app multimodal demonstration exists.
5. **Perception/device behavior:** qualify MediaProjection timestamp clocks, queue draining/idle timeouts/rotation, ML Kit geometry, event-churn settling, masking before all processing and active-window limits on target phones.
6. **Controller scope:** app supports open/find/search-focus/edit only. Additional native actions, semantic resolver coverage, goal predicates and explicit multi-goal UX are absent; arbitrary multi-app instructions remain unsupported. Source-reviewed resource IDs require app-version/device qualification.
7. **Outcome reporting:** focus success currently uses VERIFIED enum with ACTION_VERIFIED reason; add/review distinct action-only UI/status semantics so focus is never reported as full search/task completion.
8. **Cancellation:** instrument real AgentOrchestrator Stop/disable/re-enable, pending confirmation, queued runs, speech milestones and stale finalizers; pure fake tests do not prove Android race safety.
9. **Native lifecycle:** run JNI timeout/cancel/late-callback/close-failure stress, verify retained handles/quarantine and phone↔browser/Blind Aid transitions. Missing acknowledgement/throwing close must not allow another resident engine.
10. **Installer durability:** execute malicious archive/link/inventory tests plus disk-full, rename failure and crash/power-loss recovery. Manual backup recovery, directory fsync durability, cross-process lock, expansion quota, local receipt trust and whole-descriptor multi-archive activation remain unresolved.
11. **Qualification storage/UI:** persist model-neutral evidence and connect reviewed approval/backend identity to UI; current record validator is evidence-only. Automatic E4B deletion stays disabled regardless.
12. **SkillsV2 integration:** provide exact-digest review/editor/approval UI, trusted fixture provenance, native permission/confirmation guard, installed-app-version map, approved version selection and native-only run statistics. Offline replay does not execute Android effects.
13. **Partner integration:** deploy/review an actual UnoBridge partner, pin signer/permission/descriptor/capabilities, wire discovery/authorization/UI and test visibility, Binder death and uncertain invocation. Blocking Binder transact has no hard remote execution timeout; do not retry uncertain calls.
14. **AppFunctions/Qwen:** AppFunctions currently unavailable; no alpha dependency/invocation. Qwen has no operational backend or qualified Android artifact. Research/descriptors are not implementation; remaining work includes artifact/license/runtime conversion, engine integration and physical evaluation.
15. **Diagnostics/privacy:** wire real controller/action/retry/grounding metrics; keep unknowns unavailable. Review background lifecycle clearing, preview TTL/provider ownership and repository-wide logging/network behavior.
16. **Evaluation execution:** build/provision the eight synthetic native fixture hosts and real task runner/manual evidence flow. Collector scripts only collect; they do not execute or infer success. All 100 tasks remain pending; fixed 50-task sustained subset is not an observed run.
17. **Physical qualification:** complete exact APK/model/runtime/device identity, native postconditions, false-success/wrong-action/grounding/retry/latency metrics, sustained RAM/thermal/battery, crashes/ANR/OOM, airplane-mode plus independent network review, Hindi/English/accessibility regression and recovery checks. Publish raw evidence, not estimated numbers.
18. **Rollback:** test same-signer APK downgrade/data compatibility, model selection rollback and archive backup recovery without deleting data or model files. Retain original-source export and trusted baseline APK.

## Reproducible developer setup and commands

Use JDK **17**, Android SDK platform **35**, platform-tools and build-tools requested by the checked-in Gradle files. Set JAVA_HOME and ANDROID_HOME/ANDROID_SDK_ROOT (or local.properties sdk.dir); accept SDK licenses locally. Use the repository Gradle wrapper, not a globally installed Gradle. Python 3 is required; on Windows use the user's **python** command, not python3. Network access may be needed to obtain dependencies/models; offline runtime claims do not mean first-time builds are offline.

From repository root, Windows PowerShell:

```powershell
java -version
python --version
python scripts/verify_unoone_v2_invariants.py
python scripts/ci/check_repo_invariants.py
python -m unittest discover -s scripts/ci -p "test_*.py" -v
python evaluation/device-agent/validate.py
Set-Location android-app/UnoOneAgent
.\gradlew.bat --version
.\gradlew.bat testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
```

Linux/macOS equivalent from root:

```sh
java -version
python3 scripts/verify_unoone_v2_invariants.py
python3 scripts/ci/check_repo_invariants.py
python3 -m unittest discover -s scripts/ci -p 'test_*.py' -v
python3 evaluation/device-agent/validate.py
cd android-app/UnoOneAgent
./gradlew --version
./gradlew testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
```

These are reproduction instructions, **not results**. Do not regenerate the frozen corpus during comparison merely to make it pass. Run device procedures separately in [UNOONE_V3_XIAOMI_TEST.md](UNOONE_V3_XIAOMI_TEST.md); inspect the installed instrumentation runner instead of inventing one.

Expected variance: clean versus warm dependency cache, OS paths/permissions, JDK patch, SDK/build-tools, Gradle daemon resources, Android/vendor build, app versions/localization, actual CPU/GPU backend, available RAM, temperature/battery and cold/warm model state. Record these, command lines, commit plus dirty-diff digest, APK SHA-256 and full model hash. Cached owner scripts outside the checkout are diagnostic provenance, not a portable build system.

## Evidence policy

[Baseline audit](UNOONE_V3_BASELINE_AUDIT.md) documents original risks; it is not current qualification. [Benchmarks](UNOONE_V3_BENCHMARKS.md) define pending measurement, not achieved performance. [Migration/rollback](UNOONE_V3_MIGRATION_ROLLBACK.md) preserves data/E4B. No historical DEVICE_VALIDATION report was edited by this documentation pass. Parent final report must state exact completed/failed/skipped tasks and residual blockers; a host green check cannot close the mission.
