package com.unoone.agent.task

import android.content.Context
import com.unoone.agent.AgentOrchestrator
import com.unoone.agent.core.model.InputType
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.ExclusiveBrainLeaseState
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.core.runtime.GlobalTaskCancellation
import com.unoone.agent.core.task.*
import com.unoone.agent.storage.dao.NoteDao
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

/** Exact native tool+arguments binding. Neither a model-chosen tool nor changed arguments inherit a grant. */
object TaskToolAuthorization {
    fun handle(call: com.unoone.agent.core.model.ToolCall): String {
        fun canonical(value: kotlinx.serialization.json.JsonElement): String = when (value) {
            is kotlinx.serialization.json.JsonObject -> value.entries.sortedBy { it.key }
                .joinToString(prefix = "{", postfix = "}") { kotlinx.serialization.json.JsonPrimitive(it.key).toString() + ":" + canonical(it.value) }
            is kotlinx.serialization.json.JsonArray -> value.joinToString(prefix = "[", postfix = "]") { canonical(it) }
            else -> value.toString()
        }
        val bytes = (call.tool + "\n" + canonical(call.args)).toByteArray(Charsets.UTF_8)
        return "tool-sha256:" + java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

class TaskScopePreparingException : IllegalStateException("Stored skill scopes are preparing; retry shortly")

/** Output is memory-only and keyed by receipt, never stored in the metadata journal. */
data class NativeTaskOutput(val result: TaskResult, val text: String)

class NativeTaskRuntime(context: Context, private val orchestrator: AgentOrchestrator,
    private val notes: NoteDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val journal = TaskJournalStore(context)
    private val historyLock = Any()
    private val clearedTaskIds = mutableSetOf<TaskId>()
    val recoveredTasks get() = journal.recoveredTasks
    val journalHealth get() = journal.health
    private val output = MutableStateFlow<Map<TaskId, NativeTaskOutput>>(emptyMap())
    val results: StateFlow<Map<TaskId, NativeTaskOutput>> = output.asStateFlow()
    private val command = WorkerKind("command")
    private val voice = WorkerKind("command-voice")
    private val search = WorkerKind("notes-search")
    private val draft = WorkerKind("draft")
    private val preparation = WorkerKind("preparation")
    private val owl = WorkerKind("owl-screen")
    private val owlRequests = java.util.concurrent.ConcurrentHashMap<String, Pair<com.unoone.agent.owl.OwlTaskConsent, List<com.unoone.agent.NativeDeviceGoal>>>()
    val coordinator = TaskCoordinator(listOf(
        WorkerRegistration(owl, WorkerLane.INTERACTIVE, NativeTaskWorker { ctx ->
            val request = owlRequests.remove(ctx.instruction)
                ?: return@NativeTaskWorker finish(ctx, TaskResult(TaskOutcome.NEEDS_USER), "Consent expired")
            com.unoone.agent.voice.VoiceService.beginForegroundTask()
            try {
                journal.record(ctx.taskId, ReceiptStage.PREPARED)
                val outcome = withContext(NativeTaskExecution(ctx, journal)) {
                    val execution = requireNotNull(currentCoroutineContext()[NativeTaskExecution])
                    fun checkApproval() { request.first.checkApproval(); ctx.checkActive() }
                    ProcessTaskResources.ui.withLease(ctx.taskId, ::checkApproval) {
                        fun checkOwned() {
                            checkApproval()
                            check(ProcessTaskResources.ui.owner() == ctx.taskId) { "Owl UI ownership revoked" }
                            check(com.unoone.agent.phonecontrol.ScreenshotCapture.hasPermission()) { "Owl capture permission revoked" }
                            check(com.unoone.agent.accessibilitycontrol.UnoOneAccessibilityService.getInstance() != null) { "Owl accessibility unavailable" }
                            check(orchestrator.isLlmLoaded() && orchestrator.loadedBrainProfile()?.id == com.unoone.agent.core.model.BrainModelId.GUI_OWL_1_5_4B_INSTRUCT) { "Resident Owl profile required before navigation" }
                        }
                        com.unoone.agent.owl.runApprovedOwlStart(
                            ownPractice = request.first.packageName == context.packageName,
                            checkActive = ::checkOwned,
                            beforeLaunch = { execution.beforeEffect(TaskCapability.UI_WRITE) },
                            launch = {
                                context.startActivity(android.content.Intent(context, com.unoone.agent.owl.OwlPracticeActivity::class.java)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        ) { orchestrator.runOwlTask(request.first, request.second) }
                    }
                }
                finish(ctx, TaskResult(if (outcome.status == com.unoone.agent.core.device.DeviceOutcomeStatus.VERIFIED) TaskOutcome.VERIFIED else TaskOutcome.NEEDS_USER), outcome.reason)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { finish(ctx, TaskResult(TaskOutcome.NEEDS_USER), "Owl stopped: capture/model/scope check failed; no replay") }
            finally { com.unoone.agent.voice.VoiceService.endForegroundTask() }
        }),
        WorkerRegistration(preparation, WorkerLane.BACKGROUND, NativeTaskWorker { ctx ->
            val payload = org.json.JSONObject(ctx.instruction)
            val children = listOf(
                ChildRequest(RequestId(UUID.randomUUID().toString()), search, payload.getString("query"),
                    TaskScope(setOf(TaskCapability.LOCAL_READ))),
                ChildRequest(RequestId(UUID.randomUUID().toString()), draft, payload.getString("draft"),
                    TaskScope(setOf(TaskCapability.MODEL)))
            )
            for (child in children) {
                ctx.checkActive()
                if (ctx.delegate(child) is Admission.Rejected)
                    return@NativeTaskWorker WorkerResult.Finished(TaskResult(TaskOutcome.FAILED, TaskReason.CHILD_FAILED))
            }
            // Distinct outputs: draft is not grounded in the sibling's notes, and neither is web research.
            WorkerResult.WaitingForChildren
        }),
        WorkerRegistration(command, WorkerLane.INTERACTIVE, NativeTaskWorker { runCommand(it, InputType.TEXT) }),
        WorkerRegistration(voice, WorkerLane.INTERACTIVE, NativeTaskWorker { runCommand(it, InputType.VOICE) }),
        WorkerRegistration(search, WorkerLane.BACKGROUND, NativeTaskWorker { ctx ->
            ctx.beforeAction(TaskCapability.LOCAL_READ)
            val matches = withContext(Dispatchers.IO) { notes.searchBounded(ctx.instruction.trim()) }
            ctx.checkActive()
            finish(ctx, TaskResult(TaskOutcome.RESPONDED), boundedTaskText(matches.asSequence().flatMap { sequenceOf(it.title, "\n", it.content, "\n\n") }))
        }),
        WorkerRegistration(draft, WorkerLane.BACKGROUND, NativeTaskWorker { ctx ->
            if (ExclusiveBrainLeaseState.isActive())
                return@NativeTaskWorker finish(ctx, TaskResult(TaskOutcome.NEEDS_USER), "Local model is in use; retry after the current owner releases it.")
            val request = try { DraftRequest.decode(ctx.instruction) }
                catch (_: Exception) { return@NativeTaskWorker finish(ctx, TaskResult(TaskOutcome.NEEDS_USER), "Invalid native draft request; no draft verified.") }
            val execution = DraftQualityGate.execute(request) { prompt, phrases ->
                ProcessTaskResources.model.withLease(ctx.taskId, ctx::checkActive) {
                    if (ExclusiveBrainLeaseState.isActive()) Result.Error("MODEL_BUSY", TaskModelBusy())
                    else {
                        ctx.beforeModelCall()
                        orchestrator.chatForTask(prompt, phrases)
                    }
                }
            }
            ctx.checkActive()
            val checks = execution.attempts.mapIndexed { index, attempt ->
                "Attempt ${index + 1}: ${attempt.checks.metadata()}"
            }.joinToString("\n")
            val status = if (execution.outcome == TaskOutcome.RESPONDED) "Draft (facts unverified):"
                else "Unverified draft — needs user review. Exact checks failed or model unavailable:"
            finish(ctx, TaskResult(execution.outcome), "$checks\n$status\n${execution.last?.text.orEmpty()}")
        })
    ), scope)
    val tasks = coordinator.tasks
    init {
        scope.launch(Dispatchers.IO) {
            val last = mutableMapOf<TaskId, TaskSummary>()
            tasks.collect { summaries ->
                summaries.forEach { summary ->
                    if (last[summary.id] != summary) {
                        synchronized(historyLock) {
                        if (summary.id !in clearedTaskIds) try {
                            journal.recordSummary(summary)
                            last[summary.id] = summary
                        } catch (_: Exception) { /* journal health stays failed; synchronous intents fail closed */ }
                        }
                    }
                }
                last.keys.retainAll(summaries.map { it.id }.toSet())
            }
        }
    }
    // AgentOrchestrator's existing global-stop registration revokes this coordinator first,
    // then performs its native teardown. Do not register a second generation increment here.

    private suspend fun runCommand(ctx: TaskContext, type: InputType): WorkerResult {
        com.unoone.agent.voice.VoiceService.beginForegroundTask()
        try {
            journal.record(ctx.taskId, ReceiptStage.PREPARED)
            val result = withContext(NativeTaskExecution(ctx, journal)) {
                ProcessTaskResources.ui.withLease(ctx.taskId, ctx::checkActive) {
                    orchestrator.executeAcceptedTask(ctx, type)
                }
            }
            return finish(ctx, result.result, result.text)
        } finally {
            com.unoone.agent.voice.VoiceService.endForegroundTask()
        }
    }
    private fun finish(ctx: TaskContext, result: TaskResult, text: String): WorkerResult {
        val bounded = boundedTaskText(sequenceOf(text))
        // Only bounded in-memory publication runs under the coordinator cancellation lock.
        // Terminal metadata is written by the existing IO summary collector. A crash before
        // that write leaves the durable intent needing review, never replay authority.
        ctx.complete(result) {
            synchronized(output) {
                output.value = (output.value + (ctx.taskId to NativeTaskOutput(result, bounded)))
                    .entries.toList().takeLast(128).associate { it.toPair() }
            }
        }
        return WorkerResult.Finished(result)
    }
    private fun submit(kind: WorkerKind, text: String, taskScope: TaskScope, source: TaskSource,
        generation: Long): Admission = synchronized(historyLock) {
        if (journal.health != "HEALTHY") return@synchronized Admission.Rejected(RejectionReason.CLOSED)
        if ((kind == search) && (text.isBlank() || text.length > 4000))
            return@synchronized Admission.Rejected(RejectionReason.INVALID_REQUEST)
        if (!AgentRuntimeGate.isEnabled()) return@synchronized Admission.Rejected(RejectionReason.CLOSED)
        return@synchronized coordinator.submit(TaskRequest(RequestId(UUID.randomUUID().toString()), kind,
            text, taskScope, generation, source,
            priority = if (kind == command || kind == voice) TaskPriority.INTERACTIVE else TaskPriority.NORMAL))
    }
    fun submitCommand(text: String, inputType: InputType = InputType.TEXT): Admission {
        val generation = coordinator.captureGeneration()
        if (orchestrator.handleImmediateInput(text, inputType)) return Admission.Rejected(RejectionReason.INVALID_REQUEST)
        val authorized = try { orchestrator.authorizeTaskScope(text) }
            catch (_: TaskScopePreparingException) { return Admission.Rejected(RejectionReason.PREPARING) }
            catch (_: IllegalArgumentException) { return Admission.Rejected(RejectionReason.INVALID_REQUEST) }
        return submit(if (inputType == InputType.VOICE) voice else command, text,
            authorized, if (inputType == InputType.VOICE) TaskSource.VOICE else TaskSource.TEXT, generation)
    }
    /** Suspending UI/compatibility entry: generation is captured BEFORE any Room suspension. */
    suspend fun submitPreparedCommand(text: String, inputType: InputType = InputType.TEXT,
        admissionGeneration: Long? = null): Admission {
        val generation = coordinator.captureGeneration()
        val globalGeneration = GlobalTaskCancellation.generation
        if (orchestrator.handleImmediateInput(text, inputType)) return Admission.Rejected(RejectionReason.INVALID_REQUEST)
        if (admissionGeneration != null && admissionGeneration != globalGeneration)
            return Admission.Rejected(RejectionReason.CLOSED)
        orchestrator.ensureTaskScopesReady()
        if (GlobalTaskCancellation.generation != globalGeneration)
            return Admission.Rejected(RejectionReason.CLOSED)
        val authorized = try { orchestrator.authorizeTaskScope(text, freshlyPrepared = true) }
            catch (_: TaskScopePreparingException) { return Admission.Rejected(RejectionReason.PREPARING) }
            catch (_: IllegalArgumentException) { return Admission.Rejected(RejectionReason.INVALID_REQUEST) }
        return submit(if (inputType == InputType.VOICE) voice else command, text, authorized,
            if (inputType == InputType.VOICE) TaskSource.VOICE else TaskSource.TEXT, generation)
    }
    /** Called only after displaying this exact task's package/goal/limits and receiving consent. */
    fun submitOwl(consent: com.unoone.agent.owl.OwlTaskConsent, registry: com.unoone.agent.phonecontrol.AppRegistry): Admission {
        val generation = coordinator.captureGeneration()
        // Never replace the review's global epoch with a fresh one at Approve/IO completion.
        if (consent.approvalEpoch != GlobalTaskCancellation.generation ||
            !AgentRuntimeGate.isEnabled() || journal.health != "HEALTHY") return Admission.Rejected(RejectionReason.CLOSED)
        val parsed = com.unoone.agent.NativeDeviceCommands.parse(consent.instruction) { name ->
            (registry.resolve(name) as? com.unoone.agent.phonecontrol.AppRegistry.Resolution.Found)?.app?.packageName
        } ?: return Admission.Rejected(RejectionReason.INVALID_REQUEST)
        val goals = if (parsed is com.unoone.agent.NativeDeviceGoal.Sequence) parsed.goals else listOf(parsed)
        val supported = goals.withIndex().all { (index, goal) -> when (goal) {
            is com.unoone.agent.NativeDeviceGoal.OpenApp -> index == 0
            is com.unoone.agent.NativeDeviceGoal.ReadScreen, is com.unoone.agent.NativeDeviceGoal.Find,
            is com.unoone.agent.NativeDeviceGoal.Click, is com.unoone.agent.NativeDeviceGoal.Interact -> true
            is com.unoone.agent.NativeDeviceGoal.Current -> goal.command == "read"
            else -> false
        } }
        if (!supported || goals.size > consent.maxSteps || goals.any { it is com.unoone.agent.NativeDeviceGoal.NeedsUser } ||
            com.unoone.agent.NativeDeviceCommands.scopePackages(parsed) { consent.packageName } != setOf(consent.packageName))
            return Admission.Rejected(RejectionReason.INVALID_REQUEST)
        if (consent.approvalEpoch != GlobalTaskCancellation.generation)
            return Admission.Rejected(RejectionReason.CLOSED)
        val key = UUID.randomUUID().toString()
        owlRequests[key] = consent to goals
        val admitted = coordinator.submit(TaskRequest(RequestId(key), owl, key,
            TaskScope(setOf(TaskCapability.UI_READ, TaskCapability.UI_WRITE, TaskCapability.MODEL), setOf(consent.packageName)),
            generation, TaskSource.NATIVE, TaskPriority.INTERACTIVE,
            TaskBudget(consent.maxSeconds * 1000L, actions = 128, modelCalls = consent.maxSteps)))
        if (admitted is Admission.Rejected) owlRequests.remove(key)
        if (admitted is Admission.Accepted) {
            // await is terminal even when cancelled before the worker removes its request.
            scope.launch { try { coordinator.await(admitted.taskId) } finally { owlRequests.remove(key) } }
        }
        return admitted
    }

    fun submitNotesSearch(query: String): Admission = submit(search, query,
        TaskScope(setOf(TaskCapability.LOCAL_READ)), TaskSource.NATIVE, coordinator.captureGeneration())
    fun submitDraft(prompt: String, requiredPhrases: List<String> = emptyList()): Admission {
        val encoded = try { DraftRequest(prompt, requiredPhrases).encode() }
            catch (_: IllegalArgumentException) { return Admission.Rejected(RejectionReason.INVALID_REQUEST) }
        return submit(draft, encoded, TaskScope(setOf(TaskCapability.MODEL)), TaskSource.NATIVE, coordinator.captureGeneration())
    }
    /** A supervised parent with two least-privilege children; outputs remain child-scoped. */
    fun submitPreparation(query: String, draftRequest: String, requiredPhrases: List<String> = emptyList()): Admission {
        if (query.isBlank() || draftRequest.isBlank() || query.length > 4000 || draftRequest.length > 4000)
            return Admission.Rejected(RejectionReason.INVALID_REQUEST)
        val encoded = try { DraftRequest(draftRequest, requiredPhrases).encode() }
            catch (_: IllegalArgumentException) { return Admission.Rejected(RejectionReason.INVALID_REQUEST) }
        val instruction = org.json.JSONObject().put("query", query).put("draft", encoded).toString()
        return submit(preparation, instruction, TaskScope(setOf(TaskCapability.LOCAL_READ, TaskCapability.MODEL)),
            TaskSource.NATIVE, coordinator.captureGeneration())
    }
    /** Does not cancel, undo, resume, or authorize any task. Must be explicitly requested. */
    fun clearMetadataHistory(): Boolean {
        val globalGeneration = GlobalTaskCancellation.generation
        val reservation = coordinator.beginMaintenance() ?: return false
        val maintenanceTaskId = TaskId(UUID.randomUUID().toString())
        fun checkMaintenance() {
            if (GlobalTaskCancellation.generation != globalGeneration)
                throw CancellationException("Maintenance stopped")
            reservation.checkActive()
        }
        try {
            // Fail fast for existing independent browser/skill owners. A racing acquisition
            // is handled by cancellable lease waits, never by owner snapshots alone.
            if (ProcessTaskResources.ui.owner() != null || ProcessTaskResources.model.owner() != null ||
                ExclusiveBrainLeaseState.isActive()) return false
            return runBlocking {
                val waitingJob = currentCoroutineContext()[Job]!!
                val revocation = launch(Dispatchers.Default) {
                    while (isActive) {
                        try { checkMaintenance() }
                        catch (e: CancellationException) { waitingJob.cancel(e); return@launch }
                        delay(10)
                    }
                }
                try {
                    ProcessTaskResources.ui.withLease(maintenanceTaskId, ::checkMaintenance) {
                        ProcessTaskResources.model.withLease(maintenanceTaskId, ::checkMaintenance) {
                            synchronized(historyLock) {
                                checkMaintenance()
                                val cleared = journal.clearMetadataHistory {
                                    checkMaintenance()
                                    ProcessTaskResources.ui.owner() == maintenanceTaskId &&
                                        ProcessTaskResources.model.owner() == maintenanceTaskId &&
                                        !ExclusiveBrainLeaseState.isActive()
                                }
                                if (cleared) {
                                    clearedTaskIds.retainAll(tasks.value.map { it.id }.toSet())
                                    clearedTaskIds.addAll(tasks.value.map { it.id })
                                    output.value = emptyMap()
                                }
                                cleared
                            }
                        }
                    }
                } finally { revocation.cancel() }
            }
        } catch (_: CancellationException) {
            return false
        } finally {
            reservation.close()
        }
    }
    suspend fun await(id: TaskId): TaskResult = coordinator.await(id)
    fun cancelTask(id: TaskId): CancelReceipt {
        val receipt = coordinator.cancel(id)
        receipt.cancelled.forEach(orchestrator::cancelTaskOwner)
        return receipt
    }
}

/** UTF-8 byte budget is checked before append; never join an unbounded intermediate. */
internal fun boundedTaskText(parts: Sequence<String>, maxBytes: Int = 32768): String {
    require(maxBytes >= 0)
    val out = StringBuilder(minOf(maxBytes, 4096))
    var bytes = 0
    for (part in parts) {
        var i = 0
        while (i < part.length) {
            val cp = Character.codePointAt(part, i)
            val cost = when { cp <= 0x7f -> 1; cp <= 0x7ff -> 2; cp <= 0xffff -> 3; else -> 4 }
            if (bytes + cost > maxBytes) return out.toString()
            out.appendCodePoint(cp)
            bytes += cost
            i += Character.charCount(cp)
        }
    }
    return out.toString()
}

internal class TaskModelBusy : IllegalStateException("MODEL_BUSY")
internal fun draftErrorResult(error: Result.Error): TaskResult = TaskResult(
    if (error.cause is TaskModelBusy) TaskOutcome.NEEDS_USER else TaskOutcome.UNVERIFIED)
