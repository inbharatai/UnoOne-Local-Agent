package com.unoone.agent.skills

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unoone.agent.AgentOrchestrator
import com.unoone.agent.core.model.InputType
import com.unoone.agent.storage.db.UnoOneDatabase
import com.unoone.agent.storage.entity.NoteEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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
 * Verifies skill steps no longer bypass safety: each step runs through the same
 * permission → risk → block → confirm → execute pipeline as a standalone command.
 *
 * - A skill containing a `delete all notes` step must surface STRONG_CONFIRM and only proceed
 *   once the user confirms (and then actually deletes the notes).
 * - A skill step whose input is classified BLOCK (e.g. references a bank/credit-card action)
 *   must be blocked and the skill must stop without completing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkillSafetyRoutingTest {

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
    fun skillWithDeleteAllNotesStepRequiresStrongConfirmAndDeletesWhenConfirmed() = runBlocking {
        // Seed two notes; the skill must wipe them only after STRONG_CONFIRM is granted.
        db.noteDao().insert(NoteEntity(title = "Groceries", content = "Milk and eggs", tags = "home"))
        db.noteDao().insert(NoteEntity(title = "Ideas", content = "Ship the offline agent", tags = "work"))
        assertEquals(2, db.noteDao().recent(100).size)

        orchestrator.skillsModule.saveSkill(
            name = "Cleanup",
            triggerPhrases = listOf("cleanup notes"),
            steps = listOf("delete all notes")
        )

        // Auto-confirm any confirmation prompt so the STRONG_CONFIRM gate can be satisfied.
        orchestrator.onConfirmationRequiredMulticast.add { _, callback -> callback.invoke(true) }

        orchestrator.processCommand("cleanup notes", InputType.TEXT)

        val timeline = orchestrator.timelineSteps.value
        assertTrue(
            "delete_all_notes step must be classified STRONG_CONFIRM (got: ${timeline.map { it.detail }})",
            timeline.any { it.detail == "Risk: STRONG_CONFIRM" }
        )
        assertTrue(
            "Skill must report completion after the confirmed delete",
            timeline.any { it.label == "Skill Complete" }
        )
        // The confirmation was real — the notes are now gone.
        assertEquals("Notes must be deleted after confirmation", 0, db.noteDao().recent(100).size)
    }

    @Test
    fun skillWithDeleteAllNotesStepDoesNotDeleteWhenConfirmationDenied() = runBlocking {
        db.noteDao().insert(NoteEntity(title = "Keep", content = "do not delete me", tags = "x"))
        orchestrator.skillsModule.saveSkill(
            name = "Cleanup2",
            triggerPhrases = listOf("cleanup2"),
            steps = listOf("delete all notes")
        )

        // Deny the confirmation — the safety pipeline must abort the step before execution.
        orchestrator.onConfirmationRequiredMulticast.add { _, callback -> callback.invoke(false) }

        orchestrator.processCommand("cleanup2", InputType.TEXT)

        val timeline = orchestrator.timelineSteps.value
        assertTrue(
            "STRONG_CONFIRM must still be surfaced even when denied",
            timeline.any { it.detail == "Risk: STRONG_CONFIRM" }
        )
        assertFalse(
            "Skill must NOT complete when the user declines confirmation",
            timeline.any { it.label == "Skill Complete" }
        )
        assertEquals("Notes must survive a denied confirmation", 1, db.noteDao().recent(100).size)
    }

    @Test
    fun skillStepWithBlockedInputStopsTheSkill() = runBlocking {
        // "open google ..." parses to open_url (CONFIRM at the tool level), but the input mentions
        // "bank", which SafetyGuard.classifyFromInput escalates to BLOCK — so the step is blocked.
        orchestrator.skillsModule.saveSkill(
            name = "BankHelper",
            triggerPhrases = listOf("check bank"),
            steps = listOf("open google to check my bank balance")
        )

        orchestrator.processCommand("check bank", InputType.TEXT)

        val timeline = orchestrator.timelineSteps.value
        assertTrue(
            "Blocked skill step must produce a Security Block entry",
            timeline.any { it.label == "Security Block" }
        )
        assertFalse(
            "A blocked step must stop the skill — no Skill Complete",
            timeline.any { it.label == "Skill Complete" }
        )
    }

    @Test
    fun unparseableSkillStepFailsVisiblyInsteadOfBeingSkipped() = runBlocking {
        orchestrator.skillsModule.saveSkill(
            name = "Broken routine",
            triggerPhrases = listOf("run broken routine"),
            steps = listOf("flibbertigibbet zzq")
        )

        orchestrator.processCommand("run broken routine", InputType.TEXT)

        val timeline = orchestrator.timelineSteps.value
        assertTrue(timeline.any { it.label == "Invalid Skill Step" })
        assertFalse(timeline.any { it.label == "Skill Complete" })
    }

    @Test
    fun repeatedSafeUseCreatesDisabledSuggestionOnly() = runBlocking {
        repeat(3) {
            orchestrator.skillsModule.recordSuccessfulUse("open calendar", "open_calendar")
        }

        val suggestion = db.skillDao().getAll().first()
            .single { it.name == "Suggested · Open Calendar" }
        assertFalse("Learned skills must require review before activation", suggestion.enabled)
        assertEquals(listOf("open calendar"), orchestrator.skillsModule.getSkillSteps(suggestion))
    }
}
