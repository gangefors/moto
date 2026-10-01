// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto.debug

import android.content.Context
import androidx.compose.runtime.Composable
import se.gangefors.moto.core.Favourites
import se.gangefors.moto.core.RideMatchReport
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.LoopOptions
import se.gangefors.moto.core.RoundTripTarget
import se.gangefors.moto.core.Route
import se.gangefors.moto.core.RouteOptions

/**
 * Release builds: the debug tools (src/debug) do nothing and show nothing.
 * Same functions, so the app compiles the same in both builds.
 */
@Suppress("UNUSED_PARAMETER")
object DebugTools {
    fun <T> startup(name: String, block: () -> T): T = block()

    fun mark(name: String) = Unit

    fun ridesMatched(report: RideMatchReport) = Unit

    fun overlayBuilt(favourites: Favourites) = Unit

    fun routes(
        from: LatLon,
        via: List<LatLon>,
        to: LatLon,
        opts: RouteOptions,
        favourites: Favourites?,
        block: () -> List<Route>,
    ): List<Route> = block()

    fun loops(
        start: LatLon,
        target: RoundTripTarget,
        opts: RouteOptions,
        favourites: Favourites?,
        shape: LoopOptions,
        ahead: Boolean,
        block: () -> List<Route>,
    ): List<Route> = block()

    fun <T> query(kind: String, block: () -> T): T = block()

    fun rideEnded(context: Context, km: Double, minutes: Int, batteryPerHour: Double?) = Unit

    @Composable
    fun MenuEntry(onOpen: () -> Unit) = Unit

    @Composable
    fun Page(onDismiss: () -> Unit) = Unit
}
