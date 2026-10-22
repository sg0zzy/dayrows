// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.diagnostics

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.Process
import android.os.Build
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars as C
import it.stefanogozzi.dayrows.data.*
import it.stefanogozzi.dayrows.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

internal interface DiagnosticProbe {
    suspend fun read(): List<ProviderCalendar>
    suspend fun sync(calendars: List<ProviderCalendar>): CalendarSyncResult
}

/** Independent raw reads; never calls application repositories, writes preferences or reads events. */
internal class CalendarProviderProbe(context: Context, private val journal: DiagnosticJournal) : DiagnosticProbe {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val manager = AccountManager.get(this.context)
    private val statusErrors = mutableSetOf<SyncAccount>()
    private var targets = emptyList<SyncAccount>()
    private var audit: DiagnosticSyncAudit? = null

    override suspend fun read(): List<ProviderCalendar> = withContext(Dispatchers.IO) {
        val columns = arrayOf(C._ID, C.ACCOUNT_NAME, C.ACCOUNT_TYPE, C.CALENDAR_DISPLAY_NAME,
            C.CALENDAR_COLOR, C.VISIBLE, C.SYNC_EVENTS, C.CALENDAR_ACCESS_LEVEL, C.DELETED)
        // Intentionally no application visibility/access/deleted filters; retain raw nulls.
        val cursor = resolver.query(C.CONTENT_URI, columns, null, null, "${C._ID} ASC")
            ?: error("Provider returned no cursor")
        cursor.use {
            fun string(column: String): String? = it.getColumnIndexOrThrow(column).let { index ->
                if (it.isNull(index)) null else it.getString(index)
            }
            fun int(column: String): Int? = it.getColumnIndexOrThrow(column).let { index ->
                if (it.isNull(index)) null else it.getInt(index)
            }
            buildList {
                while (it.moveToNext()) add(ProviderCalendar(it.getLong(it.getColumnIndexOrThrow(C._ID)),
                    string(C.ACCOUNT_NAME), string(C.ACCOUNT_TYPE), string(C.CALENDAR_DISPLAY_NAME),
                    int(C.CALENDAR_COLOR), int(C.VISIBLE), int(C.SYNC_EVENTS), int(C.CALENDAR_ACCESS_LEVEL), int(C.DELETED)))
            }
        }
    }

    private val coordinator = CalendarSync(object : CalendarSyncAccess {
        override fun enabled(account: SyncAccount): Boolean {
            val index = targets.indexOf(account)
            try {
                val adapters = ContentResolver.getSyncAdapterTypes().filter {
                    it.authority == CalendarContract.AUTHORITY && it.accountType == account.second
                }
                val visible = manager.getAccountsByType(account.second)
                val matches = visible.count { it.name == account.first && it.type == account.second }
                audit?.account(index, "PREFLIGHT adapterMatches=${adapters.size} visibleTypeAccounts=${visible.size} exactVisibleMatches=$matches")
                if (matches == 0) {
                    audit?.account(index, "SKIP_API reason=account_not_visible; READ_CALENDAR does not grant account access")
                    throw CalendarAccountAccessRequired()
                }
                if (!calendarSyncAccountAccessible(matches > 0, adapters.isNotEmpty())) {
                    audit?.account(index, "SKIP_API reason=no_visible_adapter (missing package visibility and absent adapter are indistinguishable here)")
                    return false
                }
                val owner = Account(account.first, account.second)
                val master = ContentResolver.getMasterSyncAutomatically()
                val automatic = ContentResolver.getSyncAutomatically(owner, CalendarContract.AUTHORITY)
                val syncable = ContentResolver.getIsSyncable(owner, CalendarContract.AUTHORITY)
                audit?.account(index, "SETTINGS master=$master automatic=$automatic syncable=$syncable")
                if (!master || !automatic || syncable == 0) audit?.account(index,
                    "SKIP_API reason=sync_settings_or_filtered_access; false/0 alone does not prove user-disabled sync")
                return master && automatic && syncable != 0
            } catch (e: CalendarAccountAccessRequired) { throw e }
            catch (e: Exception) {
                audit?.failure(index, "PREFLIGHT_THROW", e)
                throw e
            }
        }

        override fun activity(account: SyncAccount): SyncActivity {
            try {
                val owner = Account(account.first, account.second)
                val activity = SyncActivity(ContentResolver.isSyncActive(owner, CalendarContract.AUTHORITY),
                    ContentResolver.isSyncPending(owner, CalendarContract.AUTHORITY))
                audit?.observe(targets.indexOf(account), activity)
                return activity
            } catch (e: Exception) {
                if (statusErrors.add(account)) audit?.failure(targets.indexOf(account), "STATUS_THROW observation=UNKNOWN", e)
                throw e
            }
        }

        override fun request(account: SyncAccount, manual: Boolean) {
            val index = targets.indexOf(account)
            val owner = Account(account.first, account.second)
            val authority = CalendarContract.AUTHORITY
            val extras = calendarSyncExtras(manual = true)
            audit?.account(index, "ACCOUNT_OBJECT providerNameMatches=${owner.name == account.first} " +
                "providerTypeMatches=${owner.type == account.second} google=${owner.type == "com.google"}")
            audit?.account(index, "API_ARGUMENTS authority=$authority expedited=${extras.getBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED)} " +
                "manual=${extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL)} ignoreSettings=${extras.getBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS)} " +
                "ignoreBackoff=${extras.getBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF)}")
            requireNotNull(audit).call(index, authority) {
                submitCalendarSync(owner, extras)
            }
        }
    }, SystemClock::elapsedRealtime, { message -> audit?.record("COORDINATOR $message") })

    override suspend fun sync(calendars: List<ProviderCalendar>): CalendarSyncResult = withContext(Dispatchers.IO) {
        statusErrors.clear()
        val accounts = calendars.mapNotNull { row ->
            val name = row.accountName?.takeIf { it.isNotBlank() }
            val type = row.accountType?.takeIf { it.isNotBlank() }
            if (name == null || type == null) null else name to type
        }.distinct()
        targets = accounts
        val trace = DiagnosticSyncAudit("${Process.myPid()}-${attempts.incrementAndGet()}", journal, SystemClock::elapsedRealtime)
        audit = trace
        trace.record("RUN_START package=${context.packageName} uid=${Process.myUid()} pid=${Process.myPid()} sdk=${Build.VERSION.SDK_INT} authority=${CalendarContract.AUTHORITY}")
        trace.record("APP_CONTEXT debug=${BuildConfig.DEBUG} version=${BuildConfig.VERSION_NAME} targetSdk=${context.applicationInfo.targetSdkVersion}")
        fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        trace.record("PERMISSIONS readCalendar=${granted(Manifest.permission.READ_CALENDAR)} readSyncSettings=${granted(Manifest.permission.READ_SYNC_SETTINGS)} " +
            "readSyncStats=${granted(Manifest.permission.READ_SYNC_STATS)} writeSyncSettings=${granted(Manifest.permission.WRITE_SYNC_SETTINGS)}")
        trace.record("TARGETS accounts=${accounts.size} providerRows=${calendars.size}; no account names/types are logged; 30s monitoring bound")
        accounts.forEachIndexed { index, account ->
            val ids = calendars.filter { it.accountName == account.first && it.accountType == account.second }.map { it.id }
            trace.account(index, "TARGET google=${account.second == "com.google"} providerIds=${ids.take(12).joinToString(",")} totalRows=${ids.size} " +
                "nameEdgeWhitespace=${account.first != account.first.trim()} typeEdgeWhitespace=${account.second != account.second.trim()}")
        }
        trace.record("REQUEST_POLICY expedited=true manual=false; expected AOSP source=OTHER, not USER; joins existing pending/active work")
        try {
            coordinator.run(accounts, manual = true).also(trace::finish)
        } catch (e: kotlinx.coroutines.CancellationException) {
            trace.record("RUN_CANCELED monitoring stopped; scheduler delivery remains unconfirmed unless observed")
            throw e
        } finally { audit = null }
    }

    private companion object { val attempts = AtomicLong() }
}
