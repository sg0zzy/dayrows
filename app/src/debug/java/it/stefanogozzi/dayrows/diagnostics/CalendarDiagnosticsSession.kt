// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.diagnostics

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import it.stefanogozzi.dayrows.data.CalendarSyncResult

internal data class DiagnosticState(val snapshots: List<ProviderSnapshot> = emptyList(), val busy: Boolean = false,
    val syncResult: CalendarSyncResult? = null)

/** One foreground operation, five in-memory snapshots, no observers/services or persistence. */
internal class CalendarDiagnosticsSession(
    private val probe: DiagnosticProbe,
    private val scope: CoroutineScope,
    val journal: DiagnosticJournal,
    private val appColors: () -> Map<Long, Int>,
    private val now: () -> Instant = Instant::now,
) {
    private val _state = MutableStateFlow(DiagnosticState())
    val state = _state.asStateFlow()
    private var operation: Job? = null
    private var sequence = 0L

    fun read() = launchOperation {
        capture()
    }

    fun requestSync() {
        journal.record("REQUEST_SYNC_ACTION: handler entered; API has not been called yet")
        launchOperation {
            val before = capture()
            val result = probe.sync(before.calendars)
            _state.update { it.copy(syncResult = result) }
            journal.record("coordinatorResult=$result; check API_CALL/OBSERVED evidence; no per-request scheduling or remote-success acknowledgement")
            val after = capture()
            val changes = compareSnapshots(before, after)
            journal.record("post-sync comparison: ${changes.size} changes, ${changes.count { "color" in it.fields }} color changes")
        }
    }

    private fun launchOperation(action: suspend () -> Unit) {
        if (_state.value.busy) {
            journal.record("ACTION_SKIPPED: diagnostic operation already in progress; API not called")
            return
        }
        _state.update { it.copy(busy = true) }
        operation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { action() }
            catch (e: CancellationException) {
                journal.record("diagnostic operation canceled; account adapter work is not canceled")
                throw e
            } catch (e: Exception) { journal.failure("diagnostic operation failed", e) }
            finally { _state.update { it.copy(busy = false) } }
        }
    }

    private suspend fun capture(): ProviderSnapshot {
        val app = appColors().toMap()
        val at = now()
        val rows = probe.read()
        currentCoroutineContext().ensureActive()
        val snapshot = ProviderSnapshot(++sequence, at, rows.toList(), app)
        _state.update { it.copy(snapshots = (it.snapshots + snapshot).takeLast(5)) }
        journal.record("provider snapshot #${snapshot.sequence}: ${rows.size} calendars")
        return snapshot
    }

    fun cancel() { operation?.cancel() }
}
