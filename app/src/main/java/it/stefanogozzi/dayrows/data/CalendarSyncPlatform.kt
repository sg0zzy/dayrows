// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.data

import android.accounts.Account
import android.content.ContentResolver
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars as C

/** Read source accounts without application selection/access-level filters or cached metadata.
 * Call on an IO dispatcher. Reading calendars does not grant AccountManager visibility. */
internal fun readCalendarSyncAccounts(resolver: ContentResolver): List<SyncAccount> {
    val cursor = resolver.query(C.CONTENT_URI,
        arrayOf(C.ACCOUNT_NAME, C.ACCOUNT_TYPE), null, null, "${C._ID} ASC")
        ?: error("Calendar account query returned no cursor")
    return cursor.use {
        val nameColumn = it.getColumnIndexOrThrow(C.ACCOUNT_NAME)
        val typeColumn = it.getColumnIndexOrThrow(C.ACCOUNT_TYPE)
        buildList {
            while (it.moveToNext()) {
                val name = it.getString(nameColumn)?.takeIf(String::isNotBlank)
                val type = it.getString(typeColumn)?.takeIf(String::isNotBlank)
                if (name != null && type != null) add(name to type)
            }
        }.distinct()
    }
}

internal fun calendarSyncExtras(manual: Boolean) = Bundle().apply {
    if (manual) putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
}

/** The standard public request shared by normal Reload and debug diagnostics. */
internal fun submitCalendarSync(account: Account, extras: Bundle) {
    ContentResolver.requestSync(account, CalendarContract.AUTHORITY, extras)
}
