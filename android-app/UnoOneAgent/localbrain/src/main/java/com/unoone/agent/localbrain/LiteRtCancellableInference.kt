package com.unoone.agent.localbrain

import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.unoone.agent.core.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class OutputTokenLimitException(limit: Int) :
    IllegalStateException("LiteRT-LM output exceeded the configured $limit-token limit")

/**
 * Callback-based LiteRT-LM generation with real native cancellation.
 *
 * Kotlin cancellation alone does not interrupt JNI. On timeout, caller cancellation, output-budget
 * exhaustion, mode transition or master disable, this bridge invokes `Conversation.cancelProcess()`
 * and waits for the native callback to finish before allowing a conversation/engine close.
 */
@OptIn(ExperimentalApi::class)
internal class LiteRtCancellableInference(private val owner: String) {
    @Volatile private var activeConversation: Conversation? = null
    @Volatile private var activeCompletion: CompletableDeferred<Unit>? = null

    fun cancelActive(reason: String) {
        val conversation = activeConversation ?: return
        Logger.i("$owner: cancelling native inference ($reason)")
        runCatching { conversation.cancelProcess() }
            .onFailure { Logger.w("$owner: native cancel failed: ${it.message}") }
    }

    /** Returns false when native code did not acknowledge cancellation; callers must not close it. */
    suspend fun awaitNativeIdle(timeoutMs: Long = NATIVE_CANCEL_GRACE_MS): Boolean {
        val completion = activeCompletion ?: return true
        cancelActive("close requested")
        return withContext(NonCancellable) {
            withTimeoutOrNull(timeoutMs) { completion.await() } != null
        }
    }

    suspend fun send(
        conversation: Conversation,
        message: Message,
        timeoutMs: Long,
        maxOutputTokens: Int,
        onMessage: (Message) -> Unit = {}
    ): Message {
        check(activeCompletion == null) { "$owner already has native inference in flight" }
        val nativeDone = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Message>()
        val outputCancelled = AtomicBoolean(false)
        val lastMessage = AtomicReference<Message?>(null)
        activeConversation = conversation
        activeCompletion = nativeDone

        try {
            conversation.sendMessageAsync(
                message,
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        lastMessage.set(message)
                        onMessage(message)
                        val decodeTokens = runCatching<Int> {
                            conversation.getBenchmarkInfo().lastDecodeTokenCount
                        }.getOrDefault(0)
                        if (decodeTokens >= maxOutputTokens && outputCancelled.compareAndSet(false, true)) {
                            runCatching { conversation.cancelProcess() }
                        }
                    }

                    override fun onDone() {
                        // Publish native idleness before waking the response waiter. Completing the
                        // response first lets its coroutine run `finally` while nativeDone is still
                        // false, leaving activeCompletion stuck forever and rejecting the next
                        // sequential voice command as "already in flight".
                        nativeDone.complete(Unit)
                        if (outputCancelled.get()) {
                            response.completeExceptionally(OutputTokenLimitException(maxOutputTokens))
                        } else {
                            val final = lastMessage.get()
                            if (final == null) response.completeExceptionally(
                                IllegalStateException("LiteRT-LM completed without a response")
                            ) else response.complete(final)
                        }
                    }

                    override fun onError(throwable: Throwable) {
                        nativeDone.complete(Unit)
                        response.completeExceptionally(throwable)
                    }
                }
            )
            return withTimeout(timeoutMs) { response.await() }
        } catch (error: TimeoutCancellationException) {
            cancelActive("timeout")
            awaitNativeCallback(nativeDone)
            throw error
        } catch (error: CancellationException) {
            cancelActive("coroutine cancelled")
            awaitNativeCallback(nativeDone)
            throw error
        } finally {
            if (nativeDone.isCompleted) {
                activeConversation = null
                activeCompletion = null
            }
        }
    }

    private suspend fun awaitNativeCallback(done: CompletableDeferred<Unit>) {
        val stopped = withContext(NonCancellable) {
            withTimeoutOrNull(NATIVE_CANCEL_GRACE_MS) { done.await() } != null
        }
        if (!stopped) {
            Logger.e("$owner: native inference did not acknowledge cancellation; engine close is blocked")
        }
    }

    companion object {
        const val NATIVE_CANCEL_GRACE_MS = 5_000L
    }
}
