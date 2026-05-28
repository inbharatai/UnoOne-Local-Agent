package com.unoone.agent.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.unoone.agent.storage.entity.ModelMetadataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelMetadataDao {
    @Insert
    suspend fun insert(model: ModelMetadataEntity): Long

    @Update
    suspend fun update(model: ModelMetadataEntity)

    @Query("SELECT * FROM model_metadata ORDER BY modelType")
    fun getAll(): Flow<List<ModelMetadataEntity>>

    @Query("SELECT * FROM model_metadata WHERE modelType = :type")
    fun getByType(type: String): Flow<List<ModelMetadataEntity>>

    @Query("SELECT * FROM model_metadata WHERE modelName = :name LIMIT 1")
    suspend fun getByName(name: String): ModelMetadataEntity?
}
