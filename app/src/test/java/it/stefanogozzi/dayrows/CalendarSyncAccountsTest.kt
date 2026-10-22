// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows

import it.stefanogozzi.dayrows.data.*
import org.junit.Assert.*
import org.junit.Test

class CalendarSyncAccountsTest {
    private fun calendar(id: Long, account: String?, type: String?) =
        DeviceCalendar(id, "key-$id", "Name $id", account, type, null, null, 0, 200, true, true)

    @Test fun distinctAccountsRequestedOnceInFirstSeenOrder() {
        val calendars = listOf(
            calendar(1, "work@example.test", "com.google"),
            calendar(2, "personal@example.test", "com.google"),
            calendar(3, "work@example.test", "com.google"),
            calendar(4, "work@example.test", "other.type"),
        )
        assertEquals(listOf("work@example.test" to "com.google", "personal@example.test" to "com.google",
            "work@example.test" to "other.type"), syncAccounts(calendars))
    }

    @Test fun calendarsWithoutCompleteAccountAreSkipped() {
        val calendars = listOf(calendar(1, null, "local"), calendar(2, "named", null), calendar(3, "a@b.test", "t"),
            calendar(4, "", "com.google"), calendar(5, "named", " "))
        assertEquals(listOf("a@b.test" to "t"), syncAccounts(calendars))
    }

    @Test fun emptyDiscoveryRequestsNothing() { assertEquals(emptyList<Pair<String, String>>(), syncAccounts(emptyList())) }
}
