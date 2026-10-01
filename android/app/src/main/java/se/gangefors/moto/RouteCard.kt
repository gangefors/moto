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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import se.gangefors.moto.core.Avoid
import se.gangefors.moto.core.FavouritesMode
import se.gangefors.moto.core.Gravel
import se.gangefors.moto.core.UnriddenMode
import kotlin.math.roundToInt
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material3.Slider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.material3.SliderDefaults
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
 * line), it shows [details] too, the choices themselves, below a line;
 * the header stays fixed above it and only the choices scroll, unless the
 * header alone takes more than half the sheet (very large fonts). At rest,
 * or in that case, everything below the handle scrolls when it doesn't
 * fit in [maxHeight]. A drag the content can't scroll any further moves
 * the sheet instead, like Android's own sheets: up opens it, down from
 * the top puts it to rest. The handle and a fixed header always drag it;
 * Back puts it to rest.
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
        tonalElevation = PLAN_SHEET_ELEVATION,
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
            // Pulled up, everything down to the line stays put and only the
            // choices below it scroll (Stefan); unless the top alone would
            // take more than half the sheet (very large fonts), when it all
            // scrolls as one, as at rest, so the choices keep room.
            var headerPx by remember { mutableIntStateOf(0) }
            val roomPx = with(LocalDensity.current) { (maxHeight - SHEET_HANDLE_HEIGHT).toPx() }
            val fixedTop = expanded && fixesTop(headerPx, roomPx)
            val measured = Modifier.onSizeChanged { headerPx = it.height }
            val divider = @Composable { HorizontalDivider(Modifier.padding(top = 8.dp, end = 8.dp, bottom = 8.dp)) }
            if (fixedTop) {
                // The fixed top drags the sheet as the handle does.
                Column(handleDrag) {
                    Column(measured) { header() }
                    divider()
                }
            }
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .scrollHints(scroll)
                    .nestedScroll(overscroll)
                    .verticalScroll(scroll),
            ) {
                if (!fixedTop) {
                    Column(measured) { header() }
                    if (expanded) divider()
                }
                if (expanded) details()
            }
        }
    }
}

/** Height of the sheet's handle, the part that drags it at any time. */
private val SHEET_HANDLE_HEIGHT = 32.dp

/** The planning sheet's tonal elevation (its colour; the navigation bar's
 * buttons follow it). */
val PLAN_SHEET_ELEVATION = 3.dp

/** How far the sheet must be dragged to open or put it to rest. */
private val SHEET_DRAG = 24.dp

/**
 * The route between the two long-pressed points: its figures (or that it
 * is being found), Save, Share and the cross, and which of the routes to
 * choose from it is; at rest a line with the arrival, via points,
 * gravel and avoided favourites; pulled up, the choices: via points, a
 * time to arrive by, gravel, favourites and roads to avoid. Any choice
 * finds the routes again.
 */
@Composable
fun RouteCard(
    summary: RouteSummary?,
    gravel: Gravel,
    onGravel: (Gravel) -> Unit,
    favourites: FavouritesMode,
    onFavourites: (FavouritesMode) -> Unit,
    unridden: UnriddenMode,
    onUnridden: (UnriddenMode) -> Unit,
    avoid: Avoid,
    onAvoid: (Avoid) -> Unit,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    viaCount: Int,
    onAddVia: () -> Unit,
    onClearVia: () -> Unit,
    arriveBy: Long?,
    arrivalNote: String?,
    arrival: SummaryItem.ArrivesAt?,
    onArriveBy: (Long?) -> Unit,
    position: Int,
    count: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    kept: Int,
    problem: String?,
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
                problem = problem,
                problemTitle = stringResource(R.string.route_none_title),
                found = summary != null,
                onSave = onSave,
                onShare = onShare,
                onClose = onClose,
                closeDescription = stringResource(R.string.route_close),
                kept = kept,
            )
            ChoiceSwitcher(
                found = summary != null,
                failed = problem != null,
                position = position,
                count = count,
                onPrevious = onPrevious,
                onNext = onNext,
                previousDescription = stringResource(R.string.route_previous),
                nextDescription = stringResource(R.string.route_next),
                kept = kept,
            )
            if (!expanded) {
                OptionChips(routeSummaryItems(arrival, viaCount, gravel, favourites, unridden), allowedKinds(avoid)) { onExpandedChange(true) }
            }
        },
        details = {
            RouteCardDetails(
                gravel, onGravel, favourites, onFavourites, unridden, onUnridden, avoid, onAvoid,
                viaCount, onAddVia, onClearVia, arriveBy, arrivalNote, onArriveBy,
            )
        },
    )
}

/** The route's choices, one labelled row each: waypoints, a time to
 * arrive by, gravel, favourites and roads to avoid. */
@Composable
private fun RouteCardDetails(
    gravel: Gravel,
    onGravel: (Gravel) -> Unit,
    favourites: FavouritesMode,
    onFavourites: (FavouritesMode) -> Unit,
    unridden: UnriddenMode,
    onUnridden: (UnriddenMode) -> Unit,
    avoid: Avoid,
    onAvoid: (Avoid) -> Unit,
    viaCount: Int,
    onAddVia: () -> Unit,
    onClearVia: () -> Unit,
    arriveBy: Long?,
    arrivalNote: String?,
    onArriveBy: (Long?) -> Unit,
) {
    var pickingTime by remember { mutableStateOf(false) }
    val zone = remember { ZoneId.systemDefault() }
    Column(Modifier.padding(end = 8.dp)) {
        OptionHeading(stringResource(R.string.route_waypoints_title))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (viaCount > 0) {
                    pluralStringResource(R.plurals.route_via_count, viaCount, viaCount)
                } else {
                    stringResource(R.string.route_waypoints_none)
                },
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAddVia, enabled = viaCount < MAX_VIA_POINTS) {
                OneLine(stringResource(R.string.route_add_via))
            }
            if (viaCount > 1) {
                // Several go at once: a second tap confirms, like every bin
                // that deletes more than one thing.
                var confirming by remember(viaCount) { mutableStateOf(false) }
                DeleteButton(
                    confirming = confirming,
                    onArm = { confirming = true },
                    onDelete = {
                        confirming = false
                        onClearVia()
                    },
                )
            } else if (viaCount == 1) {
                IconButton(
                    onClick = onClearVia,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = DELETE_COLOR),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_delete),
                        contentDescription = pluralStringResource(R.plurals.route_clear_via, viaCount, viaCount),
                    )
                }
            }
        }
        // A time to arrive by: the routes then spend the time until then.
        OptionHeading(stringResource(R.string.route_arrive_heading))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (arriveBy == null) {
                Text(stringResource(R.string.route_arrive_none), Modifier.weight(1f))
                TextButton(onClick = { pickingTime = true }) { OneLine(stringResource(R.string.route_arrive_pick)) }
            } else {
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(onClickLabel = stringResource(R.string.route_arrive_pick)) { pickingTime = true },
                ) {
                    Text(clockTime(arriveBy, zone), style = MaterialTheme.typography.titleMedium)
                    arrivalNote?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = { onArriveBy(null) }) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.route_arrive_clear))
                }
            }
        }
        OptionHeading(stringResource(R.string.route_gravel_label), stringResource(R.string.routing_gravel_hint))
        GravelChips(gravel, onGravel)
        OptionHeading(stringResource(R.string.favourites_label), stringResource(R.string.favourites_hint))
        FavouritesChips(favourites, onFavourites)
        OptionHeading(stringResource(R.string.unridden_label), stringResource(R.string.unridden_hint))
        UnriddenChips(unridden, onUnridden)
        OptionHeading(stringResource(R.string.avoid_heading), stringResource(R.string.avoid_hint))
        AllowChips(avoid, onAvoid)
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
 * set; at rest a line with the length, direction, gravel and avoided
 * favourites; pulled up, those choices. Any choice finds the loops again. The other loops of
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
    kept: Int,
    direction: LoopDirection,
    onDirection: (LoopDirection) -> Unit,
    choice: LoopChoice,
    onChoice: (LoopChoice) -> Unit,
    gravel: Gravel,
    onGravel: (Gravel) -> Unit,
    favourites: FavouritesMode,
    onFavourites: (FavouritesMode) -> Unit,
    unridden: UnriddenMode,
    onUnridden: (UnriddenMode) -> Unit,
    avoid: Avoid,
    onAvoid: (Avoid) -> Unit,
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
                problemTitle = stringResource(R.string.loop_none_title),
                found = found,
                onSave = onSave,
                onShare = onShare,
                onClose = onClose,
                closeDescription = stringResource(R.string.loop_close),
                kept = kept,
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
                kept = kept,
                onShuffle = onShuffle,
            )
            if (!expanded) {
                OptionChips(loopSummaryItems(choice, direction, gravel, favourites, unridden), allowedKinds(avoid)) {
                    onExpandedChange(true)
                }
            }
        },
        details = {
            LoopCardDetails(direction, onDirection, choice, onChoice, gravel, onGravel, favourites, onFavourites, unridden, onUnridden, avoid, onAvoid)
        },
    )
}

/**
 * Which of the routes or loops to choose from is shown, with the previous
 * and next ones, and for loops Shuffle ([onShuffle]) for another set.
 * While they are being found the old count is gone (a new set is on its
 * way, Stefan): "– / –", dimmed, with the buttons off, where the set
 * before ([kept] choices) had the row, so nothing moves; when none were
 * found ([failed]) Shuffle still works. With Shuffle the count always
 * shows, 1 / 1 too, so the button never moves. Nothing to choose from
 * and no Shuffle: no row.
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
    kept: Int,
    onShuffle: (() -> Unit)? = null,
) {
    // None found: 0 / 0, its buttons off; finding: no count.
    val shown = choiceCount(found, failed, position, count)
    if (!showsSwitcher(shown, failed, kept, onShuffle != null)) return
    val shownCount = shown?.second ?: 0
    FlowRow(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        run {
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
                    if (shown == null) {
                        stringResource(R.string.loop_count_finding)
                    } else {
                        stringResource(R.string.loop_count, if (shownCount == 0) 0 else shown.first + 1, shownCount)
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                IconButton(onClick = onNext, enabled = switchable) {
                    Icon(painterResource(R.drawable.ic_chevron_right), nextDescription)
                }
            }
        }
        if (onShuffle != null) {
            // As tall as the switcher beside it.
            FilledTonalButton(onClick = onShuffle, enabled = found || failed, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(painterResource(R.drawable.ic_shuffle), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                OneLine(stringResource(R.string.loop_shuffle))
            }
        }
    }
}

/** The loop's choices, one labelled row each: length, direction, gravel,
 * favourites and roads to avoid. */
@Composable
private fun LoopCardDetails(
    direction: LoopDirection,
    onDirection: (LoopDirection) -> Unit,
    choice: LoopChoice,
    onChoice: (LoopChoice) -> Unit,
    gravel: Gravel,
    onGravel: (Gravel) -> Unit,
    favourites: FavouritesMode,
    onFavourites: (FavouritesMode) -> Unit,
    unridden: UnriddenMode,
    onUnridden: (UnriddenMode) -> Unit,
    avoid: Avoid,
    onAvoid: (Avoid) -> Unit,
) {
    Column(Modifier.padding(end = 8.dp)) {
        LoopLengthSlider(stringResource(R.string.loop_length), choice, onChoice)
        OptionHeading(stringResource(R.string.loop_direction), stringResource(R.string.loop_direction_hint))
        DirectionChips(direction, onDirection)
        OptionHeading(stringResource(R.string.route_gravel_label), stringResource(R.string.routing_gravel_hint))
        GravelChips(gravel, onGravel)
        OptionHeading(stringResource(R.string.favourites_label), stringResource(R.string.favourites_hint))
        FavouritesChips(favourites, onFavourites)
        OptionHeading(stringResource(R.string.unridden_label), stringResource(R.string.unridden_hint))
        UnriddenChips(unridden, onUnridden)
        OptionHeading(stringResource(R.string.avoid_heading), stringResource(R.string.avoid_hint))
        AllowChips(avoid, onAvoid)
    }
}

/**
 * The sheet's top: the distance and time (or that it is being found, or
 * [problem]: why nothing was), then Save, Share (both only once something
 * is found) and the cross, as icons in every state of the sheet; below,
 * across the whole width, what the route is worth (or the problem). While
 * a new route or loop is being found, [computing] shows, dimmed, and
 * the old figures are gone, so it is clear they no longer apply; when
 * [kept] (a setting or the end changed, so the sheet had results) the figures line
 * stays with dashes, so nothing moves (Stefan).
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
    kept: Int,
    problem: String? = null,
    problemTitle: String = "",
) {
    val shown = summary
    val finding = shown == null && problem == null
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The distance and the time on one line, in the largest of a few
        // sizes that fits; when not even the smallest does (very large
        // fonts), the time goes below the distance as a whole.
        val texts = when {
            problem != null -> listOf(problemTitle)
            shown == null -> listOf(computing)
            else -> listOf(noBreak(stringResource(R.string.route_km, shown.km)), noBreak(durationText(shown.minutes)))
        }
        val styles = listOf(
            MaterialTheme.typography.headlineSmall,
            MaterialTheme.typography.titleLarge,
            MaterialTheme.typography.titleMedium,
        )
        val measurer = rememberTextMeasurer()
        val gap = 12.dp
        val gapPx = with(LocalDensity.current) { gap.roundToPx() }
        BoxWithConstraints(Modifier.weight(1f).alpha(if (finding) 0.5f else 1f)) {
            val widths = styles.map { st ->
                texts.sumOf { measurer.measure(it, st, softWrap = false, maxLines = 1).size.width } + gapPx * (texts.size - 1)
            }
            val style = styles[firstFitting(widths, constraints.maxWidth) ?: styles.lastIndex]
            FlowRow(horizontalArrangement = Arrangement.spacedBy(gap)) {
                texts.forEach { Text(it, style = style) }
            }
        }
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
    if (problem != null) {
        Text(
            problem,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        return
    }
    if (shown == null) {
        if (kept > 0) FindingFigures()
        return
    }
    // What the route is worth, as icons and figures on one quiet line.
    // At rest the sheet grows from the bottom, so should the line ever
    // wrap (large fonts) it pushes the figures up, never the switcher down.
    FlowRow(
        modifier = Modifier.padding(top = 6.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        routeStats(shown).forEach { StatFigure(it, shown) }
    }
}

/** The figures line while new routes or loops are found after a setting
 * changed: the figures that are always there, with a dash, dimmed, so
 * the line keeps its place. A screen reader skips it (the title says
 * what is happening). */
@Composable
private fun FindingFigures() {
    Row(
        Modifier
            .padding(top = 6.dp, end = 8.dp)
            .alpha(0.5f)
            .clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(R.drawable.ic_star, R.drawable.ic_curvy).forEach { icon ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    noBreak(stringResource(R.string.route_stat_percent_none)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** One figure about the route or loop, its icon before it ("[star] 18 %");
 * a screen reader says it in words ("18 % on favourites"). Not a button. */
@Composable
private fun StatFigure(stat: RouteStat, s: RouteSummary) {
    val (icon, text, said) = when (stat.kind) {
        RouteStatKind.FASTEST -> Triple(null, stringResource(R.string.route_fastest), stringResource(R.string.route_fastest))
        RouteStatKind.EXTRA -> Triple(
            R.drawable.ic_time,
            stringResource(R.string.route_stat_minutes, s.extraMinutes),
            stringResource(R.string.route_extra, s.extraMinutes),
        )
        RouteStatKind.FAVOURITES -> Triple(
            R.drawable.ic_star,
            stringResource(R.string.route_stat_percent, s.favouritePercent),
            stringResource(R.string.route_on_favourites, s.favouritePercent),
        )
        RouteStatKind.CURVY -> Triple(
            R.drawable.ic_curvy,
            stringResource(R.string.route_stat_percent, s.curvyPercent),
            stringResource(R.string.route_curvy, s.curvyPercent),
        )
        RouteStatKind.UNRIDDEN -> Triple(
            R.drawable.ic_unridden,
            if (showsUnriddenValue(s.unriddenPercent)) stringResource(R.string.route_stat_percent, s.unriddenPercent) else "",
            if (showsUnriddenValue(s.unriddenPercent)) {
                stringResource(R.string.route_unridden, s.unriddenPercent)
            } else {
                stringResource(R.string.route_unridden_all)
            },
        )
        RouteStatKind.GRAVEL -> Triple(
            R.drawable.ic_gravel,
            stringResource(R.string.route_km, s.gravelKm),
            stringResource(R.string.route_gravel, s.gravelKm),
        )
        RouteStatKind.TOLL -> Triple(
            R.drawable.ic_toll,
            stringResource(R.string.route_km, s.tollKm),
            stringResource(R.string.route_toll, s.tollKm),
        )
    }
    Row(
        Modifier
            .alpha(if (stat.zero) 0.45f else 1f)
            .clearAndSetSemantics { contentDescription = said },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        icon?.let {
            Icon(
                painterResource(it),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        if (text.isNotEmpty()) {
            Text(noBreak(text), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** The choices as chips ([items]: icons, with a figure where one is
 * needed), the kinds of road [allowed] as their icons alone (none while
 * all are avoided, the default), then an arrow; tapping any pulls the
 * sheet up to change them. A screen reader says each in words. */
@Composable
private fun OptionChips(items: List<SummaryItem>, allowed: List<AvoidKind>, onClick: () -> Unit) {
    FlowRow(
        modifier = Modifier.padding(top = 4.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { SummaryChip(it, onClick) }
        allowed.forEach { kind ->
            IconChip(avoidIcon(kind), null, stringResource(R.string.avoid_allowed, avoidLabel(kind)), onClick = onClick)
        }
        IconButton(onClick = onClick) {
            Icon(painterResource(R.drawable.ic_expand_less), contentDescription = stringResource(R.string.card_expand))
        }
    }
}

/** One [SummaryItem] as a chip: the length in words; a compass needle
 * turned the way a loop heads, with its letter; the gravel icon, with +
 * when preferred; the crossed-out star; a pin with the waypoints' count;
 * a clock with the arrival time, in the error colour when it misses the
 * time set. */
@Composable
private fun SummaryChip(item: SummaryItem, onClick: () -> Unit) {
    when (item) {
        is SummaryItem.Length -> SuggestionChip(onClick = onClick, label = { OneLine(loopLengthText(item.choice)) })
        is SummaryItem.Heading -> {
            val (letter, said) = when (item.direction) {
                LoopDirection.NORTH -> R.string.loop_direction_north to R.string.loop_heading_north
                LoopDirection.EAST -> R.string.loop_direction_east to R.string.loop_heading_east
                LoopDirection.SOUTH -> R.string.loop_direction_south to R.string.loop_heading_south
                LoopDirection.WEST -> R.string.loop_direction_west to R.string.loop_heading_west
                LoopDirection.ANY -> R.string.loop_direction_any to R.string.loop_heading_any
            }
            IconChip(
                R.drawable.ic_compass,
                stringResource(letter),
                stringResource(R.string.summary_heading, stringResource(said)),
                rotation = (item.direction.bearing ?: 0.0).toFloat(),
                onClick = onClick,
            )
        }
        is SummaryItem.GravelRoads -> IconChip(
            R.drawable.ic_gravel,
            if (item.gravel == Gravel.PREFER) "+" else null,
            stringResource(if (item.gravel == Gravel.PREFER) R.string.gravel_summary_prefer else R.string.gravel_summary_allow),
            onClick = onClick,
        )
        SummaryItem.FavouritesAvoided ->
            IconChip(R.drawable.ic_star_off, null, stringResource(R.string.favourites_summary_avoid), onClick = onClick)
        SummaryItem.UnriddenPreferred ->
            IconChip(R.drawable.ic_unridden, null, stringResource(R.string.unridden_summary_prefer), onClick = onClick)
        is SummaryItem.Waypoints -> IconChip(
            R.drawable.ic_pin,
            item.count.toString(),
            pluralStringResource(R.plurals.route_via_count, item.count, item.count),
            onClick = onClick,
        )
        is SummaryItem.ArrivesAt -> {
            val zone = remember { ZoneId.systemDefault() }
            val at = clockTime(item.arrival.atSec, zone)
            IconChip(
                R.drawable.ic_clock,
                at,
                if (item.arrival.late) {
                    stringResource(R.string.route_arrives_late, clockTime(item.by, zone), at)
                } else {
                    stringResource(R.string.route_arrives, at)
                },
                late = item.arrival.late,
                onClick = onClick,
            )
        }
    }
}

/** A summary chip with [icon] (turned by [rotation] degrees) after which
 * [text] comes, or the icon alone; a screen reader says [said] instead. */
@Composable
private fun IconChip(icon: Int, text: String?, said: String, rotation: Float = 0f, late: Boolean = false, onClick: () -> Unit) {
    val colors = if (late) {
        SuggestionChipDefaults.suggestionChipColors(
            labelColor = MaterialTheme.colorScheme.error,
            iconContentColor = MaterialTheme.colorScheme.error,
        )
    } else {
        SuggestionChipDefaults.suggestionChipColors()
    }
    val image = @Composable {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp).rotate(rotation))
    }
    SuggestionChip(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = said },
        colors = colors,
        icon = if (text != null) image else null,
        label = {
            if (text != null) {
                Text(text, maxLines = 1, softWrap = false, modifier = Modifier.clearAndSetSemantics {})
            } else {
                image()
            }
        },
    )
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
 * not in use (e.g. while an arrival time is set). Steps for which [mark]
 * is true get their [label] on a scale under the slider, in place of a
 * dot for every step. The title looks like the sheet's other option
 * headings by default; a settings page passes its headings' [titleStyle]
 * and [titleColor], and [titleAlone] to put the title on a line of its
 * own, [trailing] and the value below it. [trailing] comes before the
 * value, so it stays put while the value changes.
 */
@Composable
fun <T> StepSlider(
    title: String,
    steps: List<T>,
    value: T,
    label: @Composable (T) -> String,
    onCommit: (T) -> Unit,
    dimmed: Boolean = false,
    mark: (T) -> Boolean = { false },
    titleExtra: @Composable () -> Unit = {},
    titleStyle: TextStyle = MaterialTheme.typography.labelLarge,
    titleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleAlone: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    val start = steps.indexOf(value).coerceAtLeast(0)
    var position by remember(steps, value) { mutableFloatStateOf(start.toFloat()) }
    val at = steps[position.roundToInt().coerceIn(0, steps.lastIndex)]
    Column(Modifier.padding(top = if (titleAlone) 0.dp else 12.dp, end = 8.dp)) {
        val titleRow = @Composable {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = titleStyle, color = titleColor)
                titleExtra()
            }
        }
        if (titleAlone) titleRow()
        // The chips before the value, so they stay put while it changes.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (!titleAlone) titleRow()
            trailing()
            Text(
                label(at),
                style = MaterialTheme.typography.titleMedium,
                color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
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
            colors = if (steps.any(mark)) {
                SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)
            } else {
                SliderDefaults.colors()
            },
        )
        val marks = steps.indices.filter { mark(steps[it]) }
        if (marks.isNotEmpty()) {
            val texts = marks.map { label(steps[it]) }
            val last = steps.lastIndex.coerceAtLeast(1)
            SliderScale(texts, marks.map { it.toFloat() / last })
        }
    }
}

/**
 * Labels under a slider, each centred on its point ([fractions] of the
 * track, 0 to 1); labels that don't fit beside each other are left out.
 */
@Composable
fun SliderScale(texts: List<String>, fractions: List<Float>) {
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    Layout(
        content = {
            texts.forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val total = constraints.maxWidth
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val shown = scaleLabels(
            centres = fractions.map { (it * total).roundToInt() },
            widths = placeables.map { it.width },
            total = total,
            gap = gap,
        )
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(total, height) {
            shown.forEach { (i, left) -> placeables[i].placeRelative(left, 0) }
        }
    }
}

/** A loop length as a slider over its steps, in hours or kilometres
 * (chips beside the [title] switch the unit). */
@Composable
fun LoopLengthSlider(
    title: String,
    choice: LoopChoice,
    onChoice: (LoopChoice) -> Unit,
    info: @Composable () -> Unit = {},
    titleStyle: TextStyle = MaterialTheme.typography.labelLarge,
    titleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleAlone: Boolean = false,
) {
    StepSlider(
        title = title,
        steps = loopSteps(choice),
        value = choice,
        label = { loopLengthText(it) },
        onCommit = onChoice,
        mark = ::isLoopMark,
        titleExtra = info,
        titleStyle = titleStyle,
        titleColor = titleColor,
        titleAlone = titleAlone,
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
}

/** A loop length as text: "2 h 30 min", "45 min" or "120 km". */
@Composable
fun loopLengthText(c: LoopChoice): String = when (c) {
    is LoopChoice.Km -> stringResource(R.string.loop_km, c.km)
    is LoopChoice.Minutes -> durationText(c.minutes)
}

/** A riding time as text: "2 h 50 min", "3 h" or "45 min". */
@Composable
fun durationText(minutes: Int): String = durationText(LocalResources.current, minutes)

/** [durationText] outside composition. */
fun durationText(res: android.content.res.Resources, minutes: Int): String = when {
    minutes < 60 -> res.getString(R.string.loop_minutes, minutes.coerceAtLeast(0))
    minutes % 60 == 0 -> res.getString(R.string.loop_hours, minutes / 60)
    else -> res.getString(R.string.loop_hours_minutes, minutes / 60, minutes % 60)
}

/** [text] with its spaces kept from breaking ("2 h 50 min" on one line). */
fun noBreak(text: String): String = text.replace(' ', '\u00A0')
