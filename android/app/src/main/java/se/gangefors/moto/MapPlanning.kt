// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.maplibre.android.geometry.LatLng
import se.gangefors.moto.core.Favourites
import se.gangefors.moto.core.RouteOptions
import se.gangefors.moto.core.LoopOptions
import se.gangefors.moto.core.Route

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
