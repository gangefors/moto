// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.gangefors.moto.core.Route
import se.gangefors.moto.debug.DebugTools
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap

/** Rides [route] (ADR-0011): planning ends, recording starts (or the
 * ride being recorded follows it), asking for the permissions first. */
internal fun MapScreenScope.beginRide(route: RideRoute) {
    with(state) {
        if (routeEnds != null) closeRoute()
        if (loopStart != null) closeLoop()
        shownSaved = null
        shownRide = null
        overlays?.route?.show(null, null, null)
        startPicked = null
        if (recording is Recording.State.Active) {
            RecordingService.ride(context, route)
        } else {
            pendingRide = route
            recordPermissions.launch(recordingPermissions())
        }
    }
}

/** Rides recorded ride [r] again (2026-10-02): its line, a
 * loop when it ended where it started, its favourites as they are
 * now, in the time it took. The new recording gets the usual name. */
internal fun MapScreenScope.rideAgain(r: ShownRide) {
    with(state) {
        val line = rideAgainLine(r.segments)
        if (line == null) {
            notify(resources.getString(R.string.ride_again_too_short))
            return
        }
        val s = (store as? StoreState.Ready)?.store
        scope.launch {
            val found = s?.let {
                withContext(Dispatchers.IO) {
                    runCatching {
                        DebugTools.query("favourites on ride", { "${it.parts.size} parts" }) { s.favouritePartsAlong(line) }
                    }.getOrNull()
                }
            }
            beginRide(
                RideRoute(
                    rideName(r.track.name, r.track.startedAt, ZoneId.systemDefault()),
                    rideAgainIsLoop(line),
                    rideAgainDurationS(r.track.startedAt, r.track.endedAt),
                    line,
                    found?.parts ?: emptyList(),
                    found?.ratings ?: emptyList(),
                ),
            )
        }
    }
}

/** Rides a planned route or loop, named from where it goes, as a saved
 * one is ("Loop from Höör via Linderöd"). */
internal fun MapScreenScope.startRide(route: Route, isLoop: Boolean) {
    with(state) {
        val engine = (region as? RegionState.Ready)?.engine
        scope.launch {
            val named = engine?.let { e ->
                withContext(Dispatchers.Default) {
                    runCatching {
                        val far = if (isLoop) farthestPoint(route.geometry)?.let { p -> e.describe(listOf(p, p)) } else null
                        planName(isLoop, e.describe(route.geometry), far)
                    }.getOrNull()
                }
            }
            val name = named?.let { planNameText(resources, it) }
                ?: resources.getString(if (isLoop) R.string.ride_name_loop else R.string.ride_name_route)
            beginRide(rideRouteOf(route, name, isLoop))
        }
    }
}

@Composable
internal fun MapScreenScope.RideEffects() {
    with(state) {
        LaunchedEffect(riding) { if (!riding) leavingRoute = false }

        // Ride settings: the screen stays on while a ride records, for a phone
        // on a handlebar mount.
        val view = LocalView.current
        val screenOn = keepScreenOn && recording is Recording.State.Active
        DisposableEffect(view, screenOn) {
            view.keepScreenOn = screenOn
            onDispose { view.keepScreenOn = false }
        }
        LaunchedEffect(overlays, recording, shownRide) {
            overlays?.ride?.show((recording as? Recording.State.Active)?.line?.let { listOf(it) } ?: shownRide?.segments)
        }
        // The route being ridden: ahead and behind, and the way back to it.
        LaunchedEffect(overlays, following?.route) { overlays?.follow?.show(following?.route) }
        LaunchedEffect(overlays, following?.state?.segment, following?.state?.segmentT) {
            val st = following?.state ?: return@LaunchedEffect
            overlays?.follow?.at(st.segment.toInt(), st.segmentT)
        }
        LaunchedEffect(overlays, following?.back) { overlays?.follow?.back(following?.back) }
        LaunchedEffect(recording) {
            when (val r = recording) {
                // Short, so the toast stays clear of the buttons; the figures
                // (battery use too) go to the debug tools.
                is Recording.State.Finished -> {
                    Toasts.show(resources.getString(R.string.recording_saved))
                    val minutes = (((r.track.endedAt ?: r.track.startedAt) - r.track.startedAt) / 60).toInt()
                    DebugTools.rideEnded(context, sectionKm(r.track.distanceM), minutes, r.batteryPerHour)
                    // Named from where it went, as an imported ride is.
                    val s = (store as? StoreState.Ready)?.store
                    val engine = (region as? RegionState.Ready)?.engine
                    if (s != null && engine != null && r.track.name == null) {
                        withContext(Dispatchers.IO) { s.nameRide(resources, engine, r.track) }
                        RideChanges.changed()
                    }
                }
                is Recording.State.Failed -> notify(resources.getString(R.string.recording_failed, r.message), long = true)
                else -> Unit
            }
        }
    }
}

@Composable
internal fun MapScreenScope.RideModeEffects() {
    with(state) {
        LaunchedEffect(rideMode) {
            if (!rideMode) {
                ridePanned = false
                rideZoomNudge = 0.0
            }
        }
        // Recording pauses while the rider plans and carries on after
        // (2026-10-03): no standing still and GPS jitter in the ride.
        LaunchedEffect(pauseWanted) { RecordingService.pause(context, pauseWanted) }
        val lastFix = (recording as? Recording.State.Active)?.lastFix
        LaunchedEffect(freeRiding, lastFix, sections, store) {
            if (!freeRiding) {
                nearby = emptyList()
                return@LaunchedEffect
            }
            val s = (store as? StoreState.Ready)?.store ?: return@LaunchedEffect
            val fix = lastFix ?: return@LaunchedEffect
            nearby = withContext(Dispatchers.IO) {
                runCatching { s.nearFavourites(fix.position, fix.bearingDeg) }.getOrNull()
            } ?: return@LaunchedEffect
        }
        DisposableEffect(map, freeRiding) {
            val m = map
            if (m == null || !freeRiding) return@DisposableEffect onDispose {}
            val move = MapLibreMap.OnCameraMoveListener { mapBearing = m.cameraPosition.bearing.toFloat() }
            m.addOnCameraMoveListener(move)
            mapBearing = m.cameraPosition.bearing.toFloat()
            onDispose { m.removeOnCameraMoveListener(move) }
        }
    }
}

@Composable
internal fun MapScreenScope.RideCameraEffects() {
    with(state) {
        // Riding a route (ADR-0011): the map follows the rider, the way they
        // are going up (or north up), the rider low on the screen; a pan or
        // pinch stops it until Recentre. MapLibre's compass shows while the map
        // is turned, but takes no taps while riding: a tap would fix north up
        // with no way back (2026-10-03; a switch for that comes later).
        DisposableEffect(map, rideMode) {
            val compass = if (map != null) mapView.findCompass() else null
            compass?.isClickable = !rideMode
            onDispose { compass?.isClickable = true }
        }
        DisposableEffect(map, style, hasLocation, rideMode, turnMap, ridePanned, mapSize, landscape, insets) {
            val m = map
            val s = style
            if (m == null || s == null || !hasLocation || !rideMode) return@DisposableEffect onDispose {}
            enableLocation(context, m, s)
            val lc = m.locationComponent
            lc.renderMode = RenderMode.GPS
            if (!ridePanned) {
                // On its side the rider sits in the right half, clear of the card.
                val d = density.density
                val sides = rideViewSidesDp(landscape, (mapSize.width / d).roundToInt(), insets.left / d, insets.right / d)
                m.moveCamera(
                    CameraUpdateFactory.paddingTo(
                        (sides.leftDp * d).toDouble(),
                        riderTopPadding(mapSize.height).toDouble(),
                        (sides.rightDp * d).toDouble(),
                        0.0,
                    ),
                )
                // Straight to the ride's zoom as it starts following, so +
                // and − step from there, not from wherever the map was.
                val speed = (recording as? Recording.State.Active)?.lastFix?.speedMps
                val zoom = rideZoom(speed, rideZoomOffset(rideZoomStep) + rideZoomNudge)
                lc.setCameraMode(
                    if (turnMap) CameraMode.TRACKING_GPS else CameraMode.TRACKING,
                    RIDE_CAMERA_TRANSITION_MS,
                    zoom,
                    if (turnMap) null else 0.0,
                    null,
                    null,
                )
            }
            val dismissed = object : OnCameraTrackingChangedListener {
                override fun onCameraTrackingDismissed() {
                    if (rideModeNow.value) ridePanned = true
                }

                override fun onCameraTrackingChanged(currentMode: Int) = Unit
            }
            lc.addOnCameraTrackingChangedListener(dismissed)
            onDispose { lc.removeOnCameraTrackingChangedListener(dismissed) }
        }
        // Ride mode over: the map's padding goes and the position shows as
        // before; a map that followed the rider turned stops, north up again
        // (only when ride mode was on: not at the start, when the map follows
        // the rider to the area zoom).
        LaunchedEffect(map, rideMode) {
            val m = map ?: return@LaunchedEffect
            if (rideMode) {
                wasRideMode = true
                return@LaunchedEffect
            }
            m.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0))
            val lc = m.locationComponent.takeIf { it.isLocationComponentActivated }
            lc?.renderMode = RenderMode.COMPASS
            if (wasRideMode) {
                wasRideMode = false
                if (lc?.cameraMode == CameraMode.TRACKING_GPS) lc.cameraMode = CameraMode.NONE
                m.animateCamera(CameraUpdateFactory.bearingTo(0.0))
            }
        }
        // Zoom by speed while followed: closer when slow, wider at speed.
        val rideZoomTarget = if (rideMode) {
            Math.round(rideZoom((recording as? Recording.State.Active)?.lastFix?.speedMps, rideZoomOffset(rideZoomStep) + rideZoomNudge) * 4) / 4.0
        } else {
            0.0
        }
        LaunchedEffect(map, rideMode, ridePanned, rideZoomTarget) {
            val m = map ?: return@LaunchedEffect
            if (!rideMode || ridePanned) return@LaunchedEffect
            m.locationComponent.takeIf { it.isLocationComponentActivated }
                ?.zoomWhileTracking(rideZoomTarget, rideZoomDurationMs(SystemClock.elapsedRealtime(), rideZoomTappedAt))
        }
    }
}
