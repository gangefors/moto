// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.TimePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * A sheet at the bottom of the map for planning a route or a loop (the
 * map shows what it plans above it). At rest it shows [header] only: the
 * figures, the actions and a line that sums up the choices. Pulled up
 * (drag the handle or the header up, or tap the handle or the summary
 * line), it shows [details] too, the choices themselves. In both states
 * everything below the handle scrolls when it doesn't fit in [maxHeight]
 * (large fonts); a drag the content can't scroll any further moves the
 * sheet instead, like Android's own sheets: up opens it, down from the
 * top puts it to rest. The handle always drags it; Back puts it to rest.
 */
@Composable
fun PlanSheet(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    header: @Composable ColumnScope.() -> Unit,
    details: @Composable ColumnScope.() -> Unit,
) {
    val threshold = with(LocalDensity.current) { SHEET_DRAG.toPx() }
    var dragged by remember { mutableFloatStateOf(0f) }
    val drag = rememberDraggableState { dragged += it }
    BackHandler(enabled = expanded) { onExpandedChange(false) }
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(max = maxHeight),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
        ) {
            val handleDrag = Modifier.draggable(
                state = drag,
                orientation = Orientation.Vertical,
                onDragStarted = { dragged = 0f },
                onDragStopped = {
                    if (dragged < -threshold) onExpandedChange(true)
                    if (dragged > threshold) onExpandedChange(false)
                },
            )
            val handleLabel = stringResource(if (expanded) R.string.card_collapse else R.string.card_expand)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(SHEET_HANDLE_HEIGHT)
                    .then(handleDrag)
                    .clickable(onClickLabel = handleLabel) { onExpandedChange(!expanded) }
                    .semantics { contentDescription = handleLabel },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(2.dp)),
                )
            }
            // What the content can't scroll any further moves the sheet;
            // the gesture's end (its fling) decides, like the handle's.
            val overscroll = remember(onExpandedChange) {
                object : NestedScrollConnection {
                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                        if (source == NestedScrollSource.UserInput) dragged += available.y
                        return Offset.Zero
                    }

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        if (dragged < -threshold) onExpandedChange(true)
                        if (dragged > threshold) onExpandedChange(false)
                        dragged = 0f
                        return Velocity.Zero
                    }
                }
            }
            val scroll = rememberScrollState()
            LaunchedEffect(expanded) { if (!expanded) scroll.scrollTo(0) }
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .nestedScroll(overscroll)
                    .verticalScroll(scroll),
            ) {
                header()
                if (expanded) {
                    HorizontalDivider(Modifier.padding(top = 8.dp, end = 8.dp, bottom = 8.dp))
                    details()
                }
            }
        }
    }
}

/** Height of the sheet's handle, the part that drags it at any time. */
private val SHEET_HANDLE_HEIGHT = 32.dp

/** How far the sheet must be dragged to open or put it to rest. */
private val SHEET_DRAG = 24.dp

/**
 * The route between the two long-pressed points: its figures (or that it
 * is being found), Save, Share and the cross, and which of the routes to
 * choose from it is; at rest a line with the arrival, via points and
 * gravel; pulled up, the choices: via points, a time to arrive by, and
 * gravel. Any choice finds the routes again.
 */
@Composable
fun RouteCard(
    summary: RouteSummary?,
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
    position: Int,
    count: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    PlanSheet(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        maxHeight = maxHeight,
        modifier = modifier,
        header = {
            SheetTop(
                summary = summary,
                computing = stringResource(R.string.route_computing),
                found = summary != null,
                onSave = onSave,
                onShare = onShare,
                onClose = onClose,
                closeDescription = stringResource(R.string.route_close),
            )
            ChoiceSwitcher(
                found = summary != null,
                failed = false,
                position = position,
                count = count,
                onPrevious = onPrevious,
                onNext = onNext,
                previousDescription = stringResource(R.string.route_previous),
                nextDescription = stringResource(R.string.route_next),
            )
            if (!expanded) {
                val parts = buildList {
                    arrivalNote?.let { add(it) }
                    if (viaCount > 0) add(pluralStringResource(R.plurals.route_via_count, viaCount, viaCount))
                    add(gravelSummary(gravel))
                }
                SummaryLine(parts.joinToString(" · ")) { onExpandedChange(true) }
            }
        },
        details = {
            RouteCardDetails(
                gravel, onGravel,
                viaCount, onAddVia, onClearVia, arriveBy, arrivalNote, onArriveBy,
            )
        },
    )
}

/** The route's choices: via points, a time to arrive by, and gravel. */
@Composable
private fun RouteCardDetails(
    gravel: Gravel,
    onGravel: (Gravel) -> Unit,
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
                TextButton(
                    onClick = onClearVia,
                    colors = ButtonDefaults.textButtonColors(contentColor = DELETE_COLOR),
                ) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    OneLine(pluralStringResource(R.plurals.route_clear_via, viaCount, viaCount))
                }
            }
        }
        // A time to arrive by: the routes then spend the time until then.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
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
            if (arriveBy != null) {
                IconButton(onClick = { onArriveBy(null) }) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.route_arrive_clear))
                }
            }
        }
        GravelChoice(gravel, onGravel)
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
 * Round trips from a start (PRD R7): the loop shown (or that loops are
 * being found, or why none were), Save, Share and the cross; which of the
 * set it is, with the previous and next ones, and Shuffle for another
 * set; at rest a line with the length, direction and gravel; pulled up,
 * those choices. Any choice finds the loops again. The other loops of
 * the set are drawn faint on the map; tapping one shows it.
 */
@Composable
fun LoopCard(
    summary: RouteSummary?,
    problem: String?,
    position: Int,
    count: Int,
    onPrevious: () -> Unit,
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
    onExpandedChange: (Boolean) -> Unit,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val found = summary != null
    PlanSheet(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        maxHeight = maxHeight,
        modifier = modifier,
        header = {
            SheetTop(
                summary = summary,
                computing = stringResource(R.string.loop_computing),
                problem = problem,
                found = found,
                onSave = onSave,
                onShare = onShare,
                onClose = onClose,
                closeDescription = stringResource(R.string.loop_close),
            )
            ChoiceSwitcher(
                found = found,
                failed = problem != null,
                position = position,
                count = count,
                onPrevious = onPrevious,
                onNext = onNext,
                previousDescription = stringResource(R.string.loop_previous),
                nextDescription = stringResource(R.string.loop_next),
                onShuffle = onShuffle,
            )
            if (!expanded) {
                val heading = stringResource(
                    when (direction) {
                        LoopDirection.ANY -> R.string.loop_heading_any
                        LoopDirection.NORTH -> R.string.loop_heading_north
                        LoopDirection.EAST -> R.string.loop_heading_east
                        LoopDirection.SOUTH -> R.string.loop_heading_south
                        LoopDirection.WEST -> R.string.loop_heading_west
                    },
                )
                SummaryLine(listOf(loopLengthText(choice), heading, gravelSummary(gravel)).joinToString(" · ")) {
                    onExpandedChange(true)
                }
            }
        },
        details = { LoopCardDetails(direction, onDirection, choice, onChoice, gravel, onGravel) },
    )
}

/**
 * Which of the routes or loops to choose from is shown, with the previous
 * and next ones, and for loops Shuffle ([onShuffle]) for another set.
 * While they are being found the row keeps the last figures, its buttons
 * off, so the sheet doesn't move; when none were found ([failed]) Shuffle
 * still works. With Shuffle the count always shows, 1 / 1 too, so the
 * button never moves. Nothing to choose from and no Shuffle: no row.
 */
@Composable
private fun ChoiceSwitcher(
    found: Boolean,
    failed: Boolean,
    position: Int,
    count: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    previousDescription: String,
    nextDescription: String,
    onShuffle: (() -> Unit)? = null,
) {
    val last = remember { mutableStateOf(position to count) }
    SideEffect { if (found) last.value = position to count }
    // None found: 0 / 0, its buttons off.
    val (shownPosition, shownCount) = when {
        found -> position to count
        failed -> -1 to 0
        else -> last.value
    }
    if (shownCount <= 1 && !failed && onShuffle == null) return
    FlowRow(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (shownCount > 1 || failed || onShuffle != null) {
            val switchable = found && shownCount > 1
            Row(
                Modifier
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .alpha(if (found) 1f else 0.5f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrevious, enabled = switchable) {
                    Icon(painterResource(R.drawable.ic_chevron_left), previousDescription)
                }
                Text(
                    stringResource(
                        R.string.loop_count,
                        if (shownCount == 0) 0 else shownPosition + 1,
                        shownCount,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                IconButton(onClick = onNext, enabled = switchable) {
                    Icon(painterResource(R.drawable.ic_chevron_right), nextDescription)
                }
            }
        }
        if (onShuffle != null) {
            FilledTonalButton(onClick = onShuffle, enabled = found || failed) {
                Icon(painterResource(R.drawable.ic_shuffle), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                OneLine(stringResource(R.string.loop_shuffle))
            }
        }
    }
}

/** The loop's choices: length, direction and gravel. */
@Composable
private fun LoopCardDetails(
    direction: LoopDirection,
    onDirection: (LoopDirection) -> Unit,
    choice: LoopChoice,
    onChoice: (LoopChoice) -> Unit,
    gravel: Gravel,
    onGravel: (Gravel) -> Unit,
) {
    Column {
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
        GravelChoice(gravel, onGravel)
    }
}

/**
 * The sheet's top: the distance and time (or that it is being found, or
 * [problem]: why nothing was), then Save, Share (both only once something
 * is found) and the cross, as icons in every state of the sheet; below,
 * across the whole width, what the route is worth (or the problem). While
 * a new route is being found the last figures stay, dimmed, so the sheet
 * keeps its size; [computing] shows only before the first one.
 */
@Composable
private fun SheetTop(
    summary: RouteSummary?,
    computing: String,
    found: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit,
    closeDescription: String,
    problem: String? = null,
) {
    val last = remember { mutableStateOf(summary) }
    SideEffect { if (summary != null) last.value = summary }
    val shown = summary ?: last.value
    val dim = if (summary == null && problem == null) Modifier.alpha(0.5f) else Modifier
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                problem != null -> stringResource(R.string.loop_none_title)
                shown == null -> computing
                else -> stringResource(R.string.route_summary, shown.km, shown.minutes)
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).then(dim),
        )
        IconButton(onClick = onSave, enabled = found) {
            Icon(painterResource(R.drawable.ic_bookmark), stringResource(R.string.route_save))
        }
        IconButton(onClick = onShare, enabled = found) {
            Icon(painterResource(R.drawable.ic_share), stringResource(R.string.route_share))
        }
        IconButton(onClick = onClose) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = closeDescription)
        }
    }
    val details = when {
        problem != null -> problem
        shown == null -> ""
        else -> buildList {
            if (shown.fastest) add(stringResource(R.string.route_fastest))
            if (shown.extraMinutes > 0) add(stringResource(R.string.route_extra, shown.extraMinutes))
            if (shown.favouritePercent > 0) {
                add(stringResource(R.string.route_on_favourites, shown.favouritePercent))
            }
            if (shown.curvyPercent > 0) add(stringResource(R.string.route_curvy, shown.curvyPercent))
            if (shown.gravelKm > 0.0) add(stringResource(R.string.route_gravel, shown.gravelKm))
        }.joinToString(" · ")
    }
    Text(
        details,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(end = 8.dp).then(dim),
    )
}

/** The choices summed up in one line; tapping it pulls the sheet up. */
@Composable
private fun SummaryLine(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.padding(top = 8.dp, end = 8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                painterResource(R.drawable.ic_expand_less),
                contentDescription = stringResource(R.string.card_expand),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Avoid gravel", "Gravel allowed" or "Prefer gravel". */
@Composable
private fun gravelSummary(g: Gravel): String = stringResource(
    when (g) {
        Gravel.AVOID -> R.string.gravel_summary_avoid
        Gravel.ALLOW -> R.string.gravel_summary_allow
        Gravel.PREFER -> R.string.gravel_summary_prefer
    },
)

/** The gravel choice (the same setting as in My data: changing it here
 * routes again, to see what gravel roads change). */
@Composable
private fun GravelChoice(gravel: Gravel, onGravel: (Gravel) -> Unit) {
    Column {
        Text(
            stringResource(R.string.route_gravel_label),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        GravelChips(gravel, onGravel)
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
