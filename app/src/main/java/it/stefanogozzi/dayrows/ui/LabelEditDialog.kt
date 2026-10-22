// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.stefanogozzi.dayrows.R
import it.stefanogozzi.dayrows.data.DeviceCalendar

/** Edits a calendar's local label, the same private name shown in Settings. An empty value
 * restores the source display name. The source calendar itself is never changed. */
@Composable
fun LabelEditDialog(calendar: DeviceCalendar, currentLabel: String, onDismiss: () -> Unit,
                    onConfirm: (String) -> Unit) {
    var label by rememberSaveable(calendar.key) { mutableStateOf(currentLabel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_calendar_label)) },
        text = {
            Column {
                Text(calendar.displayName.ifBlank { stringResource(R.string.unnamed_calendar) },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = label, onValueChange = { label = it },
                    label = { Text(stringResource(R.string.local_label)) },
                    placeholder = { Text(calendar.displayName) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(label) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
