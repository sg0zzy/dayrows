// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows

import it.stefanogozzi.dayrows.ui.components.periodSwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Swiping is the only way to change the period in portrait, so its rules must be exact. */
class PeriodSwipeTest {
    private val threshold = 100f

    @Test
    fun `swipe away from the reached edge moves one period`() {
        // Columns already at the right edge: a leftward drag is period travel.
        assertEquals(1, periodSwipeDirection(-threshold, threshold, atStart = false, atEnd = true))
        // Columns already at the left edge: a rightward drag is period travel.
        assertEquals(-1, periodSwipeDirection(threshold, threshold, atStart = true, atEnd = false))
    }

    @Test
    fun `movement below the threshold changes nothing`() {
        assertNull(periodSwipeDirection(-threshold + 1, threshold, atStart = false, atEnd = true))
        assertNull(periodSwipeDirection(threshold - 1, threshold, atStart = true, atEnd = false))
        assertNull(periodSwipeDirection(0f, threshold, atStart = true, atEnd = true))
    }

    @Test
    fun `a drag with scroll left in that direction belongs to the columns`() {
        // Not at the right edge yet, so a leftward drag scrolls the columns forward.
        assertNull(periodSwipeDirection(-500f, threshold, atStart = true, atEnd = false))
        // Not at the left edge, so a rightward drag scrolls the columns back.
        assertNull(periodSwipeDirection(500f, threshold, atStart = false, atEnd = true))
        // When nothing overflows both edges coincide; either direction moves the period.
        assertEquals(-1, periodSwipeDirection(threshold, threshold, atStart = true, atEnd = true))
        assertEquals(1, periodSwipeDirection(-threshold, threshold, atStart = true, atEnd = true))
    }
}
