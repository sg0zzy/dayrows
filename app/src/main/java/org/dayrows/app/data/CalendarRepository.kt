// SPDX-License-Identifier: GPL-3.0-or-later
package org.dayrows.app.data

import android.content.ContentResolver
import android.content.ContentUris
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars as C
import android.provider.CalendarContract.Instances as I
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.time.*
import org.dayrows.app.domain.instanceQueryBounds

interface CalendarRepository {
    val changes: Flow<Unit>
    suspend fun calendars(): List<DeviceCalendar>
    suspend fun instances(ids: Set<Long>, start: LocalDate, endExclusive: LocalDate, zone: ZoneId): List<EventInstance>
}

/** This class never inserts, updates or deletes provider data. */
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
        val result = mutableListOf<DeviceCalendar>()
        val cursor = resolver.query(C.CONTENT_URI, projection, "${C.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(C.CAL_ACCESS_READ.toString()), "${C._ID} ASC") ?: error("Calendar query returned no cursor")
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
}
