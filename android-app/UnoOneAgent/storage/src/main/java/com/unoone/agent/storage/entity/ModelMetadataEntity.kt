package com.unoone.agent.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "model_metadata")
data class ModelMetadataEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val modelName: String,
    val modelType: String, // llm, asr, tts, vad, punctuation, ocr
    val localPath: String,
    val checksum: String = "",
    val status: String = "missing", // missing, present, loaded, error
    val lastLoadedAt: Long? = null
)
