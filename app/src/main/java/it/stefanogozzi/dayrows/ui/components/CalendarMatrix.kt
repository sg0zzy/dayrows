// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.Role
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

private val headerShape = RoundedCornerShape(8.dp)
private val dateButtonMinHeight = 48.dp /* Material minimum interactive touch target */

@Composable
fun CalendarMatrix(matrix: CalendarMatrix, zone: ZoneId, onEvent: (EventInstance) -> Unit,
                   onNewEvent: (LocalDate) -> Unit, onCalendarName: (DeviceCalendar) -> Unit,
                   onToday: () -> Unit, onNavigate: (Int) -> Unit,
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
    } + 16.dp /* cell padding */ + 20.dp /* the date button's own padding */
    BoxWithConstraints(modifier) {
        val nameWidth = (maxWidth * .28f).coerceIn(88.dp, 140.dp)
        val dayWidth = (if (matrix.dates.size == 7) 164.dp else (maxWidth - nameWidth) / matrix.dates.size)
            .coerceAtLeast(maxOf(110.dp, minimumHeaderWidth))
        // The period arrows are gone: swiping moves the period. The day columns keep their own
        // horizontal scroll, so a swipe only changes the period when that scroll has nothing
        // left to give in the swipe direction: forward from the right edge, back from the left
        // edge. The observer runs in the Initial pass and never consumes, so the columns keep
        // scrolling exactly as before and the two gestures cannot fight each other.
        // TalkBack reaches both directions as custom accessibility actions, because a touch
        // gesture must never be the only way to change the period.
        val previousLabel = stringResource(R.string.previous_period)
        val nextLabel = stringResource(R.string.next_period)
        val periodGestures = Modifier
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(previousLabel) { onNavigate(-1); true },
                    CustomAccessibilityAction(nextLabel) { onNavigate(1); true },
                )
            }
            .pointerInput(scroll.maxValue, onNavigate) {
                val threshold = 48.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val startX = down.position.x
                    // Sampled once per gesture: the columns must already be at the edge the
                    // swipe is moving away from, otherwise the drag belongs to them.
                    val atStart = scroll.value <= 0
                    val atEnd = scroll.value >= scroll.maxValue
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            periodSwipeDirection(change.position.x - startX, threshold, atStart, atEnd)
                                ?.let(onNavigate)
                            break
                        }
                    }
                }
            }
        Column(periodGestures) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
                // The corner cell used to read "Calendar"; it now carries the way back to today,
                // which is the one date action the removed period row still needs to offer.
                Box(Modifier.width(nameWidth).heightIn(min = 56.dp).padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center) {
                    IconButton(onClick = onToday) {
                        Icon(Icons.Default.Home, stringResource(R.string.today))
                    }
                }
                Row(Modifier.horizontalScroll(scroll)) {
                    matrix.dates.forEach { date ->
                        // The new-event action belongs to the day, not to a calendar row: no receiving
                        // app guarantees it will use the calendar DayRows would name, so the action
                        // promises only the day. It now sits on the date itself, drawn as a button, so
                        // the header carries the gesture it performs and no calendar cell does. Today
                        // keeps the primary text color; the button outline and press ripple, not the
                        // accent, carry the touch affordance.
                        val description = stringResource(R.string.add_event_on_date, dateFormat.format(date))
                        Box(Modifier.width(dayWidth).heightIn(min = 56.dp).padding(horizontal = 8.dp),
                            contentAlignment = Alignment.CenterStart) {
                            Box(Modifier.heightIn(min = dateButtonMinHeight).border(1.dp, MaterialTheme.colorScheme.outlineVariant, headerShape)
                                .clip(headerShape).clickable(role = Role.Button) { onNewEvent(date) }
                                .semantics { contentDescription = description },
                                contentAlignment = Alignment.Center) {
                                Text(dateFormat.format(date), Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    style = headerStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (date == LocalDate.now(zone)) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface)
                            }
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
                            row.cells.forEach { events ->
                                Row(Modifier.width(dayWidth).fillMaxHeight()) {
                                    VerticalDivider()
                                    // An empty cell only says the day is free. The new-event marker lives in
                                    // the day header, so a row never offers an action its calendar might not
                                    // be used for, and every row reads the same way.
                                    Column(Modifier.weight(1f).heightIn(min = 48.dp)
                                        .padding(horizontal = 7.dp, vertical = 6.dp),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        if (events.isEmpty()) {
                                            Text("—", Modifier.padding(6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/**
 * Which period step a finished horizontal swipe asks for, or null when the gesture belongs to
 * the day columns instead.
 *
 * [travel] is the signed distance from touch-down to release (positive = rightwards),
 * [atStart]/[atEnd] say whether the columns were already at the left/right edge when the
 * gesture began. A swipe away from an edge the columns were already sitting on is period
 * travel; anything else is the columns scrolling, so it must not also change the period.
 */
internal fun periodSwipeDirection(travel: Float, threshold: Float, atStart: Boolean, atEnd: Boolean): Int? = when {
    travel <= -threshold && atEnd -> 1      // swipe left: forward one period
    travel >= threshold && atStart -> -1    // swipe right: back one period
    else -> null
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
