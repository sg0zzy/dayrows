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

/** An account the user already authorized for DayRows.
 *
 * The platform chooser records the grant in AccountManagerService, keyed by the
 * account's name and type, and the grant survives restarts. DayRows keeps its own
 * copy of the same fact so a source that was authorized is never asked for a second
 * time, even when the account type has no authenticator on the device and the
 * account therefore never appears in AccountManager's filtered account list. */
data class AccountAuthorization(
    val name: String,
    val type: String,
)

/** The accounts that still need the platform chooser.
 *
 * An account needs the chooser only when it is not already visible *and* the user
 * never authorized it. The second condition is what stops DayRows from asking
 * again for a source the user already granted: a granted account is recorded by
 * AccountManagerService, but an account type without an authenticator on the device
 * never shows up in the filtered account list, so visibility alone would ask again
 * forever. */
fun List<SyncAccountChoice>.needingAccountAccess(authorized: Set<AccountAuthorization>): List<SyncAccountChoice> =
    filter { !it.visible && AccountAuthorization(it.name, it.type) !in authorized }

/** The accounts the platform chooser authorized in the result that just came back.
 *
 * The chooser is single-select and is opened for one account at a time, so a completed
 * result authorizes exactly the account it was opened for: [prompted] is that account,
 * and nothing is matched by display name, which two accounts of different types can
 * share. A cancelled result — the user backing out, or the account type having no
 * authenticator to show — authorizes nothing. Recording a grant there would drop the
 * source from every later prompt while its calendars stay unavailable. */
fun chooserGrant(accepted: Boolean, prompted: SyncAccountChoice?): List<SyncAccountChoice> =
    if (accepted && prompted != null) listOf(prompted) else emptyList()
