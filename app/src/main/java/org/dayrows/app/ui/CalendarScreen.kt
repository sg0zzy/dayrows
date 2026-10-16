// SPDX-License-Identifier: GPL-3.0-or-later
package org.dayrows.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.dayrows.app.R
import org.dayrows.app.data.EventInstance
import org.dayrows.app.ui.components.CalendarMatrix
import org.dayrows.app.viewmodel.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(state: CalendarUiState, onSettings: () -> Unit, onDays: (Int) -> Unit,
                   onNavigate: (Int) -> Unit, onToday: () -> Unit, onRetry: () -> Unit, onEvent: (EventInstance) -> Unit) {
    val matrix = state.matrix
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val locale = configuration.locales[0]
    val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val period = if (matrix.dates.size == 1) format.format(state.start)
        else "${format.format(state.start)} – ${format.format(matrix.dates.last())}"
    Scaffold(topBar = {
        if (landscape) {
            val shortFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)
            val shortPeriod = if (matrix.dates.size == 1) shortFormat.format(state.start)
                else "${shortFormat.format(state.start)} – ${shortFormat.format(matrix.dates.last())}"
            LandscapeToolbar(state.preferences.dayCount, shortPeriod, onSettings, onDays, onNavigate, onToday)
        } else {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) }, actions = {
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
            })
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!landscape) {
                DayModeSelector(state.preferences.dayCount, onDays,
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onNavigate(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_period)) }
                    Text(period, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = onToday) { Text(stringResource(R.string.today)) }
                    IconButton(onClick = { onNavigate(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_period)) }
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.error != null) ErrorMessage(state.error, onRetry)
            when {
                matrix.rows.isNotEmpty() -> CalendarMatrix(matrix, state.zone, onEvent, Modifier.weight(1f))
                !state.loading && state.discovered -> Column(Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.no_selected_calendars), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = onSettings) { Text(stringResource(R.string.open_settings)) }
                }
            }
        }
    }
}

/** One safe-inset-aware row; unusually narrow windows or large fonts can scroll horizontally. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LandscapeToolbar(selectedDays: Int, period: String, onSettings: () -> Unit,
                             onDays: (Int) -> Unit, onNavigate: (Int) -> Unit, onToday: () -> Unit) {
    val scroll = rememberScrollState()
    val fontScale = LocalConfiguration.current.fontScale.coerceAtLeast(1f)
    Surface {
        BoxWithConstraints(Modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets)) {
            Row(Modifier.horizontalScroll(scroll).widthIn(min = maxWidth).heightIn(min = 56.dp)
                .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Spacer(Modifier.width(12.dp))
                DayModeSelector(selectedDays, onDays, Modifier.width(216.dp * fontScale), compact = true)
                Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                IconButton(onClick = { onNavigate(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_period)) }
                Text(period, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                TextButton(onClick = onToday) { Text(stringResource(R.string.today), maxLines = 1) }
                IconButton(onClick = { onNavigate(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_period)) }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayModeSelector(selected: Int, onDays: (Int) -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    SingleChoiceSegmentedButtonRow(modifier) {
        listOf(1, 2, 7).forEachIndexed { index, days ->
            SegmentedButton(selected = selected == days, onClick = { onDays(days) },
                shape = SegmentedButtonDefaults.itemShape(index, 3),
                icon = { if (!compact) SegmentedButtonDefaults.Icon(active = selected == days) }) {
                Text(stringResource(when (days) { 1 -> R.string.one_day; 2 -> R.string.two_days; else -> R.string.seven_days }),
                    style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                    maxLines = if (compact) 1 else Int.MAX_VALUE)
            }
        }
    }
}

@Composable
fun ErrorMessage(error: LoadError, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(stringResource(if (error == LoadError.PREFERENCES) R.string.preferences_error else R.string.provider_error),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}
