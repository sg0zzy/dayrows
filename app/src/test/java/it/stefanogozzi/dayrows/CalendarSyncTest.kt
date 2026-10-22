// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows

import it.stefanogozzi.dayrows.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarSyncTest {
    private val first = "first@example.test" to "com.google"
    private val second = "second@example.test" to "com.google"
    @Test fun hiddenAccountOffersRecoveryEvenWhenAdapterDiscoveryIsEmpty() {
        for (adapterVisible in listOf(false, true)) {
            try {
                calendarSyncAccountAccessible(false, adapterVisible)
                fail("Account visibility must be recovered before adapter eligibility")
            } catch (_: CalendarAccountAccessRequired) { }
        }
        assertFalse(calendarSyncAccountAccessible(true, false))
        assertTrue(calendarSyncAccountAccessible(true, true))
    }
    private class Access : CalendarSyncAccess {
        val requests = mutableListOf<Pair<String, String>>()
        var enabled: (SyncAccount) -> Boolean = { true }
        var activity: (SyncAccount) -> SyncActivity = { SyncActivity(false, false) }
        override fun enabled(account: SyncAccount) = enabled.invoke(account)
        override fun activity(account: SyncAccount) = activity.invoke(account)
        override fun request(account: SyncAccount, manual: Boolean) { requests += account }
    }

    @Test fun waitsForEveryAccountAndDeduplicatesSharedCalendars() = runTest {
        val access = Access()
        access.activity = { account ->
            val end = if (account == first) 1_000 else 2_000
            SyncActivity(testScheduler.currentTime in 500 until end, false)
        }
        val log = mutableListOf<String>()
        val sync = CalendarSync(access, { testScheduler.currentTime }, log::add)
        val result = async { sync.run(listOf(first, second, first), true) }
        runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(1_500)
        assertFalse(result.isCompleted)
        assertEquals(CalendarSyncResult.FINISHED, result.await())
        assertEquals(listOf(first, second), access.requests)
        assertEquals(2, log.count { "started" in it })
        assertEquals(2, log.count { "completed activity" in it })
        assertTrue(log.none { "@" in it })
    }

    @Test fun idleOrOfflineIsNotMistakenForCompletionAndDoesNotRetry() = runTest {
        val access = Access()
        val sync = CalendarSync(access, { testScheduler.currentTime })
        assertEquals(CalendarSyncResult.TIMED_OUT, sync.run(listOf(first), true))
        assertEquals(30_000L, testScheduler.currentTime)
        assertEquals(listOf(first), access.requests)
    }

    @Test fun disabledSyncDoesNotRequestOrWait() = runTest {
        val access = Access().apply { enabled = { false } }
        val sync = CalendarSync(access, { testScheduler.currentTime })
        assertEquals(CalendarSyncResult.UNAVAILABLE, sync.run(listOf(first), true))
        assertTrue(access.requests.isEmpty())
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun restrictedAccountDoesNotPreventOtherAccountsFromSyncing() = runTest {
        val access = Access().apply {
            enabled = { if (it == first) throw SecurityException("private account") else true }
            activity = { SyncActivity(testScheduler.currentTime in 500..999, false) }
        }
        val sync = CalendarSync(access, { testScheduler.currentTime })
        assertEquals(CalendarSyncResult.UNAVAILABLE, sync.run(listOf(first, second), true))
        assertEquals(listOf(second), access.requests)
    }

    @Test fun unavailableStatsStillRequestsAndUsesTimeout() = runTest {
        val access = Access().apply { activity = { throw SecurityException() } }
        val sync = CalendarSync(access, { testScheduler.currentTime })
        assertEquals(CalendarSyncResult.TIMED_OUT, sync.run(listOf(first), true))
        assertEquals(listOf(first), access.requests)
    }

    @Test fun missingAccountVisibilityOffersRecoveryAndAllowsImmediateRetryAfterGrant() = runTest {
        val access = Access().apply { enabled = { throw CalendarAccountAccessRequired() } }
        val sync = CalendarSync(access, { testScheduler.currentTime })
        assertEquals(CalendarSyncResult.ACCESS_REQUIRED, sync.run(listOf(first), true))
        assertTrue(access.requests.isEmpty())
        access.enabled = { true }
        access.activity = { SyncActivity(testScheduler.currentTime in 500..999, false) }
        assertEquals(CalendarSyncResult.FINISHED, sync.run(listOf(first), true))
        assertEquals(listOf(first), access.requests)
    }

    @Test fun missingVisibilityDoesNotPreventVisibleAccountsFromSyncing() = runTest {
        val access = Access().apply {
            enabled = { if (it == first) throw CalendarAccountAccessRequired() else true }
            activity = { SyncActivity(testScheduler.currentTime in 500..999, false) }
        }
        val sync = CalendarSync(access, { testScheduler.currentTime })
        assertEquals(CalendarSyncResult.ACCESS_REQUIRED, sync.run(listOf(first, second), true))
        assertEquals(listOf(second), access.requests)
    }

    @Test fun pendingSyncIsJoinedWithoutSubmittingAnotherRequest() = runTest {
        val access = Access().apply {
            activity = { SyncActivity(testScheduler.currentTime in 500..999, testScheduler.currentTime < 500) }
        }
        val sync = CalendarSync(access, { testScheduler.currentTime })
        assertEquals(CalendarSyncResult.FINISHED, sync.run(listOf(first), true))
        assertTrue(access.requests.isEmpty())
    }

    @Test fun overlappingCallsAreNotQueuedAndStartupIsThrottled() = runTest {
        val access = Access().apply {
            activity = { SyncActivity(testScheduler.currentTime % 10_000 in 500..999, false) }
        }
        val sync = CalendarSync(access, { testScheduler.currentTime })
        val initial = async { sync.run(listOf(first), false) }
        runCurrent()
        assertEquals(CalendarSyncResult.THROTTLED, sync.run(listOf(first), true))
        assertEquals(CalendarSyncResult.FINISHED, initial.await())
        assertEquals(CalendarSyncResult.THROTTLED, sync.run(listOf(first), false))
        assertEquals(CalendarSyncResult.THROTTLED, sync.run(listOf(first), true))
        advanceTimeBy(9_000)
        assertEquals(CalendarSyncResult.FINISHED, sync.run(listOf(first), true))
        assertEquals(2, access.requests.size)
    }

    @Test fun cancellationReleasesWaitWithoutCancellingAccountSync() = runTest {
        val access = Access()
        val sync = CalendarSync(access, { testScheduler.currentTime })
        val initial = launch { sync.run(listOf(first), true) }
        runCurrent()
        initial.cancelAndJoin()
        advanceTimeBy(10_000)
        assertEquals(CalendarSyncResult.TIMED_OUT, sync.run(listOf(first), true))
        assertEquals(2, access.requests.size)
    }
}
