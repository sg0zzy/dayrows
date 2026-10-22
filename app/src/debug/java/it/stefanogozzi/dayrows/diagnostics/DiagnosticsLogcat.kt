// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.diagnostics

import android.util.Log
import it.stefanogozzi.dayrows.BuildConfig

/** Only sanitized journal entries. No system log property is needed for an open debug session. */
internal fun diagnosticLogcat(event: DiagnosticEvent) {
    if (BuildConfig.DEBUG) Log.i("DayRowsSync", "${event.at} ${event.message}")
}
