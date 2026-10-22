// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.domain

/** How much work a refresh button performs.
 *
 * [LIGHT] re-reads the local Calendar Provider only. It is the first-release
 * behaviour: no sync request, no account access prompt, no network.
 * [HARD] additionally asks the owning account apps to synchronize and may ask the
 * user to grant account access first. */
enum class RefreshMode { LIGHT, HARD }

/** A provider account that DayRows can try to synchronize, and whether the user
 * already granted DayRows visibility for it. */
data class SyncAccountChoice(
    val name: String,
    val type: String,
    val visible: Boolean,
) {
    /** Display name for the account. Google accounts are addresses; other types are opaque. */
    val display: String get() = name
}
