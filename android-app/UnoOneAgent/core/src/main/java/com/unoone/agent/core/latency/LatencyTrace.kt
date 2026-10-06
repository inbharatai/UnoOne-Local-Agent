package com.unoone.agent.core.latency

import java.util.UUID
import kotlin.math.ceil

fun interface LatencyClock { fun nanos(): Long }
enum class LatencyOrigin { FLOATING, FOREGROUND, WAKE, EXPLICIT_OWL }
enum class LatencySource { ANDROID_MONOTONIC, PROVIDER_BOUNDARY, PLAYBACK_WAIT_PROXY, NATIVE_DURATION }
enum class LatencyPath { UNRESOLVED, RULE_REPLY, NATIVE, MODEL_FALLBACK, OWL_NATIVE_READ, OWL_MODEL }
enum class LatencyProfile { UNKNOWN, SHERPA_TRANSDUCER, SHERPA_WHISPER, SHERPA_OMNILINGUAL, SHERPA_TTS, SYSTEM, OWL }
enum class LatencyCold { UNKNOWN, MODEL_COLD, LOADED_WARM }
enum class LatencyReason { NONE, SILENCE, MAX_DURATION, MANUAL, NO_SPEECH, SUPERSEDED, UNAVAILABLE, ERROR }
enum class LatencyOutcome { VERIFIED, COMPLETED_NON_ACTION, UNVERIFIED, NEEDS_USER, REJECTED, FAILED, CANCELLED, TIMED_OUT, INTERRUPTED }
enum class LatencyStage { MODEL_WAIT, MIC_REQUEST, MIC_START_REQUEST, MIC_RECORDING_CONFIRMED, ENDPOINT_DECISION, RECORDER_STOP, CUE_REQUEST, STT_SUBMIT, STT_LOCK_REQUEST, STT_LOCK_ACQUIRED, PCM_CONVERSION_BEGIN, PCM_CONVERSION_END, DECODE_BEGIN, DECODE_END, FINAL_TRANSCRIPT_READY, ADMISSION, TASK_ENQUEUED, WORKER_START, RULE_PARSE_BEGIN, RULE_PARSE_END, ROUTE_SELECTED, NATIVE_BIND, PRE_OBSERVE, CAPTURE, MODEL_BEGIN, MODEL_END, APPROVAL_SHOWN, APPROVAL_READY, APPROVAL_RESPONSE, APPROVAL_RESOLVED, DISPATCH_BEGIN, DISPATCH_RETURN, POSTCONDITION_RESULT, RESULT_READY, UI_STATE_PUBLISHED, TTS_REQUEST, TTS_QUEUE_REQUEST, TTS_QUEUE_ACQUIRED, SYNTHESIS_BEGIN, SYNTHESIS_END, AUDIO_ENQUEUE, PLAYBACK_COMPLETE, NEXT_CAPTURE_AVAILABLE }
/** Opaque, random diagnostic identity; never an authorization or task ID. */
data class LatencyToken internal constructor(val id: String)
data class LatencyEvent(val stage: LatencyStage, val offsetUs: Long, val source: LatencySource, val reason: LatencyReason, val profile: LatencyProfile, val cold: LatencyCold)
data class LatencySnapshot(val token: LatencyToken, val origin: LatencyOrigin, val path: LatencyPath, val events: List<LatencyEvent>, val outcome: LatencyOutcome?, val terminalUs: Long?, val drops: Int, val invalid: Int)
data class LatencySummary(val eligible: Int, val samples: Int, val p50Us: Long?, val p95Us: Long?, val failures: Int, val timeouts: Int, val censored: Int)

/** Volatile opt-in metadata only. No task payload API; bounded even for unfinished attempts. */
class LatencyRecorder(private val clock: LatencyClock) {
    private data class Entry(val token: LatencyToken, val origin: LatencyOrigin, val start: Long, val events: MutableList<LatencyEvent> = ArrayList(), var path: LatencyPath = LatencyPath.UNRESOLVED, var outcome: LatencyOutcome? = null, var end: Long? = null, var drops: Int = 0, var invalid: Int = 0, var accepted: Boolean = false)
    private var evictions = 0L
    private val traces = LinkedHashMap<LatencyToken, Entry>()
    // Ephemeral lookup only; never included in snapshots/export.
    private val requests = LinkedHashMap<String, LatencyToken>()
    @Volatile var enabled = false
        set(value) { field = value; if (!value) clear() }
    @Synchronized fun begin(origin: LatencyOrigin): LatencyToken? {
        if (!enabled) return null
        return beginAt(origin, clock.nanos())
    }
    /** Same-clock historical capture boundary, at most two minutes old; invalid history is absent, not zero. */
    @Synchronized fun beginAt(origin: LatencyOrigin, startNanos: Long): LatencyToken? {
        if (!enabled) return null
        val now = clock.nanos()
        if (startNanos < 0 || startNanos > now || now - startNanos > 120_000_000_000L) return null
        val token = LatencyToken(UUID.randomUUID().toString())
        if (traces.size >= 256) { evictions++; val oldest = traces.keys.first(); traces.remove(oldest); requests.entries.removeAll { it.value == oldest } }
        traces[token] = Entry(token, origin, startNanos)
        return token
    }
    @Synchronized fun bindRequest(requestId: String, token: LatencyToken?) { if (token != null && traces.containsKey(token)) { if (requests.size >= 256) requests.remove(requests.keys.first()); requests[requestId] = token } }
    @Synchronized fun forRequest(requestId: String): LatencyToken? = requests[requestId]
    @Synchronized fun mark(token: LatencyToken?, stage: LatencyStage, source: LatencySource = LatencySource.ANDROID_MONOTONIC, reason: LatencyReason = LatencyReason.NONE, profile: LatencyProfile = LatencyProfile.UNKNOWN, cold: LatencyCold = LatencyCold.UNKNOWN) {
        if (!enabled || token == null || !traces.containsKey(token)) return
        markAt(token, stage, clock.nanos(), source, reason, profile, cold)
    }
    @Synchronized fun markAt(token: LatencyToken?, stage: LatencyStage, atNanos: Long, source: LatencySource = LatencySource.ANDROID_MONOTONIC, reason: LatencyReason = LatencyReason.NONE, profile: LatencyProfile = LatencyProfile.UNKNOWN, cold: LatencyCold = LatencyCold.UNKNOWN) {
        val entry = traces[token] ?: return
        if (entry.outcome != null) { entry.invalid++; return }
        if (atNanos < entry.start || atNanos > clock.nanos()) { entry.invalid++; return }
        val offset = (atNanos - entry.start) / 1000
        if (offset < 0 || offset < (entry.events.lastOrNull()?.offsetUs ?: 0)) { entry.invalid++; return }
        if (entry.events.size >= 128) { entry.drops++; return }
        entry.events.add(LatencyEvent(stage, offset, source, reason, profile, cold))
    }
    @Synchronized fun route(token: LatencyToken?, path: LatencyPath) { traces[token]?.takeIf { it.outcome == null }?.path = path; mark(token, LatencyStage.ROUTE_SELECTED) }
    @Synchronized fun acceptOwnership(token: LatencyToken?) { traces[token]?.accepted = true }
    @Synchronized fun closeCapture(token: LatencyToken?, outcome: LatencyOutcome = LatencyOutcome.INTERRUPTED) {
        if (traces[token]?.accepted != true) close(token, outcome)
    }
    @Synchronized fun close(token: LatencyToken?, outcome: LatencyOutcome) {
        val entry = traces[token] ?: return
        if (entry.outcome != null) return
        val elapsed = (clock.nanos() - entry.start) / 1000
        if (elapsed < (entry.events.lastOrNull()?.offsetUs ?: 0)) { entry.invalid++; return }
        entry.outcome = outcome; entry.end = elapsed
        requests.entries.removeAll { it.value == token }
    }
    @Synchronized fun clear() { traces.clear(); requests.clear(); evictions = 0 }
    @Synchronized fun snapshots(): List<LatencySnapshot> = traces.values.map { LatencySnapshot(it.token, it.origin, it.path, it.events.toList(), it.outcome, it.end, it.drops, it.invalid) }
    /** Conditional successful-pair nearest-rank distribution; failures/timeouts never become zero samples. */
    fun summary(start: LatencyStage, end: LatencyStage, path: LatencyPath, origin: LatencyOrigin? = null, profile: LatencyProfile? = null, source: LatencySource? = null): LatencySummary {
        val rows = snapshots().filter { it.path == path && (origin == null || it.origin == origin) && (profile == null || it.events.any { e -> e.profile == profile }) && (source == null || it.events.any { e -> e.source == source }) }
        val values = rows.filter { it.outcome == LatencyOutcome.VERIFIED || it.outcome == LatencyOutcome.COMPLETED_NON_ACTION }.mapNotNull { row ->
            val a = row.events.firstOrNull { it.stage == start }?.offsetUs
            val b = row.events.firstOrNull { it.stage == end }?.offsetUs
            if (a != null && b != null && b >= a) b-a else null
        }.sorted()
        fun q(p: Double): Long? = if (values.isEmpty()) null else values[ceil(values.size*p).toInt()-1]
        return LatencySummary(rows.size, values.size, q(.5), if (values.size >= 20) q(.95) else null, rows.count { it.outcome == LatencyOutcome.FAILED }, rows.count { it.outcome == LatencyOutcome.TIMED_OUT }, rows.count { it.outcome == null || it.outcome == LatencyOutcome.INTERRUPTED || it.outcome == LatencyOutcome.CANCELLED })
    }
    @Synchronized fun evictionCount(): Long = evictions
    /** All strings below are closed enums or recorder-generated UUIDs. No reflection/payload serialization. */
    fun exportJson(): String = snapshots().joinToString(prefix = "{\"schema_version\":1,\"evicted_count\":${evictionCount()},\"ttft\":\"NOT_MEASURED\",\"coverage\":\"VOLATILE_OPT_IN\",\"clock_domain\":\"ANDROID_ELAPSED_REALTIME\",\"traces\":[", postfix = "]}") { t ->
        "{\"trace_id\":\"${t.token.id}\",\"origin\":\"${t.origin.name}\",\"path\":\"${t.path.name}\",\"outcome\":${t.outcome?.let { "\"${it.name}\"" } ?: "null"},\"terminal_us\":${t.terminalUs},\"drop_count\":${t.drops},\"invalid_event_count\":${t.invalid},\"events\":" + t.events.joinToString(prefix="[",postfix="]}") { e -> "{\"stage\":\"${e.stage.name}\",\"offset_us\":${e.offsetUs},\"source\":\"${e.source.name}\",\"reason\":\"${e.reason.name}\",\"profile\":\"${e.profile.name}\",\"cold\":\"${e.cold.name}\"}" }
    }
}

/** Request-local identity propagated by coroutine context, never a global current token. */
class CurrentLatencyContext(val token: LatencyToken?) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<CurrentLatencyContext>
}
