// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import se.gangefors.moto.core.Gravel

/**
 * The heading of an option on a sheet or page ("Waypoints", "Gravel
 * roads"), with an (i) after it when there is [info] to explain it.
 */
@Composable
fun OptionHeading(text: String, info: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
        info?.let { InfoButton(text, it) }
    }
}

/**
 * An (i) that shows [text] in a rich tooltip (Material 3's way to explain
 * a control in place): it opens on a tap and stays until a tap elsewhere
 * or Back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoButton(title: String, text: String) {
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { RichTooltip(title = { Text(title) }) { Text(text) } },
        state = state,
    ) {
        IconButton(onClick = { scope.launch { state.show() } }) {
            Icon(
                painterResource(R.drawable.ic_info),
                contentDescription = stringResource(R.string.settings_info, title),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One of a few [options], as segmented buttons (Material 3's control for
 * picking one of two to five) across the width; as chips that wrap when
 * the labels don't fit side by side (large fonts, narrow screens), so
 * nothing is squeezed.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun <T> SingleChoice(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    val labels = options.map { label(it) }
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val segmentPadding = with(LocalDensity.current) { SEGMENT_PADDING.roundToPx() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widest = labels.maxOf { measurer.measure(it, style).size.width }
        if (segmentsFit(widest, labels.size, segmentPadding, constraints.maxWidth)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, o ->
                    SegmentedButton(
                        selected = o == selected,
                        onClick = { onSelect(o) },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        icon = {},
                        label = { OneLine(labels[i]) },
                    )
                }
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEachIndexed { i, o ->
                    FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { OneLine(labels[i]) })
                }
            }
        }
    }
}

/** Room a segment needs besides its label (its padding and border). */
private val SEGMENT_PADDING = 28.dp

/** Avoid / Allow / Prefer gravel, one of them selected. */
@Composable
fun GravelChips(gravel: Gravel, onGravel: (Gravel) -> Unit) {
    SingleChoice(
        options = GRAVEL_CHOICES,
        selected = gravel,
        label = {
            stringResource(
                when (it) {
                    Gravel.AVOID -> R.string.gravel_avoid
                    Gravel.ALLOW -> R.string.gravel_allow
                    Gravel.PREFER -> R.string.gravel_prefer
                },
            )
        },
        onSelect = onGravel,
    )
}
