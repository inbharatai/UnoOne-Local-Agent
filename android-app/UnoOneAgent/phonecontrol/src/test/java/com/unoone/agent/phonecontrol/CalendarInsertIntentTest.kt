package com.unoone.agent.phonecontrol

import android.content.Intent
import android.provider.CalendarContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CalendarInsertIntentTest {
    @Test
    fun `calendar draft carries resolvable event mime type and review values`() {
        val context = RuntimeEnvironment.getApplication()
        val intent = PhoneControl(context).calendarInsertIntent(
            title = "UnoOne QA",
            startTime = 1_000L,
            endTime = 2_000L,
            description = "Review only",
            location = "Office"
        )

        assertEquals(Intent.ACTION_INSERT, intent.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, intent.data)
        assertEquals("vnd.android.cursor.dir/event", intent.type)
        assertEquals("UnoOne QA", intent.getStringExtra(CalendarContract.Events.TITLE))
        assertEquals(1_000L, intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1L))
        assertEquals(2_000L, intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1L))
        assertEquals("Review only", intent.getStringExtra(CalendarContract.Events.DESCRIPTION))
        assertEquals("Office", intent.getStringExtra(CalendarContract.Events.EVENT_LOCATION))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
