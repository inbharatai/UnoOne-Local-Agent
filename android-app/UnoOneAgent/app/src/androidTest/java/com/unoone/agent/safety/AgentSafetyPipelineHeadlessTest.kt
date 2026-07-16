package com.unoone.agent.safety

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unoone.agent.AgentOrchestrator
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

/**
 * Real on-device, HEADLESS agent safety-pipeline end-to-end gate (DEVICE_VERIFICATION §3:
 * "destructive-action confirmation", "blocked payment/credential/OTP request", and "skills execution
 * through the same safety path"). This is the on-device counterpart of the JVM `SkillSafetyRoutingTest`:
 * it constructs the real [AgentOrchestrator] with an in-memory Room DB (no Hilt, no Gemma — the skill
 * trigger + rule path do not load the brain) and drives `processCommand` for a user-created skill,
 * asserting the FULL permission→risk→block→confirm→execute→audit pipeline runs on the device's ART
 * with real Room/SQLite.
 *
 *  - A skill with a `delete all notes` step surfaces STRONG_CONFIRM and only deletes after the user
 *    confirms (and then the notes are actually gone).
 *  - If the user denies confirmation, the step does NOT execute and the notes survive.
 *  - A skill step whose input is classified BLOCK (mentions "bank") is blocked → "Security Block" and
 *    the skill does not complete.
 *
 * Run: am instrument -e class com.unoone.agent.safety.AgentSafetyPipelineHeadlessTest ...
 */
class AgentSafetyPipelineHeadlessTest {

    private lateinit var db: UnoOneDatabase
    private lateinit var orchestrator: AgentOrchestrator
    private lateinit var context: android.content.Context
    private lateinit var previousSecurityLevel: SecurityLevel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // These cases verify the production safety contract. Do not inherit Prototype/Off from
        // the developer's installed app preferences, and restore their choice after each case.
        previousSecurityLevel = SecurityLevel.current(context)
        SecurityLevel.set(context, SecurityLevel.STANDARD)
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
    fun tearDown() {
        db.close()
        SecurityLevel.set(context, previousSecurityLevel)
    }

    @Test
    fun skillWithDeleteAllNotesStepRequiresStrongConfirmAndDeletesWhenConfirmed() = runBlocking {
        db.noteDao().insert(NoteEntity(title = "Groceries", content = "Milk and eggs", tags = "home"))
        db.noteDao().insert(NoteEntity(title = "Ideas", content = "Ship the offline agent", tags = "work"))
        assertEquals(2, db.noteDao().recent(100).size)

        orchestrator.skillsModule.saveSkill(
            name = "Cleanup",
            triggerPhrases = listOf("cleanup notes"),
            steps = listOf("delete all notes")
        )
        // auto-confirm any prompt so the STRONG_CONFIRM gate can be satisfied
        orchestrator.onConfirmationRequiredMulticast.add { _, callback -> callback.invoke(true) }

        orchestrator.processCommand("cleanup notes", InputType.TEXT)

        val timeline = orchestrator.timelineSteps.value
        assertTrue(
            "delete_all_notes step must be classified STRONG_CONFIRM (got: ${timeline.map { it.detail }})",
            timeline.any { it.detail == "Risk: STRONG_CONFIRM" })
        assertTrue(
            "Skill must report completion after the confirmed delete",
            timeline.any { it.label == "Skill Complete" })
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
        // deny the confirmation → the safety pipeline must abort before execution
        orchestrator.onConfirmationRequiredMulticast.add { _, callback -> callback.invoke(false) }

        orchestrator.processCommand("cleanup2", InputType.TEXT)

        val timeline = orchestrator.timelineSteps.value
        assertTrue(
            "STRONG_CONFIRM must still be surfaced even when denied",
            timeline.any { it.detail == "Risk: STRONG_CONFIRM" })
        assertFalse(
            "Skill must NOT complete when the user declines confirmation",
            timeline.any { it.label == "Skill Complete" })
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
            timeline.any { it.label == "Security Block" })
        assertFalse(
            "A blocked step must stop the skill — no Skill Complete",
            timeline.any { it.label == "Skill Complete" })
    }
}
