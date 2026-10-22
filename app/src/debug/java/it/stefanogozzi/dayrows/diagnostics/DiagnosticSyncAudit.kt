// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.diagnostics

import it.stefanogozzi.dayrows.data.CalendarSyncResult
import it.stefanogozzi.dayrows.data.SyncActivity

/** Account-scoped evidence, not a scheduler acknowledgement or ownership claim. No account values
 * enter this object: indices refer to the distinct accounts in the before-request snapshot. */
internal class DiagnosticSyncAudit(
    private val runId: String,
    private val journal: DiagnosticJournal,
    private val nowMs: () -> Long,
) {
    private data class Evidence(
        var calls: Int = 0, var returned: Int = 0, var failures: Int = 0,
        var last: SyncActivity? = null, var pending: Boolean = false,
        var active: Boolean = false, var ended: Boolean = false,
    )
    private val accounts = linkedMapOf<Int, Evidence>()
    fun record(message: String) = journal.record("run=$runId $message")
    fun account(index: Int, message: String) = record("account#$index $message")

    fun call(index: Int, authority: String, submit: () -> Unit) {
        require(authority == "com.android.calendar") { "Diagnostic sync must target the standard calendar authority" }
        val evidence = accounts.getOrPut(index) { Evidence() }
        evidence.calls++
        val start = nowMs()
        // Immediately surrounds the actual ContentResolver call, not the button or eligibility test.
        account(index, "API_CALL_ENTER authority=$authority expedited=true manual=false ignoreSettings=false ignoreBackoff=false")
        try {
            submit()
            evidence.returned++
            account(index, "API_CALL_RETURN elapsedMs=${nowMs() - start} scheduling=UNCONFIRMED (void API; not a scheduler acknowledgement)")
        } catch (e: Exception) {
            evidence.failures++
            failure(index, "API_CALL_THROW elapsedMs=${nowMs() - start}", e)
            throw e
        }
    }

    fun observe(index: Int, activity: SyncActivity) {
        val evidence = accounts.getOrPut(index) { Evidence() }
        if (activity != evidence.last) {
            account(index, "OBSERVED active=${activity.active} pending=${activity.pending} " +
                "phase=${if (evidence.calls == 0) "BEFORE_CALL_OR_JOIN" else "AFTER_CALL"}")
        }
        if (evidence.returned > 0) {
            if (activity.pending && !evidence.pending) account(index, "PENDING_OBSERVED schedulingEvidence=account-level; attribution=UNCONFIRMED")
            if (activity.active && !evidence.active) account(index, "ACTIVE_OBSERVED startedEvidence=account-level; attribution=UNCONFIRMED")
            evidence.pending = evidence.pending || activity.pending
            evidence.active = evidence.active || activity.active
            if (evidence.active && !activity.active && !activity.pending && !evidence.ended) {
                evidence.ended = true
                account(index, "ACTIVITY_ENDED result=UNKNOWN (no remote-success or request-ownership proof)")
            }
        }
        evidence.last = activity
    }

    fun failure(index: Int?, stage: String, error: Exception) {
        val prefix = "run=$runId ${index?.let { "account#$it " }.orEmpty()}$stage"
        journal.failure(prefix, error)
        val permissions = listOf("READ_CALENDAR", "READ_SYNC_SETTINGS", "READ_SYNC_STATS",
            "WRITE_SYNC_SETTINGS", "GET_ACCOUNTS", "READ_CONTACTS", "INTERACT_ACROSS_USERS_FULL")
        val mentioned = permissions.filter { "android.permission.$it" in error.message.orEmpty() }
        if (mentioned.isNotEmpty()) record("exceptionPermissionMentions=${mentioned.joinToString(",")}")
        // Stack locations and cause types help locate binder/permission failures without exporting
        // exception messages, which can embed account names or calendar identifiers.
        error.stackTrace.take(4).forEach { frame ->
            journal.record("run=$runId stack ${frame.className}.${frame.methodName}:${frame.lineNumber}")
        }
        var cause = error.cause
        repeat(3) {
            val current = cause ?: return@repeat
            journal.record("run=$runId cause=${current.javaClass.name}")
            cause = current.cause
        }
    }

    fun finish(result: CalendarSyncResult) {
        val values = accounts.values
        record("RUN_RESULT coordinator=$result apiCalls=${values.sumOf { it.calls }} apiReturned=${values.sumOf { it.returned }} apiFailures=${values.sumOf { it.failures }}")
        record("RUN_EVIDENCE postCallPending=${values.count { it.pending }} postCallActive=${values.count { it.active }} activityEnded=${values.count { it.ended }} attribution=account-level-only")
        if (values.sumOf { it.calls } == 0) record("NO_API_CALL: preflight/throttle/empty accounts or existing work joined; no new request submitted")
        else if (values.none { it.pending || it.active }) record("DELIVERY_UNCONFIRMED: no pending/active evidence observed; inspect dumpsys content (including pending jobs), not only history")
    }
}
