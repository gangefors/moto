// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import se.gangefors.moto.core.RoadClass
import se.gangefors.moto.core.RoadInfo
import se.gangefors.moto.core.Surface as RoadSurface

/**
 * What the region file knows about the road the rider tapped: its kind,
 * surface, the speed the router assumes, how curvy it is and anything to
 * look out for. The cross closes it.
 */
@Composable
fun RoadInfoCard(info: RoadInfo, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val facts = roadFacts(info)
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 8.dp)) {
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Text(stringResource(roadClassName(info.`class`)), style = MaterialTheme.typography.titleMedium)
                Text(
                    listOf(
                        stringResource(surfaceName(info.surface)),
                        stringResource(R.string.road_speed, facts.speedKmh),
                        curvinessText(facts),
                    ).joinToString(" · "),
                )
                if (facts.notes.isNotEmpty()) {
                    Text(
                        facts.notes.map { stringResource(noteName(it)) }.joinToString(" · "),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                val small = MaterialTheme.typography.bodySmall
                val grey = MaterialTheme.colorScheme.onSurfaceVariant
                Text(stringResource(R.string.road_details, facts.lengthKm, info.wayId), style = small, color = grey)
                facts.tapOffM?.let { Text(stringResource(R.string.road_tap_off, it), style = small, color = grey) }
            }
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.Top)) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.road_close))
            }
        }
    }
}

@Composable
private fun curvinessText(facts: RoadFacts): String = when (facts.curviness) {
    Curviness.STRAIGHT -> stringResource(R.string.road_straight)
    Curviness.GENTLE -> stringResource(R.string.road_gentle, facts.curvyPercent)
    Curviness.CURVY -> stringResource(R.string.road_curvy, facts.curvyPercent)
    Curviness.VERY_CURVY -> stringResource(R.string.road_very_curvy, facts.curvyPercent)
}

@StringRes
private fun roadClassName(c: RoadClass): Int = when (c) {
    RoadClass.MOTORWAY -> R.string.road_motorway
    RoadClass.TRUNK -> R.string.road_trunk
    RoadClass.PRIMARY -> R.string.road_primary
    RoadClass.SECONDARY -> R.string.road_secondary
    RoadClass.TERTIARY -> R.string.road_tertiary
    RoadClass.UNCLASSIFIED -> R.string.road_unclassified
    RoadClass.RESIDENTIAL -> R.string.road_residential
    RoadClass.LIVING_STREET -> R.string.road_living_street
    RoadClass.SERVICE -> R.string.road_service
    RoadClass.TRACK -> R.string.road_track
    RoadClass.FERRY -> R.string.road_ferry
    RoadClass.OTHER -> R.string.road_other
}

@StringRes
private fun surfaceName(s: RoadSurface): Int = when (s) {
    RoadSurface.UNKNOWN -> R.string.surface_unknown
    RoadSurface.ASPHALT -> R.string.surface_asphalt
    RoadSurface.CONCRETE -> R.string.surface_concrete
    RoadSurface.PAVED -> R.string.surface_paved
    RoadSurface.SETT -> R.string.surface_sett
    RoadSurface.COMPACTED -> R.string.surface_compacted
    RoadSurface.GRAVEL -> R.string.surface_gravel
    RoadSurface.DIRT -> R.string.surface_dirt
}

@StringRes
private fun noteName(n: RoadNote): Int = when (n) {
    RoadNote.ONE_WAY -> R.string.road_one_way
    RoadNote.UNPAVED -> R.string.road_unpaved
    RoadNote.TOLL -> R.string.road_toll
    RoadNote.FERRY -> R.string.road_ferry_note
    RoadNote.DESTINATION_ONLY -> R.string.road_destination_only
}
