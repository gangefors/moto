// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.Track
import java.time.ZoneId

/** A saved ride drawn on the map, and its line. */
data class ShownRide(val track: Track, val line: List<LatLon>)

/** Which ride is on the map: its name and figures, with a cross to hide it. */
@Composable
fun ShownRideCard(ride: ShownRide, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val zone = remember { ZoneId.systemDefault() }
    val res = LocalResources.current
    MapCard(
        title = rideName(ride.track.name, ride.track.startedAt, zone),
        supporting = stringResource(R.string.library_ride, rideSummary(res, ride.track)),
        onClose = onClose,
        closeDescription = stringResource(R.string.ride_hide),
        modifier = modifier,
    )
}

/**
 * A card over the map about what it shows (a ride, a saved route, a road):
 * [title], [supporting] lines below it, then [actions] (icons) and the
 * cross at the top right, as on the planning sheet; [content] below.
 */
@Composable
fun MapCard(
    title: String,
    onClose: () -> Unit,
    closeDescription: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit = {},
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, bottom = 12.dp)) {
            Row {
                Column(Modifier.weight(1f).padding(top = 12.dp, end = 4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    supporting?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                actions()
                IconButton(onClick = onClose) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = closeDescription)
                }
            }
            Column(Modifier.padding(end = 12.dp)) { content() }
        }
    }
}
