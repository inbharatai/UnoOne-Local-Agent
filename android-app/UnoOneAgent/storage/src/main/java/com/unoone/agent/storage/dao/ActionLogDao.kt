package com.unoone.agent.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.unoone.agent.storage.entity.ActionLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActionLogDao {
    @Insert
    suspend fun insert(log: ActionLogEntity): Long

    @Query("SELECT * FROM action_logs ORDER BY createdAt DESC LIMIT :limit")
    fun getRecent(limit: Int = 100): Flow<List<ActionLogEntity>>

    @Query("SELECT * FROM action_logs WHERE status = :status ORDER BY createdAt DESC")
    fun getByStatus(status: String): Flow<List<ActionLogEntity>>

    @Query("DELETE FROM action_logs")
    suspend fun clearAll()

    /** One-time privacy migration for releases that previously persisted raw prompts/tool values. */
    @Query(
        "UPDATE action_logs SET inputText = '[legacy private content removed]', " +
            "toolArgsJson = '{\"legacyPrivateArgsRemoved\":true}'"
    )
    suspend fun redactLegacyPrivateContent()

    /** Synchronous query for export — not a Flow, returns List directly */
    @Query("SELECT * FROM action_logs ORDER BY createdAt DESC LIMIT :limit")
    fun getRecentSync(limit: Int = 1000): List<ActionLogEntity>
}
