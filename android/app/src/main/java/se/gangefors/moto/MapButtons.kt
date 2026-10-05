// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The round buttons at the top of the map. */
internal val TOP_BUTTON_SIZE = 48.dp
private val TOP_BUTTON_MARGIN = 16.dp

/** The zoom buttons' height, dp: two 52 dp cells and the 1 dp divider (see [ZoomButtons]). */
internal const val ZOOM_BUTTONS_DP = 105f

/** A round button at the top of the map (menu, ride settings). */
@Composable
internal fun TopMapButton(icon: Int, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .safeDrawingPadding()
            .padding(top = 8.dp, start = TOP_BUTTON_MARGIN, end = TOP_BUTTON_MARGIN)
            .size(TOP_BUTTON_SIZE)
            .semantics { contentDescription = description },
        shape = CircleShape,
        // The accent colour, as the other map buttons (Material's floating
        // action buttons): lilac on the light theme, deep purple on the dark.
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null)
        }
    }
}

/** Frames the whole route or loop: the size of the top buttons, on the plain surface. */
@Composable
internal fun FitRouteButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.fit_route)
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(TOP_BUTTON_SIZE)
            .semantics { contentDescription = description },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_fit_route), contentDescription = null)
        }
    }
}

/**
 * Shows or hides the ridden roads (ADR-0010) while planning: the size of
 * the top buttons, in the accent colour when on (as the other map
 * buttons), the plain surface when off. A screen reader says "Show ridden
 * roads" with the switch's state.
 */
@Composable
internal fun RiddenButton(on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.settings_show_ridden)
    Surface(
        checked = on,
        onCheckedChange = onChange,
        modifier = modifier
            .size(TOP_BUTTON_SIZE)
            .semantics { contentDescription = description },
        shape = CircleShape,
        color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_ridden), contentDescription = null)
        }
    }
}

/** Quick-tag button: large enough to hit with gloves on. */
internal val TAG_BUTTON_SIZE: Dp = 96.dp

/** The add-favourite icon on the tag button, big enough to read at a glance. */
internal val TAG_ICON_SIZE: Dp = 48.dp

internal val TAG_COLOR = Color(0xFFE8710A)

/** The record symbol's red. */
internal val RECORD_RED = Color(0xFFD93025)

/** The bottom-right buttons' distance from the safe edges. */
internal val FAB_PADDING: Dp = 16.dp
