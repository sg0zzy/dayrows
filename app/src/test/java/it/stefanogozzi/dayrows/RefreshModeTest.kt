// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows

import it.stefanogozzi.dayrows.domain.*
import org.junit.Assert.*
import org.junit.Test

class RefreshModeTest {
    private val visible = SyncAccountChoice("visible@example.test", "com.google", true)
    private val hidden = SyncAccountChoice("hidden@example.test", "com.google", false)
    private val authorized = setOf(AccountAuthorization("hidden@example.test", "com.google"))

    /** The platform chooser is the only way to gain account visibility, so an account
     * needs it only while it is invisible and the user has never authorized it. */
    @Test fun anAccountThatAlreadyHasAccessIsNeverAskedForAgain() {
        assertEquals(emptyList<SyncAccountChoice>(), listOf(visible).needingAccountAccess(emptySet()))
        assertEquals(emptyList<SyncAccountChoice>(), listOf(hidden).needingAccountAccess(authorized))
    }

    /** An account that is invisible and was never authorized has no path to its calendars
     * until the user authorizes it, so it must still be asked for. */
    @Test fun anUnauthorizedInvisibleAccountStillNeedsTheChooser() {
        assertEquals(listOf(hidden), listOf(hidden).needingAccountAccess(emptySet()))
    }

    /** A selection mixes accounts that already have access with accounts that do not: only
     * the latter are asked for, so one authorized source cannot drag the others back into
     * the chooser. */
    @Test fun onlyTheAccountsWithoutAccessAreAskedFor() {
        val other = SyncAccountChoice("other@example.test", "com.google", false)
        val chosen = listOf(visible, hidden, other)
        assertEquals(listOf(other), chosen.needingAccountAccess(authorized))
    }

    /** The grant the platform records is per account *and type*. Authorizing one type must
     * not silently drop the prompt for the same address under a different type, which would
     * hide that source's calendars for good. */
    @Test fun authorizingOneTypeLeavesTheSameAddressUnderAnotherTypeStillAskedFor() {
        val google = SyncAccountChoice("person@example.test", "com.google", false)
        val exchange = SyncAccountChoice("person@example.test", "com.google.android.gm.exchange", false)
        val granted = chooserGrant(accepted = true, prompted = google)
            .map { AccountAuthorization(it.name, it.type) }.toSet()
        assertEquals(listOf(exchange), listOf(google, exchange).needingAccountAccess(granted))
    }

    /** A completed chooser authorizes exactly the account it was opened for, keeping its
     * type, which is what the remembered authorization is keyed on. */
    @Test fun aCompletedChooserAuthorizesTheAccountItWasOpenedFor() {
        assertEquals(listOf(hidden), chooserGrant(accepted = true, prompted = hidden))
    }

    /** The chooser reports cancelled both when the user backs out and when the account type
     * has no authenticator to show. Neither is a grant: recording one would remove the source
     * from every later prompt while its calendars stay unavailable. */
    @Test fun aCancelledChooserAuthorizesNothing() {
        assertEquals(emptyList<SyncAccountChoice>(), chooserGrant(accepted = false, prompted = hidden))
    }

    /** An empty queue has no account to authorize, so a stray result grants nothing. */
    @Test fun aResultWithoutAPromptedAccountAuthorizesNothing() {
        assertEquals(emptyList<SyncAccountChoice>(), chooserGrant(accepted = true, prompted = null))
    }
}
