// SPDX-License-Identifier: GPL-3.0-or-later
package org.dayrows.app

import org.dayrows.app.data.*
import org.dayrows.app.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.Locale
import kotlinx.coroutines.test.runTest

class CalendarMatrixBuilderTest {
    private val rome = ZoneId.of("Europe/Rome")
    private val date = LocalDate.of(2026, 10, 16)
    private fun calendar(id: Long = 1, key: String = "source-$id", name: String = "Original") =
        DeviceCalendar(id, key, name, "person@example.test", "generic", "owner", "source", 0xff315d76.toInt(), 200, true, true)
    private fun event(id: Long = 1, calendar: Long = 1, from: String = "2026-10-16T09:00:00+02:00", to: String = "2026-10-16T10:00:00+02:00", allDay: Boolean = false, title: String? = "Event") =
        EventInstance(id, calendar, title, OffsetDateTime.parse(from).toInstant(), OffsetDateTime.parse(to).toInstant(), allDay)
    private fun matrix(events: List<EventInstance>, count: Int = 2, start: LocalDate = date, zone: ZoneId = rome,
                       calendars: List<DeviceCalendar> = listOf(calendar()), prefs: UserPreferences = UserPreferences(calendars.map { CalendarPreference(it.key, true) })) =
        CalendarMatrixBuilder().build(calendars, events, visibleDates(start, count), prefs, zone)

    @Test fun singleEventInOneCell() {
        val cells = matrix(listOf(event())).rows.single().cells
        assertEquals(listOf(1L), cells[0].map { it.instance.eventId }); assertTrue(cells[1].isEmpty())
    }
    @Test fun chronologicalWithDeterministicTies() {
        val events = listOf(event(9), event(2), event(1, from = "2026-10-16T08:00:00+02:00"))
        assertEquals(listOf(1L, 2L, 9L), matrix(events).rows[0].cells[0].map { it.instance.eventId })
    }
    @Test fun calendarsRemainSeparate() {
        val rows = matrix(listOf(event(), event(2, 2)), calendars = listOf(calendar(), calendar(2))).rows
        assertEquals(listOf(1L), rows[0].cells[0].map { it.instance.eventId })
        assertEquals(listOf(2L), rows[1].cells[0].map { it.instance.eventId })
    }
    @Test fun allDayFirstAndUtcDateIndependentOfDeviceZone() {
        val allDay = event(2, from = "2026-10-16T00:00:00Z", to = "2026-10-17T00:00:00Z", allDay = true)
        val cells = matrix(listOf(event(), allDay), zone = ZoneId.of("America/Los_Angeles")).rows[0].cells
        assertTrue(cells[0].first().instance.allDay); assertTrue(cells[1].isEmpty())
        val previous = matrix(listOf(allDay), start = date.minusDays(1), zone = ZoneId.of("Pacific/Kiritimati")).rows[0].cells
        assertTrue(previous[0].isEmpty()); assertEquals(1, previous[1].size)
    }
    @Test fun multiDayAllDayHasExclusiveEnd() {
        val e = event(from = "2026-10-16T00:00:00Z", to = "2026-10-19T00:00:00Z", allDay = true)
        assertEquals(listOf(1, 1, 1, 0, 0, 0, 0), matrix(listOf(e), 7).rows[0].cells.map { it.size })
    }
    @Test fun recurringInstancesKeepOccurrencesAndRemoveDuplicateRows() {
        val e = event(); val next = e.copy(start = e.start.plusSeconds(86400), end = e.end.plusSeconds(86400))
        assertEquals(listOf(1, 1), matrix(listOf(e, e, next)).rows[0].cells.map { it.size })
    }
    @Test fun spanningMidnightIsClippedInBothCells() {
        val e = event(from = "2026-10-16T22:00:00+02:00", to = "2026-10-17T02:00:00+02:00")
        val cells = matrix(listOf(e)).rows[0].cells
        assertEquals(Instant.parse("2026-10-16T22:00:00Z"), cells[0][0].clippedEnd)
        assertTrue(cells[0][0].continuesAfter); assertTrue(cells[1][0].continuesBefore)
        assertEquals("↤–02:00", cells[1][0].timeLabel(rome, Locale.ITALY))
    }
    @Test fun multiDayTimedEventFillsEachIntersectingDay() {
        val e = event(from = "2026-10-15T22:00:00+02:00", to = "2026-10-19T10:00:00+02:00")
        val cells = matrix(listOf(e), 7).rows[0].cells
        assertEquals(listOf(1, 1, 1, 1, 0, 0, 0), cells.map { it.size })
        assertEquals("↤–↦", cells[1][0].timeLabel(rome, Locale.ITALY))
    }
    @Test fun midnightEndDoesNotLeakIntoNextDate() {
        val e = event(to = "2026-10-17T00:00:00+02:00")
        val cells = matrix(listOf(e)).rows[0].cells
        assertEquals(1, cells[0].size); assertTrue(cells[1].isEmpty())
        assertEquals("09:00–24:00", cells[0][0].timeLabel(rome, Locale.ITALY))
    }
    @Test fun springDstDayHas23Hours() {
        val day = LocalDate.of(2026, 3, 29)
        val e = event(from = "2026-03-28T23:00:00+01:00", to = "2026-03-30T01:00:00+02:00")
        val cell = matrix(listOf(e), start = day).rows[0].cells[0][0]
        assertEquals(23, Duration.between(cell.clippedStart, cell.clippedEnd).toHours())
    }
    @Test fun autumnDstDayHas25Hours() {
        val e = event(from = "2026-10-24T23:00:00+02:00", to = "2026-10-26T01:00:00+01:00")
        val cell = matrix(listOf(e), start = LocalDate.of(2026, 10, 25)).rows[0].cells[0][0]
        assertEquals(25, Duration.between(cell.clippedStart, cell.clippedEnd).toHours())
    }
    @Test fun continuedEventsSortByActualStart() {
        val older = event(8, from = "2026-10-15T23:00:00+02:00", to = "2026-10-16T13:00:00+02:00")
        assertEquals(listOf(8L, 1L), matrix(listOf(event(), older)).rows[0].cells[0].map { it.instance.eventId })
    }
    @Test fun datesCrossYearsAndLeapMonths() {
        assertEquals(LocalDate.of(2027, 1, 1), visibleDates(LocalDate.of(2026, 12, 31), 2).last())
        assertEquals(LocalDate.of(2028, 3, 1), visibleDates(LocalDate.of(2028, 2, 24), 7).last())
    }
    @Test fun emptyCalendarStillHasRow() { assertEquals(listOf(0, 0), matrix(emptyList()).rows.single().cells.map { it.size }) }
    @Test fun noSelectedCalendarsHasNoRows() {
        assertTrue(matrix(listOf(event()), prefs = UserPreferences()).rows.isEmpty())
    }
    @Test fun missingAndBlankTitlesUseLocalizedFallback() {
        assertEquals("Untitled", event(title = null).displayTitle("Untitled"))
        assertEquals("Senza titolo", event(title = "  ").displayTitle("Senza titolo"))
        assertEquals("Dentist", event(title = " Dentist ").displayTitle("Untitled"))
    }
    @Test fun customLabelFallbackAndSourceRename() {
        val prefs = UserPreferences(listOf(CalendarPreference("source-1", true, "   ")))
        assertEquals("Original", prefs.label(calendar()))
        val custom = prefs.copy(calendars = listOf(CalendarPreference("source-1", true, " Ani ")))
        assertEquals("Ani", custom.label(calendar(name = "Renamed source")))
    }
    @Test fun explicitOrderSurvivesDiscoveryAndNewCalendarsAppend() {
        val a = calendar(1); val b = calendar(2); val c = calendar(3)
        val prefs = UserPreferences(listOf(CalendarPreference(b.key, true), CalendarPreference(a.key, true))).discover(listOf(a, b, c))
        assertEquals(listOf(2L, 1L, 3L), prefs.ordered(listOf(c, a, b)).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), prefs.move(a.key, b.key).ordered(listOf(c, a, b)).map { it.id })
    }
    @Test fun disappearedCalendarPreferencesReturnByKeyWithChangedId() {
        val old = calendar(); val prefs = UserPreferences(listOf(CalendarPreference(old.key, true, "Ani")))
        val missing = prefs.discover(emptyList()); val returned = old.copy(id = 99)
        assertEquals("Ani", missing.discover(listOf(returned)).label(returned))
        assertTrue(missing.preference(returned).selected)
    }
    @Test fun identityIgnoresLocalIdButSeparatesAccountAndSource() {
        val first = calendarIdentity("a", "type", "owner", "name", "remote-1")
        assertEquals(first, calendarIdentity("a", "type", "owner", "renamed", "remote-1"))
        assertNotEquals(first, calendarIdentity("b", "type", "owner", "name", "remote-1"))
        assertNotEquals(first, calendarIdentity("a", "type", "owner", "name", "remote-2"))
        assertNull(calendarIdentity(null, "type", "owner", "name", null))
        assertNull(calendarIdentity("a", "type", "owner", null, null))
    }
    @Test fun reusedNumericIdDoesNotMatchUnrelatedPreference() {
        val prefs = UserPreferences(listOf(CalendarPreference("old-source", true, "Private")))
        val unrelated = calendar(id = 1, key = "new-source")
        assertFalse(prefs.preference(unrelated).selected); assertEquals("Original", prefs.label(unrelated))
    }
    @Test fun lengthPrefixedIdentityCannotCollideOnDelimiters() {
        assertNotEquals(calendarIdentity("a:b", "c", null, "d", null), calendarIdentity("a", "b:c", null, "d", null))
    }
    @Test fun queryBoundsCoverAllDayAndLocalDatesAtBothOffsets() {
        for (id in listOf("Pacific/Kiritimati", "America/Los_Angeles")) {
            val zone = ZoneId.of(id); val (from, to) = instanceQueryBounds(date, date.plusDays(2), zone)
            assertTrue(from <= date.atStartOfDay(zone).toInstant())
            assertTrue(from <= date.atStartOfDay(ZoneOffset.UTC).toInstant())
            assertTrue(to >= date.plusDays(2).atStartOfDay(zone).toInstant())
            assertTrue(to >= date.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant())
            assertTrue(Duration.between(from, to).toHours() < 72)
        }
    }
    @Test fun zeroDurationPointAppearsOnlyOnStartDay() {
        assertEquals(listOf(1, 0), matrix(listOf(event(to = "2026-10-16T09:00:00+02:00"))).rows[0].cells.map { it.size })
    }
    @Test fun invalidNegativeDurationIsIgnored() {
        assertTrue(matrix(listOf(event(to = "2026-10-16T08:00:00+02:00"))).rows[0].cells.all { it.isEmpty() })
    }
    @Test fun fakeRepositoryFiltersCalendarAndVisibleRange() = runTest {
        val fake = FakeCalendarRepository(listOf(calendar()), listOf(event(), event(2, 2), event(3, from = "2025-01-01T09:00:00Z", to = "2025-01-01T10:00:00Z")))
        assertEquals(listOf(1L), fake.instances(setOf(1), date, date.plusDays(2), rome).map { it.eventId })
        assertEquals(1, fake.calendars().size)
    }
    @Test fun ambiguousSourcesHaveSeparateSessionPreferences() {
        val resolver = CalendarIdentityResolver()
        val duplicates = listOf(calendar(1, "same"), calendar(2, "same"))
        val resolved = resolver.resolve(duplicates)
        assertNotEquals(resolved[0].key, resolved[1].key)
        assertTrue(resolved.none { it.reliableIdentity })
        assertEquals(resolved.map { it.key }, resolver.resolve(duplicates).map { it.key })
    }
    @Test fun temporaryIdentityExpiresOnDisappearanceOrSourceChange() {
        val resolver = CalendarIdentityResolver()
        val unknown = calendar().copy(key = "", reliableIdentity = false)
        val original = resolver.resolve(listOf(unknown)).single().key
        resolver.resolve(emptyList())
        val returned = resolver.resolve(listOf(unknown)).single().key
        assertNotEquals(original, returned)
        val reused = resolver.resolve(listOf(unknown.copy(displayName = "Unrelated"))).single().key
        assertNotEquals(returned, reused)
    }

}
