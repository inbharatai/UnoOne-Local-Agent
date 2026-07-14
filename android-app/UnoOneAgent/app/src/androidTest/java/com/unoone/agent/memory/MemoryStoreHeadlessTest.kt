package com.unoone.agent.memory

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unoone.agent.memory.MemoryModule
import com.unoone.agent.storage.dao.MemoryDao
import com.unoone.agent.storage.db.UnoOneDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Real on-device, HEADLESS agent-memory gate. The agent's long-term memory is a Room-backed store
 * (`MemoryModule` over `MemoryDao`), not the Notes table. This drives the app's own memory layer
 * with an in-memory DB (write/read/upsert/retrieve) and a file-backed DB for the survives-reopen
 * persistence check. Runs on the physical device's ART + real Room/SQLite.
 *
 * `getRelevantContext` matches query words against preference key OR value, correction value, and
 * pattern key (MemoryModule.kt:65-99), so the assertions below are exact, not guessed.
 *
 * Run: am instrument -e class com.unoone.agent.memory.MemoryStoreHeadlessTest ...
 */
class MemoryStoreHeadlessTest {

    private lateinit var db: UnoOneDatabase
    private lateinit var dao: MemoryDao
    private lateinit var mem: MemoryModule

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, UnoOneDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.memoryDao()
        mem = MemoryModule(dao)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun preferenceUpsertNoDuplicate() = runBlocking {
        mem.storePreference("user_name", "Alice")
        assertEquals("Alice", mem.getPreference("user_name"))
        // upsert by key: value replaces, no second row
        mem.storePreference("user_name", "Bob")
        assertEquals("Bob", mem.getPreference("user_name"))
        assertEquals("exactly one preference row after upsert", 1, dao.getByTypeList("preference").size)
        assertEquals("missing key returns null", null, mem.getPreference("does_not_exist"))
    }

    @Test
    fun correctionsPatternsOutcomesAndRelevantContext() = runBlocking {
        mem.storePreference("user_name", "Bob")
        mem.storePreference("home_city", "Bengaluru")
        mem.storeCorrection("open chrom", "open chrome")
        mem.storePattern("morning routine", "open news and weather")
        mem.storeOutcome("open chrome", "open_chrome", success = true)

        // word "name" is contained in key "user_name" → preference surfaces with its value "Bob"
        val byKey = mem.getRelevantContext("name")
        assertTrue("getRelevantContext must be non-blank", byKey.isNotBlank())
        assertTrue("must surface preference value Bob (key match 'name' in 'user_name'): $byKey",
            byKey.contains("Bob"))

        // word "bengaluru" is contained in value "Bengaluru" → preference surfaces
        val byValue = mem.getRelevantContext("bengaluru")
        assertTrue("must surface preference via value match: $byValue", byValue.contains("Bengaluru"))

        // word "chrome" is contained in correction value "open chrome" → correction surfaces
        val corr = mem.getRelevantContext("chrome")
        assertTrue("must surface correction 'open chrome': $corr", corr.contains("open chrome"))

        // a query with no matching words returns gracefully (no exception) and must NOT surface the
        // unrelated preference value — i.e. no false-positive match.
        val none = mem.getRelevantContext("zzznomatch")
        assertFalse("non-matching query must not surface unrelated preference Bob: $none", none.contains("Bob"))
    }

    @Test
    fun memoryPersistsAcrossDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "unoone_headless_memory_test"
        context.deleteDatabase(dbName)

        val db1 = Room.databaseBuilder(context, UnoOneDatabase::class.java, dbName)
            .allowMainThreadQueries().build()
        MemoryModule(db1.memoryDao()).storePreference("fact", "sky is blue")
        db1.close()

        val db2 = Room.databaseBuilder(context, UnoOneDatabase::class.java, dbName)
            .allowMainThreadQueries().build()
        val back = MemoryModule(db2.memoryDao()).getPreference("fact")
        assertEquals("preference must survive DB close + reopen on device", "sky is blue", back)
        db2.close()

        context.deleteDatabase(dbName)
        Unit
    }
}