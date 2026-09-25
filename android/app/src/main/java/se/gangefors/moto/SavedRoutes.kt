// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.SavedRoute

/** A saved route drawn on the map, and its line. */
data class ShownSavedRoute(val route: SavedRoute, val line: List<LatLon>)

/**
 * Asks for a route's name, starting from [initial]. Save is offered once
 * the name has something in it after cleaning (see [cleanRouteName]).
 */
@Composable
fun RouteNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val clean = cleanRouteName(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_ROUTE_NAME_CHARS * 2) },
                label = { Text(stringResource(R.string.route_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { clean?.let(onSave) }, enabled = clean != null) {
                OneLine(stringResource(R.string.section_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { OneLine(stringResource(R.string.cancel)) } },
    )
}

/** A saved route on the map: its name and figures, Share, and a cross to
 * hide it. */
@Composable
fun SavedRouteCard(
    shown: ShownSavedRoute,
    onShare: () -> Unit,
    onClose: () -> Unit,
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
                    Text(shown.route.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        savedRouteSummary(shown.route),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.Top)) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.saved_route_hide))
                }
            }
            OutlinedButton(onClick = onShare) { OneLine(stringResource(R.string.route_share)) }
        }
    }
}

@Composable
private fun savedRouteSummary(r: SavedRoute): String =
    stringResource(R.string.route_summary, sectionKm(r.distanceM), (r.durationS / 60).toInt())

/**
 * The saved routes in My data: each with Show (on the map), Rename and
 * delete (tapped twice). [routes] is null while loading.
 */
@Composable
fun SavedRoutesList(
    routes: List<SavedRoute>?,
    onShow: (SavedRoute) -> Unit,
    onRename: (SavedRoute, String) -> Unit,
    onDelete: (SavedRoute) -> Unit,
) {
    var confirmDelete by remember { mutableLongStateOf(0L) }
    var renaming by remember { mutableStateOf<SavedRoute?>(null) }
    Text(stringResource(R.string.saved_routes_title), style = MaterialTheme.typography.titleLarge)
    when {
        routes == null -> Text(stringResource(R.string.rides_loading), Modifier.padding(vertical = 16.dp))
        routes.isEmpty() -> Text(stringResource(R.string.saved_routes_none), Modifier.padding(vertical = 16.dp))
        else -> routes.forEach { r ->
            // The buttons wrap under the name when there is no room beside it.
            FlowRow(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.padding(end = 8.dp)) {
                    Text(r.name)
                    Text(
                        savedRouteSummary(r),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onShow(r) }) { OneLine(stringResource(R.string.rides_show)) }
                    TextButton(onClick = { renaming = r }) { OneLine(stringResource(R.string.saved_route_rename)) }
                    DeleteButton(
                        confirming = confirmDelete == r.id,
                        onArm = { confirmDelete = r.id },
                        onDelete = {
                            confirmDelete = 0L
                            onDelete(r)
                        },
                    )
                }
            }
            HorizontalDivider()
        }
    }
    renaming?.let { r ->
        RouteNameDialog(
            title = stringResource(R.string.saved_route_rename_title),
            initial = r.name,
            onDismiss = { renaming = null },
            onSave = { name ->
                renaming = null
                onRename(r, name)
            },
        )
    }
}
