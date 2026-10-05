// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.gangefors.moto.core.Route
import se.gangefors.moto.debug.DebugTools

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
