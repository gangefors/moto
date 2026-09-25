// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.Track
import java.time.ZoneId

/** A saved ride drawn on the map, and its line. */
data class ShownRide(val track: Track, val line: List<LatLon>)

/** Which ride is on the map, with a cross to hide it. */
@Composable
fun ShownRideCard(ride: ShownRide, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val zone = remember { ZoneId.systemDefault() }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.ride_shown, rideTitle(ride.track.startedAt, zone), sectionKm(ride.track.distanceM)),
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.ride_hide))
            }
        }
    }
}
