package com.unoone.agent.core.interfaces

/**
 * Abstraction for model management (detection, download, storage).
 * Enables testing model logic without filesystem access.
 * Return types are simple data classes to avoid cross-module dependencies.
 */
interface IModelManager {
    data class ModelStatusInfo(val name: String, val present: Boolean, val sizeMb: Long)

    suspend fun detectModels(): List<ModelStatusInfo>
    fun getStorageUsageMb(): Long
    fun ensureModelDirectories()
}