// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

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
 * again; Share hands it to a nav app as GPX; the cross clears it.
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
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    if (summary == null) {
                        Text(stringResource(R.string.route_computing))
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
                IconButton(onClick = onClose) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.route_close))
                }
            }
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                // The same setting as in My data: toggling it here routes
                // again, to see what gravel roads change.
                FilterChip(
                    selected = allowGravel,
                    onClick = { onAllowGravel(!allowGravel) },
                    label = { OneLine(stringResource(R.string.route_allow_gravel)) },
                )
                OutlinedButton(
                    onClick = onShare,
                    enabled = summary != null,
                ) { OneLine(stringResource(R.string.route_share)) }
            }
        }
    }
}
