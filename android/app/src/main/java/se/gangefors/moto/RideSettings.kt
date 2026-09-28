// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import se.gangefors.moto.core.Gravel

/** The settings on the Ride settings page, as they are now. */
data class RideSettings(
    val gravel: Gravel,
    val loopLength: LoopChoice,
    val zooms: LocateZooms,
    val keepScreenOn: Boolean,
)

/**
 * Ride settings, one tap from the map (the gear at the top right): gravel
 * roads, the length a new loop starts at, the location button's zoom
 * levels, and keeping the screen on while recording. Each change applies
 * at once ([onChange]); Back or the arrow closes the page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RideSettingsPage(settings: RideSettings, onChange: (RideSettings) -> Unit, onDismiss: () -> Unit) {
    FullPage(stringResource(R.string.ride_settings_title), onBack = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.routing_gravel), style = MaterialTheme.typography.titleMedium)
            GravelChips(settings.gravel) { onChange(settings.copy(gravel = it)) }
            Hint(stringResource(R.string.routing_gravel_hint))
            Divider()

            LoopLengthSlider(stringResource(R.string.settings_loop_length), settings.loopLength) {
                onChange(settings.copy(loopLength = it))
            }
            Hint(stringResource(R.string.settings_loop_length_hint))
            Divider()

            Text(stringResource(R.string.locate_zooms), style = MaterialTheme.typography.titleMedium)
            val zooms = settings.zooms
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                ZoomStepper(stringResource(R.string.locate_zoom_area), zooms.area) {
                    onChange(settings.copy(zooms = LocateZooms.of(it, zooms.close)))
                }
                ZoomStepper(stringResource(R.string.locate_zoom_close), zooms.close) {
                    onChange(settings.copy(zooms = LocateZooms.of(zooms.area, it)))
                }
            }
            Hint(stringResource(R.string.locate_zooms_hint))
            Divider()

            // The whole row toggles, not just the switch: easier with gloves.
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = settings.keepScreenOn,
                        role = Role.Switch,
                        onValueChange = { onChange(settings.copy(keepScreenOn = it)) },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(stringResource(R.string.settings_keep_screen_on), style = MaterialTheme.typography.titleMedium)
                    Hint(stringResource(R.string.settings_keep_screen_on_hint))
                }
                Switch(checked = settings.keepScreenOn, onCheckedChange = null)
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(vertical = 16.dp))
}

/** A zoom level with − and + beside it ([MIN_ZOOM]..[MAX_ZOOM]). */
@Composable
private fun ZoomStepper(label: String, zoom: Int, onZoom: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        IconButton(onClick = { onZoom(zoom - 1) }, enabled = zoom > MIN_ZOOM) {
            Text("−", style = MaterialTheme.typography.titleLarge)
        }
        Text("$zoom", style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { onZoom(zoom + 1) }, enabled = zoom < MAX_ZOOM) {
            Text("+", style = MaterialTheme.typography.titleLarge)
        }
    }
}
