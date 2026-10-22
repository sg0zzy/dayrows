// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.data

/** Release builds retain no diagnostic snapshots and emit no calendar diagnostic logs. */
internal class CalendarMetadataDiagnostics {
    fun record(@Suppress("UNUSED_PARAMETER") calendars: List<DeviceCalendar>) = Unit
}
internal fun calendarDebug(@Suppress("UNUSED_PARAMETER") message: String) = Unit
