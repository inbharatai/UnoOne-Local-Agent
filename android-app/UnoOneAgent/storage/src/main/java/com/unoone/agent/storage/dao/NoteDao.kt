package com.unoone.agent.storage.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.unoone.agent.storage.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    @Delete
    suspend fun delete(note: NoteEntity)

    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun getAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    fun search(query: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: Long): NoteEntity?

    /** One-shot (non-Flow) search used by the search_notes tool. */
    @Query("SELECT * FROM notes WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    suspend fun searchOnce(query: String): List<NoteEntity>

    /** Most-recent notes for the context snapshot (one-shot). */
    @Query("SELECT * FROM notes ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<NoteEntity>

    /** Deletes notes whose title/content/tags match the query (used by delete_notes after CONFIRM). */
    @Query("DELETE FROM notes WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%'")
    suspend fun deleteByQuery(query: String): Int

    /** Deletes every note (used by delete_all_notes after STRONG_CONFIRM). Returns rows deleted. */
    @Query("DELETE FROM notes")
    suspend fun deleteAll(): Int
}
