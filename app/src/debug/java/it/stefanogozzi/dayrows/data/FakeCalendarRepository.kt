// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.data

import kotlinx.coroutines.flow.MutableSharedFlow
import it.stefanogozzi.dayrows.domain.instanceQueryBounds
import java.time.*

/** Debug/test fixture only. MainActivity always constructs AndroidCalendarRepository. */
class FakeCalendarRepository(
    var sources: List<DeviceCalendar> = emptyList(),
    var events: List<EventInstance> = emptyList(),
) : CalendarRepository {
    override val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override suspend fun calendars() = sources
    var syncRequests = 0
    override suspend fun requestSync(manual: Boolean): CalendarSyncResult {
        syncRequests++
        return CalendarSyncResult.FINISHED
    }
    override suspend fun instances(ids: Set<Long>, start: LocalDate, endExclusive: LocalDate, zone: ZoneId): List<EventInstance> {
        val (from, to) = instanceQueryBounds(start, endExclusive, zone)
        return events.filter { it.calendarId in ids && it.start < to && it.end >= from }
    }
    fun notifyChanged() { changes.tryEmit(Unit) }
}
