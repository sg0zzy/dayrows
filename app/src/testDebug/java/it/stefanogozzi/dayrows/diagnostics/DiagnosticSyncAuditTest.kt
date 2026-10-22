// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.diagnostics

import it.stefanogozzi.dayrows.data.CalendarSyncResult
import it.stefanogozzi.dayrows.data.SyncActivity
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DiagnosticSyncAuditTest {
    private val journal = DiagnosticJournal()
    private val audit = DiagnosticSyncAudit("test-1", journal, { 1_000L })
    private fun messages() = journal.events().map { it.message }

    @Test fun surroundsActualCallAndDoesNotReportSchedulingFromNormalReturn() {
        var calls = 0
        audit.call(0, "com.android.calendar") {
            calls++
            assertTrue(messages().last().contains("API_CALL_ENTER"))
            assertTrue(messages().none { "API_CALL_RETURN" in it })
        }
        audit.finish(CalendarSyncResult.TIMED_OUT)
        assertEquals(1, calls)
        assertTrue(messages().any { "API_CALL_RETURN" in it && "UNCONFIRMED" in it })
        assertTrue(messages().any { "DELIVERY_UNCONFIRMED" in it })
        assertTrue(messages().none { "ACTIVE_OBSERVED" in it || "ACTIVITY_ENDED" in it })
    }

    @Test fun thrownCallIsRecordedAndRethrownWithoutPersonalExceptionDetails() {
        val error = SecurityException("permission android.permission.READ_SYNC_SETTINGS account private@example.test", IllegalStateException("private calendar"))
        try {
            audit.call(0, "com.android.calendar") { throw error }
            fail("Must rethrow to coordinator")
        } catch (actual: SecurityException) { assertSame(error, actual) }
        audit.finish(CalendarSyncResult.UNAVAILABLE)
        assertTrue(messages().any { "API_CALL_THROW" in it && "SecurityException" in it })
        assertTrue(messages().any { "cause=java.lang.IllegalStateException" in it })
        assertTrue(messages().any { "apiFailures=1" in it })
        assertTrue(messages().any { "exceptionPermissionMentions=READ_SYNC_SETTINGS" in it })
        assertTrue(messages().none { "API_CALL_RETURN" in it })
        val report = exportDiagnostics(emptyList(), journal.events())
        assertFalse(report.contains("private@example.test"))
        assertFalse(report.contains("private calendar"))
    }

    @Test fun idleWithoutObservedStartIsNotCompletionOrSchedulingEvidence() {
        audit.observe(0, SyncActivity(false, false))
        audit.call(0, "com.android.calendar") {}
        audit.observe(0, SyncActivity(false, false))
        audit.finish(CalendarSyncResult.TIMED_OUT)
        assertTrue(messages().any { "DELIVERY_UNCONFIRMED" in it })
        assertTrue(messages().none { "PENDING_OBSERVED" in it || "ACTIVE_OBSERVED" in it || "ACTIVITY_ENDED" in it })
    }

    @Test fun distinguishesPostCallPendingStartedAndActivityEnded() {
        audit.observe(0, SyncActivity(false, false))
        audit.call(0, "com.android.calendar") {}
        audit.observe(0, SyncActivity(false, true))
        audit.observe(0, SyncActivity(true, false))
        audit.observe(0, SyncActivity(false, false))
        audit.finish(CalendarSyncResult.FINISHED)
        assertEquals(1, messages().count { "PENDING_OBSERVED" in it })
        assertEquals(1, messages().count { "ACTIVE_OBSERVED" in it })
        assertEquals(1, messages().count { "ACTIVITY_ENDED" in it })
        assertTrue(messages().any { "attribution=UNCONFIRMED" in it })
        assertTrue(messages().none { "DELIVERY_UNCONFIRMED" in it })
    }

    @Test fun joiningExistingWorkDoesNotClaimThatANewRequestWasSubmitted() {
        audit.observe(0, SyncActivity(true, false))
        audit.observe(0, SyncActivity(false, false))
        audit.finish(CalendarSyncResult.FINISHED)
        assertTrue(messages().any { "NO_API_CALL" in it })
        assertTrue(messages().any { "apiCalls=0" in it })
        assertTrue(messages().none { "API_CALL_ENTER" in it || "ACTIVITY_ENDED" in it })
    }

    @Test fun refusesWrongAuthorityBeforeInvokingPlatform() {
        var called = false
        try {
            audit.call(0, "wrong.authority") { called = true }
            fail("Must require standard calendar authority")
        } catch (_: IllegalArgumentException) { }
        assertFalse(called)
    }

    @Test fun repeatedUnchangedStatusDoesNotFloodJournal() {
        repeat(100) { audit.observe(0, SyncActivity(false, false)) }
        assertEquals(1, messages().count { "OBSERVED" in it })
    }

    @Test fun journalSinkReceivesSameBoundedSanitizedEntryAndTimestamp() {
        val received = mutableListOf<DiagnosticEvent>()
        val now = Instant.parse("2026-10-22T14:55:00Z")
        val log = DiagnosticJournal(now = { now }, sink = received::add)
        log.record("x".repeat(500))
        assertEquals(now, received.single().at)
        assertEquals(300, received.single().message.length)
        assertEquals(log.events(), received)
    }
}
