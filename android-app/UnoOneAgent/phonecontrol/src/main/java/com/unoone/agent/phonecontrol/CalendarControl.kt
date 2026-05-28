package com.unoone.agent.phonecontrol

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger

class CalendarControl(private val context: Context) {

    fun getEvents(startTime: Long, endTime: Long): Result<List<CalendarEvent>> {
        return try {
            val events = mutableListOf<CalendarEvent>()
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
                ContentUris.appendId(this, startTime)
                ContentUris.appendId(this, endTime)
            }.build()

            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION
            )

            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                val startIdx = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                val endIdx = cursor.getColumnIndex(CalendarContract.Instances.END)
                val locIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)

                while (cursor.moveToNext()) {
                    events.add(
                        CalendarEvent(
                            title = cursor.getString(titleIdx),
                            startTime = cursor.getLong(startIdx),
                            endTime = cursor.getLong(endIdx),
                            location = cursor.getString(locIdx) ?: ""
                        )
                    )
                }
            }
            Result.Success(events)
        } catch (e: Exception) {
            Logger.e("Failed to read calendar", e)
            Result.Error("Cannot read calendar: ${e.message}")
        }
    }

    data class CalendarEvent(
        val title: String,
        val startTime: Long,
        val endTime: Long,
        val location: String
    )
}
