package com.unoone.agent.data

import android.content.Context
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger
import com.unoone.agent.storage.dao.ActionLogDao
import com.unoone.agent.storage.dao.MemoryDao
import com.unoone.agent.storage.dao.NoteDao
import com.unoone.agent.storage.dao.SkillDao
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports the user's local data (notes, skills, memories, action logs) to a single JSON file in
 * app-private external storage, returning its absolute path. The file is reachable via a file
 * manager or `adb pull` and can be shared later through a FileProvider share sheet (wiring that
 * provider is a follow-up UI task; the export itself is complete and real here).
 */
class DataExporter(
    private val context: Context,
    private val noteDao: NoteDao,
    private val skillDao: SkillDao,
    private val memoryDao: MemoryDao,
    private val actionLogDao: ActionLogDao
) {

    suspend fun export(): Result<String> {
        return try {
            val notes = noteDao.getAll().first()
            val skills = skillDao.getAll().first()
            val memories = memoryDao.getAll().first()
            val logs = actionLogDao.getRecentSync(1000)

            val json = buildJsonObject {
                put("exportedAt", JsonPrimitive(System.currentTimeMillis()))
                put("app", JsonPrimitive("UnoOne"))
                put("notes", notes.toJsonArray { it.toJson() })
                put("skills", skills.toJsonArray { it.toJson() })
                put("memories", memories.toJsonArray { it.toJson() })
                put("actionLogs", logs.toJsonArray { it.toJson() })
            }

            val exportDir = context.getExternalFilesDir("exports") ?: context.filesDir
            exportDir.mkdirs()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(System.currentTimeMillis()))
            val file = File(exportDir, "unoone_export_$timestamp.json")
            file.writeText(json.toString())

            Logger.i("DataExporter: wrote ${file.length()} bytes to ${file.absolutePath}")
            Result.Success(file.absolutePath)
        } catch (e: Exception) {
            Logger.e("DataExporter: export failed", e)
            Result.Error("Export failed: ${e.message}")
        }
    }

    private fun <T> List<T>.toJsonArray(transform: (T) -> JsonObject): JsonArray =
        buildJsonArray { forEach { add(transform(it)) } }

    private fun com.unoone.agent.storage.entity.NoteEntity.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("title", title)
        put("content", content)
        put("tags", tags)
        put("createdAt", createdAt)
    }

    private fun com.unoone.agent.storage.entity.SkillEntity.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("triggerPhrases", triggerPhrases)
        put("stepsJson", stepsJson)
        put("enabled", enabled)
        put("createdAt", createdAt)
    }

    private fun com.unoone.agent.storage.entity.MemoryEntity.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("key", key)
        put("type", type)
        put("value", value)
        put("updatedAt", updatedAt)
    }

    private fun com.unoone.agent.storage.entity.ActionLogEntity.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("inputText", inputText)
        put("inputType", inputType)
        put("selectedTool", selectedTool)
        put("status", status)
        put("createdAt", createdAt)
    }
}