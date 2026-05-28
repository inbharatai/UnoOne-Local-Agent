package com.unoone.agent.storage.db

import androidx.room.Database
import androidx.room.RoomDatabase
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
    version = 1,
    exportSchema = false
)
abstract class UnoOneDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun skillDao(): SkillDao
    abstract fun memoryDao(): MemoryDao
    abstract fun actionLogDao(): ActionLogDao
    abstract fun modelMetadataDao(): ModelMetadataDao
}
