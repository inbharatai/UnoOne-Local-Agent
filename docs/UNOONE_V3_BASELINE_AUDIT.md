# UnoOne V3 baseline audit

Baseline main: 69bf5e097b85a1bc40da14c07609dde8ca765cbd. Audit completed before production changes. Date: 2026-10-05. Source audit is not physical qualification.

# Prism — Architecture Auditor: Android core / V3 integration audit

## Scope and provenance

- **READ ONLY source audit**, not a production patch or commit.
- Checkout: `/agent/workspace/UnoOne-Local-Agent`.
- Observed branch: `feat/unoone-v3-local-computer-use`.
- Observed HEAD: `69bf5e097b85a1bc40da14c07609dde8ca765cbd` (requested baseline `69bf5e0`). Git status was clean before and after inspection.
- This report is deliberately **outside** the checkout: `/agent/workspace/audit-core.md`.
- Unless stated otherwise, source paths below are relative to **`/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/`**. Thus `app/src/main/...` refers to that exact absolute root, not a similarly named web component.
- Findings distinguish code-proven behavior from runtime risks. Android/device tests were **inventoried, not executed**. No APK, model inference, emulator, installation, or phone side effects were run. No Gradle build was run, avoiding checkout-generated build/cache files.
- Read-only check executed: `python3 /agent/workspace/UnoOne-Local-Agent/scripts/verify_unoone_v2_invariants.py` — **PASS**, exit 0. This checks manifest/source invariants; it does not hash an installed device artifact, execute Kotlin, or prove functional safety.

## Executive assessment

The baseline has a useful modular Android application with real deterministic actions, manual model tool proposals, bounded ReAct, explicit OS access checks, confirmation UI, verified model selection, and a persistent master gate. It is **not yet a safe general-purpose local computer-use executor**. The principal release blockers are:

1. **Generic Accessibility operations are not guarded by target semantics**: payments, final Send/Submit, secrets, security settings, and destructive controls can be reached through `system_control`, independently of the blocked named-tool list.
2. **Stop/cancel is not a durable execution barrier**: pending confirmation is not invalidated; multi-step paths lack generation checks; an in-flight Accessibility helper retains its service reference; a new run can overlap the old one.
3. **Success is frequently an acknowledgement, not verified completion**: launch intents, gesture dispatch, browser handoff, Blind Aid activation, partial compounds, and halted ReAct can all become successful task records.
4. **Native lifecycle safety is only partly enforced**: phone unload cannot report refusal, transition callers can proceed anyway, and per-inference conversation cleanup can close after an unacknowledged native cancellation.
5. **Validation and observation trust boundaries are incomplete**: schema checking is only on the active model output route, context capture happens before per-tool consent, and raw model arguments are logged.

**Recommendation:** retain the architecture and tool names, but establish cancellation, target-policy, typed outcome, and native-idle invariants before exposing arbitrary V3 screen actions. Increasing loop limits or adding screenshot-driven tapping first would amplify existing failure modes.

## 1. Module map and actual control flow

### Gradle topology

`settings.gradle.kts:21-37` includes **15 modules**. `app/build.gradle.kts:72-85` depends on all 14 libraries.

| Module | Responsibility / source evidence | Project dependencies |
|---|---|---|
| `app` | Android composition, orchestrator, executor, permissions, UI, browser lease | all libraries, `app/build.gradle.kts:72-85` |
| `core` | `ToolCall`, canonical schema, runtime gate/coordinator, pure lane/ReAct/safety policies | lower-level contract module |
| `storage` | Room DAOs/entities | core, `storage/build.gradle.kts:27` |
| `modelmanager` | catalog, exact model path, artifact verification/install | core/storage, `modelmanager/build.gradle.kts:35-36` |
| `languagepacks` | speech catalogs/install management | core/modelmanager/storage, `languagepacks/build.gradle.kts:31-33` |
| `localbrain` | rule parser, Gemma planner, PageAgent planner, prompt/schema adapters | core/observability, `localbrain/build.gradle.kts:26-32` |
| `voice` | STT/TTS/wake and voice runtime | core/observability, `voice/build.gradle.kts:32-33` |
| `agentrouter` | synchronous plugin handler registry | core/safetyguard/phonecontrol/storage, `agentrouter/build.gradle.kts:26-29` |
| `safetyguard` | deterministic tool/input risk tiers | core, `safetyguard/build.gradle.kts:26` |
| `phonecontrol` | intents, calendar reads, OCR/screenshots, documents, Blind Aid | core, `phonecontrol/build.gradle.kts:26` |
| `memory` | context and outcome memory | core/storage, `memory/build.gradle.kts:26-27` |
| `skills` | persisted deterministic routines / reviewed suggestions | core/storage, `skills/build.gradle.kts:27-28` |
| `observability` | diagnostic aggregation | core/storage, `observability/build.gradle.kts:33-34` |
| `accessibilitycontrol` | Android Accessibility service and wrappers | core, `accessibilitycontrol/build.gradle.kts:26` |
| `securebrowser` | WebView/browser protocol, policies and bridge | core, `securebrowser/build.gradle.kts:31` |

App configuration: compile/target SDK 35, min SDK 28, Java/Kotlin JVM 17, Compose/Hilt/KSP/Room, release minification and resource shrinking (`app/build.gradle.kts:1-49,105-124`). LiteRT-LM is pinned to `0.13.1` (`localbrain/build.gradle.kts:31-32`). This is an Android modular monolith, not independent agent services. `AgentOrchestrator` constructs concrete components rather than injecting its parser/safety/executor interfaces (`app/src/main/java/com/unoone/agent/AgentOrchestrator.kt:161-193`); this makes full orchestration race tests harder than pure policy tests.

### Runtime pipeline (implemented)

1. `AgentOrchestrator.processCommand`: master gate, pending voice confirmation resolution, atomic command lock, sanitization; explicit voice language and fast voice replies precede ordinary planning (`.../AgentOrchestrator.kt:656-740`).
2. Enabled custom skill trigger precedes intent classification. Each stored step is rule-parsed and safety-gated; unparseable/failed steps stop the skill (`:752-835`).
3. `IntentClassifier` trusts rule matches; action-free question-shaped text goes to CHAT; unknown/action text goes to the planning path (`core/src/main/java/com/unoone/agent/core/agent/IntentClassifier.kt:79-103`). CHAT uses a tool-less conversation and failed CHAT **does not** fall through to tools (`AgentOrchestrator.kt:866-912`). Some parser comments claiming fallback are stale.
4. `CommandParser` always tries `RuleBasedParser` first. Otherwise it gathers context and calls `LocalBrain` streaming or normal inference, tracking Rule versus LLM provenance (`app/src/main/java/com/unoone/agent/parsing/CommandParser.kt:48-87`). `LocalBrain` is a thin `GemmaPlanner` facade (`localbrain/src/main/java/com/unoone/agent/localbrain/LocalBrain.kt:20-90`).
5. `GemmaPlanner` exposes command-relevant canonical schemas using manual tool calling (`.../GemmaPlanner.kt:509-519`). The active route is `PlannerToolRouter` + `CanonicalOpenApiTool`, **not automatic execution of `UnoOneToolSet` methods**. There are 29 canonical tools (`core/src/main/java/com/unoone/agent/core/model/CanonicalToolRegistry.kt:127-142`).
6. Compound subcalls, normal calls, skill steps, and ReAct follow-ups use `runValidatedToolCall`: access -> risk -> optional model judge -> block/confirm -> executor -> outcome learning/diagnostics (`AgentOrchestrator.kt:1374-1527`). Its name does **not** imply canonical argument validation; that is currently in the model adapter.
7. `ActionExecutor` checks master enable at entry, dispatches known branches, otherwise uses `AgentRouter`. Unknown unregistered plugin names return error (`app/src/main/java/com/unoone/agent/execution/ActionExecutor.kt:52-57,289-295`; `agentrouter/src/main/java/com/unoone/agent/agentrouter/AgentRouter.kt:30-33`).
8. ReAct starts only for an LLM-planned observation tool; rules never start a model conversation/loop. Maximum executed steps is 3; immediate identical proposals stop. `speak_response` ends the loop without executor invocation (`AgentOrchestrator.kt:1025-1038`; `core/.../agent/ReActLoopController.kt:30-45,59-94`). This is not an app-launch -> inspect -> act -> verify computer-use loop.

## 2. Findings: safety and execution integrity

### F01 — HIGH: sensitive controls reachable through generic Accessibility; named-tool blocking is insufficient

**Evidence:** `SafetyPipeline.classifyRisk` receives only the tool name and raw original input (`app/.../safety/SafetyPipeline.kt:50-59`); deterministic policy never reads action arguments or current target. `system_control` is STRONG_CONFIRM (`safetyguard/.../SafetyGuard.kt:49`), while prohibited tools are separate names (`:56-61`). The model judge is called only for CONFIRM, explicitly not STRONG_CONFIRM (`AgentOrchestrator.kt:1421-1436`). Execution translates `system_control` into click/type/fill/find-and-click without target-sensitive checks (`ActionExecutor.kt:512-541`). Service actions select matching/focused/editable nodes without checking password, package, semantic purpose, or final-submit status (`accessibilitycontrol/.../UnoOneAccessibilityService.kt:50-115`).

**Impact:** a model follow-up after benign original text can propose clicking a final Send/Pay/Approve control. STANDARD provides only a generic strong confirmation, not a hard sensitive-target block or meaningful action review. Rules also bypass `PlannerToolRouter`'s secret filter: e.g. a `fill passcode ...` rule is not caught by `SafetyGuard`'s password/OTP keyword list. RELAXED removes the confirmation; OFF removes block enforcement too (`AgentOrchestrator.kt:1406-1409,1451-1469`). `SecurityLevel.kt:21-26` asserts OFF is safe because blocked names have no handlers; **that argument does not cover generic Accessibility**.

**Minimal fix:** deterministic `SensitiveTargetPolicy` at the executor/service boundary consuming fresh package/node metadata + operation + value class + original user intent. Hard-deny secret fields, payment/final send/legal/security/destructive targets or require native manual takeover, regardless of SecurityLevel. Do not rely on English keyword scanning or an LLM judge. Keep release builds from disabling these invariant controls.

### F02 — HIGH: cancellation and pending confirmations can authorize an old action after Stop

**Evidence:** `cancelCurrentCommand` marks a generation and clears the processing lock but does not complete/clear `pendingVoiceConfirmation` (`AgentOrchestrator.kt:1680-1700`). `awaitConfirmation` owns a deferred Boolean with a callback independent of generation (`:1530-1572`). `runValidatedToolCall` does not accept a run token or check cancellation after the user answers (`:1374-1476`). Skill, compound, and ReAct loops do not check the original run generation at each step (`:763-829,1135-1173,1237-1308`). `shutdownForDisable` calls the same cancel path (`:1703-1710`).

**Reproduction candidate:** start `delete all notes`, wait at prompt, press Stop, then invoke the still-live approval callback. Gate remains enabled for ordinary Stop; executor accepts the old action. With master disable the entry gate blocks while disabled, but rapid re-enable can make a stale callback eligible again. This is code-path evidence; no destructive reproduction was run.

**Minimal fix:** a per-run context (Job + monotonic generation), cancellation invalidating every confirmation/pending permission/plan, checks before and after every suspension and immediately before side effects. Bind approval to run ID and immutable action digest. User cancellation must resolve pending decisions as denial, not only hide UI.

### F03 — HIGH: stale runs can release the next run's lock; in-flight lower-layer actions outlive the gate

**Evidence:** Stop immediately sets `processingLock=false` (`AgentOrchestrator.kt:1688-1689`). Old code retains execution flow and eventually calls `releaseProcessingLock`; early returns and the `finally` both release it (`:972-1000,1075-1084,1662-1667`). Release has no ownership/generation argument. New commands can therefore start before old work settles, then have their lock cleared by old cleanup. `AccessibilityControl.findAndClick` obtains one service reference, performs repeated scroll/delay/click through it (`accessibilitycontrol/.../AccessibilityControl.kt:181-194`). `getInstance()` gates acquisition (`UnoOneAccessibilityService.kt:199-201`), but the service action methods themselves lack gate checks (`:42-190`).

**Impact:** after disable during the 500 ms retry delay, retained service references can still perform an additional gesture/click. Returning UI to idle is not proof of native/action idleness. Rapid Stop/restart also corrupts shared history/timeline/confirmation ownership.

**Minimal fix:** owned mutex/job lifecycle; only the current owner may release processing state. All low-level action entry points need current enabled generation checks, not merely service acquisition. Suspendable retry loops must recheck on every iteration. Emit “stopping” until outstanding callbacks settle; distinguish stopping from stopped.

### F04 — HIGH: phone native unload refusal is not propagated to mode transitions

**Evidence:** `GemmaPlanner.close(): Unit` detects failed native idle acknowledgement and returns without closing (`localbrain/.../GemmaPlanner.kt:634-645`). `LocalBrain.unloadModel` and orchestrator unload also return Unit (`LocalBrain.kt:43-46`; `AgentOrchestrator.kt:482-484`). Browser acquire calls phone unload, then loads another engine with no acknowledgement check (`app/.../browser/SecureBrowserModelLease.kt:54-77`). Blind Aid runs unload in `runCatching` then sets active true even after failure (`AgentOrchestrator.kt:563-577`).

**Impact:** a refused close is indistinguishable from successful unload. Browser/CameraX can allocate beside an old E4B runtime. The coordinator serializes operations, but its snapshot does not enforce single resident ownership (`core/.../model/E4bRuntimeCoordinator.kt:36-45`). The browser-side `close(): Boolean` is stronger and release retains ownership on refusal (`PageAgentGemmaPlanner.kt:202-215`; `SecureBrowserModelLease.kt:147-153`).

**Minimal fix:** phone unload returns `Closed/AlreadyClosed/StillRunning/Error`; transition must fail closed until native idle and engine deallocation are proven. Reuse the browser's refusal-aware contract. Enforce ownership and enable generation inside the shared lifecycle lock, not only in caller guards.

### F05 — HIGH: per-inference cleanup bypasses the native-idle close guard

**Evidence:** callback bridge waits only 5 seconds for native cancellation; on timeout it logs and returns while nativeDone can remain incomplete (`localbrain/.../LiteRtCancellableInference.kt:102-128`). Chat and safety judge then unconditionally close their conversation in `finally` (`GemmaPlanner.kt:399-407,467-482`). Browser inference likewise always closes `stepConversation` (`PageAgentGemmaPlanner.kt:148-158`). Phone model replacement calls `closeInternal()` without the close method's idle check (`GemmaPlanner.kt:124-128`), and planning resets the old conversation similarly (`:522-524`). Browser `plan` captures `engine` before acquiring lifecycle mutex (`PageAgentGemmaPlanner.kt:125,138-141`), so a concurrent close can invalidate that captured reference before use.

**Impact:** potential JNI use-after-free / native crash when cancellation is not acknowledged; the explicit close guards do not cover all close sites. Also if a late completion arrives after `send`'s finally, it does not clear the retained activeCompletion slot, potentially leaving subsequent sends rejected by the non-null check (`LiteRtCancellableInference.kt:56,79-98,110-114`). No crash was reproduced.

**Minimal fix:** one lease-managed conversation cleanup mechanism, no native close without acknowledged idleness; quarantine/recover a wedged engine rather than reusing/closing it. Read engine/profile only while holding lifecycle ownership lock. Handle late terminal callback cleanup by compare-and-set identity.

### F06 — HIGH: screen context can be read before action consent, and secrets are not filtered from nodes

**Evidence:** `CommandParser.buildContextSnapshot` reads Accessibility text and optional screenshot OCR before model planning (`app/.../parsing/CommandParser.kt:143-176`), before `runValidatedToolCall` decides whether screen access should be confirmed. It is relevance-gated, which is useful, but not per-run consent-gated. `captureVisibleText` traverses visible text/content descriptions without excluding `isPassword` nodes, private packages, or sensitive labels (`UnoOneAccessibilityService.kt:117-140`).

**Impact:** an unmatched screen-related request may ingest content even if its eventual tool is rejected or its confirmation denied. Existing OS grants permit capture but are not the same as action-specific informed approval. Prompt text calls it “verified context,” but contains untrusted screen content; token stripping is not prompt-injection prevention (`localbrain/.../PromptBuilder.kt:69-107,167-180`).

**Minimal fix:** explicit observation authorization before capture, metadata-first safe snapshot, private-package deny rules, password/OTP/payment redaction, provenance labels and bounded lifetime. Policy must also prevent model-generated actions based on page instructions from enlarging user-authorized scope.

### F07 — MEDIUM/HIGH: confirmations lack action/target detail and policy can become stale while waiting

**Evidence:** confirmation text is only `Execute $tool?` or “This action ($tool) is sensitive” (`SafetyPipeline.kt:79-83`). Voice narration uses only a strong/non-strong flag (`AgentOrchestrator.kt:1531,1560-1564`). UI correctly requires typing confirm for strong prompts (`app/.../ui/components/ConfirmationDialog.kt:46-76`), but does not have immutable args/target metadata. Permissions/risk/security posture are read before a potentially 60-second prompt, then executor runs without refreshing the screen/target/access (`AgentOrchestrator.kt:1379-1476`).

**Impact:** user may approve a harmless-looking generic `system_control` action while a changed foreground app or newly matching node receives the operation. Approval cannot be audited against a particular target/value. Repeated prompts have identical strings.

**Minimal fix:** structured confirmation summary with operation, exact destination/recipient/target, redacted value preview and scope; bind to snapshot ID/action digest. Revalidate node identity, generation, permission, and policy after confirmation, deny on drift.

## 3. Findings: false-success and incomplete contracts

### F08 — HIGH: terminal state, telemetry and learned memory overclaim completion

**Evidence:** executor `Result.Success` unconditionally becomes successful diagnostics/outcome memory (`AgentOrchestrator.kt:1476-1494`) and “Task complete” (`:1522-1525`). Compound execution continues after `Result.Error` and mixed outcomes become `Result.Success`; persisted status is success (`:1171-1206`). ReAct planner error, no plan, stall, and maximum-step stop all reach `status = "success"` (`:1311-1340`). `GemmaPlanner` converts arbitrary no-tool model text into a successful `speak_response` (`:592-595`), and ReAct's terminal speech bypasses execution verification (`core/.../agent/ReActLoopController.kt:73-76`).

**Impact:** “could not finish” can be recorded as success; model “done” is ungrounded task evidence; learned tool reliability becomes optimistic. Partial dependent actions may continue after prerequisite failure.

**Minimal fix:** separate `ActionAttempt` and `TaskOutcome`; terminal states `VERIFIED`, `DISPATCHED_UNVERIFIED`, `NEEDS_USER`, `PARTIAL`, `FAILED`, `CANCELLED`, `BLOCKED`, `LIMIT_REACHED`. Completion requires tool-specific evidence. ReAct text never sets task success by itself. Fail-fast compound chains by default; explicit independent steps may use partial status.

### F09 — MEDIUM/HIGH: intent/gesture acceptance is still called success on several branches

**Evidence:** Chrome, URL, camera, dialer and share methods return Unit after `startActivity`, without foreground check (`phonecontrol/.../PhoneControl.kt:25-54,158-169,249-279`). Executor maps these to “Chrome opened”, “Camera active”, “Opened URL”, etc. (`ActionExecutor.kt:180-183,196-206`). Service gestures use `dispatchGesture(..., null, null)` (`UnoOneAccessibilityService.kt:42-47,171-183`), so dispatch acceptance is treated as completed gesture by wrappers/executor. Node `performAction` only proves Android accepted an operation, not task completion.

**Working improvement:** app/email/WhatsApp/calendar routes use `LaunchAttempt` and poll exact expected package, failing explicitly without Accessibility (`ActionExecutor.kt:120-177,310-328`). This is materially better than trusting startActivity.

**Remaining gap:** observed package has no timestamp/request correlation (`UnoOneAccessibilityService.kt:19-24`). If the target app was already foreground, an old package value satisfies the verifier without proving the requested draft/calendar insert is visible. Draft recipient/body and actual inserted-view fields are not checked.

**Minimal fix:** shared launch verification for all intent branches, observation freshness and expected activity/UI predicate where available. Await gesture callback, then verify expected state change. If effect cannot be verified, report dispatch/unverified rather than completed or safe-to-retry.

### F10 — MEDIUM: asynchronous UI handoffs and mode activation return completion too early

**Evidence:** Secure Browser callback invokes a Unit UI handler and immediately returns Success “Opening ... I'll start” (`AgentOrchestrator.kt:230-237`). Browser result wording is an acknowledgement, but outer orchestrator still records task success. `prepare_document_fill` similarly confirms picker dispatch (`ActionExecutor.kt:185-194`); the orchestrator wires a non-null wrapper around an optional callback (`AgentOrchestrator.kt:211-212`), so the wrapper can do nothing and still succeed. Blind Aid actions succeed even with null callbacks (`ActionExecutor.kt:211-217`); wired activation is asynchronous and may be refused by `brainReleaseGuard` (`AgentOrchestrator.kt:563-577,622-632`). Offline `web_search` is Success with unavailable text (`ActionExecutor.kt:241-251`).

**Minimal fix:** task handles with awaited readiness/completion callbacks; `NEEDS_USER` for document picking, `STARTED` for browser handoff, `UNAVAILABLE` for offline search. Blind Aid success only after actual camera/analyzer readiness, not a Boolean request. Null callbacks must error. Keep current honest textual acknowledgements, fix their machine status.

### F11 — MEDIUM/HIGH: canonical validation is not a universal executor invariant

**Evidence:** model validation checks only declared parameters, never rejects unknown keys, only tests `STRING_LIST` as `List<*>`, and lacks length/blank/enum/range semantics (`GemmaPlanner.kt:603-625`). `system_control.action` is unconstrained String (`CanonicalToolRegistry.kt:68-72`). Executor accepts undocumented `y` for coordinate long press (`ActionExecutor.kt:525-529`). Rule-created `create_skill.steps` is a pipe-delimited String, while canonical schema requires STRING_LIST (`RuleBasedParser.kt:99-108`; `CanonicalToolRegistry.kt:75-78`). Rule/tool calls go directly through safety into executor without schema validation. `LocalBrain.parseToolCall` accepts arbitrary names/args but is described as inactive fallback (`LocalBrain.kt:92-110`). Unknown permission names default to None (`core/.../safety/ToolPermissionRegistry.kt:92-98`); plugins can register arbitrary handlers (`AgentRouter.kt:20-22`).

**Minimal fix:** move a pure `ToolCallValidator` into core and apply to every source after normalization: rules, model, skills, debug, replay, plugin. Fail closed on unknown keys/names unless plugin registers complete schema+policy+access+verification metadata. Add operation enum and per-operation required fields; strict string-array members. Normalize pipe strings only in legacy parser adapter. Do not activate permissive fallback parsing as a V3 shortcut.

### F12 — MEDIUM: deterministic parser can silently drop or reinterpret user intent

**Evidence:** rules precede chat (`CommandParser.kt:61-62`); rule triggers often use `contains` instead of anchored imperative/negation handling (`RuleBasedParser.kt:336-350,384-394,411-422,434-437`). “search my notes for ...” meets the broad note-creation branch at `:411-422`; no deterministic search_notes branch exists in the inspected simple-rule section. Compound parser drops unrecognized parts (`mapNotNull`), truncates to 3 and can return one surviving part (`:221-240`). Many argument paths derive values from lowercased input, e.g. fill `:386-389`, browser tasks `:280-294`, compounds `:224-225`.

**Impact:** a question about notes can become a direct note write; “do not open chrome” can match the Chrome-open rule; partial orders appear fully handled; case-sensitive user text can be altered. These are source-derived examples, not executed Kotlin reproductions. A general-purpose V3 planner would inherit these fast-path surprises.

**Minimal fix:** anchored intent grammar/explicit negation; precise search-before-create routing; preserve original payload spans; return parsed coverage/unhandled spans instead of discarding them. Ask clarification for incomplete compounds. Keep fast paths only for exact high-confidence actions.

### F13 — MEDIUM: permission resumption restarts entire multi-step commands

**Evidence:** skill, compound and ReAct needs-access branches save the original command rather than a step cursor (`AgentOrchestrator.kt:788-801,1145-1154,1269-1277`). `clearPendingAndReExecute` calls `processCommand` anew (`:1087-1116`).

**Impact:** granting access after step 2 can duplicate step 1's note creation, draft, export, or launch. Verification errors after a dispatched action similarly cannot safely imply that retry is idempotent.

**Minimal fix:** checkpoint executed step IDs/outcomes, resume only unexecuted step after revalidation, attach idempotency keys to local writes. Require user review when prior side effect is unknown; do not automatically replay uncertain actions.

### F14 — MEDIUM: planner/lease state conflates occupied and usable; timeout recovery comments diverge

**Evidence:** `CommandParser.isModelLoaded()` intentionally includes external lease ownership (`CommandParser.kt:118-126`) while actual inference only checks the local brain. Useful to suppress duplicate self-heal, but `isLlmLoaded` describes this as available for planning (`AgentOrchestrator.kt:519-520`), and safety-judge eligibility uses it (`:1425`). `runPhoneInference` on timeout returns Error but does not close/mark unloaded (`GemmaPlanner.kt:485-507`), despite timeout/self-heal comments expecting a closed engine (`AgentOrchestrator.kt:491-505`). Streaming Error becomes `ParseOutcome.None` (`CommandParser.kt:81-86`), so the orchestrator's exception-only synchronous fallback (`AgentOrchestrator.kt:945-948`) is not taken for ordinary returned inference errors.

**Minimal fix:** distinct readiness states `PHONE_READY/BROWSER_LEASED/LOADING/STOPPING/WEDGED/DISABLED`; route/status/judge based on usable phone readiness. Bounded explicit error recovery, not exception-only fallback. Update comments/tests to actual cancellation lifecycle; no automatic retry after possible action dispatch.

### F15 — MEDIUM: privacy-redacted audit is undermined by raw model logging; accepted steps lack equivalent policy audit

**Evidence:** ordinary action logs store command-length markers and argument keys (`AgentOrchestrator.kt:734-737,1059-1065`); `AuditLogger` hashes input (`app/.../safety/AuditLogger.kt:34-45`). However `GemmaPlanner` logs full argument JSON and no-tool answer (`GemmaPlanner.kt:589,594`); `core/.../util/Logger.kt:8-11` forwards directly to Android Log with no local build gate. `runValidatedToolCall` writes safety audit for escalation/block/cancel/flaky events but not a corresponding per-step confirmed/allowed/executed policy record (`AgentOrchestrator.kt:1429-1527`). Skill/compound outer logs contain aggregate status, not each approved step's target.

**Minimal fix:** scrub raw args/text, log tool name/schema keys/lengths and correlation IDs; preserve restricted test debug output only by explicit opt-in. Emit one redacted structured per-step decision/execution/verification record including run ID, policy version, security mode, consent/action digest, and evidence class. Do not treat an unsalted input hash as strong anonymity for short predictable secrets.

## 4. Working foundations worth retaining

- **Manual model tool calling** and canonical known-name rejection; automatic model-side actions are disabled (`GemmaPlanner.kt:517-518,566-595`).
- **Real OS access registry** distinguishes Accessibility from overlay and MediaProjection from camera, and calendar insertion uses user-review intent without provider-write permission (`core/.../safety/ToolPermissionRegistry.kt:33-63`; `app/.../PermissionManager.kt:148-170`).
- **Same native safety route for skills and continuations**, with strong-confirm note deletion and fail-closed missing listener/timeout (`AgentOrchestrator.kt:776-779,1262,1530-1572`). This centralization is an appropriate V3 seam despite missing cancellation/target validation.
- **Persistent master disable closes the process gate before teardown**, stops voice, projection, floating/browser work and schedules engine release; enable is explicit (`app/.../UnoOneApplication.kt:202-282`). Preserve this and strengthen in-flight generation semantics.
- **No hidden auto-send in communication intent handlers**: email/WhatsApp are drafts and dialer is ACTION_DIAL (`PhoneControl.kt:184-259`). However generic Accessibility must not be allowed to turn those drafts into an implicit send.
- **Specific launch verification** for selected routes and explicit failure without observation instead of fabricated success (`ActionExecutor.kt:310-328`). Extend, don't remove it.
- **Exact E4B artifact selection**, declared filename/folder/size/hash verification and no largest-file fallback (`modelmanager/.../ModelManager.kt:390-416`). The read-only invariant script passed, checking one Android E4B artifact, pinned upstream revision and 2K runtime configuration.
- **One process operation mutex**, native cancellation bridge, bounded output, conservative CPU AUTO selection (`GemmaPlanner.kt:124,166-174`; `LiteRtCancellableInference.kt:49-114`). Fix incomplete close coverage rather than replacing the stack.
- **CHAT has no tools and no screen context**, bounded answer validation/retry; no action fallback after chat failure (`AgentOrchestrator.kt:866-912`; `GemmaPlanner.kt:426-482`).
- **Vision is explicitly inactive**, not an implemented visual computer-use capability: `VISION_MODEL_ENABLED=false` and OCR/context fallback (`AgentOrchestrator.kt:106-115,202-209`; `ActionExecutor.kt:442-499`). Do not advertise object/layout vision from text-only E4B.
- **Manifest least-privilege improvements**: no contacts/calendar-write/all-packages permission, exact package queries, internal services and no backup (`app/src/main/AndroidManifest.xml:34-72,74-76,105-141`). These do not substitute for Accessibility target policy.

## 5. Regression inventory and test limitations

Source inventory: **103 Kotlin test files**: **80 unit-test source files** and **23 instrumentation-test source files**. Counts are source files, not individual test methods or passing results.

| Module | `src/test` files | `src/androidTest` files |
|---|---:|---:|
| app | 22 | 23 |
| core | 23 | 0 |
| localbrain | 6 | 0 |
| modelmanager | 4 | 0 |
| securebrowser | 6 | 0 |
| phonecontrol | 3 | 0 |
| voice | 13 | 0 |
| languagepacks | 1 | 0 |
| skills | 1 | 0 |
| observability | 1 | 0 |

### Existing tests most relevant to V3

- `app/src/test/java/com/unoone/agent/execution/ActionExecutorToolCoverageTest.kt:63-79,101-127`: every manually listed tool avoids router fallback; real Room note CRUD. **Not success-proof**: the coverage loop accepts any handled Error; the Blind Aid test explicitly expects success with no callback wired. The list is manual (`:152-189`), not a strict canonical-set equality assertion.
- `app/src/test/java/com/unoone/agent/execution/ForegroundLaunchVerifierTest.kt`: expected package matching/unavailable outcomes; does not prove fresh foreground UI after intent on a device.
- `app/src/test/java/com/unoone/agent/skills/SkillSafetyRoutingTest.kt:55-148`: strong-confirm deletion allowed/denied, keyword-blocked step, unparseable skill step. Useful integration tests, but no deferred Stop/late-confirm race.
- `app/src/test/java/com/unoone/agent/safety/{SafetyPipelineTest,SecurityLevelTest}.kt` and `safetyguard/{SafetyGuardTest,SafetyGuardToolCoverageTest}.kt`: risk/access/security posture policy and coverage; generic UI semantic safety is not demonstrated by keyword tests.
- `app/src/test/java/com/unoone/agent/{RuleBasedParserTest,CompoundStepsTest}.kt`, `parsing/{CommandParserTest,ContextSnapshotTest}.kt`: deterministic routing and JSON/context behaviors.
- `core/src/test/java/com/unoone/agent/core/{agent/IntentClassifierTest,agent/ReActLoopControllerTest,agent/SafetyJudgePolicyTest,model/CanonicalToolRegistryTest,safety/ToolPermissionRegistryTest}.kt`: pure policies and registry structure, not end-to-end postconditions/native execution.
- `localbrain/src/test/java/com/unoone/agent/localbrain/ToolRegistryAgreementTest.kt:18-60`: exactly 29 tools, expected eval tool exposed, at most seven schemas, prohibited-secret prompts expose speech. **Does not prove rules use this filter, executor rejects malformed schemas, or model respects the offered subset.**
- `core/src/test/java/com/unoone/agent/core/model/{E4bRuntimeBudgetTest,E4bRuntimeCoordinatorTest}.kt`, `app/src/test/java/com/unoone/agent/ModelLoadGateTest.kt`: budget/lock coordination; JNI callback/close hazards require a fake runtime plus device validation.
- `modelmanager/src/test/java/com/unoone/agent/modelmanager/{ArtifactVerifierTest,E4bCleanupGateTest,ModelInstallerTest,ModelManifestTest}.kt`: artifact/cleanup contracts.
- `app/src/test/java/com/unoone/agent/ui/viewmodel/AgentViewModelDisableLifecycleTest.kt` and `core/.../runtime/AgentRuntimeGateTest.kt`: disable state at lifecycle/gate boundaries.
- `app/src/androidTest/java/com/unoone/agent/runtime/MasterDisableInstrumentedTest.kt:34-103`: durable preference, microphone/TTS entry points, atomic Boolean toggling, disabled command submission, explicit re-enable. The test does **not** pause an in-flight Accessibility retry, pending confirmation, native timeout, or compound step before disable.
- Device suites exist for safety, planner accuracy/eval/model diagnostics, voice command tool execution, headless CRUD/OCR/camera, and browser policies/forms/read-page; see full path appendix below. Their existence is not evidence they passed on this baseline.

### CI and what was actually verified

`/agent/workspace/UnoOne-Local-Agent/.github/workflows/android-ci.yml:30-38,122-142,174-185` defines invariant, unit-test, lint and debug APK gates with final enforcement. It also builds/tests PageAgent and Playwright. The inspected workflow has no Android emulator/connected instrumentation invocation. Java 17 is configured (`:92-96`); source comments correctly caution that LiteRT bytecode/device behavior is not covered by ordinary JVM tests. **Only the read-only Python V2 invariant check was run in this audit.**

### Must-add regression acceptance cases (ordered)

1. Stop while waiting for strong confirmation; invoke old true callback; assert zero side effects, denied terminal outcome. Repeat disable -> enable -> old callback.
2. Stop between compound/skill/ReAct steps; start a new run; old cleanup must not unlock/overwrite new run. Old run cannot perform further steps or speak completion.
3. Disable during `findAndClick` delay and gesture callback; retained service cannot scroll/click/type; result cancelled, not success.
4. Sensitive target fixtures across native apps: password/OTP/PIN/card/payment/final Send/legal acceptance/security settings/destructive buttons. Test misleading labels, localized labels, benign original prompt with dangerous generated args, all three SecurityLevels. Must fail closed/manual takeover without action.
5. Screen changes between approval and action; stale snapshot/node ID, duplicate labels, wrong package, recycled node, password focus. Deny/re-observe, never click a substitute.
6. Dispatch accepted but UI unchanged; gesture cancelled; wrong foreground package; target already foreground before request; stale window event; draft fields not populated. Outcome unverified/failed, never VERIFIED.
7. Browser task accepted but lease/load/task fails; document callback missing; Blind Aid guard refuses or camera fails; offline web lookup. Never task success or successful-use learning.
8. Compound second step fails; later dependent side effect not executed. Mixed independent results PARTIAL. ReAct planner error/stall/ceiling must not persist success. No-tool “Done” cannot certify a mutation.
9. Permission pause after first local write; grant permission/resume; write occurs once. Uncertain dispatch is not auto-replayed.
10. Table-driven universal schema validation: unknown tool/key, empty required string, wrong types, mixed array, out-of-range/nonfinite coordinates, invalid action enum, missing action-specific value, legacy skill normalization. Exercise rule/model/skill/debug/plugin entry points.
11. Fake native runtime: no cancellation acknowledgement within grace, late acknowledgement, synchronous send exception, output-token cutoff, replacement during timeout, browser acquire during load, disabled load completion. No conversation/engine close while native work live; no second resident engine; ownership retained on failure.
12. Rules: `search my notes for X`, negated imperative, question mentioning notes, more than three compound parts, one unparseable part, mixed-case form/email content. No silent reinterpretation/drop.
13. Privacy: denied observation captures nothing; sensitive nodes absent; logs contain no raw args/OCR/secrets. Policy audit complete for every executed step.

## 6. Concrete minimal V3 integration sequence

### Step A — strengthen contracts in existing modules; no new native engine

Add core contracts adjacent to `ToolCall`/`Result` rather than proliferating modules:

- `RunContext(runId, generation, cancellation, deadline)`.
- `UiSnapshot(snapshotId, capturedAt, packageName, windowId, bounded nodes)` with opaque node IDs, role/editability/password/sensitivity metadata, redacted text and provenance.
- `ActionProposal(toolCall, snapshotId, expectedPackage, targetId, expectedEffect)`.
- `ActionOutcome(status, evidence, userMessage, retrySafety)` with explicit dispatch/verified/partial/blocked/cancelled states.
- `ToolCallValidator` and `SensitiveTargetPolicy`, pure/testable in core, consumed by both planner and executor.

Keep the existing 29 public names for compatibility. Do not add hidden `y` or guessed schema fields. If V3 needs node-ID actions, version their schema explicitly and keep adapters for legacy text targets. Make every executor entry require a validated proposal and live run token. Initially reject ambiguous text matches rather than selecting the first clickable ancestor.

### Step B — fix stop and native lifecycle before introducing broader automation

- One owned command Job/mutex; pending prompt and permission continuation scoped to it. Do not free the execution lock as a UI trick.
- Runtime gate must carry a generation changed on disable/enable; old work stays revoked after re-enable.
- Guard each actual Android side effect and retry iteration.
- Return acknowledged unload state; browser and Blind Aid transition only on proven engine release.
- Centralize all conversation close sites behind native-idle discipline. On wedged native work, quarantine and retain ownership; report unavailable instead of loading a second engine.
- Split usable phone readiness from intentional browser occupancy; retain CPU AUTO until qualification demonstrates GPU correctness/stability.

### Step C — introduce an opt-in bounded V3 observe/act/verify lane

Keep exact deterministic fast paths and tool-less CHAT. Add a separate explicitly enabled computer-use lane for unmatched actionable requests, **not a global increase of existing ReAct limits**. Sequence: authorized redacted observation -> proposal -> universal validation -> deterministic target policy -> bound confirmation/takeover -> execute one action -> await callback -> fresh observation -> verify postcondition. Bound steps, elapsed time and repeated-state cycles. Do not let model text declare task success. Preserve the same SecurityLevel-independent hard safety boundary across native Accessibility and Secure Browser.

Use structured Accessibility nodes first. OCR is fallback evidence, not authority to infer a clickable coordinate. Keep screenshot/vision inactive until a vision-capable artifact is actually available and device-qualified; the current text-only E4B cannot provide a proven visual-action loop by flipping one flag.

### Step D — outcome plumbing, migration and release gates

- Teach timeline, voice responses, audit, diagnostics, outcome memory and skill-learning to consume typed outcome status; only learn successful use from VERIFIED.
- Preserve acknowledged-start UX for browser/pickers, with a task ID and later completion event.
- Migrate all intent launch branches to one verifier; verify the right strength of postcondition (app foreground is not draft ready).
- Save replay cursors/idempotency, not just original commands.
- Add failure-injection/JVM tests through parser/safety/executor/runtime interfaces before device runs. Require instrumented disable/confirmation races and targeted sensitive-control tests on the device matrix.
- Production default remains STANDARD; release cannot turn off hard target restrictions. No hidden recovery re-enables runtime or resumes old tasks.

**Minimal release definition:** a stopped run cannot act again; an approved action cannot target a different screen; a sensitive final control cannot be automated through a generic alias; one resident model owner is enforced even on timeout; and no failed/unverified/partial task is learned or presented as completed.

## Appendix A — complete Kotlin regression source inventory

The following exact relative paths were mechanically inventoried from `src/test` and `src/androidTest` (not generated/build directories). Names describe intended coverage only. They are not claimed passing test executions.

```text
app/src/androidTest/java/com/unoone/agent/languagepacks/HandsFreeVoiceActivationTest.kt
app/src/androidTest/java/com/unoone/agent/languagepacks/IndicSpeechRoundTripTest.kt
app/src/androidTest/java/com/unoone/agent/languagepacks/LanguagePackInstallTest.kt
app/src/androidTest/java/com/unoone/agent/languagepacks/LanguagePackRepairRetainTest.kt
app/src/androidTest/java/com/unoone/agent/languagepacks/SpeechEngineFunctionalTest.kt
app/src/androidTest/java/com/unoone/agent/languagepacks/SpeechNoCloudFallbackTest.kt
app/src/androidTest/java/com/unoone/agent/languagepacks/VoiceCommandToolExecutionDeviceTest.kt
app/src/androidTest/java/com/unoone/agent/localbrain/BrainEvalHarnessTest.kt
app/src/androidTest/java/com/unoone/agent/localbrain/GemmaPlannerAccuracyTest.kt
app/src/androidTest/java/com/unoone/agent/localbrain/ModelPathDiagnosticTest.kt
app/src/androidTest/java/com/unoone/agent/memory/MemoryStoreHeadlessTest.kt
app/src/androidTest/java/com/unoone/agent/phonecontrol/BlindAidCellPhoneModelTest.kt
app/src/androidTest/java/com/unoone/agent/phonecontrol/CameraAccessHeadlessTest.kt
app/src/androidTest/java/com/unoone/agent/phonecontrol/DocumentFillEngineDeviceTest.kt
app/src/androidTest/java/com/unoone/agent/phonecontrol/OcrControlHeadlessTest.kt
app/src/androidTest/java/com/unoone/agent/runtime/MasterDisableInstrumentedTest.kt
app/src/androidTest/java/com/unoone/agent/safety/AgentSafetyPipelineHeadlessTest.kt
app/src/androidTest/java/com/unoone/agent/safety/SecurityLevelHeadlessTest.kt
app/src/androidTest/java/com/unoone/agent/safetyguard/SafetyGuardHeadlessTest.kt
app/src/androidTest/java/com/unoone/agent/securebrowser/SecureBrowserPageAgentFormDeviceTest.kt
app/src/androidTest/java/com/unoone/agent/securebrowser/SecureBrowserPolicyHeadlessTest.kt
app/src/androidTest/java/com/unoone/agent/securebrowser/SecureBrowserReadPageDeviceTest.kt
app/src/androidTest/java/com/unoone/agent/storage/NotesCrudHeadlessTest.kt
app/src/test/java/com/unoone/agent/CompoundStepsTest.kt
app/src/test/java/com/unoone/agent/ModelLoadGateTest.kt
app/src/test/java/com/unoone/agent/RuleBasedParserTest.kt
app/src/test/java/com/unoone/agent/brain/BrainSelfTestPolicyTest.kt
app/src/test/java/com/unoone/agent/core/model/ResultTest.kt
app/src/test/java/com/unoone/agent/core/model/RiskAssessmentTest.kt
app/src/test/java/com/unoone/agent/core/util/CallbackMulticastTest.kt
app/src/test/java/com/unoone/agent/core/util/InputSanitizerTest.kt
app/src/test/java/com/unoone/agent/execution/ActionExecutorToolCoverageTest.kt
app/src/test/java/com/unoone/agent/execution/ForegroundLaunchVerifierTest.kt
app/src/test/java/com/unoone/agent/parsing/CommandParserTest.kt
app/src/test/java/com/unoone/agent/parsing/ContextSnapshotTest.kt
app/src/test/java/com/unoone/agent/safety/SafetyPipelineTest.kt
app/src/test/java/com/unoone/agent/safety/SecurityLevelTest.kt
app/src/test/java/com/unoone/agent/safetyguard/SafetyGuardTest.kt
app/src/test/java/com/unoone/agent/safetyguard/SafetyGuardToolCoverageTest.kt
app/src/test/java/com/unoone/agent/skills/SkillSafetyRoutingTest.kt
app/src/test/java/com/unoone/agent/ui/screens/CapabilityTest.kt
app/src/test/java/com/unoone/agent/ui/viewmodel/AgentViewModelDisableLifecycleTest.kt
app/src/test/java/com/unoone/agent/ui/viewmodel/BrowserFileSelectionTest.kt
app/src/test/java/com/unoone/agent/ui/viewmodel/LanguagePackActivationTest.kt
app/src/test/java/com/unoone/agent/ui/viewmodel/SecureBrowserTaskRoutingTest.kt
core/src/test/java/com/unoone/agent/core/agent/BlindAidNarratorTest.kt
core/src/test/java/com/unoone/agent/core/agent/IntentClassifierTest.kt
core/src/test/java/com/unoone/agent/core/agent/NarrationPolicyTest.kt
core/src/test/java/com/unoone/agent/core/agent/ReActLoopControllerTest.kt
core/src/test/java/com/unoone/agent/core/agent/ResponseTextJoinerTest.kt
core/src/test/java/com/unoone/agent/core/agent/SafetyJudgePolicyTest.kt
core/src/test/java/com/unoone/agent/core/agent/SceneDescriptionBuilderTest.kt
core/src/test/java/com/unoone/agent/core/agent/ScreenReferenceTest.kt
core/src/test/java/com/unoone/agent/core/agent/StreamingTextReducerTest.kt
core/src/test/java/com/unoone/agent/core/agent/ToolHealthTrackerTest.kt
core/src/test/java/com/unoone/agent/core/agent/VoiceFastReplyTest.kt
core/src/test/java/com/unoone/agent/core/agent/VoiceResponseLocalizerTest.kt
core/src/test/java/com/unoone/agent/core/document/DocxTemplateProcessorTest.kt
core/src/test/java/com/unoone/agent/core/document/HtmlTextExtractorTest.kt
core/src/test/java/com/unoone/agent/core/document/PlainTextExtractorTest.kt
core/src/test/java/com/unoone/agent/core/document/XlsxTextExtractorTest.kt
core/src/test/java/com/unoone/agent/core/eval/EvalScorerTest.kt
core/src/test/java/com/unoone/agent/core/memory/OutcomeMemoryPolicyTest.kt
core/src/test/java/com/unoone/agent/core/model/CanonicalToolRegistryTest.kt
core/src/test/java/com/unoone/agent/core/model/E4bRuntimeBudgetTest.kt
core/src/test/java/com/unoone/agent/core/model/E4bRuntimeCoordinatorTest.kt
core/src/test/java/com/unoone/agent/core/runtime/AgentRuntimeGateTest.kt
core/src/test/java/com/unoone/agent/core/safety/ToolPermissionRegistryTest.kt
languagepacks/src/test/java/com/unoone/agent/languagepacks/LanguagePackManifestTest.kt
localbrain/src/test/java/com/unoone/agent/localbrain/CalendarCommandParserTest.kt
localbrain/src/test/java/com/unoone/agent/localbrain/ChatAnswerValidatorTest.kt
localbrain/src/test/java/com/unoone/agent/localbrain/PageAgentGemmaPlannerPromptTest.kt
localbrain/src/test/java/com/unoone/agent/localbrain/PromptBuilderTest.kt
localbrain/src/test/java/com/unoone/agent/localbrain/RAGManagerTest.kt
localbrain/src/test/java/com/unoone/agent/localbrain/ToolRegistryAgreementTest.kt
modelmanager/src/test/java/com/unoone/agent/modelmanager/ArtifactVerifierTest.kt
modelmanager/src/test/java/com/unoone/agent/modelmanager/E4bCleanupGateTest.kt
modelmanager/src/test/java/com/unoone/agent/modelmanager/ModelInstallerTest.kt
modelmanager/src/test/java/com/unoone/agent/modelmanager/ModelManifestTest.kt
observability/src/test/java/com/unoone/agent/observability/DiagnosticsTest.kt
phonecontrol/src/test/java/com/unoone/agent/phonecontrol/CalendarInsertIntentTest.kt
phonecontrol/src/test/java/com/unoone/agent/phonecontrol/EmailDraftUriTest.kt
phonecontrol/src/test/java/com/unoone/agent/phonecontrol/ObjectLabelEvidenceTest.kt
securebrowser/src/test/java/com/unoone/agent/securebrowser/ApprovedOriginPolicyTest.kt
securebrowser/src/test/java/com/unoone/agent/securebrowser/BrowserDomainPolicyTest.kt
securebrowser/src/test/java/com/unoone/agent/securebrowser/BrowserSafetyPolicyTest.kt
securebrowser/src/test/java/com/unoone/agent/securebrowser/PageAgentTaskResultTest.kt
securebrowser/src/test/java/com/unoone/agent/securebrowser/PageTextResultDecoderTest.kt
securebrowser/src/test/java/com/unoone/agent/securebrowser/SecureBrowserNativeHandlerTest.kt
skills/src/test/java/com/unoone/agent/skills/SkillPoliciesTest.kt
voice/src/test/java/com/unoone/agent/voice/AdaptiveSpeechDetectorTest.kt
voice/src/test/java/com/unoone/agent/voice/PcmChunkAccumulatorTest.kt
voice/src/test/java/com/unoone/agent/voice/VoiceActivityPolicyTest.kt
voice/src/test/java/com/unoone/agent/voice/VoiceAgentRuntimeTest.kt
voice/src/test/java/com/unoone/agent/voice/VoiceCapturePolicyTest.kt
voice/src/test/java/com/unoone/agent/voice/VoiceConfirmationPolicyTest.kt
voice/src/test/java/com/unoone/agent/voice/VoiceLanguageMappingTest.kt
voice/src/test/java/com/unoone/agent/voice/WakeActivationGateTest.kt
voice/src/test/java/com/unoone/agent/voice/WakePhrasesTest.kt
voice/src/test/java/com/unoone/agent/voice/stt/KeywordSpotterConfigTest.kt
voice/src/test/java/com/unoone/agent/voice/stt/SherpaOmnilingualResolverTest.kt
voice/src/test/java/com/unoone/agent/voice/stt/SherpaWhisperResolverTest.kt
voice/src/test/java/com/unoone/agent/voice/stt/TranscriptQualityTest.kt
```

## Appendix B — exact primary source paths

Abbreviated evidence references above resolve to these paths; line numbers are from the unchanged HEAD inspected.

```text
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/accessibilitycontrol/src/main/java/com/unoone/agent/accessibilitycontrol/AccessibilityControl.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/accessibilitycontrol/src/main/java/com/unoone/agent/accessibilitycontrol/UnoOneAccessibilityService.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/AgentOrchestrator.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/ModelLoadGate.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/PermissionManager.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/UnoOneApplication.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/browser/SecureBrowserModelLease.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/execution/ActionExecutor.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/parsing/CommandParser.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/safety/AuditLogger.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/safety/SafetyPipeline.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/safety/SecurityLevel.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/ui/components/ConfirmationDialog.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/core/src/main/java/com/unoone/agent/core/agent/IntentClassifier.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/core/src/main/java/com/unoone/agent/core/agent/ReActLoopController.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/core/src/main/java/com/unoone/agent/core/model/CanonicalToolRegistry.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/core/src/main/java/com/unoone/agent/core/model/E4bRuntimeCoordinator.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/core/src/main/java/com/unoone/agent/core/runtime/AgentRuntimeGate.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/core/src/main/java/com/unoone/agent/core/safety/ToolPermissionRegistry.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/core/src/main/java/com/unoone/agent/core/util/Logger.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/localbrain/src/main/java/com/unoone/agent/localbrain/GemmaPlanner.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/localbrain/src/main/java/com/unoone/agent/localbrain/LiteRtCancellableInference.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/localbrain/src/main/java/com/unoone/agent/localbrain/LocalBrain.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/localbrain/src/main/java/com/unoone/agent/localbrain/PageAgentGemmaPlanner.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/localbrain/src/main/java/com/unoone/agent/localbrain/PlannerToolRouter.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/localbrain/src/main/java/com/unoone/agent/localbrain/PromptBuilder.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/localbrain/src/main/java/com/unoone/agent/localbrain/RuleBasedParser.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/localbrain/src/main/java/com/unoone/agent/localbrain/UnoOneToolSet.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/modelmanager/src/main/java/com/unoone/agent/modelmanager/ModelManager.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/phonecontrol/src/main/java/com/unoone/agent/phonecontrol/PhoneControl.kt
/agent/workspace/UnoOne-Local-Agent/android-app/UnoOneAgent/safetyguard/src/main/java/com/unoone/agent/safetyguard/SafetyGuard.kt
```


---

# Retina — Perception audit for the V3 baseline

## Scope and evidence boundary

- Checkout: `/agent/workspace/UnoOne-Local-Agent`.
- Inspected HEAD: `69bf5e097b85a1bc40da14c07609dde8ca765cbd`.
- Audit is source inspection only. Initial and final `git status --short` were empty; HEAD remained unchanged. No production edits, commits, dependency installation, builds, or device tests were performed. This report is outside the checkout.
- **Working** below means concrete, connected implementation exists, not that this audit independently qualified it on a phone. **Incomplete** means a missing guarantee, metadata, integration, or qualification. **Legacy/inactive** is not synonymous with safe-to-delete.
- Repository `STATUS.md:18–29,92–107` reports prior physical voice/master-disable/Blind Aid/OCR/camera/document/Page Agent and memory/Skills matrices, but also explicitly leaves sustained E4B qualification, acoustics, external-app regressions and browser accuracy outstanding. Do not promote those historical results into new V3 qualification.

### Path notation

All source references below are relative to the checkout. To keep evidence readable:

- `A/` = `android-app/UnoOneAgent/`.
- `AC/` = `A/accessibilitycontrol/src/main/java/com/unoone/agent/accessibilitycontrol/`.
- `PC/` = `A/phonecontrol/src/main/java/com/unoone/agent/phonecontrol/`.
- `APP/` = `A/app/src/main/java/com/unoone/agent/`.
- `CORE/` = `A/core/src/main/java/com/unoone/agent/`.
- `LB/` = `A/localbrain/src/main/java/com/unoone/agent/localbrain/`.
- `VOICE/` = `A/voice/src/main/java/com/unoone/agent/voice/`.
- `SB/` = `A/securebrowser/src/main/java/com/unoone/agent/securebrowser/`.
- `WEB/` = `web-runtime/page-agent-unoone/src/`.

## Executive findings

1. **There is substantial real perception, but not a unified perception contract.** Accessibility emits deduplicated strings, OCR emits a string, Blind Aid emits mutable-rectangle overlays and spoken callbacks, voice emits strings plus mutable last-confidence, and Page Agent works with current DOM indexes. `ContextSnapshot` is a prompt DTO, not a timestamped, provenance-bearing observation. There is no common snapshot ID that binds what was seen to what was authorized and acted upon.
2. **Action acceptance is frequently presented as completion.** Gestures have no completion callback; accessibility actions lack postcondition observation. Some phone launches do exact foreground matching, but against an untimestamped cache. Browser task IDs already prevent stale task completion, yet DOM authorization still lacks snapshot/revision binding.
3. **Screen acquisition deserves the first reliability patch.** MediaProjection correctly uses a foreground service and reuses a virtual display, but a dimensions change recreates a display on the same token despite the Android 14 one-display constraint documented in the same method. Shared static capture resources are accessed under per-instance capture locks; acquisition polls with `Thread.sleep`; padded bitmaps and exceptional resource cleanup are not normalized.
4. **Protect the offline, model-independent paths.** English/Hindi bundled OCR, shared bilingual Sherpa STT, no silent system-STT fallback, TTS stop epochs, CameraX latest-frame backpressure, session-only Blind Aid evidence, and exclusive E4B browser leases are important existing capabilities—not scaffolding to replace.
5. **Do not claim general visual understanding.** `describe_scene` is OCR plus foreground context today; the vision callback is explicitly disabled. `detect_objects` activates Blind Aid but does not return detections or prove a frame was analyzed.
6. **Minimal V3 direction:** add immutable observation DTOs plus source-scoped epochs/sequences, an event-driven bounded wait/verification service, and adapters around existing producers. Do not merge camera, browser, speech and accessibility into a heavy always-on multimodal pipeline.

## 1. AccessibilityControl and UnoOneAccessibilityService

### Working implementation

- Runtime disable gates service access and event ingestion: `AC/UnoOneAccessibilityService.kt:19–25,196–201`. Package/activity are updated from window-state events, and service connection/destruction updates the singleton (`:14–39`).
- Real text click, parent-click fallback, focused set-text and editable-field fill exist (`:50–115`). Node lists and root nodes are recycled in `finally` blocks. Global navigation and gestures are implemented (`:42–48,143–190`).
- Text extraction traverses the active tree, includes visible node text or content description, recycles children/root, and deduplicates (`:117–141`). `AC/AccessibilityControl.kt:60–78` formats it with a 100,000-character limit; click/type sanitize inputs (`:14–35`).
- Configuration already requests all event types, interactive windows and view IDs: `A/app/src/main/res/xml/accessibility_service_config.xml:3–8`. V3 can consume events without requiring a new broad capability grant.

### Incomplete guarantees / risks

- `currentPackage` and `currentActivity` are separate volatile fields, not one atomic observation; neither carries a timestamp, window ID or generation. They are not reset when the runtime is disabled. Re-enabling may expose stale cached foreground state until another event (`AC/UnoOneAccessibilityService.kt:14–25,199–201`).
- Only `TYPE_WINDOW_STATE_CHANGED` is consumed, despite subscribing to all events. Content changes, scroll events and windows changes do not drive freshness or wait completion (`:19–25`).
- The text API discards node identity, bounds, role/class, actions, editable/clickable/enabled state, view ID, window ID, hierarchy and duplicate positions. Distinct text is good for narration, not safe target selection (`:118–140`). Password/sensitive-node exclusion and traversal/node budgets are not present in this traversal.
- Text click chooses the first clickable match/ancestor and returns immediately even when its action fails; there is no ambiguity result or try-next-after-failure policy (`:53–70`). `fillFieldWithText` is text search on editable nodes, not structured hint/label association (`:96–115`).
- `dispatchGesture(..., null, null)` reports dispatch acceptance, not completed/cancelled execution (`:42–47,171–183`). Executor messages nevertheless say “Clicked”, “Scrolled” etc. (`APP/execution/ActionExecutor.kt:512–541`).
- `findAndClick` uses unconditional scrolling plus `delay(500)`, not an event/condition wait (`AC/AccessibilityControl.kt:181–194`). It ignores scroll failure and has no explicit no-progress detection.
- `CORE/core/interfaces/IAccessibilityControl.kt:9–24` is an unused abstraction in the inspected Kotlin source: the concrete `AccessibilityControl` does not implement it and its suspend signatures differ. Treat as **unintegrated legacy interface**, not the working extension point. Prefer a small new perception port or consciously repair/adopt this interface rather than assuming dependency inversion already exists.

## 2. PhoneControl and PackageResolver

### Working implementation

- `LaunchAttempt` distinguishes a requested launch from observed foreground success (`PC/PhoneControl.kt:13–21`). `openApp` handles WhatsApp consumer/business packages and reliable launch flags (`:56–80,324–330`). Calendar supports Google/Xiaomi and category fallback (`:83–156`); email and WhatsApp create reviewable drafts (`:184–246`).
- `APP/execution/ForegroundLaunchVerifier.kt:9–22` matches exact packages, with honest unavailable/mismatch messages. Executor polls 20 times at 125 ms (`APP/execution/ActionExecutor.kt:322–328,400–403`). App/calendar routes use it (`:156–178`).
- Package aliases are actually used: `PC/PackageResolver.kt:3–18`, `APP/execution/ActionExecutor.kt:168–175`.

### Incomplete guarantees / risks

- Alias mapping is ten fixed English strings, lowercase but not trim/locale-root normalized; `camera` maps to `com.android.camera`, which is OEM-sensitive. No installed-label resolution or ambiguity reporting exists (`PC/PackageResolver.kt:4–16`). Do not silently turn arbitrary model-generated package names into verified app identity.
- Foreground verification has no “observed after action” timestamp. An old matching cache can satisfy a launch; a permission/chooser transition can change what success should mean. Introduce freshness without requiring a new event if a **fresh root query** proves the app was already foreground.
- `open_url`, `open_chrome`, `open_camera`, dialer and share return launch acceptance and are rendered as “Opened”, “Camera active” etc. without the same verifier (`APP/execution/ActionExecutor.kt:180–183,196–206`; `PC/PhoneControl.kt:25–53,158–180,249–278`). Normalize result semantics before widening autonomy.
- `PhoneControl` URL sanitization is not the Secure Browser origin policy: it allows HTTP and its comment about credential rejection is broader than code, which rejects `@` only when the string does not start with `http` (`:282–305`). Keep external-launch validation separate and do not claim the browser sandbox applies to it.

## 3. OCR, MediaProjection and screenshot acquisition

### Working implementation

- OCR is real lazy-initialized bundled ML Kit Devanagari (Latin + Hindi), not a network stub (`PC/OcrControl.kt:15–35`). Screen OCR explicitly fails without projection consent (`:43–50`).
- Consent activity forwards granted data to the foreground service before projection construction; Android 14 requests the default display explicitly (`APP/screenshot/ScreenshotPermissionActivity.kt:24–45,48–64`).
- Service starts foreground with media-projection type, registers projection stop callback, installs/clears the token and gates on runtime enablement (`APP/screenshot/MediaProjectionService.kt:35–99,104–122`).
- Capture retains a shared ImageReader/VirtualDisplay for repeat captures and ignores stale-token clear callbacks (`PC/ScreenshotCapture.kt:32–71,120–141`).
- Preserve permission semantics: `read_screen` is accessibility-only; `ocr_screen` actually performs OCR and cannot silently substitute tree text (`APP/execution/ActionExecutor.kt:405–440`).

### Incomplete / high-priority defects inferred from source

- **Rotation/resize token reuse:** a width/height change releases capture resources and calls `createVirtualDisplay` again on the same `MediaProjection` (`PC/ScreenshotCapture.kt:121–140`). This conflicts with the Android 14 constraint explicitly stated at `:121–122`. Prefer resizing/rebinding the existing display/surface or explicit fresh consent where required; verify actual device behavior.
- **Concurrency:** capture is synchronized on a `ScreenshotCapture` instance (`:109–111`), but its resources are companion/shared (`:41–44`). OCR and executor each instantiate capture helpers (`PC/OcrControl.kt:23`; `APP/execution/ActionExecutor.kt:470` uses its own capturer). Static install/clear synchronization is not the same monitor. Unify ownership/locking before adding parallel perception consumers.
- **Frame freshness:** acquisition returns latest available image without recording `Image.timestamp`, request start, rotation or capture sequence. Ten 50 ms blocking sleeps are not cancellable frame-ready waits (`PC/ScreenshotCapture.kt:145–155`). A snapshot cannot currently prove it was captured after an action.
- **Geometry/resources:** output width includes row padding and is not cropped to display width (`:158–172`); image closure is not in a `finally` covering conversion; API 30+ density is fixed to `DENSITY_MEDIUM` (`:180–189`). OCR receives no coordinate transform. `recognizeScreen` does not recycle its owned bitmap (`PC/OcrControl.kt:47–50`).
- OCR returns only `visionText.text`, throwing away blocks, lines, bounding boxes and any available per-element confidence; `suspendCoroutine` does not provide cancellation and text preview is logged (`PC/OcrControl.kt:25–35`). Result.Error vs “success but blank” differs by consumer.
- **Legacy route:** `ScreenshotCapture.requestPermission/onActivityResult` (`:81–104`) directly constructs projection without the service path and has no Kotlin callers found in this checkout. Mark legacy/not recommended; do not reuse it for V3. Consent activity class comment (`:18–20`) also describes older mid-execution behavior contradicted by current gated execution/service ownership.
- Existing device OCR tests explicitly establish synthetic-bitmap OCR and permission failure, not live screenshot quality: `A/app/src/androidTest/java/com/unoone/agent/phonecontrol/OcrControlHeadlessTest.kt:18–31,50–94`.

## 4. Blind Aid, CameraX and EfficientDet

### Working implementation

- Independent of Gemma by design (`PC/BlindAidManager.kt:42–48`); dedicated single-thread analyzer, lazy MediaPipe detector, optional custom model and bundled `models/efficientdet_lite2_int8.tflite` (`:56–63,87–145`). Native linkage failure does not kill the app.
- CameraX uses async provider callback, compatible preview composition, latest-frame backpressure and RGBA analysis on its dedicated executor (`APP/ui/screens/AgentScreen.kt:983–1032`). Disposal unbinds only that session's use cases, stops queued speech and releases the manager (`:961–980`).
- Analysis samples every third frame, applies rotation, detects full-frame plus centered two-thirds crop, merges overlaps and closes/recycles images (`PC/BlindAidManager.kt:168–274,408–431`).
- Overlay coordinates are upright normalized; speech has stronger class-aware thresholds and three-hit recent evidence (`:279–310,434–445`; `PC/ObjectLabelEvidence.kt:8–31`). Scene narration and proximity warning are throttled, with haptic/tone feedback (`PC/BlindAidManager.kt:321–400`). Missing current labels are not narrated from history alone.
- Release clears scene state and speech timestamps (`:447–485`). Brain unload/reload barriers and assistive-not-certified disclaimer are present (`APP/AgentOrchestrator.kt:571–632`). TTS and UI disposal reinforce stale-speech suppression.

### Incomplete guarantees / distinctions

- `DetectionOverlay` has `List<DetectedBox>` containing mutable Android `RectF`; public overlay drops confidence and capture time (`PC/BlindAidManager.kt:30–40,283–296`). It is not deeply immutable despite Kotlin `val`/StateFlow. Overlay persistence for 1.5 s is deliberate visual smoothing, not fresh evidence (`:248–258`).
- Bounding-box area is a **proximity heuristic**, not measured distance or navigation safety (`:345–395`). Do not feed it into an agent as “obstacle at N meters” or a certified collision prediction.
- Custom-model existence selects it; initialization failure does not retry the bundled model because initialization is attempted only once (`:101–145`). An arbitrary YOLO file is not proof of MediaPipe-compatible model metadata/output. Expose unavailable vs initialized vs fresh-frame state to the UI and orchestrator.
- The code's NPU/CPU performance comment is not evidence of an explicitly selected NPU delegate: current BaseOptions sets a model only (`:115–125,174–176`). Actual sustained-device profiling remains required.
- `detect_objects` and deactivation invoke a nullable callback and unconditionally return success; no analyzed frame/detections are returned (`APP/execution/ActionExecutor.kt:211–217`). The ReAct observation-tool set nevertheless includes `detect_objects` (`CORE/core/agent/ReActLoopController.kt:39–42`). V3 should separate mode-start acceptance, camera readiness and actual scene observation.
- `release()` can await two seconds and is called in Compose disposal (`PC/BlindAidManager.kt:461–470`; `APP/ui/screens/AgentScreen.kt:979`); audit UI-thread shutdown and in-flight detector closing races before increasing frame work.
- Representative cell-phone model test is real but still-image scoped, not camera lifecycle/navigation qualification (`A/app/src/androidTest/java/com/unoone/agent/phonecontrol/BlindAidCellPhoneModelTest.kt:19–57`).

## 5. Voice, Sherpa STT/TTS and command provenance

### Working implementation

- Sherpa is default; system STT fallback is explicit opt-in and unavailable models prevent pointless recording (`VOICE/VoiceModule.kt:42–59,106–129,220–235`). System TTS fallback is separately explicit in runtime state/logging but automatically used when local TTS is unavailable (`:172–188,303–320`). Do not conflate their policies.
- Current input recognizer is **shared bilingual Omnilingual CTC**, independent of English/Hindi reply selection (`VOICE/VoiceLanguage.kt:26–53`; `VOICE/VoiceModule.kt:132–162`). English transducer and Whisper remain implementation compatibility/evaluation paths, not the current selected bilingual input route (`VOICE/stt/SherpaSttEngine.kt:79–94,97–184`).
- STT uses native Sherpa APIs with filesystem models and null AssetManager, serialized decode and graceful native-load failure (`VOICE/stt/SherpaSttEngine.kt:79–94,123–127,181–205`). Decode runs off UI thread (`VOICE/VoiceModule.kt:253–280`).
- Wake service accumulates every post-wake chunk, validates the wake transcript, routes command through an in-process callback rather than speech-bearing broadcast, and reuses application STT (`VOICE/VoiceService.kt:473–545,584–586`).
- Awaited speech gates wake capture and waits for echo decay (`VOICE/VoiceModule.kt:323–343`). Sherpa TTS uses synthesis mutex and stop epochs so completed native synthesis cannot play stale Blind Aid speech (`VOICE/tts/SherpaTtsEngine.kt:49–60,107–132,161–180`).

### Incomplete guarantees / legacy claims

- Transcript output is a plain string; confidence is mutable `lastSttConfidence`, not coupled to an utterance ID. PCM source, capture start/end, selected engine, quality result and run ID are not returned together (`VOICE/VoiceModule.kt:61–67,239–280`). Native generation/decoding is not made cancellable merely by coroutine cancellation; retain epoch/run checks when integrating event waits.
- Sherpa “confidence” is documented as heuristic `TranscriptQuality`, not model token probability (`VOICE/stt/SherpaSttEngine.kt:70–76`). Android fallback assigns 1.0 for any nonempty success (`VOICE/VoiceModule.kt:248–250`). Preserve this distinction in structured data.
- Public `transcribeWithAndroid` bypasses the normal opt-in branch if a future caller invokes it directly (`VOICE/VoiceModule.kt:290–300`); do not expose this as a generic perception fallback without the settings/runtime gate.
- Old multi-Indic/English-specific STT and TTS comments should not be mistaken for current product-language qualification. `VoiceLanguage.SUPPORTED` is English/Hindi (`:26–29`).

## 6. Memory, Skills and prompt context

### Working implementation

- Preferences/corrections/patterns and latest command-signature/tool outcomes are stored in Room; outcome failures are nonfatal (`A/memory/src/main/java/com/unoone/agent/memory/MemoryModule.kt:14–63,119–130`). Relevant context is lexical substring matching plus outcome policy rendering (`:65–98`). This is persistent user/context memory, not live perception.
- Skills validate creation limits, seed/refresh built-ins while preserving enable choice, and create **disabled** learned suggestions after repeated safe successful use (`A/skills/src/main/java/com/unoone/agent/skills/SkillsModule.kt:24–49,52–121`).
- Every skill step is reparsed and enters `runValidatedToolCall`; errors, access gaps, blocks and cancellation stop execution (`APP/AgentOrchestrator.kt:752–835`). Preserve this; skill enablement is not blanket permission.
- Context collection deliberately avoids screen text/OCR for non-screen commands; accessibility is first, OCR only when tree text is blank (`APP/parsing/CommandParser.kt:143–176`). Memory, recent note titles, enabled skill names and commands are then attached (`:178–208`).

### Incomplete / stale-data risks

- `LB/ContextSnapshot.kt:8–26` has no ID, time, provenance, source validity or privacy status. Sequential collection can combine old package/activity, new text, later OCR and persistent memory as if contemporaneous. Errors/denial are collapsed into empty strings (`APP/parsing/CommandParser.kt:148–181`). Distinguish not requested, denied, unavailable, empty and stale.
- `MemoryEntity` stores key/value/type/created/updated times only; no source snapshot or confidence (`A/storage/src/main/java/com/unoone/agent/storage/entity/MemoryEntity.kt:14–21`). Persistent outcomes should record verification status/summary, not pretend a historical successful action proves current screen state.
- Corrections and patterns key on hashes; pattern retrieval matches query terms against the hashed key rather than original trigger (`MemoryModule.kt:28–35,80–82,119–125`). This is limited lexical memory, not semantic recall.
- Preferences/corrections/query and OCR previews are logged (`MemoryModule.kt:15,29,66`; `PC/OcrControl.kt:29`); central snapshot redaction must include logs and audit renderers, not only model prompts.
- Skills persist natural-language steps, not stable perception selectors or postconditions. On permission interruption, the pending value is the whole skill command, so restart needs idempotency protection for already-completed earlier steps (`APP/AgentOrchestrator.kt:783–800`).
- `SkillsModule.getSkillSteps` retains permissive old-format fallback (`:145–151`). Treat it as compatibility debt; do not silently remove while migrating existing stored skills.

## 7. Secure Browser / Page Agent

### Working implementation

- Native bridge has strict protocol/session/nonce/origin checks, main-frame restriction and 256 KB payload bound (`SB/PageAgentProtocol.kt:16–29`; `SB/SecureWebViewController.kt:320–403`). No arbitrary Java object is exposed.
- `TASK_RESULT` drives completion, not evaluateJavascript launch acknowledgment. Per-run task IDs reject stale completion, navigation cancels old tasks and eight-minute timeout bounds the run (`SB/SecureWebViewController.kt:135–198,272–299,419–439,517–525`). This is a good existing epoch pattern to reuse, not replace.
- DOM uses full-page indexing, semantic tags/attributes, local model fetch, no JavaScript execution tool and a 12-step bound (`WEB/index.ts:28–61`). Native authorization surrounds main action wrappers (`WEB/guarded-tools.ts:91–216`). Typed controls reject ambiguous remapping and index-token match is exact (`:18–77`). File upload opens the actual field after authorization (`:220–234`).
- Current-page readback is DOM text only, separately bounded to 4,000 characters with a double-encoded WebView result decoder (`SB/SecureWebViewController.kt:201–220,529–550`).
- Sensitive-page regex masking exists before model content is sent (`WEB/content-mask.ts:7–21`; `WEB/index.ts:48`). Standard policy blocks payment, uses credential/OTP/CAPTCHA/legal takeover and confirms final submission/file transfer (`SB/BrowserSafetyPolicy.kt:39–94`).
- Exclusive browser model lease closes the main brain before browser load and preserves the lease when cancellation cannot close native work (`APP/browser/SecureBrowserModelLease.kt:19–26,45–118`).

### Incomplete guarantees / critical distinctions

- **DOM TOCTOU:** wrappers rebuild current state to summarize an index, await native authorization/user confirmation, then execute that index (`WEB/guarded-tools.ts:13–15,116–156,206–216`). No snapshot ID, document revision or target fingerprint binds the planner's observation, displayed authorization and actual element. Typed remapping checks type/uniqueness, not original identity; another same-type element can still be selected. Full-page indexing reduces churn but does not establish immutable identity.
- `BrowserActionAuthorizationRequest` carries action/summary/index/label/category, not snapshot/revision/run metadata (`SB/PageAgentProtocol.kt:73–80`). Native classification trusts the submitted summary (`SB/SecureBrowserNativeHandler.kt:70–82`); DOM content remains untrusted even on an allowed origin.
- Nonce/origin checks are not isolation from scripts already running in the admitted main page: bootstrap publishes session data into that same window (`SB/SecureWebViewController.kt:460–474`). Do not describe them as proof that a bridge request originated from trusted runtime code rather than any same-page script. V3 IDs must be validated against native run/authorization state; adding a page-supplied ID alone is insufficient.
- Masking is best effort and format-dependent, not a full sensitive-field policy. Guard summaries are made from raw controller content and native audit retains summary up to 500 chars (`WEB/guarded-tools.ts:13–24`; `SB/SecureBrowserNativeHandler.kt:124–135`). Redact at source/serialization boundaries too, particularly before persistent audit.
- **Prototype Off is a real bypass, not merely a label:** policy returns Allow for every classified action in that mode (`SB/BrowserSafetyPolicy.kt:21–36`). Prototype navigation uses wildcard listener plus native origin validation (`SB/SecureWebViewController.kt:327–334,405–417`). Keep Standard default and explicitly scope claims/tests by safety mode.
- Configured `stepDelay: 0.4` is not a shared event/condition wait (`WEB/index.ts:49–50`). Navigation invalidation exists; same-document mutation/index invalidation is not bound into native authorization.

## 8. Working vs incomplete vs inactive summary

| Surface | Working baseline | Incomplete or inactive |
|---|---|---|
| Accessibility | Real tree reading, gestures, edit/global actions | String-only observations; no stable target/snapshot IDs; no completion/postcondition wait |
| Phone launch | Exact package verification on selected routes | Cached untimed foreground; several acceptance-only routes; fixed aliases |
| OCR | Bundled English/Hindi bitmap recognition + permission failure | Text-only results; capture concurrency/resize/freshness/resource risks |
| Blind Aid | Real offline CameraX/MediaPipe detection, overlay, speech/haptic | No immutable frame contract or tool-returned detections; area is not distance |
| Scene description | OCR + app/activity narration | Multimodal callback disabled at `APP/AgentOrchestrator.kt:115,203–208`; API scaffold at `LB/GemmaPlanner.kt:302–342` |
| Voice | Shared bilingual Sherpa STT, local TTS, wake capture, epoch stop | Strings/side-channel confidence; acoustic qualification outstanding |
| Memory/Skills | Persistent context, bounded skill authoring and per-step safety | Not perception storage; no snapshot provenance; whole-skill restart/idempotency |
| Browser | DOM Page Agent, native authorization, task IDs, origin checks | No DOM revision-bound authorization; page-world trust caveat; prototype bypass |
| Legacy | Compatibility readers/model modes retained | Unused accessibility interface; old direct projection permission route; historical comments |

## 9. Minimal cohesive V3 proposal (recommendation only; not implemented)

### A. One small immutable core contract

Add `core/perception` with pure Kotlin serializable value types, not Android classes:

- `SnapshotId(sessionEpoch, source, sequence)`; do not equate hashes with freshness. IDs are opaque to the model and minted/validated by the owning trusted coordinator.
- `ObservationMeta(id, captureStartedElapsedNanos, captureCompletedElapsedNanos, origin, sourceRevision, status, truncated, redactionApplied)`; wall-clock time optional for display, monotonic time for waits/age.
- Typed payloads: `ScreenObservation(windowId, packageName, activity, nodes)`, `OcrObservation(captureRef, lines)`, `CameraObservation(frameId, modelId, objects)`, `SpeechObservation(utteranceId, transcript, engine, qualityKind, qualityScore)`, `DomObservation(browserSessionId, documentEpoch, revision, elements)`.
- Nodes/elements carry source-local IDs, plain immutable bounds, roles, label/text, action capabilities, enabled/editable/checked state and sensitivity marker. Camera detections retain confidence; OCR confidence is nullable if unavailable. Bounds explicitly name coordinate space, rotation and crop/scale transform.
- No `Bitmap`, `RectF`, `AccessibilityNodeInfo`, DOM Node, byte-array ownership or raw PCM in the public snapshot. Copy collections at construction and do not leak backing mutable objects. Keep bounded raw media in an owner-managed transient store referenced by short-lived handles.
- Status must distinguish `NOT_REQUESTED`, `DENIED`, `UNAVAILABLE`, `EMPTY`, `READY`, `STALE`, `CANCELLED`; avoid inferring consent from blank text.

Keep `ContextSnapshot` as a **derived prompt view** with `perceptionRefs`/freshness summary rather than replacing all existing parser/tool signatures at once. Persisted user memory and active skill descriptions remain clearly separate from observations.

### B. One event hub and bounded condition waiter

Expose `PerceptionEvents` with source epoch/revision, monotonically increasing sequence and latest immutable observation. Accessibility callbacks publish lightweight invalidation/event metadata; bounded collection copies needed nodes promptly and recycles all Android objects. Do not synchronously OCR or run models from event callbacks.

Provide `awaitObservation(afterCursor, source, predicate, timeout, settleWindow, cancellation)`:

1. Register the waiter/cursor before dispatch to avoid losing fast events.
2. Record action acceptance separately from platform completion (gesture callback).
3. Observe event-driven refresh until the specific postcondition passes; a quiet period alone is not success.
4. Use bounded low-frequency refresh fallback for missing Android events; return explicit timeout/unverified status, not “Done”.
5. Support the already-satisfied condition through a fresh preflight observation; don't wait forever for a redundant window event.
6. Cancel and bump epochs on master disable, service death, projection stop, mode close, browser navigation/takeover, user interruption and command cancellation.

Migrate fixed scroll delay and foreground polling first. Preserve existing maximum wait budgets until device measurements justify changes. Replace screenshot sleeps with ImageReader frame-ready callbacks and freshness criteria under a single projection owner. No OCR/camera/microphone permission acquisition belongs inside a generic wait.

### C. Bind targets and approvals to the observation that justified them

`ActionTarget(snapshotId, nodeId, targetFingerprint)` plus `ActionReceipt(runId, actionId, beforeId, accepted, completed, afterId, verification)` is sufficient; avoid a parallel planner architecture.

- Executor revalidates source epoch, target identity, bounds/capabilities, runtime enabled state and current approval immediately before execution. On mismatch return `STALE_TARGET` and request re-observation/replanning; do not silently guess/remap.
- For browser, extend protocol version deliberately with document epoch/revision, snapshot ID and target fingerprint; associate native authorization with current run/action ID and expire it on navigation/mutation affecting target, takeover or confirmation delay. Refresh/revalidate after user approval. Native should own authorization state, not trust opaque IDs supplied by page JS.
- For accessibility, choose deterministic node identity per snapshot plus resource ID/class/ancestry/bounds fingerprint for re-resolution; never retain live node objects across model latency.
- Attach verification receipt IDs to outcome-memory records; retain only minimal redacted summary. Skill resumes should use step checkpoints/idempotency rather than replaying completed side effects.

### D. Adapt, don't couple, the independent paths

- Accessibility is primary for phone UI. OCR is opt-in/fallback under existing screen-reference/permission policy, and a screenshot-derived OCR result refers to the same capture ID/transform.
- Blind Aid publishes an optional small immutable camera observation without waiting for Gemma or changing its independent safety feedback loop. Mode-start tool returns accepted/readiness status; a distinct observation path can await the first valid frame.
- Voice produces utterance-scoped metadata and immutable quality instead of mutable “last confidence”; preserve the single shared recognizer and speech stop epochs.
- Browser preserves exclusive model lease and task IDs; use adapters to a shared receipt/event vocabulary, not a second engine or accessibility scraping of the WebView.
- Bound snapshots by node/character count, age and small in-memory ring size. No automatic persistent screen/camera/audio archive; logs redact at every serialization boundary.

## 10. Regression protection and acceptance gates

No tests below were run by this audit. Existing named suites should be retained and expanded; source existence/history is not a new pass.

1. **Accessibility freshness/targets:** duplicated labels, parent fallback, focused vs wrong field, password redaction, capped traversal, disabled/re-enabled stale cache, rotation, content-only changes, callback cancelled gestures and recycled nodes. Assert actions cannot consume an invalid snapshot; same-app-already-foreground can pass a fresh preflight.
2. **Launch semantics:** keep `ForegroundLaunchVerifierTest`, WhatsApp business fallback, Google/Xiaomi Calendar MIME+URI contract, draft-only email/WhatsApp and exact package matching. Extend acceptance-vs-verification assertions to URL/Chrome/camera/dialer/share.
3. **Projection/OCR:** preserve `OcrControlHeadlessTest` Latin/Hindi recognition and denied consent. Add live Android 14/15 grant/repeat capture/rotate/resize/revoke/regrant; concurrent OCR+describe requests; padded rows; conversion exceptions; cancellation and disable while waiting. Verify no second display per token, no stale frame used as post-action proof, correct bitmap disposal.
4. **Blind Aid:** retain `ObjectLabelEvidenceTest`, `BlindAidNarratorTest`, `BlindAidCellPhoneModelTest` and camera headless suite. Device-test preview lifecycle under E4B unload/reload, multiple activities, detector failure/custom model incompatibility, crop rotation/overlay alignment, current-label speech gating, cooldown/escalation and no old speech/boxes after close. Profile sustained thermals, not just still images.
5. **Voice:** retain speech functional/no-cloud-fallback/Indic round-trip/hands-free/voice-command device suites, wake policies and accumulator unit suites. Test engine unavailable, opt-in fallback, mixed Hindi/English, no duplicated recognizer on language switch, microphone handoff, TTS echo gate and noncancellable native synthesis finishing after stop. V3 quality must not mislabel heuristic scores as calibrated confidence.
6. **Memory/Skills:** preserve `MemoryStoreHeadlessTest`, `SkillPoliciesTest`, `SkillSafetyRoutingTest`; check disabled suggestion behavior, per-step permission/safety, no sensitive observation persistence, verified-vs-accepted outcome memory and no duplicate earlier actions after permission resume.
7. **Browser:** preserve Page Agent TS masking/guard tests, Playwright forms and native `SecureBrowserNativeHandlerTest`, `PageAgentTaskResultTest`, page-read decoder/device suites. Add DOM mutation during slow model/confirmation, same-type index reuse, navigation during authorization, old-run completion, same-page malicious bridge calls, file chooser cancel, Standard-vs-Prototype mode assertions, and sensitive values absent from summaries/audit. Validate exclusive model lease under native cancellation failure.
8. **V3 contract pure tests:** deep-copy immutability; cross-source IDs never interchangeable; monotonic ordering; missed-event race; stale/cancelled generations; timeout != success; no snapshot collection for non-screen queries; prompt truncation retains metadata; permissions never broaden implicitly.

## Recommended implementation order

1. Freeze regression cases above and introduce immutable DTOs/IDs with adapters (no user-facing routing change).
2. Fix capture ownership/resize/resource handling and publish frame metadata; make accessibility publish atomic foreground/window observations.
3. Add bounded event waits and verification receipts to existing executor paths; correct acceptance-only success messaging.
4. Bind DOM/accessibility targets and authorization to snapshot identity; add stale-target regression tests before enabling it broadly.
5. Expose camera/utterance observations and memory outcome provenance only where useful, keeping Blind Aid/voice independent.

**Bottom line:** preserve the working V2 producer paths and safety contracts. V3's missing layer is immutable evidence with identity, freshness and condition-based verification—not another detector, another LLM, or broader autonomous permissions.


---

# QuarryTwo — read-only model/runtime audit

## Scope and verdict

Repository: `/agent/workspace/UnoOne-Local-Agent`, HEAD `69bf5e097b85a1bc40da14c07609dde8ca765cbd`. Initial `git status --short` was empty. Research resumed from `/agent/workspace/model-research-evidence`; downloaded source snapshots are timestamped 2026-10-05. No production files changed; no model downloaded; no Android device/inference exercised. Paths below are relative to the repository; `A` means `android-app/UnoOneAgent`.

**The baseline is an E4B-only, text-input LiteRT-LM runtime, not an E2B multimodal implementation.** The official E2B Android artifact is fully identified in the companion `/agent/workspace/model-research.md`, but changing a manifest alone cannot enable it: registry, budgets, cleanup qualification and CI deliberately encode E4B. Historical E2B evidence is useful but is never evidence of V3 validation, current E4B qualification, or image reasoning.

## Current contracts and exact touchpoints

| Area | Exact path | Observed contract |
|---|---|---|
| Registry | `A/core/src/main/java/com/unoone/agent/core/model/BrainModel.kt:10-88` | Only `GEMMA_4_E4B`; 8,192 MB minimum / 12,288 recommended; 2,048 default context; `isDeviceVerified=false`; all persisted IDs resolve to E4B. |
| Install catalogue | `A/modelmanager/src/main/assets/models_manifest.json:1-20` | Schema version 3 (not product V3); only LLM is `gemma-4-e4b`; immutable revision `28299f30ee4d43294517a4ac93abd6163412f07f`, 3,659,530,240 bytes, SHA `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0`. |
| Manifest parsing | `A/modelmanager/src/main/java/com/unoone/agent/modelmanager/ModelManifestLoader.kt:21-48` | Parse/read failure produces empty catalogue; unknown JSON keys ignored. Semantic path/hash validation is mainly CI, not parser. Signed APK is the bundled manifest trust boundary. |
| Model resolution | `A/modelmanager/src/main/java/com/unoone/agent/modelmanager/ModelManager.kt:388-422` | Exact filename/folder/size plus SHA verification; no largest-file or arbitrary-model fallback; explicit verification can force full hash. |
| Integrity cache | `A/modelmanager/src/main/java/com/unoone/agent/modelmanager/ArtifactVerificationCache.kt:78-170` | Metadata-bound persisted proof (hash expectation, size, canonical path, mtime, manifest/model version); per-model verification mutex. |
| Runtime dependency | `A/localbrain/build.gradle.kts:32` | `com.google.ai.edge.litertlm:litertlm-android:0.13.1`. |
| Phone engine | `A/localbrain/src/main/java/com/unoone/agent/localbrain/GemmaPlanner.kt:119-247` | Process operation lock, CPU-only AUTO, explicit developer CPU/GPU override; no vision backend in EngineConfig; text user message; manual tool proposals. |
| Browser engine | `A/localbrain/src/main/java/com/unoone/agent/localbrain/PageAgentGemmaPlanner.kt` | Separate browser planner using same model family/runtime; governed through browser lease. |
| Native serialization | `A/core/src/main/java/com/unoone/agent/core/model/E4bRuntimeCoordinator.kt:28-45` | One process-wide operation mutex and observable state; state transition function is not a validated transition graph. |
| Memory ownership | `A/app/src/main/java/com/unoone/agent/browser/SecureBrowserModelLease.kt:45-169` | Exclusive lease; unload phone before browser; restoration on release; retain lease if browser native close fails. |
| Budgets | `A/core/src/main/java/com/unoone/agent/core/model/E4bRuntimeBudget.kt:9-35` | E4B-only assertion; context 2,048; phone 256, chat 96, browser 384 output tokens. |
| Native stop | `A/localbrain/src/main/java/com/unoone/agent/localbrain/LiteRtCancellableInference.kt:33-128` | Callback API, `cancelProcess()`, five-second callback grace; prevents closing while native completion unresolved. |
| Legacy deletion | `A/modelmanager/src/main/java/com/unoone/agent/modelmanager/E4bQualificationRecord.kt:19-34`; `ModelManager.kt:174-235` | Hash/size + load/self-test/eval/sustained/no-crash flags + nonblank backend/device identity + user approval; old integrity-only delete is inert. |

## Findings requiring action

### M1 — High: archive health can certify incomplete extraction

`ModelInstaller.kt:120-124,174-176,234-237` treats any nonempty extracted directory as a complete archive. `ModelManager.kt:62-70,95-100` skips archive hash and per-file verification, allowing `verified=true` for a nonempty directory. A crash after the first extracted file, missing files or later mutation is not detected. Archive entries exist for Indic ASR and English espeak data in `models_manifest.json:46,75`.

**Recommendation:** extract into staging, reject unsafe entries, validate required output files with hashes/sizes, atomically publish directory or completion marker bound to archive identity. Mark partial/unverifiable archives unverified. Add interrupted extraction and missing/corrupt member tests. This is observed control flow, not a demonstrated exploit.

### M2 — High: final filename is published before verification; fallback is not atomic

`ModelInstaller.kt:250-267` hashes *after* `attemptDownload`; the latter commits at `306-308,377`. `commitTemp:457-464` deletes old target and renames or copies into final path. Thus the advertised atomic verified activation contract is stronger than implementation: unverified bytes can briefly occupy final filename, a crash can leave them there, and fallback copy is non-atomic. The LLM resolver does independently hash, so this is **not** evidence that a bad E4B is successfully loaded through `getLlmModelPath`.

**Recommendation:** verify `.part` first; keep old known-good target until verified atomic replace; fail safely rather than copy directly into final path; coordinate install/repair/uninstall with runtime use. Test process death before/after verification and rename failure.

### M3 — Medium: proof cache is per model, not per artifact

`ArtifactVerificationCache.kt:29-32,137,145` and Preferences store use `modelId` key. Multi-file speech models rotate the same cache entry for each file. `ModelManager.installModel:143-149` seeds every file under that same key; sequential health checks usually rehash all files. Single-file Gemma benefits; multi-file packs do not.

**Recommendation:** key on model ID + relative artifact identity, invalidate model namespace on uninstall, add a two-file repeated-health test. Cache identity uses size/mtime, not fresh content; force hash for explicit integrity checks and qualification. Do not portray a cache hit as a newly calculated digest.

### M4 — High release gate: current model/device and image capability are unqualified

Registry explicitly says `isDeviceVerified=false`. AUTO uses CPU because there is no GPU qualification (`GemmaPlanner.kt:166-174`). EngineConfig at `203-211` sets no vision backend; source search found only a future-facing vision comment, not image engine initialization. E2B/E4B bundle modality support does not imply this app consumes pixels.

**Recommendation:** separate artifact-verified, engine-loaded, text-qualified, image-qualified and device-qualified statuses. For the proposed E2B work, add typed image input and opt-in vision initialization only in a separately approved implementation phase; measure native cancellation, image preprocess/transforms and memory on target hardware. Do not turn qualification true from metadata lookup.

### M5 — Medium: cleanup qualification identity is incomplete

`E4bCleanupGate` requires matching hard-coded model SHA/bytes but only nonblank backend and `deviceBuild`. It does not compare the record to current OS/device/app/runtime/backend identity or check record age; `qualifiedAtMs` is unused by gate. It is also E4B-specific. Good: explicit user approval and all boolean gates are necessary, and the old checksum-only cleanup method preserves E2B.

**Recommendation:** bind evidence to artifact + device fingerprint + app build + runtime version + backend/config + suite revision; reject stale/mismatched records. Never delete fallback solely because an alternative artifact downloaded or loaded.

### M6 — Medium: latent manifest/path validation and install concurrency gaps

Parser deserializes without validating descriptor folder/file/extractsTo; installer constructs paths directly (`ModelInstaller.kt:73,118,230-232`). CI constrains current bundled paths, so this is a defense-in-depth boundary rather than a remotely supplied manifest vulnerability. Installer also has shared mutable failure reason/retryability and no internal per-model install mutex. The artifact verification lock does not serialize full install lifecycle.

**Recommendation:** validate canonical child paths and all integrity fields at runtime as well as CI; use per-install result state and a per-artifact download lock. Test same-model concurrent install, cancellation/status consistency, and malformed descriptor rejection before expanding catalogue sources.

### M7 — Test/release evidence must count skipped model tests

`A/app/src/androidTest/java/com/unoone/agent/localbrain/GemmaPlannerAccuracyTest.kt:34,48,72,98,132` and `BrainEvalHarnessTest.kt:56` use `assumeTrue` if verified E4B path absent. A green instrumentation process can contain zero model runs. Historical evidence already demonstrates this exact trap.

**Recommendation:** qualification runner must fail when any mandatory case is skipped, record actual backend/artifact/build and executed counts, assert semantic outputs (not only inference completion), retain native logs and resource measurements.

## Historical validation — explicitly not current V3 evidence

- `artifacts/validation/xiaomi14/20260714-174410-PHASE3-DEVICE/PHASE5-GEMMA-ON-DEVICE.md`: historical branch `validation/xiaomi14-end-to-end` at `932e9ed`, v0.4.0-alpha-v2. Reports E2B hash `181938...a63c`, exact 2,588,147,712 bytes, Xiaomi 14/SM8650, GPU initialization failure then CPU fallback, 7,556 ms cold load, 18/18 tool-match suite. These are recorded historical results, not rerun here.
- Same report lines 9-26: initial `OK (4 tests)` in 0.061 s was actually assumptions due to app-inaccessible shell-owned directories. Later report records 77.657 s and no ignored tests. Do not cite the first green output.
- Report explicitly says 18 cases, not aspirational 50 phone + 50 browser; no explicit TTFT/tokens-per-second counters. It does not validate image input, current E4B, new runtime, current branch or a V3 system.
- `artifacts/validation/laptop/PHASE2_GATES.md`: historical July 13 baseline `90e879e`, 294 JVM tests, suppressed lint baseline; not proof of current HEAD gates.

## CI/tests inspected and execution this audit

`.github/workflows/android-ci.yml` runs repository invariants, PageAgent typecheck/tests/bundle/Playwright, lint, JVM tests and APK build. Recorded step failures are enforced in final gate at lines 174-185; intermediate `exit 0` is diagnostic collection, **not** a bypass. No physical model inference or image correctness job is present.

`scripts/ci/check_repo_invariants.py:50-57,105-206` pins E4B exact identity, excludes E2B from install catalogue, requires a single LLM and schema version 3. A research E2B addition deliberately fails these guards until a separately approved design changes them; do not silently relax checks.

Existing unit coverage:
- `A/modelmanager/src/test/java/com/unoone/agent/modelmanager/ModelInstallerTest.kt`: download/checksum, resume, oversized part, Content-Range mismatch, cancellation preservation, retryable network, corrupt size, archive and asset installs, idempotence, complete-part handling.
- Same directory: `ArtifactVerifierTest.kt`, `E4bCleanupGateTest.kt`, `ModelManifestTest.kt`.
- `A/core/src/test/java/com/unoone/agent/core/model/E4bRuntimeBudgetTest.kt`, `E4bRuntimeCoordinatorTest.kt`.

**Executed now:** `python3 /agent/workspace/UnoOne-Local-Agent/scripts/ci/check_repo_invariants.py` → `UnoOne V2 invariants passed`, exit 0. First attempted `python` alias was unavailable; reran with `python3`. No Gradle build/test, instrumentation, JNI race tests, full artifact hash or sustained device run was performed. No broad PASS claim is made.

## Prioritized next phase (not performed)

1. Keep release blocked on real current artifact/backend/device qualification; preserve historical E2B safely.
2. Fix archive verification and verify-before-activate transaction semantics; add failure-injection tests.
3. Introduce an explicit model/capability contract before adding E2B/Qwen; update registry, budgets, runtime coordination, cache, cleanup and CI consistently rather than renaming E4B fields.
4. Spike E2B image input using exact official artifact/API from companion research; require screenshot freshness, privacy filtering, transform bookkeeping and action revalidation outside model output.
5. Treat Qwen/MNN as a separate experimental engine adapter and AppFunctions as optional permission-gated platform integration, not drop-in model replacements.
