package com.unoone.agent.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unoone.agent.storage.dao.NoteDao
import com.unoone.agent.storage.db.UnoOneDatabase
import com.unoone.agent.storage.entity.NoteEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Real on-device, HEADLESS notes data-layer gate (DEVICE_VERIFICATION §3 "create/read/search/delete
 * note workflows" + §8 "app update preserves Room data"). No UI, no Activity, no Hilt — drives the
 * app's own [NoteDao] through an in-memory Room [UnoOneDatabase], and a file-backed DB for the
 * survives-reopen persistence check. Mirrors the construction style of the JVM SkillSafetyRoutingTest
 * but runs on the physical device's ART + real Room/SQLite (the rules forbid marking a feature
 * verified from JVM tests alone).
 *
 * Run: am instrument -e class com.unoone.agent.storage.NotesCrudHeadlessTest \
 *   com.unoone.agent.test/androidx.test.runner.AndroidJUnitRunner
 */
class NotesCrudHeadlessTest {

    private lateinit var db: UnoOneDatabase
    private lateinit var dao: NoteDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, UnoOneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.noteDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun createReadSearchUpdateDelete() = runBlocking {
        val id1 = dao.insert(NoteEntity(title = "Buy milk", content = "2 litres skimmed", tags = "home,shopping"))
        val id2 = dao.insert(NoteEntity(title = "Ship V2", content = "finish device validation", tags = "work"))
        assertTrue("inserted row ids must be positive", id1 > 0 && id2 > 0)
        assertEquals("two notes seeded", 2, dao.recent(100).size)

        // read
        val got = dao.getById(id1)
        assertNotNull(got)
        assertEquals("Buy milk", got!!.title)

        // search across title / content / tag (LIKE)
        assertEquals("title hit", 1, dao.searchOnce("milk").size)
        assertEquals("content hit", 1, dao.searchOnce("validation").size)
        assertEquals("tag hit", 1, dao.searchOnce("shopping").size)
        assertTrue("negative search is empty", dao.searchOnce("zzznotfound").isEmpty())

        // update
        val updated = got.copy(content = "3 litres full cream", updatedAt = got.updatedAt + 1)
        dao.update(updated)
        assertEquals("3 litres full cream", dao.getById(id1)!!.content)
        assertEquals("title unchanged by update", "Buy milk", dao.getById(id1)!!.title)

        // delete by entity
        dao.delete(dao.getById(id2)!!)
        assertEquals("one note remains after delete", 1, dao.recent(100).size)
        assertNull("deleted note is gone", dao.getById(id2))

        // deleteAll empties the table
        assertEquals(1, dao.deleteAll())
        assertTrue("getAll empty after deleteAll", dao.getAll().first().isEmpty())
        assertTrue("recent empty after deleteAll", dao.recent(100).isEmpty())
    }

    @Test
    fun notePersistsAcrossDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "unoone_headless_notes_test"
        context.deleteDatabase(dbName) // clean slate (no-op if absent)

        val db1 = Room.databaseBuilder(context, UnoOneDatabase::class.java, dbName)
            .allowMainThreadQueries().build()
        val id = db1.noteDao().insert(NoteEntity(title = "Persisted", content = "survives reopen"))
        db1.close()

        val db2 = Room.databaseBuilder(context, UnoOneDatabase::class.java, dbName)
            .allowMainThreadQueries().build()
        val back = db2.noteDao().getById(id)
        assertNotNull("note must survive DB close + reopen on device", back)
        assertEquals("Persisted", back!!.title)
        assertEquals("survives reopen", back.content)
        db2.close()

        context.deleteDatabase(dbName) // cleanup
        Unit
    }
}