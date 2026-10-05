// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import se.gangefors.moto.core.MotoException
import se.gangefors.moto.debug.DebugTools

@Composable
internal fun MapScreenScope.MapGestures() {
    with(state) {
        DisposableEffect(map, overlays, region) {
            val m = map
            val o = overlays
            val s = style
            if (m == null || o == null || s == null) return@DisposableEffect onDispose {}
            val ready = region as? RegionState.Ready
            ready?.let { showRegionOutline(s, it.engine.info(), it.engine.coverage(), darkMap) }
            val onClick = MapLibreMap.OnMapClickListener { tap ->
                // In ride mode (riding a route, or recording) the map only
                // shows; taps do nothing. A long-press still plans while
                // recording.
                if (rideModeNow.value) return@OnMapClickListener false
                if (marking) {
                    if (ready == null) notify(regionStatus(resources, region), long = true) else onMarkTap(ready, tap)
                    return@OnMapClickListener true
                }
                // A via point: select it, to remove just that one; any other
                // tap lets it go.
                val tappedVia = if (routeEnds != null && vias.isNotEmpty()) o.route.viaAt(m, tap) else null
                if (tappedVia != null && tappedVia in vias.indices) {
                    selectedVia = tappedVia
                    return@OnMapClickListener true
                }
                selectedVia = null
                // Another loop of the set, or route to choose, drawn faint:
                // show it.
                val start = loopStart
                val other = if (start != null && loops.size > 1) o.route.otherAt(m, tap) else null
                if (start != null && other != null && other in loops.indices) {
                    loopIndex = other
                    showLoop(start, loops, other)
                    return@OnMapClickListener true
                }
                val ends = routeEnds
                val opts = shownRoute?.second
                val choice = if (ends != null && routeChoices.size > 1) o.route.otherAt(m, tap) else null
                if (ends != null && opts != null && choice != null && choice in routeChoices.indices) {
                    showRouteChoice(ends.first, ends.second, routeChoices, choice, opts)
                    return@OnMapClickListener true
                }
                val hit = o.sections.sectionAt(m, tap)?.let { id -> sections.firstOrNull { it.id == id } }
                val tapped = hit?.let {
                    favouriteTap(
                        planning = routeEnds != null || loopStart != null || startPicked != null,
                        routeShown = shownSaved != null || shownRide != null,
                    )
                }
                if (hit != null && tapped == FavouriteTap.OPEN) {
                    showSection(hit, fit = false)
                } else if (hit != null) {
                    // The plan or the start step stays as it is; the info card
                    // that was open closes (one at a time).
                    closeInfoCardsFor(InfoCard.FAVOURITE)
                    o.snap.clear()
                    favouriteInfoId = hit.id
                } else if (ready == null) {
                    notify(regionStatus(resources, region), long = true)
                } else {
                    try {
                        val info = DebugTools.query("road info") { ready.engine.roadAt(tap.toLatLon()) }
                        o.snap.show(tap, LatLng(info.point.position.lat, info.point.position.lon))
                        // One info card for what was tapped: the road's replaces
                        // the others.
                        closeInfoCardsFor(InfoCard.ROAD)
                        roadInfo = info
                        message = null
                    } catch (e: MotoException) {
                        o.snap.show(tap, null)
                        roadInfo = null
                        message = null
                        notify(coreErrorMessage(resources, e))
                    }
                }
                true
            }
            val onLongClick = MapLibreMap.OnMapLongClickListener { point ->
                if (ridingNow.value) return@OnMapLongClickListener false
                if (marking) return@OnMapLongClickListener false
                // No planning on the move (2026-10-03): stop first.
                val fix = (recording as? Recording.State.Active)?.lastFix ?: mapFix(map)
                if (!canPlan(fix, System.currentTimeMillis())) {
                    notify(resources.getString(R.string.plan_stop_first))
                    return@OnMapLongClickListener true
                }
                if (ready == null) {
                    notify(regionStatus(resources, region), long = true)
                    return@OnMapLongClickListener true
                }
                val ends = routeEnds
                if (addingVia && ends != null) {
                    addingVia = false
                    message = null
                    val added = insertVia(ends.first.toLatLon(), vias.map { it.toLatLon() }, ends.second.toLatLon(), point.toLatLon())
                    viasBefore = vias
                    vias = added.map { LatLng(it.lat, it.lon) }
                    return@OnMapLongClickListener true
                }
                when (val step = picker.onLongPress(point)) {
                    is RoutePicker.Step.StartSet -> {
                        routeEnds = null
                        routeThrough = null
                        loopStart = null
                        shownSaved = null
                        startPicked = null
                        // New start: check it lies on a road before keeping it.
                        val problem = runCatching { DebugTools.query("snap") { ready.engine.snap(point.toLatLon()) } }.exceptionOrNull()
                        if (problem != null) {
                            picker.reset()
                            message = null
                            notify(coreErrorMessage(resources, problem), long = true)
                        } else {
                            // The start card replaces any info card.
                            closeInfoCards(infoCardsToCloseOnPlanStart(planOpen = false))
                            o.route.show(point, null, null)
                            startPicked = point
                            message = resources.getString(if (hasLocation) R.string.route_pick_end_or_me else R.string.route_pick_end)
                        }
                    }
                    is RoutePicker.Step.Complete -> {
                        // An end with no road near it changes nothing: the route,
                        // the sheet and its choices stay, and the rider picks
                        // another end (the rider).
                        val problem = runCatching { DebugTools.query("snap") { ready.engine.snap(point.toLatLon()) } }.exceptionOrNull()
                        if (problem != null) {
                            notify(coreErrorMessage(resources, problem), long = true)
                            return@OnMapLongClickListener true
                        }
                        startPicked = null
                        // A new end: a route again, not a loop.
                        routeThrough = null
                        o.route.show(step.start, step.end, null)
                        message = null
                        // A new route, or the end moved: via points and an
                        // arrival time stay only for the same start.
                        if (routeEnds?.first != step.start) {
                            vias = emptyList()
                            arriveBy = null
                        }
                        // A new route starts its sheet empty; when the end moves
                        // the rows stay, dimmed, as for a setting (the rider).
                        val planWasOpen = routeEnds != null || loopStart != null
                        if (routeEnds == null) clearRoutes()
                        routeEnds = step.start to step.end
                        closeInfoCards(infoCardsToCloseOnPlanStart(planWasOpen))
                    }
                }
                true
            }
            m.addOnMapClickListener(onClick)
            m.addOnMapLongClickListener(onLongClick)
            onDispose {
                m.removeOnMapClickListener(onClick)
                m.removeOnMapLongClickListener(onLongClick)
            }
        }
    }
}
