package com.unoone.agent.localbrain

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * Deterministic English/Hindi parser for a reviewable Calendar insert draft.
 *
 * It intentionally handles common relative and absolute forms locally instead of sending them to
 * Gemma. Missing or ambiguous date/time values remain null; the executor must ask the user to
 * clarify instead of silently scheduling at "now" or inventing 9 AM.
 */
object CalendarCommandParser {

    data class Details(
        val title: String,
        val startTimeIso: String?,
        val endTimeIso: String?
    )

    fun parse(
        command: String,
        now: ZonedDateTime = ZonedDateTime.now(),
        zoneId: ZoneId = now.zone
    ): Details {
        val normalizedDigits = normalizeNumberWords(devanagariDigitsToAscii(command))
        val lowered = normalizedDigits.lowercase()
        val localNow = now.withZoneSameInstant(zoneId)

        val relativeDuration = Regex(
            "\\b(?:in|after)\\s+(\\d+)\\s*(minutes?|hours?)\\b",
            RegexOption.IGNORE_CASE
        ).find(lowered)
        val hindiRelativeDuration = Regex(
            "(\\d+)\\s*(मिनट|घंटे|घंटा)\\s*(?:में|बाद)"
        ).find(lowered)

        val englishTime = Regex(
            "\\b(?:at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b",
            RegexOption.IGNORE_CASE
        ).find(lowered)
        val twentyFourHour = Regex("\\bat\\s+(\\d{1,2}):(\\d{2})\\b", RegexOption.IGNORE_CASE)
            .find(lowered)
        val hindiTime = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*बजे").find(lowered)
        val timeMatch = englishTime ?: twentyFourHour ?: hindiTime

        var hour = timeMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        val minute = timeMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        val meridiem = englishTime?.groupValues?.getOrNull(3)?.lowercase().orEmpty()
        var timeIsUnambiguous = englishTime != null || twentyFourHour != null
        if (hour != null) {
            if (meridiem == "pm" && hour in 1..11) hour += 12
            if (meridiem == "am" && hour == 12) hour = 0
            if (hindiTime != null && meridiem.isBlank()) {
                when {
                    (lowered.contains("शाम") || lowered.contains("रात")) && hour in 1..11 -> {
                        hour += 12
                        timeIsUnambiguous = true
                    }
                    lowered.contains("दोपहर") && hour in 1..11 -> {
                        hour += 12
                        timeIsUnambiguous = true
                    }
                    lowered.contains("सुबह") -> timeIsUnambiguous = true
                    hour in 13..23 -> timeIsUnambiguous = true
                }
            }
        }

        val durationMatch = relativeDuration ?: hindiRelativeDuration
        val start = if (durationMatch != null) {
            val amount = durationMatch.groupValues[1].toLongOrNull()
            val unit = durationMatch.groupValues[2].lowercase()
            when {
                amount == null || amount <= 0 -> null
                unit.startsWith("hour") || unit.startsWith("घंट") -> localNow.plusHours(amount)
                else -> localNow.plusMinutes(amount)
            }?.withSecond(0)?.withNano(0)
        } else {
            val date = resolveDate(lowered, localNow.toLocalDate())
            if (
                date != null && timeIsUnambiguous &&
                hour != null && hour in 0..23 && minute in 0..59
            ) {
                date.atTime(hour, minute).atZone(zoneId)
            } else {
                null
            }
        }

        val title = extractTitle(normalizedDigits)
        return Details(
            title = title.ifBlank { "New Event" },
            startTimeIso = start?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            endTimeIso = start?.plusHours(1)?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        )
    }

    private fun extractTitle(command: String): String {
        var title = command
        val removals = listOf(
            Regex("\\b(?:add|create|insert|book)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(?:schedule|set|make)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(?:to|in|on|my|the)\\s+calendar\\b", RegexOption.IGNORE_CASE),
            Regex("\\bcalendar\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(?:on|at)\\b", RegexOption.IGNORE_CASE),
            Regex("^\\s*remind\\s+me\\s+", RegexOption.IGNORE_CASE),
            Regex("\\b(?:today|tomorrow|day\\s+after\\s+tomorrow)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(?:this|next)?\\s*(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b\\d{1,2}\\s+(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)(?:\\s+\\d{4})?\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(?:at\\s+)?\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)\\b", RegexOption.IGNORE_CASE),
            Regex("\\bat\\s+\\d{1,2}:\\d{2}\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(?:in|after)\\s+\\d+\\s*(?:minutes?|hours?)\\b", RegexOption.IGNORE_CASE),
            Regex("(?:आज|कल|परसों|सुबह|दोपहर|शाम|रात)"),
            Regex("\\d{1,2}(?::\\d{2})?\\s*बजे"),
            Regex("\\d+\\s*(?:मिनट|घंटे|घंटा)\\s*(?:में|बाद)"),
            Regex("(?:कैलेंडर|में|पर|जोड़ो|बनाओ|लगाओ)"),
            Regex("^\\s*to\\s+", RegexOption.IGNORE_CASE)
        )
        removals.forEach { title = it.replace(title, " ") }
        return title.replace(Regex("\\s{2,}"), " ")
            .trim(' ', ',', '.', '-', ':')
    }

    private fun resolveDate(text: String, today: LocalDate): LocalDate? {
        if (Regex("\\bday\\s+after\\s+tomorrow\\b").containsMatchIn(text) || text.contains("परसों")) {
            return today.plusDays(2)
        }
        if (Regex("\\btomorrow\\b").containsMatchIn(text) || text.contains("कल")) {
            return today.plusDays(1)
        }
        if (Regex("\\btoday\\b").containsMatchIn(text) || text.contains("आज")) {
            return today
        }

        val absolute = Regex(
            "\\b(\\d{1,2})\\s+(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)(?:\\s+(\\d{4}))?\\b",
            RegexOption.IGNORE_CASE
        ).find(text)
        if (absolute != null) {
            val day = absolute.groupValues[1].toInt()
            val month = monthFor(absolute.groupValues[2]) ?: return null
            var year = absolute.groupValues[3].toIntOrNull() ?: today.year
            var candidate = runCatching { LocalDate.of(year, month, day) }.getOrNull()
                ?: return null
            if (absolute.groupValues[3].isBlank() && candidate.isBefore(today)) {
                year += 1
                candidate = runCatching { LocalDate.of(year, month, day) }.getOrNull()
                    ?: return null
            }
            return candidate
        }

        val weekdays = mapOf(
            "monday" to DayOfWeek.MONDAY,
            "tuesday" to DayOfWeek.TUESDAY,
            "wednesday" to DayOfWeek.WEDNESDAY,
            "thursday" to DayOfWeek.THURSDAY,
            "friday" to DayOfWeek.FRIDAY,
            "saturday" to DayOfWeek.SATURDAY,
            "sunday" to DayOfWeek.SUNDAY
        )
        val weekdayMatch = Regex(
            "\\b(?:(this|next)\\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b"
        ).find(text)
        if (weekdayMatch != null) {
            val qualifier = weekdayMatch.groupValues[1]
            val day = weekdays.getValue(weekdayMatch.groupValues[2])
            return if (qualifier == "next") {
                today.with(TemporalAdjusters.next(day))
            } else {
                today.with(TemporalAdjusters.nextOrSame(day))
            }
        }
        return null
    }

    private fun monthFor(value: String): Month? = when (value.take(3).lowercase()) {
        "jan" -> Month.JANUARY
        "feb" -> Month.FEBRUARY
        "mar" -> Month.MARCH
        "apr" -> Month.APRIL
        "may" -> Month.MAY
        "jun" -> Month.JUNE
        "jul" -> Month.JULY
        "aug" -> Month.AUGUST
        "sep" -> Month.SEPTEMBER
        "oct" -> Month.OCTOBER
        "nov" -> Month.NOVEMBER
        "dec" -> Month.DECEMBER
        else -> null
    }

    private fun normalizeNumberWords(value: String): String {
        var result = value
        val replacements = linkedMapOf(
            "twelve" to "12", "eleven" to "11", "ten" to "10", "nine" to "9",
            "eight" to "8", "seven" to "7", "six" to "6", "five" to "5",
            "four" to "4", "three" to "3", "two" to "2", "one" to "1",
            "बारह" to "12", "ग्यारह" to "11", "दस" to "10", "नौ" to "9",
            "आठ" to "8", "सात" to "7", "छह" to "6", "पाँच" to "5",
            "चार" to "4", "तीन" to "3", "दो" to "2", "एक" to "1"
        )
        replacements.forEach { (word, digit) ->
            result = if (word.first().code < 128) {
                Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE).replace(result, digit)
            } else {
                result.replace(word, digit)
            }
        }
        return result
    }

    private fun devanagariDigitsToAscii(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            append(
                when (ch) {
                    '०' -> '0'
                    '१' -> '1'
                    '२' -> '2'
                    '३' -> '3'
                    '४' -> '4'
                    '५' -> '5'
                    '६' -> '6'
                    '७' -> '7'
                    '८' -> '8'
                    '९' -> '9'
                    else -> ch
                }
            )
        }
    }
}
