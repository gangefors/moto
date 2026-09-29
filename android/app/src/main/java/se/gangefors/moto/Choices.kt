// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import android.os.SystemClock
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
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

/** A group's name on a settings-like page, in the accent colour as
 * Android's own settings show them, with a divider above all but the
 * [first]. */
@Composable
fun SettingsGroup(title: String, first: Boolean = false) {
    if (!first) HorizontalDivider(Modifier.padding(top = 24.dp))
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = if (first) 8.dp else 16.dp, bottom = 4.dp),
    )
}

/**
 * An (i) that shows [text] in a rich tooltip (Material 3's way to explain
 * a control in place): it opens on a tap and stays until a tap elsewhere
 * or Back. A tap on the (i) while it is open closes it: that tap first
 * reaches the tooltip as a tap outside it, so the click that follows is
 * ignored rather than opening it again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoButton(title: String, text: String) {
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    val interactions = remember { MutableInteractionSource() }
    var closedAt by remember { mutableLongStateOf(Long.MIN_VALUE) }
    var pressedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state) {
        snapshotFlow { state.isVisible }.drop(1).collect { if (!it) closedAt = SystemClock.uptimeMillis() }
    }
    LaunchedEffect(interactions) {
        interactions.interactions.collect { if (it is PressInteraction.Press) pressedAt = SystemClock.uptimeMillis() }
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { RichTooltip(title = { IconText(title) }) { IconText(text) } },
        state = state,
    ) {
        IconButton(
            onClick = { if (opensOnTap(pressedAt, closedAt)) scope.launch { state.show() } },
            interactionSource = interactions,
        ) {
            Icon(
                painterResource(R.drawable.ic_info),
                contentDescription = stringResource(R.string.settings_info, iconTextWords(title)),
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

/** An outlined button with an icon before its one-line label. */
@Composable
fun IconTextButton(icon: Int, label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        OneLine(label)
    }
}
