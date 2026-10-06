package com.unoone.agent.task

import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import com.unoone.agent.core.task.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Android AtomicFile/JsonReader/fsync, not JVM Android stubs. */
class TaskJournalRecoveryTest {
    private fun location(): File = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "journal-test-${UUID.randomUUID()}").apply { mkdirs() }
    private fun blocked(store: TaskJournalStore) {
        var effect = false
        try {
            store.record(TaskId("test"), ReceiptStage.DISPATCH_INTENT, 1)
            effect = true
        } catch (_: IllegalStateException) { }
        assertFalse("WAL failure must precede effects", effect)
    }
    @Test fun malformedAndOversizedRequireExplicitIdleClear() {
        for (bytes in listOf("not-json".toByteArray(), ByteArray(TaskJournalStore.MAX_BYTES + 1))) {
            val dir = location()
            try {
                val file = File(dir, "journal")
                file.writeBytes(bytes)
                val store = TaskJournalStore(AtomicFile(file))
                assertEquals("RECOVERY_FAILED", store.health)
                blocked(store)
                assertArrayEquals(bytes, file.readBytes())
                assertFalse(store.clearMetadataHistory { false })
                assertEquals("RECOVERY_FAILED", store.health)
                assertArrayEquals(bytes, file.readBytes())
                assertTrue(store.clearMetadataHistory { true })
                assertEquals("HEALTHY", store.health)
                assertTrue(TaskJournalStore.decode(file.readBytes()).isEmpty())
                assertTrue(String(file.readBytes()).contains("epoch"))
                store.record(TaskId("fresh"), ReceiptStage.DISPATCH_INTENT, 1)
                assertEquals(1, TaskJournalStore.decode(file.readBytes()).size)
            } finally { dir.deleteRecursively() }
        }
    }
    @Test fun failedWriteRemainsBlocked() {
        val dir = location()
        try {
            // A regular file cannot serve as a journal directory, even for a privileged test process.
            val parent = File(dir, "not-directory").apply { writeText("x") }
            val store = TaskJournalStore(AtomicFile(File(parent, "journal")))
            assertFalse(store.clearMetadataHistory { true })
            assertEquals("COMMIT_FAILED", store.health)
            blocked(store)
        } finally { dir.deleteRecursively() }
    }
    @Test fun terminalRecoveryPreservedAndNonterminalNeedsReview() {
        val dir = location()
        try {
            val file = File(dir, "journal")
            val store = TaskJournalStore(AtomicFile(file))
            store.recordSummary(TaskSummary(TaskId("done"), null, TaskSource.NATIVE,
                TaskState.SUCCEEDED, 1, TaskOutcome.RESPONDED))
            store.recordSummary(TaskSummary(TaskId("interrupted"), null, TaskSource.NATIVE,
                TaskState.RUNNING, 2))
            val recovered = TaskJournalStore(AtomicFile(file)).recoveredTasks.associateBy { it.id.value }
            assertEquals(TaskState.SUCCEEDED, recovered.getValue("done").state)
            assertEquals(TaskState.NEEDS_REVIEW, recovered.getValue("interrupted").state)
        } finally { dir.deleteRecursively() }
    }
    @Test fun independentProducersAndCoordinatorShareBoundedLedger() {
        val dir = location()
        try {
            val file = File(dir, "journal")
            val browser = TaskJournalStore(AtomicFile(file))
            val skill = TaskJournalStore(AtomicFile(file))
            browser.recordExternalProducer(TaskId("independent-a"), TaskSource.BROWSER)
            skill.recordExternalProducer(TaskId("independent-b"), TaskSource.SKILL)
            browser.record(TaskId("coordinator"), ReceiptStage.DISPATCH_INTENT, 1)
            val rows = TaskJournalStore.decode(file.readBytes())
            assertEquals(listOf(1L, 2L, 3L), rows.map { it.getLong("sequence") })
            val recovered = TaskJournalStore(AtomicFile(file)).recoveredTasks.associateBy { it.id.value }
            assertEquals(TaskState.NEEDS_REVIEW, recovered.getValue("independent-a").state)
            assertEquals(TaskSource.BROWSER, recovered.getValue("independent-a").source)
            assertEquals(TaskState.NEEDS_REVIEW, recovered.getValue("independent-b").state)
            assertEquals(TaskSource.SKILL, recovered.getValue("independent-b").source)
            repeat(TaskJournalStore.MAX_ROWS - 3) {
                skill.recordExternalProducer(TaskId("independent-b"), TaskSource.SKILL)
            }
            val full = file.readBytes()
            try {
                browser.recordExternalProducer(TaskId("overflow"), TaskSource.BROWSER)
                fail("Full journal must block before effect")
            } catch (_: IllegalStateException) { }
            assertArrayEquals(full, file.readBytes())
            assertEquals("COMMIT_FAILED", skill.health)
            blocked(skill)
            assertEquals(TaskJournalStore.MAX_ROWS, TaskJournalStore.decode(full).size)
        } finally { dir.deleteRecursively() }
    }
    @Test fun corruptCommittedExternalIntentFailsClosedAcrossInstances() {
        val dir = location()
        try {
            val file = File(dir, "journal")
            val collector = TaskJournalStore(AtomicFile(file))
            val faulty = TaskJournalStore(AtomicFile(file)) { file.writeText("corrupt-commit") }
            try {
                faulty.recordExternalProducer(TaskId("browser"), TaskSource.BROWSER)
                fail("Readback must detect corrupt commit")
            } catch (_: IllegalStateException) { }
            assertEquals("COMMIT_FAILED", collector.health)
            blocked(collector)
            assertEquals("RECOVERY_FAILED", TaskJournalStore(AtomicFile(file)).health)
        } finally { dir.deleteRecursively() }
    }

}
