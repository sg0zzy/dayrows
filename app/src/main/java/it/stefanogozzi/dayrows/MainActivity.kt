// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
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
import it.stefanogozzi.dayrows.ui.*
import it.stefanogozzi.dayrows.ui.theme.DayRowsTheme
import it.stefanogozzi.dayrows.viewmodel.CalendarViewModel

class MainActivity : ComponentActivity() {
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
            var accountCheckInProgress by remember { mutableStateOf(false) }
            var reloadSettingsAfterAccountChoice by rememberSaveable { mutableStateOf(false) }
            val accountLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val reloadSettings = reloadSettingsAfterAccountChoice
                accountAccessInProgress = false
                reloadSettingsAfterAccountChoice = false
                if (result.resultCode == RESULT_OK) {
                    if (reloadSettings) model.discoverCalendars(true) else model.syncNow()
                } else model.refresh()
            }
            fun requestAccountAccess(reloadSettings: Boolean) {
                if (accountAccessInProgress || accountCheckInProgress) return
                accountCheckInProgress = true
                lifecycleScope.launch {
                    var chooserLaunched = false
                    try {
                        val missing = withContext(Dispatchers.IO) {
                            val manager = AccountManager.get(applicationContext)
                            readCalendarSyncAccounts(contentResolver).filter { (name, type) ->
                                manager.getAccountsByType(type).none { it.name == name }
                            }
                        }
                        if (missing.isEmpty()) {
                            if (reloadSettings) model.discoverCalendars(true) else model.syncNow()
                        } else {
                            accountAccessInProgress = true
                            reloadSettingsAfterAccountChoice = reloadSettings
                            accountLauncher.launch(AccountManager.newChooseAccountIntent(null,
                                missing.map { (name, type) -> Account(name, type) },
                                missing.map { it.second }.distinct().toTypedArray(),
                                getString(R.string.sync_account_picker_description), null, null, null))
                            chooserLaunched = true
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: ActivityNotFoundException) {
                        Toast.makeText(this@MainActivity, R.string.sync_account_picker_unavailable, Toast.LENGTH_LONG).show()
                    } catch (_: SecurityException) {
                        Toast.makeText(this@MainActivity, R.string.sync_account_picker_unavailable, Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        calendarDebug("account access check unavailable: ${e.javaClass.simpleName}")
                        Toast.makeText(this@MainActivity, R.string.sync_unavailable, Toast.LENGTH_LONG).show()
                        model.refresh()
                    } finally {
                        accountCheckInProgress = false
                        if (!chooserLaunched) {
                            accountAccessInProgress = false
                            reloadSettingsAfterAccountChoice = false
                        }
                    }
                }
            }
            val allowAccountSync: () -> Unit = { requestAccountAccess(inSettings) }
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
                            onReload = { requestAccountAccess(reloadSettings = true) })
                        else -> CalendarScreen(state, { inSettings = true }, model::days, model::navigate, model::today,
                            onRefresh = { requestAccountAccess(reloadSettings = false) }, onRetry = model::refresh, onEvent = ::openEvent,
                            onAccountAccess = allowAccountSync)
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
