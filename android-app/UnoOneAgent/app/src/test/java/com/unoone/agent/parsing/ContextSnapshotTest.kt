package com.unoone.agent.parsing

import com.unoone.agent.storage.dao.NoteDao
import com.unoone.agent.storage.dao.SkillDao
import com.unoone.agent.storage.entity.NoteEntity
import com.unoone.agent.storage.entity.SkillEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the enriched context snapshot: recent notes, active skills, the recent-commands ring,
 * and the last tool result are all populated for the LLM planner. Uses simple fakes for the DAOs
 * (no Room DB). Robolectric is on only so android.util.Log (used by Logger inside the parser
 * pipeline) is available; accessibility/OCR are passed as null so those guarded paths short-circuit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContextSnapshotTest {

    @Test
    fun snapshotPopulatesRecentNotesActiveSkillsRecentCommandsAndLastResult() = runBlocking {
        val parser = CommandParser(
            accessibilityControl = null, // visibleText stays blank; OCR fallback is skipped
            ocrControl = null,
            memoryModule = null,
            noteDao = FakeNoteDao(
                listOf(
                    NoteEntity(id = 1, title = "Groceries", content = "milk"),
                    NoteEntity(id = 2, title = "", content = "no title here")
                )
            ),
            skillDao = FakeSkillDao(
                listOf(
                    SkillEntity(id = 1, name = "MorningRoutine", triggerPhrases = "morning", stepsJson = "[]"),
                    SkillEntity(id = 2, name = "Cleanup", triggerPhrases = "cleanup", stepsJson = "[]")
                )
            )
        )

        val snapshot = parser.buildContextSnapshot(
            command = "the second one",
            recentCommands = listOf("create note groceries", "search notes groceries"),
            lastToolResult = "Found 1 note"
        )

        assertEquals(listOf("Groceries", "no title here"), snapshot.recentNotes)
        assertEquals(listOf("MorningRoutine", "Cleanup"), snapshot.activeSkills)
        assertEquals(
            listOf("create note groceries", "search notes groceries"),
            snapshot.recentCommands
        )
        assertEquals("Found 1 note", snapshot.lastToolResult)
        // No accessibility / OCR / memory supplied → these stay blank, exercising the guards.
        assertTrue("visibleText must be blank when accessibility is unavailable", snapshot.visibleText.isBlank())
        assertTrue("ocrText must be blank when OCR is unavailable", snapshot.ocrText.isBlank())
        assertTrue("userMemory must be blank when memory module is null", snapshot.userMemory.isBlank())
    }

    @Test
    fun snapshotIsEmptyWhenNoContextSourcesAreAvailable() = runBlocking {
        val parser = CommandParser() // all sources null
        val snapshot = parser.buildContextSnapshot("anything", emptyList(), "")
        assertTrue("Snapshot with no sources must report isEmpty()", snapshot.isEmpty())
    }

    @Test
    fun snapshotPopulatesVoiceLanguageFromProvider() = runBlocking {
        // The orchestrator wires this provider to the unoone_settings/voice_language preference so
        // the planner knows the user's current language and keeps its reply in it (A6).
        val parser = CommandParser(voiceLanguageProvider = { "hi" })
        val snapshot = parser.buildContextSnapshot("create note buy milk", emptyList(), "")
        assertEquals("hi", snapshot.voiceLanguage)
    }

    private class FakeNoteDao(private val recentNotes: List<NoteEntity>) : NoteDao {
        override suspend fun insert(note: NoteEntity): Long = 0
        override suspend fun update(note: NoteEntity) = Unit
        override suspend fun delete(note: NoteEntity) = Unit
        override fun getAll() = flowOf(recentNotes)
        override fun search(query: String) = flowOf(recentNotes)
        override suspend fun getById(id: Long): NoteEntity? = recentNotes.firstOrNull { it.id == id }
        override suspend fun searchOnce(query: String): List<NoteEntity> = recentNotes
        override suspend fun recent(limit: Int): List<NoteEntity> = recentNotes.take(limit)
        override suspend fun deleteByQuery(query: String): Int = 0
        override suspend fun deleteAll(): Int = 0
    }

    private class FakeSkillDao(private val enabled: List<SkillEntity>) : SkillDao {
        override suspend fun insert(skill: SkillEntity): Long = 0
        override suspend fun update(skill: SkillEntity) = Unit
        override suspend fun delete(skill: SkillEntity) = Unit
        override fun getEnabled() = flowOf(enabled)
        override fun getAll() = flowOf(enabled)
        override suspend fun getById(id: Long): SkillEntity? = enabled.firstOrNull { it.id == id }
    }
}