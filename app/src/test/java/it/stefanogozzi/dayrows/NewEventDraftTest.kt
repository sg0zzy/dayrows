// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows

import it.stefanogozzi.dayrows.data.*
import it.stefanogozzi.dayrows.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class NewEventDraftTest {
    private val rome = ZoneId.of("Europe/Rome")
    private val date = LocalDate.of(2026, 10, 16)
    private fun calendar(id: Long = 1, access: Int = 700) =
        DeviceCalendar(id, "source-$id", "Original", "person@example.test", "generic", "owner", "source",
            0xff315d76.toInt(), access, true, true)

    @Test fun contributorAndAboveCanReceiveEvents() {
        assertTrue(calendar(access = 500).isEditable())
        assertTrue(calendar(access = 600).isEditable())
        assertTrue(calendar(access = 700).isEditable())
        assertTrue(calendar(access = 800).isEditable())
    }

    @Test fun readOnlyAndFreeBusyCalendarsCannotReceiveEvents() {
        assertFalse(calendar(access = 0).isEditable())
        assertFalse(calendar(access = 100).isEditable())
        assertFalse(calendar(access = 200).isEditable())
        assertFalse(calendar(access = 300).isEditable())
    }

    @Test fun draftCarriesTheCalendarAndTheRequestedDay() {
        val draft = newEventDraft(calendar(id = 42), date, rome)
        assertEquals(42, draft.calendarId)
        assertEquals(date, draft.date)
    }

    @Test fun draftCoversOneHourInTheDeviceZone() {
        val draft = newEventDraft(calendar(), date, rome)
        assertEquals(LocalTime.of(9, 0), draft.start.toLocalTime())
        assertEquals(LocalTime.of(10, 0), draft.end.toLocalTime())
        assertEquals(60 * 60 * 1000L, draft.endMillis - draft.startMillis)
    }

    @Test fun draftMillisMatchTheLocalWallClock() {
        val draft = newEventDraft(calendar(), date, rome)
        assertEquals(date.atTime(9, 0).atZone(rome).toInstant().toEpochMilli(), draft.startMillis)
        assertEquals(date.atTime(10, 0).atZone(rome).toInstant().toEpochMilli(), draft.endMillis)
    }

    @Test fun draftMillisAreConsistentWithItsOwnResolvedStart() {
        // The epoch values must be the very instants the draft carries, not a re-resolution
        // of the wall-clock time that a DST change could shift a second time.
        val draft = newEventDraft(calendar(), date, rome)
        assertEquals(draft.start.toInstant().toEpochMilli(), draft.startMillis)
        assertEquals(draft.end.toInstant().toEpochMilli(), draft.endMillis)
    }

    @Test fun draftStaysOnTheRequestedDayAcrossDSTChanges() {
        // Rome skips 02:00 on this date; a 09:00 slot is unaffected, but the day boundary must hold.
        val dst = LocalDate.of(2026, 3, 29)
        val draft = newEventDraft(calendar(), dst, rome)
        assertEquals(dst, Instant.ofEpochMilli(draft.startMillis).atZone(rome).toLocalDate())
        assertEquals(dst, Instant.ofEpochMilli(draft.endMillis).atZone(rome).toLocalDate())
        assertTrue(draft.endMillis > draft.startMillis)
    }

    @Test fun draftOnASpringForwardDayStillHasAForwardInterval() {
        // Europe/Rome loses an hour on 2026-03-29; the produced slot must not end before it starts.
        val dst = LocalDate.of(2026, 3, 29)
        val newYork = ZoneId.of("America/New_York")
        val draft = newEventDraft(calendar(), dst, newYork)
        assertTrue(draft.endMillis > draft.startMillis)
        assertEquals(dst, draft.date)
    }

    @Test fun draftRespectsTheGivenZone() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val draft = newEventDraft(calendar(), date, tokyo)
        assertEquals(date.atTime(9, 0).atZone(tokyo).toInstant().toEpochMilli(), draft.startMillis)
    }
}
