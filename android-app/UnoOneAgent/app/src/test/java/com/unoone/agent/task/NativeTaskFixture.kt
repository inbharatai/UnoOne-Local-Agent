package com.unoone.agent.task

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.unoone.agent.core.model.ToolCall
import com.unoone.agent.core.model.ToolCallValidator
import com.unoone.agent.core.task.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Real admission, process leases and bounded disk WAL; never constructs TaskContext or epochs. */
class NativeTaskFixture : AutoCloseable {
    val directory: File = Files.createTempDirectory("native-task-fixture-").toFile()
    val context: Context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getNoBackupFilesDir(): File = directory
        override fun getApplicationContext(): Context = this
    }
    val journal = TaskJournalStore(context)
    private val job = SupervisorJob()
    // Direct native-tool tests begin on Robolectric's SDK Main thread. Unconfined starts
    // admitted work there rather than posting a Main action behind this test's runBlocking.
    // Real concurrency/dispatcher boundaries are covered separately by coordinator tests.
    private val scope = CoroutineScope(job + Dispatchers.Unconfined)
    private val work = ConcurrentHashMap<String, suspend (TaskContext) -> Unit>()
    private val kind = WorkerKind("test-native")
    val coordinator = TaskCoordinator(listOf(WorkerRegistration(kind, WorkerLane.BACKGROUND,
        NativeTaskWorker { ctx ->
            val action = requireNotNull(work.remove(ctx.instruction))
            withContext(NativeTaskExecution(ctx, journal)) {
                ProcessTaskResources.ui.withLease(ctx.taskId, ctx::checkActive) { action(ctx) }
            }
            WorkerResult.Finished(TaskResult(TaskOutcome.VERIFIED))
        })), scope)

    data class Running<T>(val id: TaskId, val value: Deferred<T>)

    fun <T> start(taskScope: TaskScope, budget: TaskBudget = TaskBudget(), block: suspend (TaskContext) -> T): Running<T> {
        val key = UUID.randomUUID().toString()
        val result = CompletableDeferred<T>()
        work[key] = { ctx ->
            try { result.complete(block(ctx)) }
            catch (failure: Throwable) { result.completeExceptionally(failure); throw failure }
        }
        val admitted = coordinator.submit(TaskRequest(RequestId(key), kind, key, taskScope,
            coordinator.captureGeneration(), budget = budget))
        check(admitted is Admission.Accepted) { "Fixture admission rejected: $admitted" }
        return Running(admitted.taskId, result)
    }

    suspend fun <T> run(taskScope: TaskScope, budget: TaskBudget = TaskBudget(), block: suspend (TaskContext) -> T): T {
        val running = start(taskScope, budget, block)
        return try { withTimeout(10_000) { running.value.await() } }
        finally { coordinator.await(running.id) }
    }

    suspend fun <T> tool(call: ToolCall, budget: TaskBudget = TaskBudget(), block: suspend () -> T): T {
        val normalized = ToolCallValidator.adaptLegacySkill(call)
        return run(TaskScope(setOf(NativeToolEffects.capability(normalized.tool)),
            objectHandles = setOf(TaskToolAuthorization.handle(normalized))), budget) { block() }
    }

    /** Fail an actual AtomicFile write without replacing the journal or its authority. */
    fun failJournalWrites() {
        check(directory.deleteRecursively())
        check(directory.createNewFile()) // parent is now a file, so startWrite cannot create its child
    }

    override fun close() {
        coordinator.close()
        runBlocking { job.cancelAndJoin() }
        directory.deleteRecursively()
    }
}

fun <T> admittedDeviceTest(block: suspend () -> T): T = runBlocking {
    NativeTaskFixture().use { fixture ->
        fixture.run(TaskScope(setOf(TaskCapability.UI_READ, TaskCapability.UI_WRITE, TaskCapability.MODEL),
            packages = setOf("com.android.settings", "com.google.android.gm", "com.android.chrome")),
            TaskBudget(actions = 128)) { block() }
    }
}
