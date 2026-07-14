package com.unoone.agent.safety

import android.content.Context
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
 * Real on-device, HEADLESS proof of the in-app Security Level setting (Settings → Security Level).
 * Constructs the real [AgentOrchestrator] with an in-memory Room DB (no Hilt, no Gemma) and drives
 * `processCommand` for skills under each [SecurityLevel], asserting the orchestrator honors the
 * user's chosen posture:
 *
 * - STANDARD: a BLOCK-classified input ("…check my bank balance") is blocked → "Security Block".
 * - OFF: the SAME blocked input is NOT blocked (the BLOCK tier is bypassed) → no "Security Block".
 *   (In OFF the step proceeds to execute open_url, which may open the browser on the test device —
 *   that is the intended demo behavior of "everything runs". We assert only the gate was bypassed.)
 * - RELAXED: a STRONG_CONFIRM delete-all-notes step runs WITHOUT any confirmation listener and
 *   still completes + deletes (auto-confirm), proving the confirm tap is skipped.
 *
 * Prefs hygiene: these tests run in the app's real process and share the `unoone_settings` store, so
 * @Before sets STANDARD and @After removes the key (back to the STANDARD default) — other instrumented
 * classes that assume STANDARD are never polluted.
 *
 * Run: am instrument -e class com.unoone.agent.safety.SecurityLevelHeadlessTest \
 *   com.unoone.agent.test/androidx.test.runner.AndroidJUnitRunner
 */
class SecurityLevelHeadlessTest {

    private lateinit var context: Context
    private lateinit var db: UnoOneDatabase
    private lateinit var orchestrator: AgentOrchestrator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Start from the production default so the baseline test is deterministic.
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
        // Restore the STANDARD default so this test never leaves the user's app in RELAXED/OFF.
        context.getSharedPreferences(SecurityLevel.PREF_NAME, Context.MODE_PRIVATE)
            .edit().remove(SecurityLevel.PREF_KEY).commit()
        db.close()
    }

    @Test
    fun standardBlocksBankBalanceInput() = runBlocking {
        orchestrator.skillsModule.saveSkill(
            name = "BankHelper",
            triggerPhrases = listOf("check bank"),
            steps = listOf("open google to check my bank balance")
        )
        orchestrator.processCommand("check bank", InputType.TEXT)

        val timeline = orchestrator.timelineSteps.value
        assertTrue(
            "STANDARD must block a bank-balance input (got: ${timeline.map { it.label }})",
            timeline.any { it.label == "Security Block" })
    }

    @Test
    fun offBypassesTheBlockOnTheSameInput() = runBlocking {
        SecurityLevel.set(context, SecurityLevel.OFF)
        orchestrator.skillsModule.saveSkill(
            name = "BankHelperOff",
            triggerPhrases = listOf("check bank off"),
            steps = listOf("open google to check my bank balance")
        )
        orchestrator.processCommand("check bank off", InputType.TEXT)

        val timeline = orchestrator.timelineSteps.value
        assertFalse(
            "OFF must NOT block the same bank-balance input — the BLOCK tier is bypassed (got: ${timeline.map { it.label }})",
            timeline.any { it.label == "Security Block" })
        // And the active level was recorded in the timeline.
        assertTrue(
            "OFF must be visible in the timeline (got: ${timeline.map { it.detail }})",
            timeline.any { it.detail == "security: OFF" })
    }

    @Test
    fun relaxedAutoConfirmsDeleteAllWithoutATap() = runBlocking {
        SecurityLevel.set(context, SecurityLevel.RELAXED)
        db.noteDao().insert(NoteEntity(title = "Keep", content = "should be auto-deleted", tags = "x"))
        assertEquals(1, db.noteDao().recent(100).size)

        orchestrator.skillsModule.saveSkill(
            name = "CleanupRelaxed",
            triggerPhrases = listOf("cleanup relaxed"),
            steps = listOf("delete all notes")
        )
        // Deliberately add NO confirmation listener. In STANDARD this would deny the STRONG_CONFIRM
        // prompt (no listeners → default-deny) and the notes would survive. In RELAXED the prompt is
        // skipped (auto-confirmed), so the step must execute and wipe the notes with no user tap.
        orchestrator.processCommand("cleanup relaxed", InputType.TEXT)

        val timeline = orchestrator.timelineSteps.value
        assertFalse(
            "RELAXED must not block the delete (got: ${timeline.map { it.label }})",
            timeline.any { it.label == "Security Block" })
        assertTrue(
            "RELAXED must auto-confirm and complete the skill with no tap (got: ${timeline.map { it.label }})",
            timeline.any { it.label == "Skill Complete" })
        assertEquals(
            "RELAXED auto-confirm must actually delete the notes",
            0, db.noteDao().recent(100).size)
    }
}