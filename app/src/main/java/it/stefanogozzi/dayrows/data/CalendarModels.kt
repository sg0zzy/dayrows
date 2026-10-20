// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.data

import java.time.Instant

/** Provider IDs are for active queries only. Preferences use an account-scoped identity. */
data class DeviceCalendar(
    val id: Long,
    val key: String,
    val displayName: String,
    val accountName: String?,
    val accountType: String?,
    val ownerAccount: String?,
    val providerName: String?,
    val color: Int,
    val accessLevel: Int,
    val visible: Boolean,
    val syncEvents: Boolean,
    val reliableIdentity: Boolean = true,
)

data class EventInstance(
    val eventId: Long,
    val calendarId: Long,
    val title: String?,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean,
    val timeZone: String? = null,
)

data class CalendarPreference(val key: String, val selected: Boolean = false, val label: String = "")
data class UserPreferences(
    val calendars: List<CalendarPreference> = emptyList(),
    val dayCount: Int = 2,
    val configured: Boolean = false,
) {
    fun preference(calendar: DeviceCalendar) = calendars.firstOrNull { it.key == calendar.key }
        ?: CalendarPreference(calendar.key)
    fun label(calendar: DeviceCalendar) = preference(calendar).label.trim().ifBlank { calendar.displayName }
    fun discover(discovered: List<DeviceCalendar>): UserPreferences {
        val known = calendars.map { it.key }.toSet()
        return copy(calendars = calendars + discovered.filter { it.key !in known }.map { CalendarPreference(it.key) })
    }
    fun move(key: String, otherKey: String): UserPreferences {
        val result = calendars.toMutableList()
        val from = result.indexOfFirst { it.key == key }
        val to = result.indexOfFirst { it.key == otherKey }
        if (from >= 0 && to >= 0) { val item = result.removeAt(from); result.add(to, item) }
        return copy(calendars = result)
    }
    fun ordered(calendars: List<DeviceCalendar>): List<DeviceCalendar> {
        val positions = this.calendars.mapIndexed { index, value -> value.key to index }.toMap()
        return calendars.sortedWith(compareBy<DeviceCalendar> { positions[it.key] ?: Int.MAX_VALUE }.thenBy { it.id })
    }
}

/** Only source identifiers are used, never a display name or a numeric ID alone. */
fun calendarIdentity(account: String?, type: String?, owner: String?, name: String?, syncId: String?): String? {
    if (account.isNullOrBlank() || type.isNullOrBlank()) return null
    val source = syncId?.takeIf { it.isNotBlank() }?.let { "sync:$it" }
        ?: name?.takeIf { it.isNotBlank() }?.let { "name:$it" } ?: return null
    return listOf(account, type, owner.orEmpty(), source).joinToString("") { "${it.length}:$it" }
}

/** Ambiguous identities are usable only while the same observed source remains in this session. */
class CalendarIdentityResolver {
    private val temporary = mutableMapOf<Long, Pair<List<String?>, String>>()
    @Synchronized
    fun resolve(calendars: List<DeviceCalendar>): List<DeviceCalendar> {
        val duplicateKeys = calendars.filter { it.reliableIdentity }.groupingBy { it.key }.eachCount().filterValues { it > 1 }.keys
        val temporaryIds = mutableSetOf<Long>()
        val result = calendars.map { calendar ->
            if (calendar.reliableIdentity && calendar.key !in duplicateKeys) calendar
            else {
                temporaryIds += calendar.id
                val fingerprint = listOf(calendar.key, calendar.accountName, calendar.accountType,
                    calendar.ownerAccount, calendar.providerName, calendar.displayName, calendar.color.toString(), calendar.accessLevel.toString())
                val previous = temporary[calendar.id]
                val key = if (previous?.first == fingerprint) previous.second else "session:${java.util.UUID.randomUUID()}"
                temporary[calendar.id] = fingerprint to key
                calendar.copy(key = key, reliableIdentity = false)
            }
        }
        temporary.keys.retainAll(temporaryIds)
        return result
    }
}
