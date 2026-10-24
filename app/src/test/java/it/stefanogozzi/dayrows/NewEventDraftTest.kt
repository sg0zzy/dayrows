// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows

import it.stefanogozzi.dayrows.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class NewEventDraftTest {
    private val rome = ZoneId.of("Europe/Rome")
    private val date = LocalDate.of(2026, 10, 16)

    @Test fun draftCoversOneHourInTheDeviceZone() {
        val draft = newEventDraft(date, rome)
        assertEquals(LocalTime.of(9, 0), draft.start.toLocalTime())
        assertEquals(LocalTime.of(10, 0), draft.end.toLocalTime())
        assertEquals(60 * 60 * 1000L, draft.endMillis - draft.startMillis)
    }

    @Test fun draftMillisMatchTheLocalWallClock() {
        val draft = newEventDraft(date, rome)
        assertEquals(date.atTime(9, 0).atZone(rome).toInstant().toEpochMilli(), draft.startMillis)
        assertEquals(date.atTime(10, 0).atZone(rome).toInstant().toEpochMilli(), draft.endMillis)
    }

    @Test fun draftMillisAreConsistentWithItsOwnResolvedStart() {
        // The epoch values must be the very instants the draft carries, not a re-resolution
        // of the wall-clock time that a DST change could shift a second time.
        val draft = newEventDraft(date, rome)
        assertEquals(draft.start.toInstant().toEpochMilli(), draft.startMillis)
        assertEquals(draft.end.toInstant().toEpochMilli(), draft.endMillis)
    }

    @Test fun draftStaysOnTheRequestedDayAcrossDSTChanges() {
        // Rome skips 02:00 on this date; a 09:00 slot is unaffected, but the day boundary must hold.
        val dst = LocalDate.of(2026, 3, 29)
        val draft = newEventDraft(dst, rome)
        assertEquals(dst, Instant.ofEpochMilli(draft.startMillis).atZone(rome).toLocalDate())
        assertEquals(dst, Instant.ofEpochMilli(draft.endMillis).atZone(rome).toLocalDate())
        assertTrue(draft.endMillis > draft.startMillis)
    }

    @Test fun draftOnASpringForwardDayStillHasAForwardInterval() {
        // Europe/Rome loses an hour on 2026-03-29; the produced slot must not end before it starts.
        val dst = LocalDate.of(2026, 3, 29)
        val newYork = ZoneId.of("America/New_York")
        val draft = newEventDraft(dst, newYork)
        assertTrue(draft.endMillis > draft.startMillis)
        assertEquals(dst, draft.date)
    }

    @Test fun draftRespectsTheGivenZone() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val draft = newEventDraft(date, tokyo)
        assertEquals(date.atTime(9, 0).atZone(tokyo).toInstant().toEpochMilli(), draft.startMillis)
    }
}
