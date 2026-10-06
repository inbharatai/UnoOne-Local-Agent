# Multitask source audit — October 6, 2026

**Comparison base: `55c15c9d87c2bd9706d968e8110bec1dd118eeba`. Development build: 0.6.0-alpha-v3 / versionCode 6. Host integration gates passed; physical qualification pending.**

This is a bounded source/documentation review, not an independent security certification or Android test run. Read the current README, actual `NativeTaskRuntime`, core `TaskCoordinator`/`TaskContract`, `TaskBoardScreen`, command parser and all `/agent/workspace/task*handoffs` available during this pass. Handoffs are implementation reports, not integrated evidence; current source supersedes stale handoff descriptions. No Gradle, phone test, benchmark or commit was performed by this documentation pass. Base `55c15` is not asserted to be a fully validated release or an audited diff of every file.

## Findings and implemented changes

Paths below are relative to `android-app/UnoOneAgent/` unless stated otherwise.

| Finding / change | Source paths and evidence boundary |
|---|---|
| Busy-input execution now has explicit admission and a bounded native scheduler | `core/src/main/java/com/unoone/agent/core/task/TaskCoordinator.kt`, `TaskContract.kt`; queue 32, one interactive/two non-UI slots, priority with 5-second aging, bounded retention. Constructor registrations—not model output—choose lanes. |
| Genuine but narrow parent/child workflow | `app/src/main/java/com/unoone/agent/task/NativeTaskRuntime.kt`; preparation delegates notes-search and draft with subset scopes then releases the worker slot. Two direct children/depth one and shared family counters/deadline. Not independent LLM agents or automatic notes-to-draft synthesis. |
| Isolated task output and truthful native outcomes | `NativeTaskRuntime.kt`, `app/src/main/java/com/unoone/agent/AgentOrchestrator.kt`; bounded task-keyed outputs; only native verified outcome means VERIFIED. Search is RESPONDED; draft is RESPONDED only when native literal checks pass, otherwise NEEDS_USER. Neither proves facts. Current source uses atomic task completion/output publication; older app handoff's generic finish description is not the final implementation. |
| Draft is text preparation, not short chat | `localbrain/src/main/java/com/unoone/agent/localbrain/LocalBrain.kt`, `AgentOrchestrator.kt`; `DraftRequest.kt` and `DraftConstraintPolicy.kt` implement optional <=6 unique exact phrases, <=80 characters each / <=256 combined, nonblank literal checks and at most one repair; failures yield NEEDS_USER, passes RESPONDED, never fact verification. Approximately 100-word preparation prompt with bounded output, not a 45-word chat capability claim. No automatic send or sibling-note grounding. |
| Shared UI/model ownership spans more than coordinator rows | `core/src/main/java/com/unoone/agent/core/task/TaskResourceArbiter.kt`, `app/src/main/java/com/unoone/agent/task/ResourceEffects.kt`, `ModelTransitions.kt`, `app/src/main/java/com/unoone/agent/browser/SecureBrowserModelLease.kt`, `app/src/main/java/com/unoone/agent/ui/viewmodel/SecureBrowserViewModel.kt`, `SkillsV2ViewModel.kt`; browser/Skills retain independent IDs and leases, with intents in the same verified bounded metadata journal. They are NOT live Task Board workers or proof of all-lane coordinator budgets. |
| Scoped observation precedes text exposure | `accessibilitycontrol/src/main/java/com/unoone/agent/accessibilitycontrol/AndroidDeviceAdapter.kt`, core `DeviceAgentLoop.kt`, app `ResourceEffects.kt`; source-scoped adapter hook, metadata-only out-of-scope bootstrap, package pruning before label reads and read-budget checks. Observation handoff explicitly left Android/runtime regression verification to integration. |
| Reviewed semantic interactions, not universal GUI control | `app/src/main/java/com/unoone/agent/NativeDeviceGoal.kt`, `NativeGoalPolicy.kt`, `DeviceAgentSession.kt`; quoted write/focus/click/tab selectors, optional installed-app suffix, explicit opens for transitions, fresh binding and narrow postconditions. Unknown/custom/canvas targets and human takeover require user action. Sensitive-action detection remains heuristic. |
| Exact native authorization rather than model-granted scope | `NativeTaskRuntime.kt` (`TaskToolAuthorization`), `AgentOrchestrator.kt`, `app/src/main/java/com/unoone/agent/execution/ActionExecutor.kt`; canonical tool/arguments digest and native package scope. Stored skill preparation may return PREPARING; changed/time-dependent legacy steps may safely reject rather than acquire broader authority. |
| Selective and global cancellation remain distinct | `TaskCoordinator.kt`, `NativeTaskRuntime.kt`, `AgentOrchestrator.kt`; family cancellation and global generation revocation; occupied resources drain before reuse. Current coordinator includes a deadline watchdog, superseding early handoff's entry/check-only deadline description. A watchdog cannot prove a noncooperative native call has stopped. |
| Metadata recovery has no playback authority | `app/src/main/java/com/unoone/agent/task/TaskJournalStore.kt`, `NativeTaskRuntime.kt`, `TaskContract.kt`; bounded no-backup AtomicFile metadata; corruption/write failure fail closed; terminal records preserved, unfinished records review-only. Latest asynchronous terminal persistence may be lost on process death, yielding conservative review. No instruction/output/consent replay. Full task instructions are not in the new metadata journal; legacy app logs/memory and saved UI state are separate, so no app-wide RAM-only request-text claim is made. |
| History reset is an idle maintenance operation | `TaskCoordinator.kt` (`beginMaintenance`), `NativeTaskRuntime.kt` (`clearMetadataHistory`), `TaskBoardScreen.kt`; intake reservation, drained jobs, UI→model lease ordering, explicit confirmation and generation recheck. It does not undo external effects or restart work. Synchronous filesystem operations still must unwind. |
| Board is wired, with authoritative role/priority and separate outputs | `app/src/main/java/com/unoone/agent/ui/screens/TaskBoardScreen.kt`, `app/src/main/java/com/unoone/agent/ui/viewmodel/TaskBoardState.kt`, `TaskBoardViewModel.kt`, `app/src/main/java/com/unoone/agent/ui/navigation/UnoOneNavHost.kt`; current route calls `submitPreparation(query, prompt)`. Earlier UI handoff's missing metadata/unwired callback is superseded by actual source. |

## Unresolved qualification and review boundaries

1. **Current host integration gate passed: 842 JVM tests, 0 failures/errors/skips, app lint and both APK assemblies.** [Final receipts](evidence/multitask-delivery/results.json) supersede worker-local reports; counts are taken from final XML, not summed from overlapping handoffs. Earlier failures remain historical evidence, not the latest verdict. Lint still filters 15 historical baseline errors.
2. Observation handoff called for two-app text-exclusion, out-of-scope OpenApp and unauthorized-read/no-model regressions. Interaction handoff called for real coordinator-context session fixtures and epoch-preserving adapter checks. Later fixes may exist; only the release owner's current integrated receipts can close these checks.
3. Model transition reports do not establish complete cross-mode linearizability. Global Stop fan-out and BlindAid producer teardown ACK barriers now exist in source and have host gate tests. These are not native/CameraX certification: actual camera shutdown acknowledgment, rapid mode toggles, native quarantine/cleanup and post-Stop load behavior still require adversarial phone validation. No memory/performance benefit is measured.
4. Legacy helper effects and independent browser/Skills behavior must not be described as universally per-step coordinator-journaled. Independent browser navigation and manual gestures are not arbitrary newly registered tasks. Review UI takeover/context change and actual dispatch guards on the phone.
5. Native semantic matching depends on app version, exposed accessibility metadata and source-known roles. This is not arbitrary app automation. Heuristic sensitive-action blocks are not universal guarantees; do not test with valuable accounts/content.
6. Voice ordinary-task admission is not TTS barge-in. AEC-conditioned playback Stop and stop-only monitoring still need physical acoustic tests. Typed enqueue/Stop remain available; host fixtures cannot measure microphone or JNI behavior.
7. Recovered metadata is not a complete lossless event log or proof of completed external work. Inspect the actual app before issuing a fresh remaining-step command. Clear history is not rollback.

## Evidence ledger

| Evidence | Status for this candidate |
|---|---|
| 0.6 integrated JVM test count, lint, APK/Android-test assembly, hashes | **PENDING — parent/release owner must replace with matching final receipts** |
| Development source compile/APK assembly | Passed once; latest final gates/count remain PENDING, not a finalized delivery hash or phone result |
| [Original real multitask host run](evidence/multitask-host/README.md) | **FAILED**: B omitted COBALT942, JVM exit 1; preserved unchanged, despite both drafts returning RESPONDED |
| [Fresh real draft-gate host run](evidence/multitask-draft-gate/README.md) | 2/2 literal checks passed, RESPONDED not fact-verified; both first attempts passed, repair/repeated-failure unexercised. Unsupported urgency retained. Not general accuracy or phone qualification |
| 0.5 delivery's 770 JVM tests and successful host build | Historical baseline only; `docs/evidence/phone-delivery/` |
| Prior hosted CI failure | Reported runner-allocation failure, not established source-code failure; no new CI pass asserted here |
| Worker-local direct compiler/test handoffs | Partial development evidence; not recomputed, not summed, not device qualification |
| Real host Qwen/MNN text/JSON/image/JNI evidence | Unchanged historical evidence in `docs/evidence/qwen-host/` and `docs/evidence/qwen-jni/`; opt-in challenger, Android inference still pending |
| Phone task completion, speech, Stop latency, peak memory, thermal/battery | **NOT MEASURED by this audit** |
| Ten safe phone demo cases + blocked tests | Protocol authored in [multitasking guide](UNOONE_MULTITASKING.md), not executed passes |

## Documentation delivered in this pass

- `README.md`: current candidate warning, bounded capability summary, new guide/audit links, fourth conceptual diagram, historical 0.5 receipts clearly separated from pending 0.6 validation.
- `docs/UNOONE_MULTITASKING.md`: architecture, actual commands, resource/cancellation/recovery limits, phone qualification protocol and trace/outcome/latency/memory/cancelScope scorecard.
- `docs/images/v3-native-taskflow.svg`: conceptual implemented/future distinction; not telemetry or a phone screenshot.
- This audit: source paths, stale-handoff reconciliation and unresolved evidence boundaries.

External working handoffs reviewed: `task-app-handoff.md`, `task-core-handoff.md`, `task-explicit-app-handoff.md`, `task-interaction-handoff.md`, `task-lease-handoff.md`, `task-maintenance-handoff.md`, `task-observation-handoff.md`, `task-scope-handoff.md`, `task-transition-handoff.md`, `task-ui-handoff.md`. These workspace notes are not repository-distributed evidence artifacts.
