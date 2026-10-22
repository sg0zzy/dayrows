// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.diagnostics

import androidx.compose.runtime.Composable
import it.stefanogozzi.dayrows.data.DeviceCalendar

/** Variant integration point only; release contains no diagnostic UI or actions. */
@Composable
fun DeveloperDiagnosticsEntry(@Suppress("UNUSED_PARAMETER") calendars: List<DeviceCalendar>) = Unit
