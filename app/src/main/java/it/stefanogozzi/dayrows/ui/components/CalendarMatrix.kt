// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.ui.components

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import it.stefanogozzi.dayrows.R
import it.stefanogozzi.dayrows.data.DeviceCalendar
import it.stefanogozzi.dayrows.data.EventInstance
import it.stefanogozzi.dayrows.domain.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
fun CalendarMatrix(matrix: CalendarMatrix, zone: ZoneId, onEvent: (EventInstance) -> Unit,
                   onNewEvent: (DeviceCalendar, LocalDate) -> Unit, onCalendarName: (DeviceCalendar) -> Unit,
                   modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale) { DateTimeFormatter.ofPattern("EEE d MMM", locale) }
    val headerStyle = MaterialTheme.typography.labelLarge
    val textMeasurer = rememberTextMeasurer()
    val minimumHeaderWidth = with(LocalDensity.current) {
        matrix.dates.maxOf { date ->
            textMeasurer.measure(dateFormat.format(date), style = headerStyle, maxLines = 1).size.width
        }.toDp()
    } + 16.dp
    BoxWithConstraints(modifier) {
        val nameWidth = (maxWidth * .28f).coerceIn(88.dp, 140.dp)
        val dayWidth = (if (matrix.dates.size == 7) 164.dp else (maxWidth - nameWidth) / matrix.dates.size)
            .coerceAtLeast(maxOf(110.dp, minimumHeaderWidth))
        Column {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
                Box(Modifier.width(nameWidth).heightIn(min = 36.dp).padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.CenterStart) {
                    Text(stringResource(R.string.calendar), style = headerStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.horizontalScroll(scroll)) {
                    matrix.dates.forEach { date ->
                        Box(Modifier.width(dayWidth).heightIn(min = 36.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                            contentAlignment = Alignment.CenterStart) {
                            Text(dateFormat.format(date), style = headerStyle, maxLines = 1,
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
                            val nameDescription = stringResource(R.string.edit_label_for_calendar,
                                row.label.ifBlank { stringResource(R.string.unnamed_calendar) })
                            Text(row.label.ifBlank { stringResource(R.string.unnamed_calendar) },
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.clickable { onCalendarName(row.calendar) }
                                    .semantics { contentDescription = nameDescription })
                        }
                        Row(Modifier.horizontalScroll(scroll).height(IntrinsicSize.Min)) {
                            row.cells.forEachIndexed { index, events ->
                                val date = matrix.dates[index]
                                val editable = row.calendar.isEditable()
                                val description = stringResource(R.string.add_event_on_date, dateFormat.format(date))
                                Row(Modifier.width(dayWidth).fillMaxHeight()) {
                                    VerticalDivider()
                                    // An empty cell of an editable calendar is one tap target; a read-only
                                    // calendar keeps the plain placeholder and no action.
                                    Column(Modifier.weight(1f)
                                        .then(if (events.isEmpty() && editable)
                                            Modifier.clickable { onNewEvent(row.calendar, date) }
                                                .semantics { contentDescription = description } else Modifier)
                                        .heightIn(min = 48.dp)
                                        .padding(horizontal = 7.dp, vertical = 6.dp),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        if (events.isEmpty()) {
                                            if (editable) {
                                                Text("+", Modifier.padding(6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            } else {
                                                Text("—", Modifier.padding(6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
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
