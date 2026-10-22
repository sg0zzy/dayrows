// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import it.stefanogozzi.dayrows.R
import it.stefanogozzi.dayrows.BuildConfig
import it.stefanogozzi.dayrows.diagnostics.DeveloperDiagnosticsEntry
import it.stefanogozzi.dayrows.viewmodel.CalendarUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: CalendarUiState, onBack: () -> Unit, onSelect: (String, Boolean) -> Unit,
                   onLabel: (String, String) -> Unit, onMove: (String, String) -> Unit, onRetry: () -> Unit,
                   onDiscover: (Boolean) -> Unit, onAccountAccess: () -> Unit, onReload: () -> Unit) {
    LaunchedEffect(Unit) { onDiscover(false) }
    var legalDocument by rememberSaveable { mutableStateOf<String?>(null) }
    val calendars = state.preferences.ordered(state.calendars)
    val listState = rememberLazyListState()
    val currentMove by rememberUpdatedState(onMove)
    val currentCalendars by rememberUpdatedState(calendars)
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        }, actions = { TextButton(onClick = onBack) { Text(stringResource(R.string.done)) } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(stringResource(R.string.settings_intro), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            FilledTonalButton(onClick = onReload, enabled = !state.searching && !state.syncing,
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 4.dp)) {
                if (state.searching) CircularProgressIndicator(Modifier.size(18.dp).padding(end = 8.dp),
                    strokeWidth = 2.dp, color = LocalContentColor.current)
                else Icon(Icons.Default.Refresh, null, Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.refresh_calendars))
            }
            Text(stringResource(R.string.calendar_sync_hint), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.searching) Text(stringResource(R.string.searching_calendars),
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            state.newCalendars?.let { found ->
                Text(if (found == 0) stringResource(R.string.no_new_calendars)
                    else pluralStringResource(R.plurals.new_calendars_found, found, found),
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (state.loading || state.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            SyncMessage(state, onAccountAccess)
            if (state.error != null) ErrorMessage(state.error, onRetry)
            if (calendars.isEmpty() && !state.loading && !state.searching) Text(stringResource(R.string.no_available_calendars), Modifier.padding(16.dp))
            LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (BuildConfig.DEBUG) {
                    item(key = "developer_diagnostics") { DeveloperDiagnosticsEntry(state.calendars) }
                }
                itemsIndexed(calendars, key = { _, calendar -> calendar.key }) { index, calendar ->
                    val preference = state.preferences.preference(calendar)
                    var label by rememberSaveable(calendar.key) { mutableStateOf(preference.label) }
                    val dragDescription = stringResource(R.string.drag_reorder)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = preference.selected, onCheckedChange = { onSelect(calendar.key, it) },
                            modifier = Modifier.semantics { contentDescription = calendar.displayName })
                        Column(Modifier.weight(1f)) {
                            Text(calendar.displayName.ifBlank { stringResource(R.string.unnamed_calendar) }, style = MaterialTheme.typography.titleSmall)
                            Text(listOfNotNull(calendar.accountName, calendar.accountType).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.source_id, calendar.id.toString()), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!calendar.syncEvents) Text(stringResource(R.string.not_syncing), style = MaterialTheme.typography.bodySmall)
                            if (!calendar.reliableIdentity) Text(stringResource(R.string.identity_unavailable), style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(value = label, onValueChange = { label = it; onLabel(calendar.key, it) },
                                label = { Text(stringResource(R.string.local_label)) }, placeholder = { Text(calendar.displayName) },
                                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(onClick = { onMove(calendar.key, calendars[index - 1].key) }, enabled = index > 0) {
                                Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.move_up))
                            }
                            Text("≡", style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.size(40.dp).wrapContentSize().semantics { contentDescription = dragDescription }
                                    .pointerInput(calendar.key) {
                                        var dragY = 0f
                                        detectDragGesturesAfterLongPress(onDragStart = { point ->
                                            val row = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == calendar.key }
                                            dragY = (row?.offset ?: 0) + (row?.size ?: 0) / 2f
                                        }, onDrag = { change, amount ->
                                            change.consume(); dragY += amount.y
                                            val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { dragY >= it.offset && dragY < it.offset + it.size }
                                            val targetKey = target?.key as? String
                                            if (targetKey != null && targetKey != calendar.key && currentCalendars.any { it.key == targetKey }) currentMove(calendar.key, targetKey)
                                        })
                                    })
                            IconButton(onClick = { onMove(calendar.key, calendars[index + 1].key) }, enabled = index < calendars.lastIndex) {
                                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.move_down))
                            }
                        }
                    }
                    HorizontalDivider()
                }
                item(key = "legal_documents") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(stringResource(R.string.legal_notice), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { legalDocument = "PRIVACY.md" }) { Text(stringResource(R.string.privacy_policy)) }
                        TextButton(onClick = { legalDocument = "TERMS.md" }) { Text(stringResource(R.string.terms_of_use)) }
                        TextButton(onClick = { legalDocument = "LICENSE" }) { Text(stringResource(R.string.gpl_license)) }
                    }
                }
            }
        }
    }
    legalDocument?.let { file -> LegalDocumentDialog(file, onDismiss = { legalDocument = null }) }

}
