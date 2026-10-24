// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import it.stefanogozzi.dayrows.data.*
import it.stefanogozzi.dayrows.domain.SyncAccountChoice
import it.stefanogozzi.dayrows.domain.chooserGrant
import it.stefanogozzi.dayrows.domain.needingAccountAccess
import it.stefanogozzi.dayrows.domain.newEventDraft
import it.stefanogozzi.dayrows.ui.*
import it.stefanogozzi.dayrows.ui.theme.DayRowsTheme
import it.stefanogozzi.dayrows.viewmodel.CalendarViewModel
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    /** The mime type both the platform and Google Calendar declare for an insert request. */
    private companion object { const val EVENT_MIME_TYPE = "vnd.android.cursor.dir/event" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: CalendarViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CalendarViewModel(
                    AndroidCalendarRepository(contentResolver, AccountManager.get(applicationContext)),
                    PreferencesRepository(applicationContext)) as T
            })
            val state by model.state.collectAsStateWithLifecycle()
            var granted by remember { mutableStateOf(hasPermission()) }
            var requested by remember { mutableStateOf(getPreferences(MODE_PRIVATE).getBoolean("requested", false)) }
            var inSettings by rememberSaveable { mutableStateOf(false) }
            var autoOpened by rememberSaveable { mutableStateOf(false) }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
                granted = allowed; model.permission(allowed)
                if (allowed) model.refresh()
            }
            var accountAccessInProgress by rememberSaveable { mutableStateOf(false) }
            // The platform account chooser is single-select, so accounts that still need
            // visibility are granted one after another through this queue.
            var accountQueue by remember { mutableStateOf<List<SyncAccountChoice>>(emptyList()) }
            var pendingSyncAccounts by remember { mutableStateOf<List<SyncAccountChoice>>(emptyList()) }
            var showAccountDialog by remember { mutableStateOf(false) }
            val accountLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                // The chooser returns cancelled when the user backs out, and also when the
                // account type has no authenticator to show. Either way the remaining
                // accounts still get their own turn, so one unusable type cannot block
                // the others.
                // A completed chooser authorizes the account it was opened for, which is the
                // head of the queue; remembering it keeps a later hard refresh from asking for
                // the same source again.
                model.authorizeAccounts(chooserGrant(
                    result.resultCode == Activity.RESULT_OK, accountQueue.firstOrNull()))
                val remaining = accountQueue.drop(1)
                accountQueue = remaining
                if (remaining.isEmpty()) {
                    accountAccessInProgress = false
                    // Every chosen account has been through the chooser: perform the hard refresh.
                    model.hardRefresh(pendingSyncAccounts)
                    pendingSyncAccounts = emptyList()
                } else {
                    // A grant changes the account's visibility; reload it for the next dialog.
                    model.loadSyncAccounts()
                }
            }
            // Drives the one-account-at-a-time chooser from the queue.
            LaunchedEffect(accountQueue) {
                val next = accountQueue.firstOrNull() ?: return@LaunchedEffect
                try {
                    accountLauncher.launch(AccountManager.newChooseAccountIntent(null,
                        listOf(Account(next.name, next.type)),
                        arrayOf(next.type),
                        getString(R.string.sync_account_picker_description), null, null, null))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, R.string.sync_account_picker_unavailable, Toast.LENGTH_LONG).show()
                    accountQueue = accountQueue.drop(1)
                    if (accountQueue.isEmpty()) accountAccessInProgress = false
                } catch (_: SecurityException) {
                    Toast.makeText(this@MainActivity, R.string.sync_account_picker_unavailable, Toast.LENGTH_LONG).show()
                    accountQueue = accountQueue.drop(1)
                    if (accountQueue.isEmpty()) accountAccessInProgress = false
                }
            }
            /** A hard refresh first asks which accounts to synchronize. Accounts that are
             * already visible are used as they are; the others need the platform chooser.
             * The dialog opens only after the account list is current, so a source the
             * user already authorized is never shown as needing permission again. */
            fun requestHardRefresh() {
                if (accountAccessInProgress) return
                model.loadSyncAccounts { showAccountDialog = true }
            }
            val allowAccountSync: () -> Unit = { requestHardRefresh() }
            DisposableEffect(model) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        granted = hasPermission(); model.permission(granted)
                        if (granted) model.refresh()
                    }
                }
                lifecycle.addObserver(observer)
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) { model.refresh() }
                }
                ContextCompat.registerReceiver(this@MainActivity, receiver, IntentFilter().apply {
                    addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_DATE_CHANGED); addAction(Intent.ACTION_TIME_CHANGED)
                }, ContextCompat.RECEIVER_NOT_EXPORTED)
                model.permission(granted)
                onDispose { lifecycle.removeObserver(observer); unregisterReceiver(receiver) }
            }
            LaunchedEffect(granted, state.preferencesReady, state.discovered, state.preferences.configured) {
                if (granted && state.preferencesReady && state.discovered && !state.preferences.configured && !autoOpened) {
                    inSettings = true; autoOpened = true
                }
            }
            fun closeSettings() { model.finishSetup(); inSettings = false }
            BackHandler(inSettings && granted) { closeSettings() }
            DayRowsTheme {
                Surface(Modifier.fillMaxSize()) {
                    when {
                        !granted -> PermissionScreen(
                            permanentlyDenied = requested && !shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR),
                            onRequest = {
                                requested = true; getPreferences(MODE_PRIVATE).edit { putBoolean("requested", true) }
                                launcher.launch(Manifest.permission.READ_CALENDAR)
                            }, onSystemSettings = {
                                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
                            })
                        inSettings -> SettingsScreen(state, { closeSettings() }, model::select, model::label, model::move,
                            model::refresh, model::discoverCalendars, allowAccountSync,
                            onReload = { requestHardRefresh() })
                        else -> CalendarScreen(state, { inSettings = true }, model::days, model::navigate, model::today,
                            onRefresh = model::refreshLight, onRetry = model::refresh, onEvent = ::openEvent,
                            onAccountAccess = allowAccountSync, onNewEvent = ::openNewEvent, onLabel = model::label)
                    }
                    if (showAccountDialog) {
                        AccountSelectionDialog(
                            accounts = state.syncAccounts,
                            onDismiss = { showAccountDialog = false },
                            onConfirm = { chosen ->
                                showAccountDialog = false
                                // Accounts that already have visibility, and accounts the user
                                // authorized earlier, are used straight away; only the rest
                                // need the platform chooser, one at a time.
                                val needing = chosen.needingAccountAccess(state.authorizedAccounts)
                                pendingSyncAccounts = chosen
                                if (needing.isEmpty()) {
                                    model.hardRefresh(chosen)
                                    pendingSyncAccounts = emptyList()
                                } else {
                                    accountAccessInProgress = true
                                    accountQueue = needing
                                }
                            })
                    }
                }
            }
        }
    }
    private fun hasPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    private fun openEvent(event: EventInstance) {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId)
        val intent = Intent(Intent.ACTION_VIEW, uri).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.start.toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end.toEpochMilli()).putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, event.allDay)
        try { startActivity(intent) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, R.string.no_calendar_handler, Toast.LENGTH_LONG).show() }
        catch (_: SecurityException) { Toast.makeText(this, R.string.no_calendar_handler, Toast.LENGTH_LONG).show() }
    }

    /** Hands a pre-filled new event to an installed calendar app. DayRows holds only READ_CALENDAR
     * and never writes to the provider, so the receiving app performs the insertion itself.
     *
     * The intent names the day and the slot, and deliberately names no calendar. A receiving app
     * offers the calendars it can write to and that are visible to it, and it may keep its own
     * default whatever a third party asks for: Google Calendar reads no calendar-selecting insert
     * extra at all, and the platform app only honors one it would have offered anyway. A named
     * calendar would therefore be a promise DayRows cannot keep, so the calendar is chosen in the
     * app that already owns that choice.
     *
     * The mime type is the one both the platform and Google Calendar declare for an insert
     * request, so the same intent reaches either app. */
    private fun openNewEvent(date: LocalDate) {
        val draft = newEventDraft(date, ZoneId.systemDefault())
        // setDataAndType keeps both the documented events URI and the mime type; setType alone
        // would clear the URI.
        val intent = Intent(Intent.ACTION_INSERT)
            .setDataAndType(CalendarContract.Events.CONTENT_URI, EVENT_MIME_TYPE)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, draft.startMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, draft.endMillis)
        try { startActivity(intent) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, R.string.no_calendar_handler, Toast.LENGTH_LONG).show() }
        catch (_: SecurityException) { Toast.makeText(this, R.string.no_calendar_handler, Toast.LENGTH_LONG).show() }
    }
}

@Composable
private fun PermissionScreen(permanentlyDenied: Boolean, onRequest: () -> Unit, onSystemSettings: () -> Unit) {
    var showPrivacy by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.tagline), Modifier.padding(top = 8.dp, bottom = 24.dp), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.permission_explanation))
        Button(onClick = onRequest, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.allow_calendar)) }
        TextButton(onClick = { showPrivacy = true }) { Text(stringResource(R.string.privacy_policy)) }
        if (permanentlyDenied) {
            Text(stringResource(R.string.permission_settings_explanation), Modifier.padding(top = 12.dp))
            TextButton(onClick = onSystemSettings) { Text(stringResource(R.string.system_settings)) }
        }
    }
    if (showPrivacy) LegalDocumentDialog("PRIVACY.md", onDismiss = { showPrivacy = false })

}
