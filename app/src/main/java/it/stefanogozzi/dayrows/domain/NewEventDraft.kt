// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.domain

import it.stefanogozzi.dayrows.data.DeviceCalendar
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Access levels at or above [CAL_ACCESS_CONTRIBUTOR] allow writing to the calendar.
 * The provider exposes them as plain integers; the names mirror CalendarContract. */
private const val CAL_ACCESS_CONTRIBUTOR = 500

/** A calendar the user may add events to. Read-only and free/busy calendars are excluded.
 * A missing access level is reported as 0 and therefore excluded. */
fun DeviceCalendar.isEditable(): Boolean = accessLevel >= CAL_ACCESS_CONTRIBUTOR

/** Whether a receiving calendar app can preselect this calendar for a new event.
 *
 * A calendar app lists the calendars it can write to and shows them in a picker. The
 * platform calendar app selects that list with
 * `access_level >= CAL_ACCESS_CONTRIBUTOR AND visible = 1`, so a hidden calendar
 * (`visible = 0`) is never in the list. Handing such a calendar to an insert request
 * makes the app fall back to the first calendar in its list, which is a different
 * calendar than the one the user tapped. */
fun DeviceCalendar.canBePreselected(): Boolean = isEditable() && visible

/** The pre-filled content of a new event handed to an installed calendar app.
 * DayRows never writes to the provider itself; it only prepares this draft.
 * The start and end are already resolved instants, so a DST transition cannot
 * shift them a second time when the receiving app reads the epoch values. */
data class NewEventDraft(
    val calendarId: Long,
    val date: LocalDate,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
) {
    val startMillis: Long get() = start.toInstant().toEpochMilli()
    val endMillis: Long get() = end.toInstant().toEpochMilli()
}

/** A one-hour slot on the given day, in the device's current zone. */
fun newEventDraft(calendar: DeviceCalendar, date: LocalDate, zone: ZoneId): NewEventDraft {
    // Resolve the start on the requested day. A DST transition can make a local time
    // non-existent; java.time then moves it forward, so the result may differ from 09:00.
    var begin = date.atTime(DEFAULT_EVENT_START).atZone(zone)
    if (begin.toLocalDate() != date) begin = date.atStartOfDay(zone)
    val end = begin.plusHours(1)
    // The hour may reach past midnight on a short day; such a slot ends at the day's last minute.
    val finish = if (end.toLocalDate() == date) end else date.atTime(LocalTime.of(23, 59)).atZone(zone)
    return NewEventDraft(calendar.id, date, begin, finish)
}

/** Default start of a new event slot. */
private val DEFAULT_EVENT_START: LocalTime = LocalTime.of(9, 0)
