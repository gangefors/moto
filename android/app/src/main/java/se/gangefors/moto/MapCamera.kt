// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import se.gangefors.moto.core.TrackPoint
import android.graphics.PointF
import org.maplibre.android.geometry.LatLngBounds
import se.gangefors.moto.core.LatLon

/** The map's last known location as a fix, if it has one. */
internal fun mapFix(map: MapLibreMap?): TrackPoint? {
    val lc = map?.locationComponent ?: return null
    if (!lc.isLocationComponentActivated) return null
    val l = lc.lastKnownLocation ?: return null
    return checkedFix(
        timeMs = l.time,
        lat = l.latitude,
        lon = l.longitude,
        accuracyM = if (l.hasAccuracy()) l.accuracy.toDouble() else null,
        speedMps = if (l.hasSpeed()) l.speed.toDouble() else null,
        bearingDeg = if (l.hasBearing()) l.bearing.toDouble() else null,
    )
}

/** How long the panels must stay still before the map fits to them. */
internal const val SETTLE_MS = 150L

/** Room between a fitted route and the panels around it. */
internal val FIT_MARGIN = 24.dp

/** The phone's most recent known position from any provider, if any
 * (the permission is already granted where this is called). */
@SuppressLint("MissingPermission")
internal fun lastKnownPosition(context: Context): Location? {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    return runCatching {
        manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
    }.getOrNull()
}

/** How long the start waits for the map to have drawn. */
internal const val START_READY_TIMEOUT_MS = 3_000L

/** How long after the first move the start checks the map once more. */
internal const val START_RECHECK_MS = 1_500L

/** How far off the rider the map's centre may be, in degrees (about 200 m), before the start moves it. */
internal const val START_OFF_DEGREES = 0.002

/** How far the zoom may be from the area's zoom before the start sets it. */
internal const val START_ZOOM_TOLERANCE = 0.3

/** What recording a ride asks for: precise location, and on Android 13+
 * permission to show the recording notification. */
internal fun recordingPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

internal fun hasLocationPermission(context: Context): Boolean =
    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

@SuppressLint("MissingPermission") // Only called after hasLocationPermission().
internal fun enableLocation(context: Context, map: MapLibreMap, style: Style) {
    map.locationComponent.apply {
        if (!isLocationComponentActivated) {
            activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style)
                    .useDefaultLocationEngine(true)
                    .build(),
            )
        }
        isLocationComponentEnabled = true
        renderMode = RenderMode.COMPASS
    }
}

/**
 * Follows the rider's position in the middle of the map, at [zoom] if
 * given. Fitting a route leaves its padding on the camera, which would
 * keep the position off centre. So the map first glides to the last known
 * position with no padding, in one move, and following starts when it
 * gets there (a separate padding change would be cut short by following's
 * own move). Without a known position the padding goes at once. A pan
 * during the glide leaves following off, like a pan while following.
 */
internal fun followRider(map: MapLibreMap, zoom: Double? = null) {
    val location = map.locationComponent
    val here = location.takeIf { it.isLocationComponentActivated }?.lastKnownLocation
    if (here == null) {
        map.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0))
        zoom?.let { map.moveCamera(CameraUpdateFactory.zoomTo(it)) }
        location.cameraMode = CameraMode.TRACKING
        return
    }
    // Not following during the glide, so the two moves don't fight.
    location.cameraMode = CameraMode.NONE
    val centred = CameraPosition.Builder()
        .target(LatLng(here.latitude, here.longitude))
        .padding(0.0, 0.0, 0.0, 0.0)
        .apply { zoom?.let { zoom(it) } }
        .build()
    map.animateCamera(
        CameraUpdateFactory.newCameraPosition(centred),
        FOLLOW_GLIDE_MS,
        object : MapLibreMap.CancelableCallback {
            override fun onFinish() {
                location.cameraMode = CameraMode.TRACKING
            }

            override fun onCancel() = Unit
        },
    )
}

/** How long the map takes to glide to the rider's position. */
private const val FOLLOW_GLIDE_MS = 500

/** How long the section sheet's top edge must stay put before the map
 * fits the section above it, ms (the sheet slides up first). */
internal const val EDIT_FIT_SETTLE_MS = 250L

internal fun MapScreenScope.fitPaddingNow(): FitPadding {
    with(state) {
        val panels = fitPanels(
            landscape = landscape,
            width = mapSize.width,
            height = mapSize.height,
            insetLeft = insets.left,
            insetTop = insets.top,
            insetRight = insets.right,
            insetBottom = insets.bottom,
            topPanelBottom = topPanelBottom,
            columnRight = columnRight,
            buttonsLeft = buttonsLeft,
            buttonsTop = planButtonsTop,
            tagTop = tagTop,
            editTop = editTop,
            sheetTop = sheetTop,
            cardsTop = cardsTop,
            rightCardsTop = infoRightTop,
        )
        return fitPadding(panels, with(density) { FIT_MARGIN.roundToPx() })
    }
}

/** What the map shows clear of the panels, or `null` before it is laid out. */
internal fun MapScreenScope.visibleBounds(m: MapLibreMap, pad: FitPadding): GeoBounds? {
    with(state) {
        val (w, h) = mapSize.width.toFloat() to mapSize.height.toFloat()
        val (l, t, r, b) = listOf(pad.left, pad.top, w - pad.right, h - pad.bottom).map { it.toFloat() }
        if (r <= l || b <= t) return null
        val corners = listOf(PointF(l, t), PointF(r, t), PointF(l, b), PointF(r, b))
            .map { m.projection.fromScreenLocation(it) }
            .map { LatLon(it.latitude, it.longitude) }
        return boundsOf(listOf(corners))
    }
}

/**
 * Moves the map to show all of [lines] clear of the panels, zoomed in
 * as far as they allow (never closer than [minSpanM] across). Unless
 * [always], only when part of them is out of view or they are small in
 * it, so recalculating doesn't make the map jump.
 */
internal fun MapScreenScope.showOnMap(lines: List<List<LatLon>>, always: Boolean, minSpanM: Double = MIN_FIT_SPAN_M) {
    with(state) {
        val m = map ?: return
        val target = boundsOf(lines)?.withMinSpan(minSpanM) ?: return
        val pad = fitPaddingNow()
        if (!always && !needsFit(visibleBounds(m, pad), target)) return
        // Following the rider's position would pull the map straight back;
        // the location button turns it on again.
        m.locationComponent.takeIf { it.isLocationComponentActivated }?.cameraMode = CameraMode.NONE
        val bounds = LatLngBounds.Builder()
            .include(LatLng(target.north, target.east))
            .include(LatLng(target.south, target.west))
            .build()
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad.left, pad.top, pad.right, pad.bottom))
    }
}

internal fun MapScreenScope.mapWidthDp(): Double = with(state) { (mapSize.width / density.density).toDouble().coerceAtLeast(1.0) }

/** The route, loops, saved route or ride on the map, if any. */
internal fun MapScreenScope.planLines(): List<List<LatLon>> = with(state) {
    when {
        loopStart != null -> loops.map { it.geometry }
        routeEnds != null -> routeChoices.map { it.geometry }
        else -> listOfNotNull(shownSaved?.line ?: shownRide?.line)
    }
}

/** What the map shows now, for the location button. */
internal fun MapScreenScope.locateView(m: MapLibreMap, here: android.location.Location?): LocateView {
    with(state) {
        if (overviewShown) return LocateView.OVERVIEW
        val cam = m.cameraPosition
        val target = cam.target ?: return LocateView.ELSEWHERE
        if (here == null) {
            val tracking = m.locationComponent.takeIf { it.isLocationComponentActivated }?.cameraMode
            return if (tracking == CameraMode.TRACKING) LocateView.ON_RIDER else LocateView.ELSEWHERE
        }
        val offset = FloatArray(1)
        android.location.Location.distanceBetween(target.latitude, target.longitude, here.latitude, here.longitude, offset)
        val width = spanAtZoom(cam.zoom, mapWidthDp(), target.latitude)
        return if (isCentredOnRider(offset[0].toDouble(), width)) LocateView.ON_RIDER else LocateView.ELSEWHERE
    }
}

internal fun MapScreenScope.onLocateTap() {
    with(state) {
        val m = map ?: return
        val here = m.locationComponent.takeIf { it.isLocationComponentActivated }?.lastKnownLocation
        val plan = planLines().filter { it.isNotEmpty() }
        val zoom = m.cameraPosition.zoom
        when (val action = onLocateTap(locateView(m, here), zoom, plan.isNotEmpty(), zoomBeforeOverview, locateZooms)) {
            is LocateAction.Follow -> {
                overviewShown = false
                followRider(m, action.zoom)
            }
            LocateAction.ShowPlan -> {
                zoomBeforeOverview = zoom
                val rider = here?.let { listOf(LatLon(it.latitude, it.longitude)) }
                showOnMap(plan + listOfNotNull(rider), always = true)
                overviewShown = true
            }
        }
    }
}
