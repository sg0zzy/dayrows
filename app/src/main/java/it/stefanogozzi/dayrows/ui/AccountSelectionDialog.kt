// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import it.stefanogozzi.dayrows.R
import it.stefanogozzi.dayrows.domain.SyncAccountChoice

/** Lets the user pick one or more provider accounts before a hard refresh.
 *
 * The platform's own account chooser is single-select, so this dialog collects the
 * whole selection first. DayRows cannot grant account visibility itself: the platform
 * only allows the system to do that. The caller therefore opens the system chooser
 * once per checked account that still needs visibility. */
@Composable
fun AccountSelectionDialog(
    accounts: List<SyncAccountChoice>,
    onDismiss: () -> Unit,
    onConfirm: (List<SyncAccountChoice>) -> Unit,
) {
    var selected by remember(accounts) { mutableStateOf(emptySet<SyncAccountChoice>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_sync_accounts)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.select_sync_accounts_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                accounts.forEach { account ->
                    val checked = account in selected
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(role = Role.Checkbox) {
                                selected = if (checked) selected - account else selected + account
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = { checked ->
                            selected = if (checked) selected - account else selected + account
                        })
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text(account.display, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (account.visible) stringResource(R.string.account_already_allowed)
                                else stringResource(R.string.account_needs_permission),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(accounts.filter { it in selected }) },
                enabled = selected.isNotEmpty()) {
                Text(stringResource(R.string.sync_selected_accounts))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
