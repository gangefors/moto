// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.maplibre.android.geometry.LatLng
import se.gangefors.moto.core.Favourites
import se.gangefors.moto.core.RouteOptions
import se.gangefors.moto.core.LoopOptions
import se.gangefors.moto.core.Route
import se.gangefors.moto.debug.DebugTools
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.gangefors.moto.core.Avoid
import se.gangefors.moto.core.FavouritesMode
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.Section
import se.gangefors.moto.core.UnriddenMode
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import se.gangefors.moto.core.defaultRouteOptions

/** What a set of loops is found for: the same request gives the same
 * loops, so loops found ahead for it can be shown. */
internal data class LoopRequest(
    val start: LatLng,
    val choice: LoopChoice,
    val opts: RouteOptions,
    val favourites: Favourites?,
    val shape: LoopOptions,
)

/** Routes found between [ends]: the [choices], the one shown ([index]),
 * the options they were found with and when ([at], epoch seconds). */
internal data class FoundRoutes(
    val ends: Pair<LatLng, LatLng>,
    val choices: List<Route>,
    val index: Int,
    val opts: RouteOptions,
    val at: Long,
)

internal fun MapScreenScope.changeFavourites(f: FavouritesMode) {
    with(state) {
        favouritesMode = f
        RoutePrefs.setFavourites(context, f)
    }
}

internal fun MapScreenScope.changeUnridden(u: UnriddenMode) {
    with(state) {
        unriddenMode = u
        RoutePrefs.setUnridden(context, u)
    }
}

internal fun MapScreenScope.changeAvoid(a: Avoid) {
    with(state) {
        avoid = a
        RoutePrefs.setAvoid(context, a)
    }
}

/** Shows route choice [index] of [set] (found with [opts]) with its
 * figures, the others faint; the fastest of several says so, and is
 * grey unless it is also the suggested road. */
internal fun MapScreenScope.showRouteChoice(start: LatLng, end: LatLng, set: List<Route>, index: Int, opts: RouteOptions) {
    with(state) {
        val r = set.getOrNull(index) ?: return
        // Loops through a section have no fastest one.
        val fastest = if (routeThrough != null) -1 else fastestChoice(set.size)
        val dull = fastest?.takeIf { fastestIsDull(set.size, set.last().suggested) }
        val others = set.mapIndexedNotNull { i, l -> if (i == index) null else i to l.geometry }
        overlays?.route?.show(
            start, end, r.geometry, r.favouriteParts, r.unpavedParts, vias,
            others = others, dull = index == dull, dullOther = dull, favouriteRatings = r.favouriteRatings,
        )
        routeIndex = index
        // Going back to these routes shows the one picked last.
        lastFound?.takeIf { it.choices === set }?.let { lastFound = it.copy(index = index) }
        routeSummary = summarize(r.distanceM, r.durationS, r.favouriteShare, r.fastestDurationS, r.curvyShare, r.unpavedM, r.tollM, r.unriddenShare)
            .copy(fastest = index == fastest)
        shownRoute = r to opts
    }
}

/** Shows loop [index] of [set] from [start], the others faint. */
internal fun MapScreenScope.showLoop(start: LatLng, set: List<Route>, index: Int) {
    with(state) {
        val r = set.getOrNull(index) ?: return
        val others = set.mapIndexedNotNull { i, l -> if (i == index) null else i to l.geometry }
        overlays?.route?.show(
            start, null, r.geometry, r.favouriteParts, r.unpavedParts,
            others = others, favouriteRatings = r.favouriteRatings,
        )
    }
}

/** Forgets the last loops, so a new loop sheet starts empty (finding
 * loops) instead of keeping their rows, dimmed, as a setting's change
 * does. */
internal fun MapScreenScope.clearLoops() {
    with(state) {
        loops = emptyList()
        loopIndex = 0
        loopProblem = null
        loopKept = 0
    }
}

/**
 * Where the rider is, to plan from: their newest fix, with a note when
 * it is an older one; null, saying it waits for GPS, without one.
 */
internal fun MapScreenScope.riderStart(): LatLng? {
    with(state) {
        val active = recording as? Recording.State.Active
        val fix = when (val p = riderPosition(active?.lastFix, mapFix(map), System.currentTimeMillis())) {
            is RiderPosition.Fresh -> p.fix
            is RiderPosition.LastKnown -> {
                notify(resources.getString(R.string.plan_last_known))
                p.fix
            }
            RiderPosition.None -> {
                notify(resources.getString(R.string.plan_waiting_gps))
                return null
            }
        }
        return LatLng(fix.position.lat, fix.position.lon)
    }
}

/** Loops from [start], heading the default way: the standard set
 * (seed 0), or the set of [seed]. A new plan closes the info cards. */
internal fun MapScreenScope.startLoop(start: LatLng, seed: UInt = 0u) {
    with(state) {
        val planWasOpen = routeEnds != null || loopStart != null
        startPicked = null
        // A plan replaces the step that led to it ("Point set…").
        message = null
        loopSeed = seed
        loopDirection = defaultDirection
        clearLoops()
        loopStart = start
        closeInfoCards(infoCardsToCloseOnPlanStart(planWasOpen))
    }
}

/** Forgets the last route choices, so a new route sheet starts empty
 * (finding a route) instead of keeping their rows, dimmed, as a moved
 * end or a setting's change does. */
internal fun MapScreenScope.clearRoutes() {
    with(state) {
        routeSummary = null
        shownRoute = null
        routeChoices = emptyList()
        routeIndex = 0
        routeKept = 0
        routeProblem = null
        lastFound = null
        restoring = null
    }
}

internal fun MapScreenScope.closeRoute() {
    with(state) {
        routeEnds = null
        clearRoutes()
        routeThrough = null
        picker.reset()
        vias = emptyList()
        addingVia = false
        arriveBy = null
        overlays?.route?.show(null, null, null)
    }
}

internal fun MapScreenScope.closeLoop() {
    with(state) {
        loopStart = null
        clearLoops()
        loopsAhead.clear()
        overlays?.route?.show(null, null, null)
    }
}

/** Hands [line] to a nav app as GPX named [gpxName]; [opts] place its
 * route points (see `Engine.routeGpx`). */
internal fun MapScreenScope.shareLine(line: List<LatLon>, gpxName: String, opts: RouteOptions) {
    with(state) {
        val engine = (region as? RegionState.Ready)?.engine ?: return
        scope.launch {
            val now = System.currentTimeMillis() / 1000
            val zone = ZoneId.systemDefault()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val gpx = DebugTools.query("route GPX", ::bytesSummary) { engine.routeGpx(line, gpxName, opts) }
                    RouteShare.prepare(
                        context,
                        gpx,
                        routeFileName(now, zone),
                        resources.getString(R.string.route_share_title),
                    )
                }
            }
            result.fold(
                onSuccess = { context.startActivity(it) },
                onFailure = {
                    notify(resources.getString(R.string.route_share_failed, it.message ?: it.toString()), long = true)
                },
            )
        }
    }
}

/** Hands [r] to a nav app as GPX through the share sheet (PRD R9);
 * [opts] are the options it was found with. */
internal fun MapScreenScope.shareRoute(r: Route, opts: RouteOptions) {
    with(state) {
        val now = System.currentTimeMillis() / 1000
        shareLine(r.geometry, routeGpxName(now, ZoneId.systemDefault(), r.distanceM / 1000.0), opts)
    }
}

/**
 * Plans a ride of section [s] from the rider's position: a loop out
 * through it and back ([loop]; both ways round for a two-way section),
 * or a route to its nearer end and along it. Shown in the route sheet.
 */
internal fun MapScreenScope.rideSection(s: Section, loop: Boolean) {
    with(state) {
        val from = riderStart() ?: return
        val oneWay = isOneWay(s.direction)
        val (near, far) = sectionEnds(s.geometry, oneWay, from.toLatLon()) ?: return
        val planWasOpen = routeEnds != null || loopStart != null
        dataPage = null
        sectionFilter = SectionFilter()
        hideSection()
        shownSaved = null
        roadInfo = null
        overlays?.snap?.clear()
        loopStart = null
        startPicked = null
        message = null
        arriveBy = null
        picker.reset()
        picker.startAt(from)
        val ends = LatLng(near.lat, near.lon) to LatLng(far.lat, far.lon)
        clearRoutes()
        if (loop) {
            vias = listOf(ends.first, ends.second)
            routeThrough = !oneWay
            overlays?.route?.show(from, null, null)
            routeEnds = from to from
        } else {
            vias = listOf(ends.first)
            routeThrough = null
            overlays?.route?.show(from, ends.second, null)
            routeEnds = from to ends.second
        }
        closeInfoCards(infoCardsToCloseOnPlanStart(planWasOpen))
    }
}

/** Saves [r] (a loop when [isLoop]) as [name] in Routes & rides. */
internal fun MapScreenScope.saveRoute(r: Route, isLoop: Boolean, name: String) {
    with(state) {
        val ready = store as? StoreState.Ready ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { ready.store.saveRoute(name, isLoop, r) } }
            result.fold(
                onSuccess = { Toasts.show(resources.getString(R.string.route_saved, it.name)) },
                onFailure = { notify(resources.getString(R.string.route_save_failed, it.message ?: it.toString()), long = true) },
            )
        }
    }
}

/** The task card's X: leaves the task in hand, as Back would. */
internal fun MapScreenScope.cancelTask() {
    with(state) {
        when {
            marking -> if (reviewTag != null) endReview(null) else {
                stopMarking()
                message = null
            }
            addingVia -> {
                addingVia = false
                message = null
            }
            selectedVia != null -> selectedVia = null
            startPicked != null -> {
                startPicked = null
                picker.reset()
                overlays?.route?.show(null, null, null)
                message = null
            }
            else -> message = null
        }
    }
}

@Composable
internal fun MapScreenScope.PlanningEffects() {
    with(state) {
        LaunchedEffect(overlays, routeEnds, loopStart, shownSaved, shownSectionId) {
            val routeShown = routeEnds != null || loopStart != null || shownSaved != null || shownSectionId != null
            overlays?.sections?.setLook(sectionLook(routeShown = routeShown, darkMap = darkMap))
        }
        LaunchedEffect(loopStart, loopChoice, loopSeed, loopDirection, gravel, avoid, favouritesMode, unriddenMode, favourites, overlays) {
            val start = loopStart ?: return@LaunchedEffect
            val o = overlays ?: return@LaunchedEffect
            val ready = region as? RegionState.Ready ?: return@LaunchedEffect
            val favs = favourites
            val opts = routeOptions(defaultRouteOptions(), ROUTE_EXTRA_PERCENT, gravel, avoid, favouritesMode, unriddenMode)
            val choice = loopChoice
            val shape = LoopOptions(seed = loopSeed, bearing = loopDirection.bearing)
            val request = LoopRequest(start, choice, opts, favs, shape)
            // Found ahead (Shuffle), or found now. A newer request cancels
            // this one; its result is then dropped.
            val ahead = loopsAhead.take(request)
            // A new set on its way: the old loops and their figures go, so the
            // sheet says it is finding them. Loops Shuffle already has replace
            // the old ones straight away, without "finding" in between.
            if (ahead?.isCompleted != true) {
                loopKept = keptChoices(loopKept, loops.size)
                loops = emptyList()
                loopIndex = 0
                o.route.show(start, null, null)
            }
            loopProblem = null
            // No loop this time: the start and the card stay, with why, so the
            // rider can Shuffle or change the length or direction from here.
            fun noLoop(why: String) {
                loopsAhead.clear()
                nextSeed = shuffleSeed()
                loopProblem = why
                loopKept = 0
                cardExpanded = true
                o.route.show(start, null, null)
            }
            val find = { r: LoopRequest, ahead: Boolean ->
                runCatching {
                    DebugTools.loops(r.start.toLatLon(), r.choice.target, r.opts, r.favourites, r.shape, ahead) {
                        ready.engine.roundTrip(r.start.toLatLon(), r.choice.target, r.opts, r.favourites, r.shape)
                    }
                }
            }
            val result = busy.run(R.string.busy_loops) { ahead?.await() ?: withContext(Dispatchers.Default) { find(request, false) } }
            result.fold(
                onSuccess = { found ->
                    val first = found.firstOrNull()
                    if (first == null) {
                        noLoop(resources.getString(R.string.loop_none))
                    } else {
                        loops = found
                        // A new set starts at its first loop, also when Shuffle
                        // had it ready and the old set was on another one.
                        loopIndex = 0
                        loopOpts = opts
                        showLoop(start, found, 0)
                        // All the loops of the set, so Next doesn't move the map.
                        showOnMap(found.map { it.geometry } + listOf(listOf(start.toLatLon())), always = fittedFor != start)
                        fittedFor = start
                        val next = shuffleSeed()
                        nextSeed = next
                        val nextRequest = request.copy(shape = shape.copy(seed = next))
                        loopsAhead.hold(nextRequest, scope.async(Dispatchers.Default) { find(nextRequest, true) })
                    }
                },
                onFailure = {
                    noLoop(
                        if (classify(it) == CoreProblem.NO_ROUTE) {
                            resources.getString(R.string.loop_none)
                        } else {
                            coreErrorMessage(resources, it)
                        },
                    )
                },
            )
        }
        LaunchedEffect(routeEnds, vias, arriveBy, gravel, avoid, favouritesMode, unriddenMode, favourites, overlays, routeThrough) {
            val (start, end) = routeEnds ?: return@LaunchedEffect
            val o = overlays ?: return@LaunchedEffect
            val ready = region as? RegionState.Ready ?: return@LaunchedEffect
            restoring?.takeIf { it.ends == (start to end) }?.let { back ->
                restoring = null
                routeChoices = back.choices
                routeFoundAt = back.at
                showRouteChoice(start, end, back.choices, back.index, back.opts)
                showOnMap(back.choices.map { it.geometry }, always = fittedFor != (start to end))
                fittedFor = start to end
                return@LaunchedEffect
            }
            restoring = null
            routeProblem = null
            routeKept = keptChoices(routeKept, routeChoices.size)
            routeSummary = null
            shownRoute = null
            routeChoices = emptyList()
            routeIndex = 0
            val favs = favourites
            val now = System.currentTimeMillis() / 1000
            val by = arriveBy
            val opts = if (by != null) {
                arriveByOptions(defaultRouteOptions(), now, by, gravel, avoid, favouritesMode, unriddenMode)
            } else {
                routeOptions(defaultRouteOptions(), ROUTE_EXTRA_PERCENT, gravel, avoid, favouritesMode, unriddenMode)
            }
            // A newer request cancels this one; its result is then dropped.
            val result = busy.run(R.string.busy_routes) {
                withContext(Dispatchers.Default) {
                    runCatching {
                        val via = vias.map { it.toLatLon() }
                        val bothWays = routeThrough
                        if (bothWays != null) {
                            DebugTools.query("there and back", ::routesSummary) {
                                ready.engine.roundTripVia(start.toLatLon(), via, bothWays, opts, favs)
                            }
                        } else {
                            DebugTools.routes(start.toLatLon(), via, end.toLatLon(), opts, favs) {
                                ready.engine.routeChoices(start.toLatLon(), via, end.toLatLon(), opts, favs)
                            }
                        }
                    }
                }
            }
            result.fold(
                onSuccess = { found ->
                    if (found.isEmpty()) return@fold
                    routeChoices = found
                    routeFoundAt = now
                    lastFound = FoundRoutes(start to end, found, 0, opts, now)
                    viasBefore = null
                    showRouteChoice(start, end, found, 0, opts)
                    // All the choices, so switching doesn't move the map.
                    showOnMap(found.map { it.geometry }, always = fittedFor != (start to end))
                    fittedFor = start to end
                },
                onFailure = {
                    val before = viasBefore
                    if (before != null) {
                        // The new via point can't be reached: keep the route without it.
                        viasBefore = null
                        vias = before
                    } else {
                        val back = lastFound
                        when (failedSearch(back?.ends, start to end)) {
                            FailedSearch.GO_BACK -> {
                                // The end moved somewhere no route reaches: back to
                                // where it was, with its routes, the sheet as it is.
                                restoring = back
                                routeEnds = checkNotNull(back).ends
                            }
                            FailedSearch.STAY -> {
                                // Same ends, something else changed: the sheet
                                // stays and says why, for the rider to change it
                                // again or close it.
                                routeProblem = coreErrorMessage(resources, it)
                                o.route.show(start, end, null)
                                return@fold
                            }
                            FailedSearch.CLOSE -> routeEnds = null
                        }
                    }
                    notify(coreErrorMessage(resources, it), long = true)
                },
            )
        }
        // When the card grows or shrinks (expanded, collapsed, a message), the
        // route or loops shown stay in view.
        LaunchedEffect(cardExpanded, topPanelBottom, sheetTop, mapSize, landscape, columnRight) {
            // Once the sheet has settled: a fit while it still grows would
            // aim for the space above it as it was, not as it ends up.
            delay(SETTLE_MS)
            val lines = when {
                loopStart != null -> loops.map { it.geometry }
                routeEnds != null -> routeChoices.map { it.geometry }
                else -> emptyList()
            }
            if (lines.isNotEmpty()) showOnMap(lines, always = false)
        }
    }
}
