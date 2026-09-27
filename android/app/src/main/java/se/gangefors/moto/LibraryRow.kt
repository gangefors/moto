// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.ZoneId

/** What a row of Routes & rides can do. */
interface LibraryActions {
    fun show(item: LibraryItem)
    fun rename(item: LibraryItem)
    fun share(item: LibraryItem)
    fun export(item: LibraryItem)
    fun saveAsRoute(item: LibraryItem.Ride)
    fun armDelete(item: LibraryItem)
    fun delete(item: LibraryItem)
}

/** The name shown for a route or ride. */
fun libraryTitle(item: LibraryItem, zone: ZoneId): String = when (item) {
    is LibraryItem.Route -> item.route.name
    is LibraryItem.Ride -> rideName(item.track.name, item.track.startedAt, zone)
}

/**
 * One route or ride: its name, what it is with its figures, and the same
 * actions for both: Show, Rename, Share, Save as file and the bin (tapped
 * twice); a ride can also be saved as a route. A ride still recording can
 * only be renamed. The buttons wrap under the text when there is no room.
 */
@Composable
fun LibraryRow(item: LibraryItem, zone: ZoneId, confirmingDelete: Boolean, actions: LibraryActions) {
    val res = LocalResources.current
    val finished = item !is LibraryItem.Ride || item.track.endedAt != null
    FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.padding(end = 8.dp)) {
            Text(libraryTitle(item, zone))
            Text(
                when (item) {
                    is LibraryItem.Route -> stringResource(
                        if (item.route.isLoop) R.string.library_loop else R.string.library_route,
                        sectionKm(item.route.distanceM),
                        formatDuration((item.route.durationS * 1000).toLong()),
                    )
                    is LibraryItem.Ride -> stringResource(R.string.library_ride, rideSummary(res, item.track))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { actions.show(item) }, enabled = finished) {
                OneLine(stringResource(R.string.rides_show))
            }
            IconButton(onClick = { actions.rename(item) }) {
                Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.saved_route_rename))
            }
            IconButton(onClick = { actions.share(item) }, enabled = finished) {
                Icon(painterResource(R.drawable.ic_share), stringResource(R.string.library_share))
            }
            IconButton(onClick = { actions.export(item) }, enabled = finished) {
                Icon(painterResource(R.drawable.ic_export), stringResource(R.string.rides_export))
            }
            if (item is LibraryItem.Ride) {
                IconButton(onClick = { actions.saveAsRoute(item) }, enabled = finished) {
                    Icon(painterResource(R.drawable.ic_bookmark), stringResource(R.string.library_save_as_route))
                }
            }
            DeleteButton(
                confirming = confirmingDelete,
                onArm = { actions.armDelete(item) },
                onDelete = { actions.delete(item) },
                enabled = finished,
            )
        }
    }
    HorizontalDivider()
}
