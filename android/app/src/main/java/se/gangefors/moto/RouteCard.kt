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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

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
    allowGravel: Boolean,
    onAllowGravel: (Boolean) -> Unit,
    onClose: () -> Unit,
    onShare: () -> Unit,
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
            if (expanded) RouteCardDetails(budgetPercent, onBudget, allowGravel, onAllowGravel, onShare, summary != null)
        }
    }
}

/** The route card's choices: extra time, gravel and sharing. */
@Composable
private fun RouteCardDetails(
    budgetPercent: Int,
    onBudget: (Int) -> Unit,
    allowGravel: Boolean,
    onAllowGravel: (Boolean) -> Unit,
    onShare: () -> Unit,
    shareEnabled: Boolean,
) {
    Column {
        Text(
            stringResource(R.string.route_budget),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BUDGET_CHOICES.forEach { percent ->
                FilterChip(
                    selected = percent == budgetPercent,
                    onClick = { onBudget(percent) },
                    label = {
                        OneLine(
                            if (percent == 0) {
                                stringResource(R.string.route_budget_fastest)
                            } else {
                                stringResource(R.string.route_budget_extra, percent)
                            },
                        )
                    },
                )
            }
        }
        GravelAndShare(allowGravel, onAllowGravel, onShare, shareEnabled = shareEnabled)
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
    allowGravel: Boolean,
    onAllowGravel: (Boolean) -> Unit,
    onClose: () -> Unit,
    onShare: () -> Unit,
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
                    allowGravel = allowGravel,
                    onAllowGravel = onAllowGravel,
                    onShare = onShare,
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
    allowGravel: Boolean,
    onAllowGravel: (Boolean) -> Unit,
    onShare: () -> Unit,
) {
    Column {
        // Which loop, the next one, and a new set of loops (Shuffle).
        if (found) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (count > 1) {
                    Text(
                        stringResource(R.string.loop_position, position + 1, count),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    OutlinedButton(onClick = onNext) { OneLine(stringResource(R.string.loop_next)) }
                }
                OutlinedButton(onClick = onShuffle) { OneLine(stringResource(R.string.loop_shuffle)) }
            }
        }
        Text(
            stringResource(R.string.loop_length),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LOOP_CHOICES.forEach { c ->
                FilterChip(
                    selected = c == choice,
                    onClick = { onChoice(c) },
                    label = {
                        OneLine(
                            when (c) {
                                is LoopChoice.Hours -> stringResource(R.string.loop_hours, c.hours)
                                is LoopChoice.Km -> stringResource(R.string.loop_km, c.km)
                            },
                        )
                    },
                )
            }
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
        GravelAndShare(allowGravel, onAllowGravel, onShare, shareEnabled = found)
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

/** A route's figures: distance and time, then what it is worth; or
 * [computing] while it is being found. */
@Composable
private fun RouteFigures(summary: RouteSummary?, computing: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 8.dp)) {
        if (summary == null) {
            Text(computing)
        } else {
            Text(
                stringResource(R.string.route_summary, summary.km, summary.minutes),
                style = MaterialTheme.typography.titleMedium,
            )
            val details = buildList {
                if (summary.extraMinutes > 0) add(stringResource(R.string.route_extra, summary.extraMinutes))
                if (summary.favouritePercent > 0) {
                    add(stringResource(R.string.route_on_favourites, summary.favouritePercent))
                }
                if (summary.curvyPercent > 0) add(stringResource(R.string.route_curvy, summary.curvyPercent))
                if (summary.gravelKm > 0.0) add(stringResource(R.string.route_gravel, summary.gravelKm))
            }
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GravelAndShare(
    allowGravel: Boolean,
    onAllowGravel: (Boolean) -> Unit,
    onShare: () -> Unit,
    shareEnabled: Boolean,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        // The same setting as in My data: toggling it here routes again,
        // to see what gravel roads change.
        FilterChip(
            selected = allowGravel,
            onClick = { onAllowGravel(!allowGravel) },
            label = { OneLine(stringResource(R.string.route_allow_gravel)) },
        )
        OutlinedButton(
            onClick = onShare,
            enabled = shareEnabled,
        ) { OneLine(stringResource(R.string.route_share)) }
    }
}
