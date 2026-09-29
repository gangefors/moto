// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.ZoneId

/** What a row of Routes & rides can do. */
interface LibraryActions {
    fun show(item: LibraryItem)
    fun rename(item: LibraryItem)
    fun share(item: LibraryItem)
    fun delete(item: LibraryItem)
}

/** The name shown for a route or ride. */
fun libraryTitle(item: LibraryItem, zone: ZoneId): String = when (item) {
    is LibraryItem.Route -> item.route.name
    is LibraryItem.Ride -> rideName(item.track.name, item.track.startedAt, zone)
}

/**
 * One route or ride, as a list row the Material way: its kind as an icon
 * (route, loop or ride, the icons How to use explains), tap it to show it
 * on the map; Rename and Share (to a nav app, the usual next step) beside
 * it; Delete behind the three dots, which asks for a second tap in the
 * menu. A ride still recording can
 * only be renamed.
 */
@Composable
fun LibraryRow(item: LibraryItem, zone: ZoneId, actions: LibraryActions) {
    val res = LocalResources.current
    val finished = item !is LibraryItem.Ride || item.track.endedAt != null
    val title = libraryTitle(item, zone)
    var menu by remember { mutableStateOf(false) }
    var armed by remember(item.key) { mutableStateOf(false) }
    fun closeMenu() {
        menu = false
        armed = false
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                enabled = finished,
                onClickLabel = stringResource(R.string.library_show_on_map),
                onClick = { actions.show(item) },
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(
                when (item) {
                    is LibraryItem.Route -> if (item.route.isLoop) R.drawable.ic_loop else R.drawable.ic_directions
                    is LibraryItem.Ride -> R.drawable.ic_ride
                },
            ),
            contentDescription = null,
            modifier = Modifier.padding(end = 12.dp).size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f).padding(end = 4.dp)) {
            Text(title)
            Text(
                when (item) {
                    is LibraryItem.Route -> stringResource(
                        if (item.route.isLoop) R.string.library_loop else R.string.library_route,
                        sectionKm(item.route.distanceM),
                        durationText((item.route.durationS / 60).toInt()),
                    )
                    is LibraryItem.Ride -> stringResource(R.string.library_ride, rideSummary(res, item.track))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { actions.rename(item) }) {
            Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.library_rename, title))
        }
        IconButton(onClick = { actions.share(item) }, enabled = finished) {
            Icon(painterResource(R.drawable.ic_share), stringResource(R.string.library_share))
        }
        // A ride still recording has nothing more to offer; the space
        // keeps its buttons in line with the other rows'.
        if (!finished) {
            Spacer(Modifier.size(48.dp))
        } else Box {
            IconButton(onClick = { menu = true }) {
                Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.library_more, title))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { closeMenu() }) {
                // The first tap arms it (the menu stays open, saying so),
                // the second deletes.
                val deleted = stringResource(R.string.deleted)
                MenuItem(
                    R.drawable.ic_delete,
                    stringResource(if (armed) R.string.delete_confirm else R.string.delete),
                    color = DELETE_COLOR,
                    strong = armed,
                ) {
                    if (armed) {
                        closeMenu()
                        actions.delete(item)
                        Toasts.show(deleted)
                    } else {
                        armed = true
                    }
                }
            }
        }
    }
    HorizontalDivider()
}

/** A menu entry with its icon; [color] for both, [strong] in bold. */
@Composable
private fun MenuItem(icon: Int, text: String, color: Color = Color.Unspecified, strong: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = color, fontWeight = if (strong) FontWeight.Bold else null) },
        leadingIcon = {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = if (color == Color.Unspecified) LocalContentColor.current else color,
            )
        },
        onClick = onClick,
    )
}
