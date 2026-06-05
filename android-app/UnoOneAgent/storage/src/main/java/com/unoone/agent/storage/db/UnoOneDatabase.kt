package com.unoone.agent.storage.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.unoone.agent.storage.dao.ActionLogDao
import com.unoone.agent.storage.dao.MemoryDao
import com.unoone.agent.storage.dao.ModelMetadataDao
import com.unoone.agent.storage.dao.NoteDao
import com.unoone.agent.storage.dao.SkillDao
import com.unoone.agent.storage.entity.ActionLogEntity
import com.unoone.agent.storage.entity.MemoryEntity
import com.unoone.agent.storage.entity.ModelMetadataEntity
import com.unoone.agent.storage.entity.NoteEntity
import com.unoone.agent.storage.entity.SkillEntity

@Database(
    entities = [
        NoteEntity::class,
        SkillEntity::class,
        MemoryEntity::class,
        ActionLogEntity::class,
        ModelMetadataEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class UnoOneDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun skillDao(): SkillDao
    abstract fun memoryDao(): MemoryDao
    abstract fun actionLogDao(): ActionLogDao
    abstract fun modelMetadataDao(): ModelMetadataDao

    companion object {
        /**
         * Migration from v1 (no indexes) to v2 (indexes on title, tags, createdAt, etc.).
         * Safe to run on existing databases — CREATE INDEX IF NOT EXISTS is idempotent.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // notes table indexes
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_title ON notes (title)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_tags ON notes (tags)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_createdAt ON notes (createdAt)")

                // action_logs table indexes
                db.execSQL("CREATE INDEX IF NOT EXISTS index_action_logs_status ON action_logs (status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_action_logs_createdAt ON action_logs (createdAt)")

                // memories table indexes (unique constraint on key)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_memories_key ON memories (key)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_type ON memories (type)")

                // skills table indexes (unique constraint on name)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_skills_name ON skills (name)")
            }
        }
    }
}