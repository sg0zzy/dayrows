// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.domain

import it.stefanogozzi.dayrows.data.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

data class MatrixEvent(val instance: EventInstance, val clippedStart: Instant, val clippedEnd: Instant,
                       val continuesBefore: Boolean, val continuesAfter: Boolean)
data class MatrixRow(val calendar: DeviceCalendar, val label: String, val cells: List<List<MatrixEvent>>)
data class CalendarMatrix(val dates: List<LocalDate>, val rows: List<MatrixRow>)

fun visibleDates(start: LocalDate, count: Int): List<LocalDate> {
    require(count in listOf(1, 2, 7))
    return List(count) { start.plusDays(it.toLong()) }
}

/** Half-open boundaries in the current device zone; all-day dates are encoded in UTC by Android. */
class CalendarMatrixBuilder {
    fun build(calendars: List<DeviceCalendar>, events: List<EventInstance>, dates: List<LocalDate>,
              preferences: UserPreferences, zone: ZoneId): CalendarMatrix {
        val unique = events.distinctBy { listOf(it.calendarId, it.eventId, it.start, it.end) }.groupBy { it.calendarId }
        return CalendarMatrix(dates, preferences.ordered(calendars)
            .filter { preferences.preference(it).selected }.map { calendar ->
                MatrixRow(calendar, preferences.label(calendar), dates.map { date ->
                    unique[calendar.id].orEmpty().mapNotNull { event ->
                        val dayZone = if (event.allDay) ZoneOffset.UTC else zone
                        val start = date.atStartOfDay(dayZone).toInstant()
                        val end = date.plusDays(1).atStartOfDay(dayZone).toInstant()
                        // Zero-duration provider entries are points on their start date.
                        val overlaps = if (event.end == event.start) event.start >= start && event.start < end
                                       else event.end > event.start && event.start < end && event.end > start
                        if (!overlaps) null else MatrixEvent(event, maxOf(event.start, start), minOf(event.end, end),
                            continuesBefore = event.start < start, continuesAfter = event.end > end)
                    }.sortedWith(compareBy<MatrixEvent> { !it.instance.allDay }
                        .thenBy { it.instance.start }.thenBy { it.instance.end }
                        .thenBy { it.instance.eventId }.thenBy { it.instance.title.orEmpty() })
                })
            })
    }
}

fun MatrixEvent.timeLabel(zone: ZoneId, locale: Locale): String {
    val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone)
    val start = if (continuesBefore) "↤" else formatter.format(clippedStart)
    // 24:00 communicates an exclusive end at next midnight instead of a misleading 00:00.
    val midnight = clippedEnd.atZone(zone).toLocalTime() == LocalTime.MIDNIGHT && clippedEnd > clippedStart
    val end = if (continuesAfter) "↦" else if (midnight) "24:00" else formatter.format(clippedEnd)
    return if (instance.start == instance.end) start else "$start–$end"
}

fun EventInstance.displayTitle(untitled: String): String = title?.trim()?.takeIf { it.isNotEmpty() } ?: untitled

fun instanceQueryBounds(start: LocalDate, endExclusive: LocalDate, zone: ZoneId): Pair<Instant, Instant> =
    minOf(start.atStartOfDay(zone).toInstant(), start.atStartOfDay(ZoneOffset.UTC).toInstant()) to
        maxOf(endExclusive.atStartOfDay(zone).toInstant(), endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant())
