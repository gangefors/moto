// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import se.gangefors.moto.core.Avoid
import se.gangefors.moto.core.FavouritesMode
import se.gangefors.moto.core.Gravel

/** The settings on the Ride settings page, as they are now. */
data class RideSettings(
    val gravel: Gravel,
    val favourites: FavouritesMode,
    val avoid: Avoid,
    val loopLength: LoopChoice,
    val zooms: LocateZooms,
    val keepScreenOn: Boolean,
)

/**
 * Ride settings, one tap from the map (the gear at the top right), in
 * groups as Android's own settings are: routes and loops (gravel roads,
 * favourites, roads to allow, the length a new loop starts at), the map (the location button's two
 * zooms, as a range) and recording (keeping the screen on). Each change
 * applies at once ([onChange]); Back or the arrow closes the page.
 */
@Composable
fun RideSettingsPage(settings: RideSettings, onChange: (RideSettings) -> Unit, onDismiss: () -> Unit) {
    FullPage(stringResource(R.string.ride_settings_title), onBack = onDismiss) {
        val scroll = rememberScrollState()
        Column(Modifier.scrollHints(scroll).verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 8.dp)) {
            SettingsGroup(stringResource(R.string.settings_group_routing), first = true)
            Heading(stringResource(R.string.routing_gravel), stringResource(R.string.routing_gravel_hint))
            GravelChips(settings.gravel) { onChange(settings.copy(gravel = it)) }
            Box(Modifier.padding(top = 16.dp)) {
                Heading(stringResource(R.string.favourites_label), stringResource(R.string.favourites_hint))
            }
            FavouritesChips(settings.favourites) { onChange(settings.copy(favourites = it)) }
            Box(Modifier.padding(top = 16.dp)) {
                Heading(stringResource(R.string.avoid_heading), stringResource(R.string.avoid_hint))
            }
            AllowChips(settings.avoid) { onChange(settings.copy(avoid = it)) }
            val loopTitle = stringResource(R.string.settings_loop_length)
            Box(Modifier.padding(top = 16.dp)) {
                LoopLengthSlider(
                    loopTitle,
                    settings.loopLength,
                    { onChange(settings.copy(loopLength = it)) },
                    info = { InfoButton(loopTitle, stringResource(R.string.settings_loop_length_hint)) },
                    titleStyle = MaterialTheme.typography.titleMedium,
                    titleColor = MaterialTheme.colorScheme.onSurface,
                    titleAlone = true,
                )
            }

            SettingsGroup(stringResource(R.string.settings_group_map))
            Heading(stringResource(R.string.locate_zooms), stringResource(R.string.locate_zooms_hint))
            ZoomRange(settings.zooms) { onChange(settings.copy(zooms = it)) }

            SettingsGroup(stringResource(R.string.settings_group_recording))
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
                Box(Modifier.weight(1f).padding(end = 16.dp)) {
                    Heading(stringResource(R.string.settings_keep_screen_on), stringResource(R.string.settings_keep_screen_on_hint))
                }
                Switch(checked = settings.keepScreenOn, onCheckedChange = null)
            }
        }
    }
}

/** A setting's name with an (i) after it that explains it. */
@Composable
private fun Heading(title: String, info: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconText(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium)
        InfoButton(title, info)
    }
}

/**
 * The location button's two zooms as one range slider (Material's control
 * for a low and a high value), laid out by how much the map shows: the
 * left thumb is close by, the right one the area, wider to the right
 * (lower zooms, so the slider runs over [flipZoom]ed values). Each shows
 * as how wide the map is on this screen, not as a zoom number. The zooms
 * are saved when a thumb is let go.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZoomRange(zooms: LocateZooms, onZooms: (LocateZooms) -> Unit) {
    var range by remember(zooms) { mutableStateOf(flipZoom(zooms.close.toFloat())..flipZoom(zooms.area.toFloat())) }
    val shown = zoomsFromRange(range.start, range.endInclusive) ?: zooms
    val widthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp().value.toDouble() }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            noBreak(stringResource(R.string.locate_zoom_value, stringResource(R.string.locate_zoom_close), spanText(shown.close, widthDp))),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            noBreak(stringResource(R.string.locate_zoom_value, stringResource(R.string.locate_zoom_area), spanText(shown.area, widthDp))),
            style = MaterialTheme.typography.titleSmall,
        )
    }
    RangeSlider(
        value = range,
        // Thumbs that would meet or cross stay where they were.
        onValueChange = { r -> if (zoomsFromRange(r.start, r.endInclusive) != null) range = r },
        onValueChangeFinished = {
            zoomsFromRange(range.start, range.endInclusive)?.let { if (it != zooms) onZooms(it) }
        },
        valueRange = MIN_ZOOM.toFloat()..MAX_ZOOM.toFloat(),
        steps = MAX_ZOOM - MIN_ZOOM - 1,
    )
    SliderScale(
        listOf(stringResource(R.string.locate_zoom_closer), stringResource(R.string.locate_zoom_wider)),
        listOf(0f, 1f),
    )
}

/** How wide the map is at [zoom] on a screen [widthDp] wide: "18 km". */
@Composable
private fun spanText(zoom: Int, widthDp: Double): String =
    when (val s = readableSpan(spanAtZoom(zoom.toDouble(), widthDp, SETTINGS_LATITUDE))) {
        is Span.Km -> stringResource(R.string.span_km, s.km)
        is Span.KmTenths -> stringResource(R.string.span_km_tenths, s.km)
        is Span.Metres -> stringResource(R.string.span_m, s.m)
    }
