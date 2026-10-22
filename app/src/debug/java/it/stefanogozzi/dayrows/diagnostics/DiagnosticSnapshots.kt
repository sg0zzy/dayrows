// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.diagnostics

import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Raw provider values: keep null distinct from false/zero and apply no application preferences. */
internal data class ProviderCalendar(
    val id: Long,
    val accountName: String?,
    val accountType: String?,
    val displayName: String?,
    val color: Int?,
    val visible: Int?,
    val syncEvents: Int?,
    val accessLevel: Int?,
    val deleted: Int?,
)

internal fun colorHex(color: Int?): String = color?.let { String.format(Locale.ROOT, "#%08X", it) } ?: "null"

internal data class ProviderSnapshot(
    val sequence: Long,
    val at: Instant,
    val calendars: List<ProviderCalendar>,
    /** DayRows state captured at the start of the raw read, not another provider query. */
    val appColors: Map<Long, Int>,
)

internal enum class ChangeKind { ADDED, REMOVED, MODIFIED }
internal data class CalendarChange(
    val id: Long, val kind: ChangeKind, val before: ProviderCalendar?, val after: ProviderCalendar?,
    val fields: List<String>,
)

internal fun compareSnapshots(before: ProviderSnapshot, after: ProviderSnapshot): List<CalendarChange> {
    val old = before.calendars.associateBy { it.id }
    val fresh = after.calendars.associateBy { it.id }
    return (old.keys + fresh.keys).sorted().mapNotNull { id ->
        val a = old[id]; val b = fresh[id]
        when {
            a == null -> CalendarChange(id, ChangeKind.ADDED, null, b, emptyList())
            b == null -> CalendarChange(id, ChangeKind.REMOVED, a, null, emptyList())
            a == b -> null
            else -> CalendarChange(id, ChangeKind.MODIFIED, a, b, buildList {
                if (a.accountName != b.accountName) add("accountName")
                if (a.accountType != b.accountType) add("accountType")
                if (a.displayName != b.displayName) add("displayName")
                if (a.color != b.color) add("color")
                if (a.visible != b.visible) add("visible")
                if (a.syncEvents != b.syncEvents) add("syncEvents")
                if (a.accessLevel != b.accessLevel) add("accessLevel")
                if (a.deleted != b.deleted) add("deleted")
            })
        }
    }
}

internal data class DiagnosticEvent(val at: Instant, val message: String)

/** Messages must be static text, numeric/boolean status or exception class names. Never pass raw
 * provider values, exception messages, intents, account names or event contents to this journal. */
internal class DiagnosticJournal(private val limit: Int = 200, private val now: () -> Instant = Instant::now,
                                 private val sink: (DiagnosticEvent) -> Unit = {}) {
    private val entries = ArrayDeque<DiagnosticEvent>()
    private val _stream = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val stream = _stream.asStateFlow()
    init { require(limit > 0) }
    @Synchronized fun record(message: String) {
        if (entries.size == limit) entries.removeFirst()
        val event = DiagnosticEvent(now(), message.take(300))
        entries.addLast(event)
        _stream.value = entries.toList()
        sink(event)
    }
    fun failure(stage: String, error: Exception) = record("$stage: ${error.javaClass.simpleName}")
    @Synchronized fun events(): List<DiagnosticEvent> = entries.toList()
}

/** Allowlist export: names/types/titles/source identities and exception messages are never serialized.
 * Provider row IDs and numeric colors/flags are useful for correlating the retained snapshots. */
internal fun exportDiagnostics(snapshots: List<ProviderSnapshot>, events: List<DiagnosticEvent>,
                               selectedComparison: Pair<ProviderSnapshot, ProviderSnapshot>? = null): String = buildString {
    appendLine("DayRows Developer Diagnostics — redacted")
    appendLine("Local provider data only; adapter activity completion does not prove remote success.")
    appendLine("Account names/types, calendar names and event contents omitted.")
    snapshots.forEach { snapshot ->
        appendLine("Snapshot #${snapshot.sequence} ${snapshot.at}: ${snapshot.calendars.size} calendars")
        snapshot.calendars.forEach { row ->
            appendLine("id=${row.id} color=${colorHex(row.color)} visible=${row.visible} syncEvents=${row.syncEvents} " +
                "access=${row.accessLevel} deleted=${row.deleted} appColor=${colorHex(snapshot.appColors[row.id])}")
        }
    }
    (snapshots.zipWithNext() + listOfNotNull(selectedComparison))
        .distinctBy { it.first.sequence to it.second.sequence }.forEach { (before, after) ->
        appendLine("Compare #${before.sequence} -> #${after.sequence}")
        val changes = compareSnapshots(before, after)
        if (changes.isEmpty()) appendLine("No metadata changes")
        changes.forEach { change ->
            appendLine("id=${change.id} ${change.kind} fields=${change.fields.joinToString(",")}" +
                if ("color" in change.fields) " ${colorHex(change.before?.color)} -> ${colorHex(change.after?.color)}" else "")
        }
    }
    appendLine("Trace (UTC)")
    events.forEach { appendLine("${it.at} ${it.message}") }
}
