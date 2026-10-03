// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import java.util.Date
import kotlinx.coroutines.delay
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.maps.Style
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import se.gangefors.moto.core.FavouriteNearby
import se.gangefors.moto.core.FollowPhase
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.NearFavourite
import se.gangefors.moto.core.Rating

/**
 * The route being ridden on the map (ADR-0011): ahead as planned (blue,
 * favourite glow, gravel dashes), behind grey, split by a line-gradient
 * on a source with line metrics, so each fix changes one paint property
 * and the line is never sent again; and the way back to the route as a
 * dashed line. Drawn under the line of the ride being recorded.
 */
class FollowOverlay(private val style: Style, private val darkMap: Boolean) {
    private val line = style.getSourceAs(LINE_SOURCE)
        ?: GeoJsonSource(LINE_SOURCE, GeoJsonOptions().withLineMetrics(true)).also(style::addSource)
    private val glow = style.getSourceAs(GLOW_SOURCE) ?: GeoJsonSource(GLOW_SOURCE).also(style::addSource)
    private val gravel = style.getSourceAs(GRAVEL_SOURCE) ?: GeoJsonSource(GRAVEL_SOURCE).also(style::addSource)
    private val back = style.getSourceAs(BACK_SOURCE) ?: GeoJsonSource(BACK_SOURCE).also(style::addSource)
    private var progress = DoubleArray(0)
    private var shown: RideRoute? = null

    init {
        if (style.getLayer(LINE_LAYER) == null) {
            val round = arrayOf(
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            )
            add(
                LineLayer(GLOW_LAYER, GLOW_SOURCE).withProperties(
                    PropertyFactory.lineColor(Expression.get(COLOR)),
                    PropertyFactory.lineWidth(routeWidthByZoom(18f)),
                    PropertyFactory.lineBlur(4f),
                    PropertyFactory.lineOpacity(0.55f),
                    *round,
                ),
            )
            add(
                LineLayer(CASING_LAYER, LINE_SOURCE).withProperties(
                    PropertyFactory.lineColor(CASING),
                    PropertyFactory.lineWidth(routeWidthByZoom(9f)),
                    PropertyFactory.lineOpacity(outlineByZoom(outlineStrength(darkMap))),
                    *round,
                ),
            )
            // The dark map: favourite stretches outlined in their colour,
            // as on a planned route.
            if (darkMap) add(favouriteEdge(EDGE_LAYER, GLOW_SOURCE, null, COLOR))
            add(
                LineLayer(LINE_LAYER, LINE_SOURCE).withProperties(
                    PropertyFactory.lineWidth(routeWidthByZoom(5f)),
                    PropertyFactory.lineGradient(gradient(0.0)),
                    *round,
                ),
            )
            add(
                LineLayer(GRAVEL_LAYER, GRAVEL_SOURCE).withProperties(
                    PropertyFactory.lineColor(CASING),
                    PropertyFactory.lineWidth(GRAVEL_DASH_WIDTH),
                    PropertyFactory.lineDasharray(dashesByZoom(::gravelDashes)),
                    PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ).apply { minZoom = GRAVEL_MIN_ZOOM },
            )
            add(
                LineLayer(BACK_CASING_LAYER, BACK_SOURCE).withProperties(
                    PropertyFactory.lineColor(CASING),
                    PropertyFactory.lineWidth(routeWidthByZoom(7f)),
                    PropertyFactory.lineOpacity(outlineByZoom(outlineStrength(darkMap))),
                    *round,
                ),
            )
            add(
                LineLayer(BACK_LAYER, BACK_SOURCE).withProperties(
                    PropertyFactory.lineColor(routeBlue(darkMap)),
                    PropertyFactory.lineWidth(routeWidthByZoom(4f)),
                    PropertyFactory.lineDasharray(arrayOf(1.2f, 1f)),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
        }
    }

    /** Below the recorded ride's line, so the ride shows on top. */
    private fun add(layer: LineLayer) {
        if (style.getLayer(RIDE_LINE_LAYER) != null) style.addLayerBelow(layer, RIDE_LINE_LAYER) else style.addLayer(layer)
    }

    /** Shows [route] (none: nothing), all of it ahead. */
    fun show(route: RideRoute?) {
        if (route === shown) return
        shown = route
        if (route == null || route.line.size < 2) {
            progress = DoubleArray(0)
            listOf(line, glow, gravel, back).forEach { it.setGeoJson(FeatureCollection.fromFeatures(emptyList())) }
            return
        }
        progress = mercatorProgress(route.line)
        line.setGeoJson(Feature.fromGeometry(lineString(route.line)))
        glow.setGeoJson(
            FeatureCollection.fromFeatures(
                route.favouriteParts.zip(route.favouriteRatings).filter { it.first.size >= 2 }.map { (part, rating) ->
                    Feature.fromGeometry(lineString(part)).apply { addStringProperty(COLOR, ratingColor(rating, darkMap)) }
                },
            ),
        )
        gravel.setGeoJson(
            FeatureCollection.fromFeatures(route.unpavedParts.filter { it.size >= 2 }.map { Feature.fromGeometry(lineString(it)) }),
        )
        at(0, 0.0)
    }

    /** The rider is at [t] along segment [segment] of the line: behind
     * grey, ahead blue. */
    fun at(segment: Int, t: Double) {
        style.getLayer(LINE_LAYER)?.setProperties(PropertyFactory.lineGradient(gradient(lineProgressAt(progress, segment, t))))
    }

    /** The way back to the route, or none. */
    fun back(way: List<LatLon>?) {
        back.setGeoJson(
            FeatureCollection.fromFeatures(
                if (way != null && way.size >= 2) listOf(Feature.fromGeometry(lineString(way))) else emptyList(),
            ),
        )
    }

    private fun gradient(p: Double): Expression = Expression.step(
        Expression.lineProgress(),
        Expression.color(passedColor(darkMap).toColorInt()),
        Expression.stop(p, Expression.color(routeBlue(darkMap).toColorInt())),
    )

    private fun lineString(points: List<LatLon>) = LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })

    private companion object {
        const val LINE_SOURCE = "moto-follow"
        const val GLOW_SOURCE = "moto-follow-glow"
        const val GRAVEL_SOURCE = "moto-follow-gravel"
        const val BACK_SOURCE = "moto-follow-back"
        const val LINE_LAYER = "moto-follow-line"
        const val CASING_LAYER = "moto-follow-casing"
        const val GLOW_LAYER = "moto-follow-glow"
        const val EDGE_LAYER = "moto-follow-favourite-edge"
        const val GRAVEL_LAYER = "moto-follow-gravel"
        const val BACK_LAYER = "moto-follow-back"
        const val BACK_CASING_LAYER = "moto-follow-back-casing"
        const val RIDE_LINE_LAYER = "moto-ride-line"
        const val COLOR = "color"
        const val CASING = "#ffffff"
    }
}

/** The route behind the rider: grey, lighter on the dark map. */
fun passedColor(darkMap: Boolean): String = if (darkMap) "#80868b" else "#9aa0a6"

/**
 * The card at the top while riding a route (mockup "Ride a route" v5,
 * section 3a): distance and time left, the progress bar with the arrival
 * time after it, and a row per favourite near (at most two); off the
 * route in the error colours; joining and at the end a headline. Its X
 * stops following and keeps recording.
 */
@Composable
fun RideCard(
    following: Following,
    darkMap: Boolean,
    onStopFollowing: () -> Unit,
    onStopNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // The clock for the arrival time and the countdown.
    val now by produceState(System.currentTimeMillis(), following.stopsAtMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(if (following.stopsAtMs != null) 250 else 5_000)
        }
    }
    val state = following.state
    val off = state.phase == FollowPhase.OFF_ROUTE
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        color = if (off) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (off) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, bottom = 12.dp)) {
            Row {
                Column(Modifier.weight(1f).padding(top = 12.dp, end = 4.dp)) {
                    when (state.phase) {
                        FollowPhase.ON_ROUTE -> {
                            val minutes = (state.leftS / 60).toInt()
                            val left = stringResource(R.string.route_km, rideKm(state.leftM))
                            val time = durationText(minutes)
                            val said = stringResource(R.string.ride_left_description, rideKm(state.leftM), time)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                itemVerticalAlignment = Alignment.Bottom,
                                modifier = Modifier.clearAndSetSemantics { contentDescription = said },
                            ) {
                                Text(noBreak(left), style = MaterialTheme.typography.headlineMedium)
                                Text(
                                    noBreak(time),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 2.dp),
                                )
                            }
                        }
                        FollowPhase.OFF_ROUTE -> {
                            Text(stringResource(R.string.ride_off_title), style = MaterialTheme.typography.headlineSmall)
                            Text(
                                stringResource(R.string.ride_off_card, offWords(following)),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        FollowPhase.JOINING -> {
                            val wrongWay = state.wrongWay
                            Text(
                                stringResource(if (wrongWay) R.string.ride_to_start else R.string.ride_joining),
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            val backM = following.backM
                            Text(
                                if (wrongWay) {
                                    stringResource(R.string.ride_wrong_way)
                                } else if (following.back != null && backM != null) {
                                    stringResource(R.string.ride_joining_text, rideKm(backM), rideKm(state.totalM))
                                } else {
                                    stringResource(R.string.ride_joining_near)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FollowPhase.FINISHED -> {
                            Text(
                                stringResource(if (following.route.isLoop) R.string.ride_finished_loop else R.string.ride_finished_route),
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                stringResource(R.string.ride_finished_text, following.route.name, rideKm(state.totalM)),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                IconButton(onClick = onStopFollowing) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.ride_stop_following))
                }
            }
            Column(Modifier.padding(end = 12.dp)) {
                when (state.phase) {
                    FollowPhase.ON_ROUTE -> {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            LinearProgressIndicator(
                                progress = { progressShown(state) },
                                modifier = Modifier.weight(1f).height(8.dp),
                                strokeCap = StrokeCap.Round,
                            )
                            Spacer(Modifier.width(12.dp))
                            val arrive = android.text.format.DateFormat.getTimeFormat(context)
                                .format(Date(now + (state.leftS * 1000).toLong()))
                            val arriveSaid = stringResource(R.string.ride_arrive_description, arrive)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clearAndSetSemantics { contentDescription = arriveSaid },
                            ) {
                                Icon(painterResource(R.drawable.ic_clock), contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(noBreak(arrive), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        if (state.favourites.isNotEmpty()) {
                            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                state.favourites.take(2).forEach { FavouriteRow(it, darkMap) }
                            }
                        }
                    }
                    FollowPhase.FINISHED -> {
                        val left = countdownS(following.stopsAtMs, now)
                        if (left != null) {
                            Text(
                                stringResource(R.string.ride_stops_in, left),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            LinearProgressIndicator(
                                progress = { (left * 1000f / FINISH_COUNTDOWN_MS).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                strokeCap = StrokeCap.Round,
                            )
                        }
                        FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onStopNow, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                                Icon(painterResource(R.drawable.ic_stop), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                OneLine(stringResource(R.string.ride_stop_now))
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

/** "Turn round: back on it in 0.4 km." and the like. */
@Composable
private fun offWords(f: Following): String {
    val m = f.backM ?: return ""
    return stringResource(if (f.turnRound) R.string.ride_off_turn_round else R.string.ride_off_back_in, rideKm(m))
}

/** A favourite near: star and rating in its colour, the distance at the
 * end; tinted while the rider is on it (its distance then what is left). */
@Composable
private fun FavouriteRow(f: NearFavourite, darkMap: Boolean) {
    val colour = Color(ratingColor(f.rating, darkMap).toColorInt())
    val name = stringResource(ratingName(f.rating))
    val said = if (f.on) {
        stringResource(R.string.ride_fav_on_description, name, rideKm(f.distanceM))
    } else {
        stringResource(R.string.ride_fav_in_description, name, rideKm(f.distanceM))
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(if (f.on) Modifier.background(colour.copy(alpha = 0.22f)) else Modifier)
            .padding(horizontal = 8.dp)
            .clearAndSetSemantics { contentDescription = said },
    ) {
        Icon(painterResource(R.drawable.ic_star), contentDescription = null, tint = colour, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(noBreak(stringResource(R.string.route_km, rideKm(f.distanceM))), style = MaterialTheme.typography.titleMedium)
    }
}

fun ratingName(r: Rating): Int = when (r) {
    Rating.GOOD -> R.string.rating_good
    Rating.GREAT -> R.string.rating_great
    Rating.EPIC -> R.string.rating_epic
}

/** After Android stopped the app mid-ride: carry on, or save the ride. */
@Composable
fun ResumeRideDialog(name: String, onCarryOn: () -> Unit, onSave: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.ride_resume_title)) },
        text = { Text(stringResource(R.string.ride_resume_text, name)) },
        confirmButton = { TextButton(onClick = onCarryOn) { OneLine(stringResource(R.string.ride_resume_carry_on)) } },
        dismissButton = { TextButton(onClick = onSave) { OneLine(stringResource(R.string.ride_resume_save)) } },
    )
}

/** + and − on one surface, like the map's other buttons: a pinch is hard
 * with gloves while riding or recording (2026-10-03). [onZoom]
 * gets the zoom levels to add. */
@Composable
fun ZoomButtons(onZoom: (Double) -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(24.dp), shadowElevation = 3.dp, tonalElevation = 3.dp, modifier = modifier.width(48.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = { onZoom(ZOOM_BUTTON_STEP) }, modifier = Modifier.size(48.dp, 52.dp)) {
                Icon(painterResource(R.drawable.ic_zoom_in), stringResource(R.string.zoom_in))
            }
            HorizontalDivider(Modifier.width(28.dp))
            IconButton(onClick = { onZoom(-ZOOM_BUTTON_STEP) }, modifier = Modifier.size(48.dp, 52.dp)) {
                Icon(painterResource(R.drawable.ic_zoom_out), stringResource(R.string.zoom_out))
            }
        }
    }
}

/**
 * The card at the top while recording without a route (the rider,
 * 2026-10-03): how far and how long the recording has gone, and the
 * favourites near the rider ([near], nearest first), each with its rating,
 * how far and an arrow the way to it on the map, turned [mapBearing]
 * degrees; on one, how much of it is left, the row tinted.
 */
@Composable
fun RecordingCard(
    distanceM: Double,
    startedAtMs: Long,
    pausedMs: Long,
    near: List<FavouriteNearby>,
    mapBearing: Float,
    darkMap: Boolean,
    modifier: Modifier = Modifier,
) {
    val now by produceState(System.currentTimeMillis(), startedAtMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(5_000)
        }
    }
    val minutes = recordingMinutes(startedAtMs, now, pausedMs).toInt()
    val km = stringResource(R.string.route_km, rideKm(distanceM))
    val time = durationText(minutes)
    val said = stringResource(R.string.recording_card_description, rideKm(distanceM), time)
    Surface(modifier = modifier, shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                itemVerticalAlignment = Alignment.Bottom,
                modifier = Modifier.clearAndSetSemantics { contentDescription = said },
            ) {
                Text(noBreak(km), style = MaterialTheme.typography.headlineMedium)
                Text(
                    noBreak(time),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            if (near.isNotEmpty()) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    near.forEach { NearbyRow(it, mapBearing, darkMap) }
                }
            }
        }
    }
}

@Composable
private fun NearbyRow(f: FavouriteNearby, mapBearing: Float, darkMap: Boolean) {
    val colour = Color(ratingColor(f.rating, darkMap).toColorInt())
    val name = stringResource(ratingName(f.rating))
    val left = f.leftM
    val on = f.on && left != null
    val said = if (on) {
        stringResource(R.string.ride_fav_on_description, name, rideKm(left))
    } else {
        stringResource(R.string.ride_fav_near_description, name, rideKm(f.distanceM))
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(if (on) Modifier.background(colour.copy(alpha = 0.22f)) else Modifier)
            .padding(horizontal = 8.dp)
            .clearAndSetSemantics { contentDescription = said },
    ) {
        Icon(painterResource(R.drawable.ic_star), contentDescription = null, tint = colour, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(
            noBreak(stringResource(R.string.route_km, rideKm(if (on) left!! else f.distanceM))),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.width(8.dp))
        // The way to it on the map, which turns with the rider; none on it.
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (!on) {
                Icon(
                    painterResource(R.drawable.ic_navigation),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp).rotate(arrowOnMap(f.bearingDeg, mapBearing)),
                )
            }
        }
    }
}

/**
 * Asked before the ride card's X leaves the route, so a stray tap is
 * harmless (2026-10-03). While [recording]: End ride (stops
 * following and recording), Keep recording (stops following only) or
 * Cancel, stacked as three don't fit in a row; before recording started:
 * Stop riding or Cancel.
 */
@Composable
fun LeaveRouteDialog(recording: Boolean, onEndRide: () -> Unit, onKeepRecording: () -> Unit, onDismiss: () -> Unit) {
    if (recording) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.ride_leave_title)) },
            text = { Text(stringResource(R.string.ride_leave_text)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = onEndRide) { OneLine(stringResource(R.string.ride_leave_end)) }
                    TextButton(onClick = onKeepRecording) { OneLine(stringResource(R.string.ride_leave_keep)) }
                    TextButton(onClick = onDismiss) { OneLine(stringResource(R.string.cancel)) }
                }
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.ride_stop_title)) },
            text = { Text(stringResource(R.string.ride_stop_text)) },
            confirmButton = { TextButton(onClick = onKeepRecording) { OneLine(stringResource(R.string.ride_stop_riding)) } },
            dismissButton = { TextButton(onClick = onDismiss) { OneLine(stringResource(R.string.cancel)) } },
        )
    }
}
