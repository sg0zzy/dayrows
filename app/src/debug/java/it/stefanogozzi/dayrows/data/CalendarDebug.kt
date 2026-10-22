// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.data

import android.util.Log
import it.stefanogozzi.dayrows.BuildConfig

private const val TAG = "DayRowsCalendars"

/** Debug-only comparison state; never supplies rows to application queries. */
internal class CalendarMetadataDiagnostics {
    private var previous = emptyMap<Long, DeviceCalendar>()
    @Synchronized fun record(calendars: List<DeviceCalendar>) {
        calendarDebug("calendar discovery: ${calendars.size} provider calendars")
        calendars.forEach { fresh ->
            calendarDebug("id=${fresh.id} color=${fresh.color} accessLevel=${fresh.accessLevel} " +
                "visible=${fresh.visible} syncEvents=${fresh.syncEvents}")
            previous[fresh.id]?.let { old ->
                if (old != fresh) calendarDebug("calendar metadata changed: id=${fresh.id}")
                if (old.color != fresh.color) calendarDebug("provider color changed: id=${fresh.id} ${old.color} -> ${fresh.color}")
            }
        }
        previous = calendars.associateBy { it.id }
    }
}

/** Opt-in, debug-only logs never include names, titles, account names or source identities. */
internal fun calendarDebug(message: String) {
    if (BuildConfig.DEBUG && Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message)
}
