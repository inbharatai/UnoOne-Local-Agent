package com.unoone.agent

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unoone.agent.core.model.InputType
import com.unoone.agent.storage.db.UnoOneDatabase
import com.unoone.agent.storage.entity.NoteEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies compound commands expand into an ordered `steps[]` array and that each step runs
 * through the full safety pipeline (per-step confirmation/block) in declared order, stopping at
 * the first step that is cancelled, blocked, or needs access.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompoundStepsTest {

    private lateinit var db: UnoOneDatabase
    private lateinit var orchestrator: AgentOrchestrator

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, UnoOneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        orchestrator = AgentOrchestrator(
            context = context,
            noteDao = db.noteDao(),
            actionLogDao = db.actionLogDao(),
            memoryDao = db.memoryDao(),
            skillDao = db.skillDao()
        )
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun twoPartCompoundExecutesStepsInOrder() = runBlocking {
        // Create a note, then delete it. If steps run in the correct order the note is gone at the
        // end; if they ran in reverse order (delete first, then create) one note would survive.
        orchestrator.onConfirmationRequiredMulticast.add { _, callback -> callback.invoke(true) }

        orchestrator.processCommand("create note ord1 and delete note about ord1", InputType.TEXT)

        assertEquals(
            "Create-then-delete must leave zero notes (proves step order)",
            0,
            db.noteDao().recent(100).size
        )
        val timeline = orchestrator.timelineSteps.value
        assertTrue(
            "Compound must report completion",
            timeline.any { it.detail == "Compound complete" }
        )
    }

    @Test
    fun threePartCompoundExecutesAllSteps() = runBlocking {
        orchestrator.processCommand(
            "create note p1 and create note p2 and create note p3",
            InputType.TEXT
        )
        val notes = db.noteDao().recent(100).map { it.title }
        assertEquals("All 3 compound steps must execute", 3, notes.size)
        assertTrue(notes.contains("p1"))
        assertTrue(notes.contains("p2"))
        assertTrue(notes.contains("p3"))
    }

    @Test
    fun perStepSafetyStopsCompoundWhenAStepIsCancelled() = runBlocking {
        // Step 1 (create_note) is DIRECT and runs. Step 2 (open_url) is CONFIRM; denying the
        // confirmation must cancel the compound and stop further execution — but step 1 already
        // ran, so its note survives.
        orchestrator.onConfirmationRequiredMulticast.add { _, callback -> callback.invoke(false) }

        orchestrator.processCommand("create note survivor and open google", InputType.TEXT)

        val notes = db.noteDao().recent(100)
        assertEquals("Step 1 must have executed before step 2 was gated", 1, notes.size)
        assertEquals("survivor", notes[0].title)

        val timeline = orchestrator.timelineSteps.value
        assertTrue(
            "Cancelled step must be surfaced",
            timeline.any { it.label == "Cancelled" }
        )
        assertFalse(
            "Compound must NOT complete after a cancelled step",
            timeline.any { it.detail == "Compound complete" }
        )
    }
}