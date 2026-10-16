// SPDX-License-Identifier: GPL-3.0-or-later
package org.dayrows.app.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dayrows.app.R
import java.io.IOException

private data class DocumentState(val loading: Boolean = true, val text: String? = null)

/** An offline dialog inside Settings, using the exact legal files from the project root. */
@Composable
fun LegalDocumentDialog(fileName: String, onDismiss: () -> Unit) {
    val title = when (fileName) {
        "PRIVACY.md" -> R.string.privacy_policy
        "TERMS.md" -> R.string.terms_of_use
        "LICENSE" -> R.string.gpl_license
        else -> return
    }
    val assets = LocalContext.current.applicationContext.assets
    val document by produceState(DocumentState(), fileName) {
        value = withContext(Dispatchers.IO) {
            try {
                DocumentState(loading = false, text = assets.open("legal/$fileName").bufferedReader(Charsets.UTF_8).use { it.readText() })
            } catch (_: IOException) { DocumentState(loading = false) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            SelectionContainer {
                Text(document.text ?: stringResource(if (document.loading) R.string.legal_loading else R.string.legal_load_error),
                    modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
