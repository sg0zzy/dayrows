// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.domain

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The pre-filled content of a new event handed to an installed calendar app.
 * DayRows never writes to the provider itself; it only prepares this draft.
 *
 * The draft names no calendar. A calendar app offers only the calendars it can write to
 * and that are visible to it, and a receiving app is free to ignore a requested calendar
 * and keep its own default, so DayRows could not promise the one it passed. What it can
 * promise is the day and the slot, which is all this draft carries.
 *
 * The start and end are already resolved instants, so a DST transition cannot
 * shift them a second time when the receiving app reads the epoch values. */
data class NewEventDraft(
    val date: LocalDate,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
) {
    val startMillis: Long get() = start.toInstant().toEpochMilli()
    val endMillis: Long get() = end.toInstant().toEpochMilli()
}

/** A one-hour slot on the given day, in the device's current zone. */
fun newEventDraft(date: LocalDate, zone: ZoneId): NewEventDraft {
    // Resolve the start on the requested day. A DST transition can make a local time
    // non-existent; java.time then moves it forward, so the result may differ from 09:00.
    var begin = date.atTime(DEFAULT_EVENT_START).atZone(zone)
    if (begin.toLocalDate() != date) begin = date.atStartOfDay(zone)
    val end = begin.plusHours(1)
    // The hour may reach past midnight on a short day; such a slot ends at the day's last minute.
    val finish = if (end.toLocalDate() == date) end else date.atTime(LocalTime.of(23, 59)).atZone(zone)
    return NewEventDraft(date, begin, finish)
}

/** Default start of a new event slot. */
private val DEFAULT_EVENT_START: LocalTime = LocalTime.of(9, 0)
