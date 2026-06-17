package com.unoone.agent.core.interfaces

import com.unoone.agent.core.model.Result

/**
 * Abstraction for memory storage and retrieval.
 * Enables testing without a real database.
 */
interface IMemoryModule {
    suspend fun store(key: String, value: String, type: String): Result<Unit>
    suspend fun retrieve(key: String): Result<String?>
    suspend fun search(query: String): Result<List<String>>
}