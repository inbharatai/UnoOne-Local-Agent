package com.unoone.agent.task

import com.unoone.agent.core.model.Result
import com.unoone.agent.core.task.TaskOutcome
import org.junit.Assert.*
import org.junit.Test

class TaskBoundsTest {
    @Test fun outputByteBudgetAppliesBeforeAppend() {
        val result = boundedTaskText(sequenceOf("界".repeat(50000), "ignored"))
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 32768)
        assertEquals(10922, result.length)
    }
    @Test fun oversizedJournalRejectedBeforeParsing() {
        try { TaskJournalStore.decode(ByteArray(TaskJournalStore.MAX_BYTES + 1)); fail() }
        catch (_: IllegalArgumentException) {}
    }
    @Test fun typedBusyIsNeedsUserNotMessageGuessing() {
        assertEquals(TaskOutcome.NEEDS_USER, draftErrorResult(Result.Error("anything", TaskModelBusy())).outcome)
        assertEquals(TaskOutcome.UNVERIFIED, draftErrorResult(Result.Error("MODEL_BUSY")).outcome)
    }
}
