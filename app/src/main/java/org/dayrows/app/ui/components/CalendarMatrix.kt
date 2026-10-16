// SPDX-License-Identifier: GPL-3.0-or-later
package org.dayrows.app.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import org.dayrows.app.R
import org.dayrows.app.data.EventInstance
import org.dayrows.app.domain.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
fun CalendarMatrix(matrix: CalendarMatrix, zone: ZoneId, onEvent: (EventInstance) -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale) { DateTimeFormatter.ofPattern("EEE\nd MMM", locale) }
    BoxWithConstraints(modifier) {
        val nameWidth = (maxWidth * .28f).coerceIn(88.dp, 140.dp)
        val dayWidth = if (matrix.dates.size == 7) 164.dp else ((maxWidth - nameWidth) / matrix.dates.size).coerceAtLeast(110.dp)
        Column {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
                Box(Modifier.width(nameWidth).heightIn(min = 60.dp).padding(10.dp), contentAlignment = Alignment.CenterStart) {
                    Text(stringResource(R.string.calendar), style = MaterialTheme.typography.labelLarge)
                }
                Row(Modifier.horizontalScroll(scroll)) {
                    matrix.dates.forEach { date ->
                        Box(Modifier.width(dayWidth).heightIn(min = 60.dp).padding(8.dp)) {
                            Text(dateFormat.format(date), style = MaterialTheme.typography.labelLarge,
                                color = if (date == LocalDate.now(zone)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize()) {
                items(matrix.rows, key = { it.calendar.key }) { row ->
                    // Intrinsic height lets the fixed name and scrollable cells share the same row height.
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        Column(Modifier.width(nameWidth).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(10.dp).background(Color(row.calendar.color), CircleShape)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                            Text(row.label.ifBlank { stringResource(R.string.unnamed_calendar) }, style = MaterialTheme.typography.labelLarge)
                        }
                        Row(Modifier.horizontalScroll(scroll).height(IntrinsicSize.Min)) {
                            row.cells.forEach { events ->
                                Row(Modifier.width(dayWidth).fillMaxHeight()) {
                                    VerticalDivider()
                                    Column(Modifier.weight(1f).padding(horizontal = 7.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        if (events.isEmpty()) Text("—", Modifier.padding(6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        events.forEach { event ->
                                            EventCell(event, zone, onEvent)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun EventCell(event: MatrixEvent, zone: ZoneId, onEvent: (EventInstance) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    Column(Modifier.fillMaxWidth().clickable { onEvent(event.instance) }.padding(horizontal = 3.dp, vertical = 5.dp)) {
        Text(if (event.instance.allDay) stringResource(R.string.all_day) else event.timeLabel(zone, locale),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(event.instance.displayTitle(stringResource(R.string.untitled_event)),
            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
