// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.roundToInt
import java.time.ZoneId

@Composable
internal fun BoxScope.PlanSheet(screen: MapScreenScope) {
    with(screen) {
        with(state) {
            // Planning a route or loop: a sheet at the bottom, in landscape at
            // the bottom left; the map fits what it plans in the space left.
            val planCards: @Composable (Dp) -> Unit = { maxHeight ->
                    routeEnds?.let {
                        RouteCard(
                            expanded = cardExpanded,
                            onExpandedChange = { cardExpanded = it },
                            maxHeight = maxHeight,
                            landscape = landscape,
                            summary = routeSummary,
                            gravel = gravel,
                            onGravel = { g ->
                                gravel = g
                                RoutePrefs.setGravel(context, g)
                            },
                            favourites = favouritesMode,
                            onFavourites = { changeFavourites(it) },
                            unridden = unriddenMode,
                            onUnridden = { changeUnridden(it) },
                            avoid = avoid,
                            onAvoid = { changeAvoid(it) },
                            onClose = { closeRoute() },
                            onShare = { shownRoute?.let { (r, opts) -> shareRoute(r, opts) } },
                            onSave = { shownRoute?.let { (r, _) -> savingRoute = r to (routeThrough != null) } },
                            onRide = { shownRoute?.let { (r, _) -> startRide(r, routeThrough != null) } },
                            viaCount = vias.size,
                            onAddVia = {
                                addingVia = true
                                message = resources.getString(R.string.route_pick_via)
                            },
                            onClearVia = {
                                vias = emptyList()
                                addingVia = false
                            },
                            arriveBy = arriveBy,
                            arrivalNote = arriveBy?.let { by ->
                                shownRoute?.let { (r, _) ->
                                    val zone = ZoneId.systemDefault()
                                    val a = arrival(routeFoundAt, r.durationS, by)
                                    if (a.late) {
                                        stringResource(R.string.route_arrives_late, clockTime(by, zone), clockTime(a.atSec, zone))
                                    } else {
                                        stringResource(R.string.route_arrives, clockTime(a.atSec, zone))
                                    }
                                }
                            },
                            arrival = arriveBy?.let { by ->
                                shownRoute?.let { (r, _) -> SummaryItem.ArrivesAt(arrival(routeFoundAt, r.durationS, by), by) }
                            },
                            onArriveBy = { arriveBy = it },
                            position = routeIndex,
                            count = routeChoices.size,
                            onPrevious = {
                                val opts = shownRoute?.second
                                if (opts != null) {
                                    showRouteChoice(it.first, it.second, routeChoices, previousLoop(routeIndex, routeChoices.size), opts)
                                }
                            },
                            onNext = {
                                val opts = shownRoute?.second
                                if (opts != null) {
                                    showRouteChoice(it.first, it.second, routeChoices, nextLoop(routeIndex, routeChoices.size), opts)
                                }
                            },
                            kept = routeKept,
                            problem = routeProblem,
                        )
                    }
                    loopStart?.let {
                        val shown = loops.getOrNull(loopIndex)
                        LoopCard(
                            expanded = cardExpanded,
                            onExpandedChange = { cardExpanded = it },
                            maxHeight = maxHeight,
                            landscape = landscape,
                            summary = shown?.let { r ->
                                summarize(r.distanceM, r.durationS, r.favouriteShare, r.durationS, r.curvyShare, r.unpavedM, r.tollM, r.unriddenShare)
                            },
                            problem = loopProblem,
                            position = loopIndex,
                            count = loops.size,
                            onShuffle = { loopSeed = nextSeed },
                            kept = loopKept,
                            direction = loopDirection,
                            onDirection = { loopDirection = it },
                            onPrevious = {
                                loopIndex = previousLoop(loopIndex, loops.size)
                                showLoop(it, loops, loopIndex)
                            },
                            onNext = {
                                loopIndex = nextLoop(loopIndex, loops.size)
                                showLoop(it, loops, loopIndex)
                            },
                            choice = loopChoice,
                            onChoice = { c -> loopLength.pick(c) },
                            gravel = gravel,
                            onGravel = { g ->
                                gravel = g
                                RoutePrefs.setGravel(context, g)
                            },
                            favourites = favouritesMode,
                            onFavourites = { changeFavourites(it) },
                            unridden = unriddenMode,
                            onUnridden = { changeUnridden(it) },
                            avoid = avoid,
                            onAvoid = { changeAvoid(it) },
                            onClose = { closeLoop() },
                            onShare = { if (shown != null) loopOpts?.let { opts -> shareRoute(shown, opts) } },
                            onSave = { shown?.let { savingRoute = it to true } },
                            onRide = { shown?.let { startRide(it, true) } },
                        )
                    }
            }
            if (planning) {
                DisposableEffect(Unit) {
                    onDispose {
                        sheetTop = Int.MAX_VALUE
                        sheetRight = 0
                    }
                }
                val sheetMaxHeight = with(density) {
                    if (mapSize.height > 0) sheetMaxHeightPx(landscape, cardExpanded, mapSize.height, insets.top).toDp() else 600.dp
                }
                // Landscape: the column at the left, its content columnWidthDp
                // wide (the sheet reaches under a cutout, padded inside), and the
                // bar's inset at the right is not its to pad.
                Box(
                    Modifier
                        .align(if (landscape) Alignment.BottomStart else Alignment.BottomCenter)
                        .widthIn(max = if (landscape) columnWidthDp.dp + with(density) { insets.left.toDp() } else TOP_BOX_MAX_WIDTH)
                        .then(
                            if (landscape) Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Right)) else Modifier,
                        )
                        .fillMaxWidth()
                        .onGloballyPositioned {
                            sheetTop = it.boundsInRoot().top.roundToInt()
                            sheetRight = it.boundsInRoot().right.roundToInt()
                        },
                ) {
                    planCards(sheetMaxHeight)
                }
            }
        }
    }
}
