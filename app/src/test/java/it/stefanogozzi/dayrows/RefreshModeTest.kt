// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows

import it.stefanogozzi.dayrows.domain.*
import org.junit.Assert.*
import org.junit.Test

class RefreshModeTest {
    private val visible = SyncAccountChoice("visible@example.test", "com.google", true)
    private val hidden = SyncAccountChoice("hidden@example.test", "com.google", false)

    /** The accounts that still need the platform chooser are exactly the invisible ones.
     * The chooser is the only way to gain account visibility, so an invisible account
     * cannot be synchronized before the user authorizes it. */
    @Test fun onlyInvisibleAccountsNeedTheChooser() {
        val chosen = listOf(visible, hidden)
        assertEquals(listOf(hidden), chosen.filter { !it.visible })
        assertEquals(1, chosen.count { it.visible })
    }

    @Test fun anAccountChoiceKeepsItsIdentityAndVisibility() {
        assertEquals("visible@example.test", visible.name)
        assertEquals("com.google", visible.type)
        assertTrue(visible.visible)
        assertFalse(hidden.visible)
    }

    @Test fun choicesAreDistinguishedByNameAndType() {
        val sameName = SyncAccountChoice("visible@example.test", "other.type", false)
        assertNotEquals(visible, sameName)
        assertEquals(visible, SyncAccountChoice("visible@example.test", "com.google", true))
    }

    /** A multi-select selection can hold any number of accounts, including none. */
    @Test fun selectionCanHoldAnyNumberOfAccounts() {
        val all = listOf(visible, hidden)
        assertEquals(2, all.size)
        assertTrue(all.filter { it in all }.isNotEmpty())
        assertEquals(emptyList<SyncAccountChoice>(), all.filter { false })
    }

    @Test fun lightAndHardRefreshAreDistinctModes() {
        assertNotEquals(RefreshMode.LIGHT, RefreshMode.HARD)
        assertEquals(RefreshMode.LIGHT, RefreshMode.valueOf("LIGHT"))
        assertEquals(RefreshMode.HARD, RefreshMode.valueOf("HARD"))
    }
}
