// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import it.stefanogozzi.dayrows.BuildConfig
import it.stefanogozzi.dayrows.R
import it.stefanogozzi.dayrows.data.DeviceCalendar
import it.stefanogozzi.dayrows.data.CalendarSyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Small module registry: future probes add a title/content pair, with their own scoped state. */
internal class DiagnosticModule(val id: String, val title: Int, val content: @Composable () -> Unit)

@Composable
fun DeveloperDiagnosticsEntry(calendars: List<DeviceCalendar>) {
    if (!BuildConfig.DEBUG) return
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(stringResource(R.string.developer_diagnostics))
    }
    if (open) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DiagnosticsHost(listOf(DiagnosticModule("calendar-provider", R.string.diag_calendar_provider) {
            CalendarProviderDiagnostics(calendars)
        }), onClose = { open = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsHost(modules: List<DiagnosticModule>, onClose: () -> Unit) {
    var selected by remember { mutableStateOf(modules.first().id) }
    Surface(Modifier.fillMaxSize()) {
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(R.string.developer_diagnostics)) }, navigationIcon = {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (modules.size > 1) Row(Modifier.horizontalScroll(rememberScrollState())) {
                    modules.forEach { module ->
                        FilterChip(selected = selected == module.id, onClick = { selected = module.id },
                            label = { Text(stringResource(module.title)) })
                    }
                }
                key(selected) { modules.first { it.id == selected }.content() }
            }
        }
    }
}

@Composable
private fun CalendarProviderDiagnostics(calendars: List<DeviceCalendar>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestCalendars by rememberUpdatedState(calendars)
    val session = remember {
        val journal = DiagnosticJournal(sink = ::diagnosticLogcat)
        CalendarDiagnosticsSession(CalendarProviderProbe(context, journal), scope, journal,
            { latestCalendars.associate { it.id to it.color } })
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, session) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) session.cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); session.cancel() }
    }
    val state by session.state.collectAsState()
    val accountLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        session.journal.record("ACCOUNT_PICKER_RESULT accepted=${result.resultCode == Activity.RESULT_OK}; press Request Sync to recheck access")
    }
    val events by session.journal.stream.collectAsState()
    var baselineId by remember { mutableStateOf<Long?>(null) }
    var targetId by remember { mutableStateOf<Long?>(null) }
    var compared by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var copied by remember { mutableStateOf(false) }
    val baseline = state.snapshots.firstOrNull { it.sequence == baselineId } ?: state.snapshots.firstOrNull()
    val target = state.snapshots.firstOrNull { it.sequence == targetId } ?: state.snapshots.lastOrNull()
    val changes = if (baseline != null && target != null && compared == (baseline.sequence to target.sequence))
        compareSnapshots(baseline, target) else null
    val changesById = changes.orEmpty().associateBy { it.id }
    val rows = target?.calendars.orEmpty() + changes.orEmpty().filter { it.kind == ChangeKind.REMOVED }.mapNotNull { it.before }
    val clipboardLabel = stringResource(R.string.developer_diagnostics)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(stringResource(R.string.diag_calendar_provider), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.diag_explanation), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !state.busy, onClick = { targetId = null; compared = null; session.read() }) {
                    Text(stringResource(R.string.diag_read_provider))
                }
                Button(enabled = !state.busy, onClick = { targetId = null; compared = null; session.requestSync() }) {
                    Text(stringResource(R.string.diag_request_sync))
                }
                OutlinedButton(enabled = !state.busy, onClick = { targetId = null; compared = null; session.read() }) {
                    Text(stringResource(R.string.diag_refresh_snapshot))
                }
            }
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = session::cancel) { Text(stringResource(R.string.diag_stop)) }
            }
            if (state.syncResult == CalendarSyncResult.ACCESS_REQUIRED) {
                Text(stringResource(R.string.sync_account_access_required))
                TextButton(enabled = !state.busy, onClick = {
                    scope.launch {
                        try {
                            val missing = withContext(Dispatchers.IO) {
                                val manager = AccountManager.get(context)
                                state.snapshots.lastOrNull()?.calendars.orEmpty().mapNotNull { row ->
                                    val name = row.accountName?.takeIf { it.isNotBlank() }
                                    val type = row.accountType?.takeIf { it.isNotBlank() }
                                    if (name == null || type == null) null else Account(name, type)
                                }.distinct().filter { account ->
                                    manager.getAccountsByType(account.type).none { it == account }
                                }
                            }
                            if (missing.isNotEmpty()) {
                                session.journal.record("ACCOUNT_PICKER_OPEN candidates=${missing.size}; user selection required")
                                accountLauncher.launch(AccountManager.newChooseAccountIntent(null, missing,
                                    missing.map { it.type }.distinct().toTypedArray(),
                                    context.getString(R.string.sync_account_picker_description), null, null, null))
                            } else session.journal.record("ACCOUNT_PICKER_SKIPPED: accounts already visible; press Request Sync")
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { session.journal.failure("account picker unavailable", e) }
                    }
                }) { Text(stringResource(R.string.allow_account_sync)) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SnapshotPicker(R.string.diag_baseline, state.snapshots, baseline) { baselineId = it; compared = null }
                SnapshotPicker(R.string.diag_target, state.snapshots, target) { targetId = it; compared = null }
            }
            OutlinedButton(enabled = baseline != null && target != null && baseline.sequence != target.sequence && !state.busy,
                onClick = {
                    if (baseline != null && target != null) {
                        compared = baseline.sequence to target.sequence
                        session.journal.record("compare #${baseline.sequence} -> #${target.sequence}: ${compareSnapshots(baseline, target).size} changes")
                    }
                }) { Text(stringResource(R.string.diag_compare)) }
            changes?.let {
                Text(stringResource(R.string.diag_changes, it.count { c -> c.kind == ChangeKind.ADDED },
                    it.count { c -> c.kind == ChangeKind.REMOVED }, it.count { c -> c.kind == ChangeKind.MODIFIED }))
            }
            TextButton(onClick = {
                val selected = if (changes != null && baseline != null && target != null) baseline to target else null
                val report = exportDiagnostics(state.snapshots, session.journal.events(), selected)
                val clip = ClipData.newPlainText(clipboardLabel, report)
                clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                copied = true
            }) { Text(stringResource(R.string.diag_copy)) }
            if (copied) Text(stringResource(R.string.diag_copied), style = MaterialTheme.typography.bodySmall)
            target?.let { Text(stringResource(R.string.diag_snapshot_title, it.sequence, it.at.toString(), it.calendars.size)) }
        }
        items(rows, key = { "calendar-${it.id}" }) { row ->
            CalendarDiagnosticRow(row, target, changesById[row.id])
        }
        item { Text(stringResource(R.string.diag_trace), style = MaterialTheme.typography.titleMedium) }
        items(events.asReversed().withIndex().toList(), key = { "trace-${it.index}" }) { (_, event) ->
            Text("${event.at} ${event.message}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SnapshotPicker(label: Int, snapshots: List<ProviderSnapshot>, selected: ProviderSnapshot?, onSelect: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(enabled = snapshots.isNotEmpty(), onClick = { expanded = true }) {
            Text("${stringResource(label)}: #${selected?.sequence ?: "—"}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            snapshots.forEach { snapshot ->
                DropdownMenuItem(text = { Text("#${snapshot.sequence} ${snapshot.at}") },
                    onClick = { onSelect(snapshot.sequence); expanded = false })
            }
        }
    }
}

@Composable
private fun CalendarDiagnosticRow(row: ProviderCalendar, snapshot: ProviderSnapshot?, change: CalendarChange?) {
    val container = when (change?.kind) {
        ChangeKind.ADDED -> MaterialTheme.colorScheme.primaryContainer
        ChangeKind.REMOVED -> MaterialTheme.colorScheme.errorContainer
        ChangeKind.MODIFIED -> MaterialTheme.colorScheme.tertiaryContainer
        null -> MaterialTheme.colorScheme.surfaceContainer
    }
    Surface(color = container, shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            change?.let {
                Text(stringResource(when (it.kind) {
                    ChangeKind.ADDED -> R.string.diag_added
                    ChangeKind.REMOVED -> R.string.diag_removed
                    ChangeKind.MODIFIED -> R.string.diag_modified
                }) + " " + it.fields.joinToString(", "), style = MaterialTheme.typography.labelLarge)
            }
            Text(stringResource(R.string.diag_row, row.id, row.accountName ?: "null", row.accountType ?: "null",
                row.displayName ?: "null", row.visible.toString(), row.syncEvents.toString(), row.accessLevel.toString(), row.deleted.toString()),
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(24.dp).background(row.color?.let { Color(it) } ?: Color.Transparent))
                Text(colorHex(row.color))
            }
            if (change != null && "color" in change.fields) {
                Text("${colorHex(change.before?.color)} → ${colorHex(change.after?.color)}", style = MaterialTheme.typography.labelLarge)
            }
            val appColor = snapshot?.appColors?.get(row.id)
            Text(stringResource(R.string.diag_app_color, appColor?.let(::colorHex) ?: stringResource(R.string.diag_absent)),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
