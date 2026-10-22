// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.diagnostics

import it.stefanogozzi.dayrows.data.CalendarSyncResult
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticSnapshotsTest {
    private val at = Instant.parse("2026-10-22T12:00:00Z")
    private fun row(id: Long = 1) = ProviderCalendar(id, "private@example.test", "private.account.type",
        "Private calendar title", 0xff123456.toInt(), 1, 1, 500, 0)
    private fun snapshot(sequence: Long, vararg rows: ProviderCalendar) =
        ProviderSnapshot(sequence, at, rows.toList(), mapOf(1L to 0xff654321.toInt()))

    @Test fun comparesAddedRemovedColorsAndNullFlags() {
        val changes = compareSnapshots(snapshot(1, row(), row(2)),
            snapshot(2, row().copy(color = 0xffabcdef.toInt(), visible = null), row(3)))
        assertEquals(listOf(ChangeKind.MODIFIED, ChangeKind.REMOVED, ChangeKind.ADDED), changes.map { it.kind })
        assertEquals(listOf("color", "visible"), changes.first().fields)
        assertEquals("#FFABCDEF", colorHex(changes.first().after?.color))
    }

    @Test fun rowOrderingDoesNotProduceFalseChanges() {
        assertTrue(compareSnapshots(snapshot(1, row(), row(2)), snapshot(2, row(2), row())).isEmpty())
        assertEquals("null", colorHex(null))
        assertEquals("#00000000", colorHex(0))
    }

    @Test fun exportsNumericEvidenceWithoutPersonalFieldsOrExceptionMessages() {
        val journal = DiagnosticJournal(now = { at })
        journal.failure("provider read", SecurityException("private@example.test Private calendar title"))
        val report = exportDiagnostics(listOf(snapshot(1, row()), snapshot(2, row().copy(color = 0xff112233.toInt()))), journal.events())
        listOf("private@example.test", "Private calendar title", "private.account.type").forEach { assertFalse(report.contains(it)) }
        assertTrue(report.contains("SecurityException"))
        assertTrue(report.contains("#FF123456 -> #FF112233"))
        assertTrue(report.contains("appColor=#FF654321"))
        assertTrue(report.contains(at.toString()))
    }

    @Test fun journalBoundsEntryCountAndMessageSize() {
        val journal = DiagnosticJournal(limit = 3, now = { at })
        repeat(5) { journal.record("entry$it") }
        assertEquals(listOf("entry2", "entry3", "entry4"), journal.events().map { it.message })
        journal.record("x".repeat(1_000))
        assertEquals(300, journal.events().last().message.length)
        assertEquals(3, journal.stream.value.size)
    }

    @Test fun selectedNonAdjacentComparisonIsIncludedInRedactedExport() {
        val snapshots = listOf(snapshot(1, row()), snapshot(2, row()), snapshot(3, row().copy(visible = 0)))
        val report = exportDiagnostics(snapshots, emptyList(), snapshots.first() to snapshots.last())
        assertTrue(report.contains("Compare #1 -> #3"))
        assertTrue(report.contains("MODIFIED fields=visible"))
        assertFalse(report.contains("private@example.test"))
    }

    private class Probe : DiagnosticProbe {
        var rows = emptyList<ProviderCalendar>()
        var reads = 0
        var syncs = 0
        var readFailure: Exception? = null
        var performSync: suspend () -> CalendarSyncResult = { CalendarSyncResult.FINISHED }
        override suspend fun read(): List<ProviderCalendar> {
            reads++
            readFailure?.let { throw it }
            return rows
        }
        override suspend fun sync(calendars: List<ProviderCalendar>): CalendarSyncResult {
            syncs++
            return performSync()
        }
    }

    @Test fun directReadsDoNotRequestSyncAndOnlyFiveSnapshotsAreRetained() = runTest {
        val probe = Probe().apply { rows = listOf(row()) }
        val session = CalendarDiagnosticsSession(probe, this, DiagnosticJournal(), { mapOf(1L to 123) }, { at })
        repeat(8) { session.read() }
        assertEquals(0, probe.syncs)
        assertEquals(listOf(4L, 5L, 6L, 7L, 8L), session.state.value.snapshots.map { it.sequence })
        assertEquals(123, session.state.value.snapshots.last().appColors[1L])
    }

    @Test fun syncCapturesBeforeAndAfterAndIgnoresOverlappingCalls() = runTest {
        val probe = Probe().apply {
            rows = listOf(row())
            performSync = { delay(1_000); rows = listOf(row().copy(color = 0xff445566.toInt())); CalendarSyncResult.FINISHED }
        }
        val session = CalendarDiagnosticsSession(probe, this, DiagnosticJournal(), { emptyMap() })
        session.requestSync()
        session.requestSync()
        session.read()
        assertTrue(session.state.value.busy)
        assertEquals(1, probe.reads)
        advanceUntilIdle()
        assertFalse(session.state.value.busy)
        assertEquals(1, probe.syncs)
        assertTrue(session.journal.events().any { "REQUEST_SYNC_ACTION" in it.message })
        assertTrue(session.journal.events().any { "ACTION_SKIPPED" in it.message })
        assertEquals(2, probe.reads)
        val snapshots = session.state.value.snapshots
        assertEquals(listOf("color"), compareSnapshots(snapshots[0], snapshots[1]).single().fields)
    }

    @Test fun stoppingCancelsMonitoringWithoutTakingAnotherSnapshotOrRetrying() = runTest {
        val probe = Probe().apply { performSync = { awaitCancellation() } }
        val session = CalendarDiagnosticsSession(probe, this, DiagnosticJournal(), { emptyMap() })
        session.requestSync()
        session.cancel()
        runCurrent()
        assertFalse(session.state.value.busy)
        advanceTimeBy(60_000)
        assertEquals(1, probe.syncs)
        assertEquals(1, probe.reads)
        session.read()
        assertEquals(2, session.state.value.snapshots.size)
    }

    @Test fun failedReadKeepsExistingSnapshotsAndSanitizesError() = runTest {
        val probe = Probe()
        val journal = DiagnosticJournal()
        val session = CalendarDiagnosticsSession(probe, this, journal, { emptyMap() })
        session.read()
        probe.readFailure = SecurityException("private@example.test")
        session.read()
        assertEquals(1, session.state.value.snapshots.size)
        assertFalse(session.state.value.busy)
        assertTrue(journal.events().last().message.endsWith("SecurityException"))
        assertFalse(exportDiagnostics(session.state.value.snapshots, journal.events()).contains("private@example.test"))
    }

    @Test fun timedOutSyncStillTakesFinalSnapshotAndRecordsUnconfirmedResult() = runTest {
        val probe = Probe().apply { performSync = { CalendarSyncResult.TIMED_OUT } }
        val journal = DiagnosticJournal()
        val session = CalendarDiagnosticsSession(probe, this, journal, { emptyMap() })
        session.requestSync()
        assertEquals(2, session.state.value.snapshots.size)
        assertTrue(journal.events().any { "TIMED_OUT" in it.message })
    }

    @Test fun missingAccessRemainsRecoverableAfterReturningFromAccountChooser() = runTest {
        val probe = Probe().apply { performSync = { CalendarSyncResult.ACCESS_REQUIRED } }
        val session = CalendarDiagnosticsSession(probe, this, DiagnosticJournal(), { emptyMap() })
        session.requestSync()
        assertEquals(CalendarSyncResult.ACCESS_REQUIRED, session.state.value.syncResult)
        session.cancel()
        session.read()
        assertEquals(CalendarSyncResult.ACCESS_REQUIRED, session.state.value.syncResult)
        assertEquals(1, probe.syncs) // Opening/returning from the chooser does not retry diagnostics.
        probe.performSync = { CalendarSyncResult.FINISHED }
        session.requestSync()
        assertEquals(CalendarSyncResult.FINISHED, session.state.value.syncResult)
        assertEquals(2, probe.syncs)
    }
}
