// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

internal typealias SyncAccount = Pair<String, String>
internal data class SyncActivity(val active: Boolean, val pending: Boolean)
enum class CalendarSyncResult { FINISHED, TIMED_OUT, UNAVAILABLE, ACCESS_REQUIRED, THROTTLED }
internal class CalendarAccountAccessRequired : Exception()

/** Adapter discovery is visibility-filtered; it must never suppress account-access recovery. */
internal fun calendarSyncAccountAccessible(accountVisible: Boolean, adapterVisible: Boolean): Boolean {
    if (!accountVisible) throw CalendarAccountAccessRequired()
    return adapterVisible
}

/** Small platform boundary so timing, restricted accounts and concurrent calls can be tested. */
internal interface CalendarSyncAccess {
    fun enabled(account: SyncAccount): Boolean
    fun activity(account: SyncAccount): SyncActivity
    fun request(account: SyncAccount, manual: Boolean)
}

/** Observes activity, not remote success: an adapter going idle cannot prove metadata was fetched.
 * Idle without an observed start is deliberately treated as unknown until the bounded timeout.
 * A very fast adapter (or an account hidden by Android) may therefore take the timeout path. */
internal class CalendarSync(
    private val access: CalendarSyncAccess,
    private val now: () -> Long,
    private val log: (String) -> Unit = {},
    private val timeoutMs: Long = 30_000,
) {
    private val lock = Mutex()
    private var lastAttempt: Long? = null

    suspend fun run(accounts: List<SyncAccount>, manual: Boolean): CalendarSyncResult {
        if (!lock.tryLock()) return CalendarSyncResult.THROTTLED
        try {
            val minimumInterval = if (manual) 10_000 else 120_000
            if (lastAttempt?.let { now() - it < minimumInterval } == true) {
                log("sync throttled")
                return CalendarSyncResult.THROTTLED
            }
            lastAttempt = now()
            val waiting = linkedMapOf<SyncAccount, Boolean>()
            var unavailable = false
            var accessRequired = false
            accounts.distinct().forEachIndexed { index, account ->
                try {
                    if (!access.enabled(account)) {
                        unavailable = true
                        log("sync account#$index skipped: disabled or no adapter")
                    } else {
                        // A pending/active operation belongs to the account, not to DayRows. Join it.
                        val before = try { access.activity(account) } catch (_: SecurityException) { null }
                        if (before?.active != true && before?.pending != true) {
                            access.request(account, manual)
                            log("sync account#$index requested")
                        } else log("sync account#$index observing existing operation")
                        waiting[account] = before?.active == true
                        if (before?.active == true) log("sync account#$index started")
                    }
                } catch (_: CalendarAccountAccessRequired) {
                    accessRequired = true
                    log("sync account#$index needs account visibility")
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    unavailable = true
                    // Exception messages can contain account names; never log them.
                    log("sync account#$index unavailable: ${e.javaClass.simpleName}")
                }
            }
            if (waiting.isEmpty()) {
                // No request was submitted: allow an immediate attempt after account selection.
                if (accessRequired) lastAttempt = null
                return if (accessRequired) CalendarSyncResult.ACCESS_REQUIRED else CalendarSyncResult.UNAVAILABLE
            }
            val finished = withTimeoutOrNull(timeoutMs) {
                while (waiting.isNotEmpty()) {
                    waiting.keys.toList().forEach { account ->
                        val index = accounts.indexOf(account)
                        val activity = try { access.activity(account) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { null }
                        if (activity != null) {
                            if (activity.active && waiting[account] != true) {
                                waiting[account] = true
                                log("sync account#$index started")
                            }
                            if (!activity.active && !activity.pending && waiting[account] == true) {
                                waiting.remove(account)
                                log("sync account#$index completed activity (remote success unknown)")
                            }
                        }
                    }
                    if (waiting.isNotEmpty()) delay(500)
                }
                true
            } == true
            if (!finished) log("sync timed out: ${waiting.size} accounts still pending or unobservable")
            return when {
                accessRequired -> CalendarSyncResult.ACCESS_REQUIRED
                !finished -> CalendarSyncResult.TIMED_OUT
                unavailable -> CalendarSyncResult.UNAVAILABLE
                else -> CalendarSyncResult.FINISHED
            }
        } finally { lock.unlock() }
    }
}
