// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.data

import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars as C
import android.provider.CalendarContract.Instances as I
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.time.*
import it.stefanogozzi.dayrows.domain.instanceQueryBounds

interface CalendarRepository {
    val changes: Flow<Unit>
    suspend fun calendars(): List<DeviceCalendar>
    suspend fun instances(ids: Set<Long>, start: LocalDate, endExclusive: LocalDate, zone: ZoneId): List<EventInstance>
    /** Asks the account apps that own the readable calendars to run their existing calendar synchronization
     * now. Advisory: the platform schedules and batches the actual sync and gives no callback; synchronized
     * rows appear later through [changes]. DayRows itself performs no network request. */
    suspend fun requestSync()
}

/** This class never inserts, updates or deletes provider data; [requestSync] only asks the owning account
 * apps to run synchronization they already perform on their own schedule. */
class AndroidCalendarRepository(private val resolver: ContentResolver) : CalendarRepository {
    private val identities = CalendarIdentityResolver()
    override val changes = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        resolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    override suspend fun calendars(): List<DeviceCalendar> = withContext(Dispatchers.IO) {
        val projection = arrayOf(C._ID, C.CALENDAR_DISPLAY_NAME, C.ACCOUNT_NAME, C.ACCOUNT_TYPE,
            C.CALENDAR_COLOR, C.CALENDAR_ACCESS_LEVEL, C.OWNER_ACCOUNT, C.VISIBLE, C.SYNC_EVENTS, C.NAME, C._SYNC_ID)
        // Discover every accessible calendar, including secondary and shared ones already registered in the
        // provider by their account app. VISIBLE=0 and SYNC_EVENTS=0 rows stay included: DayRows selection is a
        // local preference and those flags only describe remote state. Provider-deleted rows are dropped; a null
        // access level stays included, free/busy-only calendars without event access stay excluded.
        val selection = "(${C.DELETED} IS NULL OR ${C.DELETED} <> ?)" +
            " AND (${C.CALENDAR_ACCESS_LEVEL} IS NULL OR ${C.CALENDAR_ACCESS_LEVEL} >= ?)"
        val result = mutableListOf<DeviceCalendar>()
        val cursor = resolver.query(C.CONTENT_URI, projection, selection,
            arrayOf("1", C.CAL_ACCESS_READ.toString()), "${C._ID} ASC") ?: error("Calendar query returned no cursor")
        cursor.use {
            fun value(column: String): String? = it.getColumnIndex(column).takeIf { index -> index >= 0 && !it.isNull(index) }?.let(it::getString)
            while (it.moveToNext()) {
                val id = value(C._ID)?.toLongOrNull() ?: continue
                val account = value(C.ACCOUNT_NAME)
                val type = value(C.ACCOUNT_TYPE)
                val owner = value(C.OWNER_ACCOUNT)
                val name = value(C.NAME)
                val identity = calendarIdentity(account, type, owner, name, value(C._SYNC_ID))
                result += DeviceCalendar(id, identity.orEmpty(), value(C.CALENDAR_DISPLAY_NAME).orEmpty(),
                    account, type, owner, name, value(C.CALENDAR_COLOR)?.toIntOrNull() ?: 0xff607d8b.toInt(),
                    value(C.CALENDAR_ACCESS_LEVEL)?.toIntOrNull() ?: 0, value(C.VISIBLE) == "1",
                    value(C.SYNC_EVENTS) == "1", identity != null)
            }
        }
        logDiscovery(result)
        identities.resolve(result)
    }

    override suspend fun instances(ids: Set<Long>, start: LocalDate, endExclusive: LocalDate, zone: ZoneId): List<EventInstance> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        // Include UTC date boundaries as well as local ones, so all-day events at any offset are included.
        val (begin, end) = instanceQueryBounds(start, endExclusive, zone)
        val uri = I.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin.toEpochMilli()); ContentUris.appendId(it, end.toEpochMilli())
        }.build()
        val projection = arrayOf(I.EVENT_ID, I.CALENDAR_ID, I.TITLE, I.BEGIN, I.END, I.ALL_DAY, I.EVENT_TIMEZONE)
        val result = mutableListOf<EventInstance>()
        val placeholders = ids.joinToString(",") { "?" }
        val cursor = resolver.query(uri, projection, "${I.CALENDAR_ID} IN ($placeholders)", ids.map(Long::toString).toTypedArray(), "${I.BEGIN} ASC")
            ?: error("Instance query returned no cursor")
        cursor.use {
            fun string(column: String): String? = it.getColumnIndex(column).takeIf { index -> index >= 0 && !it.isNull(index) }?.let(it::getString)
            while (it.moveToNext()) {
                val event = string(I.EVENT_ID)?.toLongOrNull() ?: continue
                val calendar = string(I.CALENDAR_ID)?.toLongOrNull() ?: continue
                val from = string(I.BEGIN)?.toLongOrNull() ?: continue
                val to = string(I.END)?.toLongOrNull() ?: continue
                if (calendar in ids) result += EventInstance(event, calendar, string(I.TITLE), Instant.ofEpochMilli(from),
                    Instant.ofEpochMilli(to), string(I.ALL_DAY) == "1", string(I.EVENT_TIMEZONE))
            }
        }
        result
    }

    override suspend fun requestSync() = withContext(Dispatchers.IO) {
        // Reuse the discovery query so only accounts with readable calendars are contacted. The manual flag
        // asks the framework to bypass sync backoff; it is honored because the manifest declares
        // WRITE_SYNC_SETTINGS. Failures are per account: an unsyncable or vanished account must not stop
        // the remaining requests, and none of them can corrupt provider data.
        val extras = Bundle().apply { putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true) }
        var requested = 0
        syncAccounts(calendars()).forEach { (name, type) ->
            try { ContentResolver.requestSync(Account(name, type), CalendarContract.AUTHORITY, extras); requested++ }
            catch (_: Exception) { }
        }
        logSync(requested)
    }
}

private const val DISCOVERY_TAG = "DayRowsCalendars"

/** Debug-only discovery diagnostics, enabled with `adb shell setprop log.tag.DayRowsCalendars DEBUG`.
 * Never logs account names, owner accounts or provider sync IDs. */
private fun logDiscovery(calendars: List<DeviceCalendar>) {
    if (!Log.isLoggable(DISCOVERY_TAG, Log.DEBUG)) return
    Log.d(DISCOVERY_TAG, "calendar discovery: ${calendars.size} provider calendars")
    calendars.forEach {
        Log.d(DISCOVERY_TAG, "id=${it.id} name=${it.displayName} accountType=${it.accountType} " +
            "accessLevel=${it.accessLevel} visible=${it.visible} syncEvents=${it.syncEvents}")
    }
}

/** Debug-only sync-request diagnostics, same enable switch as [logDiscovery]. Never logs account names. */
private fun logSync(requested: Int) {
    if (!Log.isLoggable(DISCOVERY_TAG, Log.DEBUG)) return
    Log.d(DISCOVERY_TAG, "calendar sync requested for $requested accounts")
}
