// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import se.gangefors.moto.core.Gravel
import kotlin.math.roundToInt
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material3.Slider
import java.time.ZoneId
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.TimePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog

/**
 * The route between the two long-pressed points: its figures (or that it
 * is being found), and the extra time the rider gives it for favourites.
 * Choosing another budget, or allowing gravel roads, finds the route
 * again; Share hands it to a nav app as GPX; the cross clears it. When not
 * [expanded], only the figures show.
 */
@Composable
fun RouteCard(
    summary: RouteSummary?,
    budgetPercent: Int,
    onBudget: (Int) -> Unit,
    gravel: Gravel,
    onGravel: (Gravel) -> Unit,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    viaCount: Int,
    onAddVia: () -> Unit,
    onClearVia: () -> Unit,
    arriveBy: Long?,
    arrivalNote: String?,
    onArriveBy: (Long?) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 8.dp)) {
            CardHeader(
                summary,
                stringResource(R.string.route_computing),
                expanded,
                onToggleExpanded,
                onClose,
                stringResource(R.string.route_close),
            )
            if (expanded) {
                RouteCardDetails(
                    budgetPercent, onBudget, gravel, onGravel, onShare, onSave, summary != null,
                    viaCount, onAddVia, onClearVia, arriveBy, arrivalNote, onArriveBy,
                )
            }
        }
    }
}

/** The route card's choices: via points, extra time, gravel and sharing. */
@Composable
private fun RouteCardDetails(
    budgetPercent: Int,
    onBudget: (Int) -> Unit,
    gravel: Gravel,
    onGravel: (Gravel) -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    shareEnabled: Boolean,
    viaCount: Int,
    onAddVia: () -> Unit,
    onClearVia: () -> Unit,
    arriveBy: Long?,
    arrivalNote: String?,
    onArriveBy: (Long?) -> Unit,
) {
    var pickingTime by remember { mutableStateOf(false) }
    val zone = remember { ZoneId.systemDefault() }
    Column {
        arrivalNote?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onAddVia, enabled = viaCount < MAX_VIA_POINTS) {
                OneLine(stringResource(R.string.route_add_via))
            }
            if (viaCount > 0) {
                TextButton(onClick = onClearVia) {
                    OneLine(pluralStringResource(R.plurals.route_clear_via, viaCount, viaCount))
                }
            }
        }
        // Extra time as a slider (0 = fastest); a set arrival replaces it.
        StepSlider(
            title = stringResource(R.string.route_budget),
            steps = BUDGET_CHOICES,
            value = budgetPercent,
            label = { percent ->
                if (percent == 0) {
                    stringResource(R.string.route_budget_fastest)
                } else {
                    stringResource(R.string.route_budget_extra, percent)
                }
            },
            onCommit = { percent ->
                onArriveBy(null)
                onBudget(percent)
            },
            dimmed = arriveBy != null,
        ) {
            FilterChip(
                selected = arriveBy != null,
                onClick = { pickingTime = true },
                label = {
                    OneLine(
                        arriveBy?.let { stringResource(R.string.route_arrive_by_time, clockTime(it, zone)) }
                            ?: stringResource(R.string.route_arrive_by),
                    )
                },
            )
        }
        GravelAndShare(gravel, onGravel, onShare, onSave, shareEnabled = shareEnabled)
        if (pickingTime) {
            ArriveByDialog(
                initial = arriveBy,
                zone = zone,
                onDismiss = { pickingTime = false },
                onPick = { at ->
                    pickingTime = false
                    onArriveBy(at)
                },
            )
        }
    }
}

/**
 * Round trips from a start (PRD R7): the loop shown and which of the
 * alternatives it is, Next loop to flip through them, and the length the
 * rider wants. Choosing another length, or allowing gravel, finds the
 * loops again; Share hands the loop shown to a nav app; the cross clears
 * them; Shuffle finds another set, and a direction makes them head that
 * way. When not [expanded], only the figures show.
 */
@Composable
fun LoopCard(
    summary: RouteSummary?,
    position: Int,
    count: Int,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    direction: LoopDirection,
    onDirection: (LoopDirection) -> Unit,
    choice: LoopChoice,
    onChoice: (LoopChoice) -> Unit,
    gravel: Gravel,
    onGravel: (Gravel) -> Unit,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 8.dp)) {
            CardHeader(
                summary,
                stringResource(R.string.loop_computing),
                expanded,
                onToggleExpanded,
                onClose,
                stringResource(R.string.loop_close),
            )
            if (expanded) {
                LoopCardDetails(
                    found = summary != null,
                    position = position,
                    count = count,
                    onNext = onNext,
                    onShuffle = onShuffle,
                    direction = direction,
                    onDirection = onDirection,
                    choice = choice,
                    onChoice = onChoice,
                    gravel = gravel,
                    onGravel = onGravel,
                    onShare = onShare,
                    onSave = onSave,
                )
            }
        }
    }
}

/** The loop card's choices: which loop, the length, gravel and sharing. */
@Composable
private fun LoopCardDetails(
    found: Boolean,
    position: Int,
    count: Int,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    direction: LoopDirection,
    onDirection: (LoopDirection) -> Unit,
    choice: LoopChoice,
    onChoice: (LoopChoice) -> Unit,
    gravel: Gravel,
    onGravel: (Gravel) -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
) {
    Column {
        // Which loop, the next one, and a new set of loops (Shuffle). While
        // loops are being found, the row stays with the last figures and
        // its buttons disabled, so the card doesn't move.
        val last = remember { mutableStateOf(position to count) }
        SideEffect { if (found) last.value = position to count }
        val (shownPosition, shownCount) = if (found) position to count else last.value
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (shownCount > 1) {
                Text(
                    stringResource(R.string.loop_position, shownPosition + 1, shownCount),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = if (found) Modifier else Modifier.alpha(0.5f),
                )
                OutlinedButton(onClick = onNext, enabled = found) { OneLine(stringResource(R.string.loop_next)) }
            }
            OutlinedButton(onClick = onShuffle, enabled = found) { OneLine(stringResource(R.string.loop_shuffle)) }
        }
        // Length as a slider, in hours or kilometres.
        StepSlider(
            title = stringResource(R.string.loop_length),
            steps = loopSteps(choice),
            value = choice,
            label = { loopLengthText(it) },
            onCommit = onChoice,
        ) {
            val hours = choice is LoopChoice.Minutes
            FilterChip(
                selected = hours,
                onClick = { if (!hours) onChoice(switchUnit(choice)) },
                label = { OneLine(stringResource(R.string.loop_unit_hours)) },
            )
            FilterChip(
                selected = !hours,
                onClick = { if (hours) onChoice(switchUnit(choice)) },
                label = { OneLine(stringResource(R.string.loop_unit_km)) },
            )
        }
        Text(
            stringResource(R.string.loop_direction),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LoopDirection.entries.forEach { d ->
                FilterChip(
                    selected = d == direction,
                    onClick = { onDirection(d) },
                    label = {
                        OneLine(
                            stringResource(
                                when (d) {
                                    LoopDirection.ANY -> R.string.loop_direction_any
                                    LoopDirection.NORTH -> R.string.loop_direction_north
                                    LoopDirection.EAST -> R.string.loop_direction_east
                                    LoopDirection.SOUTH -> R.string.loop_direction_south
                                    LoopDirection.WEST -> R.string.loop_direction_west
                                },
                            ),
                        )
                    },
                )
            }
        }
        GravelAndShare(gravel, onGravel, onShare, onSave, shareEnabled = found)
    }
}

/**
 * A card's top line: the route's figures, a chevron to show or hide the
 * rest of the card (tapping the figures does the same), and the cross.
 * Collapsed, only this line shows, so the card covers little of the map.
 */
@Composable
private fun CardHeader(
    summary: RouteSummary?,
    computing: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onClose: () -> Unit,
    closeDescription: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RouteFigures(summary, computing, Modifier.weight(1f).clickable(onClick = onToggleExpanded))
        IconButton(onClick = onToggleExpanded) {
            Icon(
                painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                contentDescription = stringResource(if (expanded) R.string.card_collapse else R.string.card_expand),
            )
        }
        IconButton(onClick = onClose) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = closeDescription)
        }
    }
}

/**
 * A route's figures: distance and time, then what it is worth. While a
 * new route is being found, the last figures stay, dimmed, so the card
 * keeps its size; [computing] shows only before the first one. The details
 * always take (at least) two lines, so nothing below moves when a value
 * changes.
 */
@Composable
private fun RouteFigures(summary: RouteSummary?, computing: String, modifier: Modifier = Modifier) {
    val last = remember { mutableStateOf(summary) }
    SideEffect { if (summary != null) last.value = summary }
    val shown = summary ?: last.value
    val dim = if (summary == null) Modifier.alpha(0.5f) else Modifier
    Column(modifier.padding(top = 8.dp).then(dim)) {
        Text(
            if (shown == null) computing else stringResource(R.string.route_summary, shown.km, shown.minutes),
            style = MaterialTheme.typography.titleMedium,
        )
        val details = if (shown == null) {
            emptyList()
        } else {
            buildList {
                if (shown.extraMinutes > 0) add(stringResource(R.string.route_extra, shown.extraMinutes))
                if (shown.favouritePercent > 0) {
                    add(stringResource(R.string.route_on_favourites, shown.favouritePercent))
                }
                if (shown.curvyPercent > 0) add(stringResource(R.string.route_curvy, shown.curvyPercent))
                if (shown.gravelKm > 0.0) add(stringResource(R.string.route_gravel, shown.gravelKm))
            }
        }
        Text(
            details.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 2,
        )
    }
}

/** The gravel choice (the same setting as in My data: changing it here
 * routes again, to see what gravel roads change), then Save and Share. */
@Composable
private fun GravelAndShare(
    gravel: Gravel,
    onGravel: (Gravel) -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    shareEnabled: Boolean,
) {
    Column {
        Text(
            stringResource(R.string.route_gravel_label),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        GravelChips(gravel, onGravel)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onSave,
                enabled = shareEnabled,
            ) { OneLine(stringResource(R.string.route_save)) }
            OutlinedButton(
                onClick = onShare,
                enabled = shareEnabled,
            ) { OneLine(stringResource(R.string.route_share)) }
        }
    }
}

/** Avoid / Allow / Prefer gravel, one of them selected. */
@Composable
fun GravelChips(gravel: Gravel, onGravel: (Gravel) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GRAVEL_CHOICES.forEach { g ->
            FilterChip(
                selected = g == gravel,
                onClick = { onGravel(g) },
                label = {
                    OneLine(
                        stringResource(
                            when (g) {
                                Gravel.AVOID -> R.string.gravel_avoid
                                Gravel.ALLOW -> R.string.gravel_allow
                                Gravel.PREFER -> R.string.gravel_prefer
                            },
                        ),
                    )
                },
            )
        }
    }
}

/** Picks the time to arrive by: the next time the clock shows it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArriveByDialog(initial: Long?, zone: ZoneId, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val start = remember {
        java.time.Instant.ofEpochSecond(initial ?: (System.currentTimeMillis() / 1000 + 2 * 3600)).atZone(zone)
    }
    val state = rememberTimePickerState(start.hour, start.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.route_arrive_title)) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = {
                onPick(nextTimeOfDay(System.currentTimeMillis() / 1000, zone, state.hour, state.minute))
            }) { OneLine(stringResource(R.string.route_arrive_set)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { OneLine(stringResource(R.string.cancel)) } },
    )
}

/**
 * A title, the value as text and a slider over [steps] below it, with
 * [trailing] (e.g. a chip) at the end of the title row. The text follows
 * the thumb while dragging; [onCommit] runs once, when the thumb is let
 * go, so the route is found again only then. [dimmed] shows the value as
 * not in use (e.g. while an arrival time is set).
 */
@Composable
fun <T> StepSlider(
    title: String,
    steps: List<T>,
    value: T,
    label: @Composable (T) -> String,
    onCommit: (T) -> Unit,
    dimmed: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    val start = steps.indexOf(value).coerceAtLeast(0)
    var position by remember(steps, value) { mutableFloatStateOf(start.toFloat()) }
    val at = steps[position.roundToInt().coerceIn(0, steps.lastIndex)]
    Column(Modifier.padding(top = 4.dp, end = 8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(
                label(at),
                style = MaterialTheme.typography.titleSmall,
                color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            trailing()
        }
        Slider(
            value = position,
            onValueChange = { position = it },
            onValueChangeFinished = {
                val picked = steps[position.roundToInt().coerceIn(0, steps.lastIndex)]
                if (picked != value || dimmed) onCommit(picked)
            },
            valueRange = 0f..steps.lastIndex.coerceAtLeast(1).toFloat(),
            steps = (steps.size - 2).coerceAtLeast(0),
        )
    }
}

/** A loop length as text: "2 h 30 min", "45 min" or "120 km". */
@Composable
private fun loopLengthText(c: LoopChoice): String = when (c) {
    is LoopChoice.Km -> stringResource(R.string.loop_km, c.km)
    is LoopChoice.Minutes -> when {
        c.minutes < 60 -> stringResource(R.string.loop_minutes, c.minutes)
        c.minutes % 60 == 0 -> stringResource(R.string.loop_hours, c.minutes / 60)
        else -> stringResource(R.string.loop_hours_minutes, c.minutes / 60, c.minutes % 60)
    }
}
