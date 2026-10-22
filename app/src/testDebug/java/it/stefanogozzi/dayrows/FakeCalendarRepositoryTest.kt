// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows

import it.stefanogozzi.dayrows.data.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.*

/** The fixture is debug-only; keep its existing coverage out of the release test source set. */
class FakeCalendarRepositoryTest {
    @Test fun fakeRepositoryFiltersCalendarAndVisibleRange() = runTest {
        val calendar = DeviceCalendar(1, "source-1", "Original", "person@example.test", "generic",
            "owner", "source", 0xff315d76.toInt(), 200, true, true)
        fun event(id: Long, calendar: Long = 1, from: String = "2026-10-16T09:00:00+02:00", to: String = "2026-10-16T10:00:00+02:00") =
            EventInstance(id, calendar, "Event", OffsetDateTime.parse(from).toInstant(), OffsetDateTime.parse(to).toInstant(), false)
        val fake = FakeCalendarRepository(listOf(calendar), listOf(event(1), event(2, 2),
            event(3, from = "2025-01-01T09:00:00Z", to = "2025-01-01T10:00:00Z")))
        val date = LocalDate.of(2026, 10, 16)
        assertEquals(listOf(1L), fake.instances(setOf(1), date, date.plusDays(2), ZoneId.of("Europe/Rome")).map { it.eventId })
        assertEquals(1, fake.calendars().size)
    }
}
