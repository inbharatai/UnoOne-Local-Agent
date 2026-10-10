package com.unoone.agent.task

import android.content.Context
import android.util.AtomicFile
import com.unoone.agent.core.task.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.atomic.AtomicLong

/** Metadata only; noBackupFilesDir excludes this journal from Android backup/transfer. */
class TaskJournalStore internal constructor(private val file: AtomicFile,
    private val afterCommit: () -> Unit = {}) {
    constructor(context: Context) : this(AtomicFile(File(context.noBackupFilesDir, "native-task-journal.json")))
    private var epoch: String? = null
    private val shared = synchronized(diskLock) {
        states.getOrPut(file.baseFile.canonicalPath) { SharedState() }
    }
    var health: String
        get() = shared.health
        private set(value) { shared.health = value }
    /** Bounded diagnostic: exception type and journal frame only, never exception messages or payloads. */
    val failureCause: String? get() = shared.failureCause
    private fun failed(phase: String, error: Exception) {
        val frame = error.stackTrace.firstOrNull { it.className == TaskJournalStore::class.java.name }
        shared.failureCause = (phase + "/" + error.javaClass.simpleName + "/" + (frame?.methodName ?: "unknown") + ":" + (frame?.lineNumber ?: 0)).take(160)
        health = phase
    }
    companion object {
        private val diskLock = Any()
        private class SharedState {
            @Volatile var health = "HEALTHY"
            @Volatile var failureCause: String? = null
        }
        private val states = mutableMapOf<String, SharedState>()
        /** All constructors share serialization, failure state and on-disk sequence allocation. */
        fun shared(context: Context): TaskJournalStore = TaskJournalStore(context.applicationContext)
        const val MAX_BYTES = 524288
        const val MAX_ROWS = 512
        private val token = Regex("[A-Za-z0-9_.:-]{1,128}")
        private fun checksum(row: JSONObject): String {
            val canonical = row.keys().asSequence().filter { it != "checksum" }.sorted()
                .joinToString("\n") { key -> key + "=" + JSONObject.quote(row.get(key).toString()) }
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        internal fun strictRow(row: JSONObject, previousSequence: Long): JSONObject {
            val summary = row.has("state")
            val required = if (summary) setOf("version", "sequence", "id", "state", "source", "role", "priority", "checksum")
                else setOf("version", "sequence", "id", "stage", "step", "checksum")
            val allowed = required + if (summary) setOf("parent", "outcome") else setOf("capability", "producer")
            val keys = row.keys().asSequence().toSet()
            require(keys.containsAll(required) && allowed.containsAll(keys))
            fun string(key: String): String { val v = row.get(key); require(v is String); return v }
            fun number(key: String): Long { val v = row.get(key); require(v is Long || v is Int); return (v as Number).toLong() }
            require(number("version") == 2L)
            require(number("sequence") > previousSequence && number("sequence") < Long.MAX_VALUE)
            require(token.matches(string("id")))
            if (summary) {
                val state = TaskState.valueOf(string("state"))
                TaskSource.valueOf(string("source")); TaskPriority.valueOf(string("priority"))
                require(string("role").length <= 80 && token.matches(string("role")))
                if (row.has("parent")) require(token.matches(string("parent")))
                val outcome = if (row.has("outcome")) TaskOutcome.valueOf(string("outcome")) else null
                val valid = when (state) {
                    TaskState.SUCCEEDED -> outcome in setOf(TaskOutcome.VERIFIED, TaskOutcome.RESPONDED)
                    TaskState.ACTION_VERIFIED -> outcome == TaskOutcome.ACTION_VERIFIED
                    TaskState.UNVERIFIED -> outcome == TaskOutcome.UNVERIFIED
                    TaskState.NEEDS_USER -> outcome == TaskOutcome.NEEDS_USER
                    TaskState.FAILED -> outcome == TaskOutcome.FAILED
                    TaskState.CANCELLED -> outcome == TaskOutcome.CANCELLED
                    else -> outcome == null
                }
                require(valid)
            } else {
                ReceiptStage.valueOf(string("stage")); require(number("step") >= 0)
                if (row.has("capability")) TaskCapability.valueOf(string("capability"))
                if (row.has("producer")) {
                    require(TaskSource.valueOf(string("producer")) in setOf(TaskSource.BROWSER, TaskSource.SKILL))
                    require(string("stage") == ReceiptStage.DISPATCH_INTENT.name)
                    require(string("capability") == TaskCapability.UI_WRITE.name)
                }
            }
            require(string("checksum") == checksum(row))
            return row
        }
        /** Closed scalar-only schema; reject row 513 before allocating its contents. */
        internal fun decode(bytes: ByteArray): List<JSONObject> = decodeSnapshot(bytes).second
        private fun decodeSnapshot(bytes: ByteArray): Pair<String?, List<JSONObject>> {
            require(bytes.size <= MAX_BYTES)
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            val text = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            val reader = android.util.JsonReader(java.io.StringReader(text))
            reader.isLenient = false
            val rows = ArrayList<JSONObject>()
            var epoch: String? = null
            reader.use {
                if (it.peek() == android.util.JsonToken.BEGIN_OBJECT) {
                    it.beginObject()
                    require(it.nextName() == "epoch")
                    epoch = it.nextString().also { value -> require(java.util.UUID.fromString(value).toString() == value) }
                    require(it.nextName() == "rows")
                }
                it.beginArray()
                var sequence = 0L
                while (it.hasNext()) {
                    require(rows.size < MAX_ROWS)
                    val row = JSONObject()
                    it.beginObject()
                    while (it.hasNext()) {
                        val key = it.nextName()
                        require(!row.has(key) && row.length() < 12)
                        when (it.peek()) {
                            android.util.JsonToken.STRING -> row.put(key, it.nextString())
                            android.util.JsonToken.NUMBER -> row.put(key, it.nextString().toLong())
                            else -> error("Non-scalar journal field")
                        }
                    }
                    it.endObject()
                    rows.add(strictRow(row, sequence)); sequence = row.getLong("sequence")
                }
                it.endArray()
                if (epoch != null) it.endObject()
                require(it.peek() == android.util.JsonToken.END_DOCUMENT)
            }
            return epoch to rows
        }
    }
    private fun readRows(): List<JSONObject> {
        val input = try { file.openRead() } catch (missing: java.io.FileNotFoundException) {
            if (file.baseFile.exists()) throw missing else return emptyList()
        }
        return input.use {
            require(it.channel.size() <= MAX_BYTES)
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                require(out.size() + n <= MAX_BYTES)
                out.write(buffer, 0, n)
            }
            decodeSnapshot(out.toByteArray()).let { snapshot -> epoch = snapshot.first; snapshot.second }
        }
    }
    var recoveredTasks: List<TaskSummary> = synchronized(diskLock) {
        try {
            val recovered = linkedMapOf<TaskId, TaskSummary>()
            readRows().forEach { row ->
                val id = TaskId(row.getString("id"))
                if (row.has("state")) {
                    val state = TaskState.valueOf(row.getString("state"))
                    val terminal = state in setOf(TaskState.SUCCEEDED, TaskState.ACTION_VERIFIED, TaskState.UNVERIFIED, TaskState.NEEDS_USER, TaskState.FAILED, TaskState.CANCELLED)
                    recovered[id] = TaskSummary(id, row.optString("parent").takeIf(String::isNotEmpty)?.let(::TaskId),
                        TaskSource.valueOf(row.getString("source")), if (terminal) state else TaskState.NEEDS_REVIEW,
                        row.getLong("sequence"), if (terminal) TaskOutcome.valueOf(row.getString("outcome")) else null,
                        WorkerKind(row.getString("role")), TaskPriority.valueOf(row.getString("priority")))
                } else if (id !in recovered) {
                    recovered[id] = TaskSummary(id, null,
                        if (row.has("producer")) TaskSource.valueOf(row.getString("producer")) else TaskSource.NATIVE,
                        TaskState.NEEDS_REVIEW, row.getLong("sequence"))
                }
            }
            recovered.values.toList()
        } catch (error: Exception) { failed("RECOVERY_FAILED", error); emptyList() }
    }
        private set

    /** Explicit destructive metadata reset only; never interprets history as execution authority. */
    internal fun clearMetadataHistory(isIdle: () -> Boolean): Boolean = synchronized(diskLock) {
        if (!isIdle()) return@synchronized false
        try {
            val freshEpoch = java.util.UUID.randomUUID().toString()
            val bytes = ("{\"epoch\":\"" + freshEpoch + "\",\"rows\":[]}").toByteArray(Charsets.UTF_8)
            writeVerified(bytes)
            check(epoch == freshEpoch)
            recoveredTasks = emptyList()
            shared.failureCause = null
            health = "HEALTHY"
            true
        } catch (error: Exception) { failed("COMMIT_FAILED", error); false }
    }

    private fun writeVerified(bytes: ByteArray) {
        val stream = file.startWrite()
        try { stream.write(bytes); stream.fd.sync(); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
        afterCommit()
        val committed = readRows()
        check(snapshotBytes(committed).contentEquals(bytes))
        val directory = android.system.Os.open(file.baseFile.parentFile!!.absolutePath,
            android.system.OsConstants.O_RDONLY, 0)
        try {
            check(android.system.OsConstants.S_ISDIR(android.system.Os.fstat(directory).st_mode))
            android.system.Os.fsync(directory)
        } finally { android.system.Os.close(directory) }
    }
    private fun snapshotBytes(rows: List<JSONObject>): ByteArray {
        val array = JSONArray(rows).toString()
        return (epoch?.let { "{\"epoch\":\"$it\",\"rows\":$array}" } ?: array).toByteArray(Charsets.UTF_8)
    }

    private fun append(row: JSONObject) = synchronized(diskLock) {
        check(health == "HEALTHY") { "Task journal unavailable; dispatch rejected ($failureCause)" }
        try {
            val previous = readRows()
            // A conflated asynchronous collector must not overwrite an accepted terminal summary
            // with an older RUNNING/CANCELLING snapshot it captured before synchronous commit.
            if (row.has("state")) {
                val latest = previous.lastOrNull { it.optString("id") == row.optString("id") && it.has("state") }
                val terminal = setOf("SUCCEEDED", "UNVERIFIED", "NEEDS_USER", "FAILED", "CANCELLED")
                if (latest?.optString("state") in terminal && row.optString("state") !in terminal)
                    return@synchronized
            }
            val sequence = Math.addExact(previous.lastOrNull()?.getLong("sequence") ?: 0L, 1L)
            require(sequence < Long.MAX_VALUE)
            row.put("version", 2).put("sequence", sequence)
            row.put("checksum", checksum(row))
            strictRow(row, sequence - 1)
            // Never evict a before-effect WAL entry to make room. Explicit idle reset is required.
            require(previous.size < MAX_ROWS)
            val bytes = snapshotBytes(previous + row)
            require(bytes.size <= MAX_BYTES)
            writeVerified(bytes)
        } catch (error: Exception) {
            failed("COMMIT_FAILED", error)
            throw IllegalStateException("Task journal unavailable; dispatch rejected ($failureCause)", error)
        }
    }
    fun record(id: TaskId, stage: ReceiptStage, step: Long = 0, capability: TaskCapability? = null) {
        val row = JSONObject().put("id", id.value).put("stage", stage.name).put("step", step)
        capability?.let { row.put("capability", it.name) }; append(row)
    }
    /** Native callback only: no model arguments, values, URLs or observations cross this boundary.
     * An independent producer has no terminal reconciliation; its intent stays NEEDS_REVIEW.
     */
    internal fun recordExternalProducer(owner: TaskId, producer: TaskSource) = synchronized(diskLock) {
        require(producer == TaskSource.BROWSER || producer == TaskSource.SKILL)
        append(JSONObject().put("id", owner.value).put("stage", ReceiptStage.DISPATCH_INTENT.name)
            .put("step", 0).put("capability", TaskCapability.UI_WRITE.name).put("producer", producer.name))
    }
    fun recordSummary(summary: TaskSummary) {
        val row = JSONObject().put("id", summary.id.value).put("state", summary.state.name)
            .put("source", summary.source.name).put("role", summary.role.value).put("priority", summary.priority.name)
        summary.parent?.let { row.put("parent", it.value) }
        summary.outcome?.let { row.put("outcome", it.name) }; append(row)
    }
}

/** Inherited by native adapters; does not confer capabilities beyond the immutable core scope. */
class NativeTaskExecution(val context: TaskContext, private val journal: TaskJournalStore) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NativeTaskExecution>
    private val step = AtomicLong()
    fun checkActive() = context.checkActive()
    fun beforeEffect(capability: TaskCapability): Long {
        context.beforeAction(capability)
        val next = step.incrementAndGet()
        journal.record(context.taskId, ReceiptStage.DISPATCH_INTENT, next, capability)
        context.emit(next, capability, ReceiptStage.DISPATCH_INTENT)
        context.checkActive()
        return next
    }
    fun blocked(capability: TaskCapability): PermissionTicket {
        val next = step.incrementAndGet()
        journal.record(context.taskId, ReceiptStage.BLOCKED, next, capability)
        context.emit(next, capability, ReceiptStage.BLOCKED)
        return PermissionTicket(TicketBinding(context.taskId, context.taskEpoch,
            context.stopGeneration, next, System.nanoTime() / 1_000_000 + 60_000), capability)
    }
}
