package com.unoone.agent.localbrain

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarCommandParserTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 7, 20, 11, 0, 0, 0, zone)

    @Test
    fun `parses English relative event and strips control words from title`() {
        val details = CalendarCommandParser.parse(
            "add meeting to calendar tomorrow at 5 pm",
            now,
            zone
        )

        assertEquals("meeting", details.title)
        assertEquals("2026-07-21T17:00:00+05:30", details.startTimeIso)
        assertEquals("2026-07-21T18:00:00+05:30", details.endTimeIso)
    }

    @Test
    fun `parses Hindi relative event with Devanagari time`() {
        val details = CalendarCommandParser.parse(
            "कल शाम ५ बजे मीटिंग कैलेंडर में जोड़ो",
            now,
            zone
        )

        assertEquals("मीटिंग", details.title)
        assertEquals("2026-07-21T17:00:00+05:30", details.startTimeIso)
        assertNotNull(details.endTimeIso)
    }

    @Test
    fun `parses next weekday absolute date and relative duration`() {
        val monday = CalendarCommandParser.parse(
            "schedule project call next Monday at 10 am",
            now,
            zone
        )
        assertEquals("project call", monday.title)
        assertEquals("2026-07-27T10:00:00+05:30", monday.startTimeIso)

        val relative = CalendarCommandParser.parse(
            "remind me to call Rahul in two hours",
            now,
            zone
        )
        assertEquals("call Rahul", relative.title)
        assertEquals("2026-07-20T13:00:00+05:30", relative.startTimeIso)
    }

    @Test
    fun `parses absolute date and Hindi number word`() {
        val absolute = CalendarCommandParser.parse(
            "add doctor appointment on 22 July at 11 am",
            now,
            zone
        )
        assertEquals("doctor appointment", absolute.title)
        assertEquals("2026-07-22T11:00:00+05:30", absolute.startTimeIso)

        val hindi = CalendarCommandParser.parse(
            "कल शाम चार बजे मीटिंग कैलेंडर में जोड़ो",
            now,
            zone
        )
        assertEquals("2026-07-21T16:00:00+05:30", hindi.startTimeIso)
    }

    @Test
    fun `does not invent missing or ambiguous time`() {
        assertNull(
            CalendarCommandParser.parse("schedule meeting tomorrow", now, zone).startTimeIso
        )
        assertNull(
            CalendarCommandParser.parse("कल 4 बजे मीटिंग कैलेंडर में जोड़ो", now, zone).startTimeIso
        )
        assertNull(
            CalendarCommandParser.parse("add event on 31 February at 10 am", now, zone).startTimeIso
        )
    }
}
