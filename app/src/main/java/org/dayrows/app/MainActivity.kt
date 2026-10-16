// SPDX-License-Identifier: GPL-3.0-or-later
package org.dayrows.app

import android.Manifest
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
import org.dayrows.app.data.*
import org.dayrows.app.ui.*
import org.dayrows.app.ui.theme.DayRowsTheme
import org.dayrows.app.viewmodel.CalendarViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: CalendarViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CalendarViewModel(
                    AndroidCalendarRepository(contentResolver), PreferencesRepository(applicationContext)) as T
            })
            val state by model.state.collectAsStateWithLifecycle()
            var granted by remember { mutableStateOf(hasPermission()) }
            var requested by remember { mutableStateOf(getPreferences(MODE_PRIVATE).getBoolean("requested", false)) }
            var inSettings by rememberSaveable { mutableStateOf(false) }
            var autoOpened by rememberSaveable { mutableStateOf(false) }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
                granted = allowed; model.permission(allowed)
            }
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
                        inSettings -> SettingsScreen(state, { closeSettings() }, model::select, model::label, model::move, model::refresh)
                        else -> CalendarScreen(state, { inSettings = true }, model::days, model::navigate, model::today, model::refresh, ::openEvent)
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
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.tagline), Modifier.padding(top = 8.dp, bottom = 24.dp), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.permission_explanation))
        Button(onClick = onRequest, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.allow_calendar)) }
        if (permanentlyDenied) {
            Text(stringResource(R.string.permission_settings_explanation), Modifier.padding(top = 12.dp))
            TextButton(onClick = onSystemSettings) { Text(stringResource(R.string.system_settings)) }
        }
    }
}
