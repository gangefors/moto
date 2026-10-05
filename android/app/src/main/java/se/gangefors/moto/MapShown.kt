// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.gangefors.moto.core.SavedRoute
import se.gangefors.moto.core.Section
import se.gangefors.moto.core.Track
import se.gangefors.moto.debug.DebugTools
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import org.maplibre.android.geometry.LatLng

/** Shares ride [t] as GPX, as Routes & rides does. */
internal fun MapScreenScope.shareRide(t: Track) {
    with(state) {
        val s = (store as? StoreState.Ready)?.store ?: return
        scope.launch {
            val zone = ZoneId.systemDefault()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val gpx = DebugTools.query("saved GPX", ::bytesSummary) {
                        s.exportTrackGpx(t.id, rideName(t.name, t.startedAt, zone)) ?: error(resources.getString(R.string.rides_gone))
                    }
                    RouteShare.prepare(context, gpx, rideFileName(t.startedAt, zone), resources.getString(R.string.route_share_title))
                }
            }
            result.fold(
                onSuccess = { context.startActivity(it) },
                onFailure = { notify(resources.getString(R.string.route_share_failed, it.message ?: it.toString()), long = true) },
            )
        }
    }
}

/** Deletes ride [t] from its card: the card closes and the ride leaves
 * the map. */
internal fun MapScreenScope.deleteShownRide(t: Track) {
    with(state) {
        val s = (store as? StoreState.Ready)?.store ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { s.deleteTrack(t.id) } }
            result.onFailure { notify(resources.getString(R.string.ride_delete_failed, it.message ?: it.toString()), long = true) }
            if (result.isSuccess) {
                RideChanges.changed()
                if (shownRide?.track?.id == t.id) shownRide = null
            }
        }
    }
}

/** Deletes saved route [r] from its card: the card closes and the
 * route leaves the map. */
internal fun MapScreenScope.deleteShownSaved(r: SavedRoute) {
    with(state) {
        val s = (store as? StoreState.Ready)?.store ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { s.deleteRoute(r.id) } }
            result.onFailure { notify(resources.getString(R.string.saved_route_delete_failed, it.message ?: it.toString()), long = true) }
            if (result.isSuccess && shownSaved?.route?.id == r.id) {
                shownSaved = null
                overlays?.route?.show(null, null, null)
            }
        }
    }
}

internal fun MapScreenScope.hideSection() {
    with(state) {
        if (shownSectionId == null) return
        shownSectionId = null
        overlays?.route?.show(null, null, null)
    }
}

/**
 * One info card at a time (2026-10-04): opening one closes the others
 * (a road's, a favourite's, a favourite section's, a ride's, a saved
 * route's), the newest wins, and a plan starting closes them all
 * ([startLoop], a route's first end, [rideSection]). The
 * plan's card and the start card are not info cards. Closing a saved
 * route also takes it off the map, unless a plan is using the layer.
 */
internal fun MapScreenScope.closeInfoCards(cards: Set<InfoCard>) {
    with(state) {
        cards.forEach { card ->
            when (card) {
                InfoCard.ROAD -> if (roadInfo != null) {
                    roadInfo = null
                    overlays?.snap?.clear()
                }
                InfoCard.FAVOURITE -> favouriteInfoId = null
                InfoCard.SECTION -> if (routeEnds == null && loopStart == null) hideSection() else shownSectionId = null
                InfoCard.RIDE -> shownRide = null
                InfoCard.SAVED_ROUTE -> if (shownSaved != null) {
                    shownSaved = null
                    if (routeEnds == null && loopStart == null) overlays?.route?.show(null, null, null)
                }
            }
        }
    }
}

/** Opening [opening]: closes the other info cards. */
internal fun MapScreenScope.closeInfoCardsFor(opening: InfoCard) = with(state) { closeInfoCards(infoCardsToClose(opening)) }

/**
 * Shows saved section [s] with its card (the same whether picked on
 * the map or in Menu > Sections): in its rating's colour, wider and
 * edged, so it stands out from the other sections (faded meanwhile)
 * and one that no longer fits the map shows too (grey); the map moves
 * to it when [fit] (from the
 * page), not when it was tapped where the rider looks.
 */
internal fun MapScreenScope.showSection(s: Section, fit: Boolean) {
    with(state) {
        routeEnds = null
        loopStart = null
        startPicked = null
        picker.reset()
        closeInfoCardsFor(InfoCard.SECTION)
        overlays?.snap?.clear()
        shownSectionId = s.id
        // What was on the route layer (a saved route or ride) goes.
        overlays?.route?.show(null, null, null)
        if (fit) showOnMap(listOf(s.geometry), always = true)
    }
}

/** Before a saved route or ride is shown from Routes & rides: what
 * else was on the map goes (a plan, a picked start, a shown section,
 * route or ride, and the road's and a favourite's cards), so its card
 * is the only one. */
internal fun MapScreenScope.clearForShown() {
    with(state) {
        if (routeEnds != null) closeRoute()
        if (loopStart != null) closeLoop()
        startPicked = null
        picker.reset()
        message = null
        hideSection()
        shownSaved = null
        shownRide = null
        roadInfo = null
        overlays?.snap?.clear()
        favouriteInfoId = null
        overlays?.route?.show(null, null, null)
    }
}

@Composable
internal fun MapScreenScope.ShownEffects() {
    with(state) {
        // A saved route keeps only its line: its favourite stretches are
        // worked out from the favourites as they are now, when it shows and
        // whenever they change, and glow on it as on a new route (ADR-0011).
        LaunchedEffect(shownSaved?.route?.id, shownSaved?.line, sections, store) {
            val shown = shownSaved ?: return@LaunchedEffect
            val s = (store as? StoreState.Ready)?.store ?: return@LaunchedEffect
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    DebugTools.query("favourites on saved route", { "${it.parts.size} parts" }) { s.favouritePartsAlong(shown.line) }
                }.getOrNull()
            } ?: return@LaunchedEffect
            val now = shownSaved
            if (now == null || now.route.id != shown.route.id) return@LaunchedEffect
            shownSaved = now.copy(favouriteParts = found.parts, favouriteRatings = found.ratings)
            val start = LatLng(now.line.first().lat, now.line.first().lon)
            val end = if (now.route.isLoop) null else LatLng(now.line.last().lat, now.line.last().lon)
            overlays?.route?.show(start, end, now.line, favourites = found.parts, favouriteRatings = found.ratings)
        }

        // The shown section, or else the favourite whose facts are open, is
        // drawn standing out, so the rider sees which one the card is about.
        val shownForEdit = (shownSectionId ?: favouriteInfoId)?.let { id -> sections.firstOrNull { it.id == id } }
        LaunchedEffect(overlays, shownForEdit, editPreview, darkMap) {
            val s = shownForEdit
            if (s == null) {
                overlays?.sections?.showSelected(null, null)
                return@LaunchedEffect
            }
            val (line, arrows) = shownSectionLine(s.geometry, isOneWay(s.direction), editPreview)
            val look = shownSectionLook(s.rating, fitsTheMap(s.status), darkMap)
            overlays?.sections?.showSelected(line, look, arrows)
        }
        // Another section shown (or none): no longer the one from the page.
        LaunchedEffect(shownSectionId) {
            if (shownFromPage?.first != shownSectionId) shownFromPage = null
        }
        // Planning takes the map over: the shown section goes.
        LaunchedEffect(routeEnds, loopStart) {
            if (routeEnds != null || loopStart != null) shownSectionId = null
        }
    }
}
