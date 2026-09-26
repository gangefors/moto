// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import se.gangefors.moto.core.LatLon
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** A latitude/longitude box, degrees. */
data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    val latSpan get() = north - south
    val lonSpan get() = east - west

    /** Whether [other] lies wholly inside this box. */
    fun contains(other: GeoBounds): Boolean =
        other.south >= south && other.north <= north && other.west >= west && other.east <= east
}

/** The box around every point of [lines] (routes, loops, markers), or
 * `null` when there is no valid point. Points that aren't coordinates are
 * left out. */
fun boundsOf(lines: List<List<LatLon>>): GeoBounds? {
    var b: GeoBounds? = null
    for (line in lines) {
        for (p in line) {
            val ok = p.lat.isFinite() && p.lon.isFinite() && p.lat in -90.0..90.0 && p.lon in -180.0..180.0
            if (!ok) continue
            b = b?.let {
                GeoBounds(min(it.south, p.lat), min(it.west, p.lon), max(it.north, p.lat), max(it.east, p.lon))
            } ?: GeoBounds(p.lat, p.lon, p.lat, p.lon)
        }
    }
    return b
}

/** At least [minM] metres across each way (around the same middle), so a
 * short route or a single point isn't shown at street level. */
fun GeoBounds.withMinSpan(minM: Double = MIN_FIT_SPAN_M): GeoBounds {
    val midLat = (south + north) / 2
    val midLon = (west + east) / 2
    val minLat = minM / METRES_PER_DEGREE
    val minLon = minM / (METRES_PER_DEGREE * max(cos(Math.toRadians(midLat)), 0.01))
    val (s, n) = if (latSpan >= minLat) south to north else midLat - minLat / 2 to midLat + minLat / 2
    val (w, e) = if (lonSpan >= minLon) west to east else midLon - minLon / 2 to midLon + minLon / 2
    return GeoBounds(s, w, n, e)
}

/** Space in pixels along each edge of a [width] × [height] map that
 * panels cover (the card at the top, the buttons at the right and
 * bottom, the system bars). */
data class Panels(
    val width: Int,
    val height: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/** Padding in pixels around what the map fits in view. */
data class FitPadding(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * The padding to fit a route clear of [panels], plus [margin]. When the
 * panels leave less than [MIN_FREE_SHARE] of the map free either way (a
 * tall card with large fonts, a small screen), the padding that way
 * shrinks in proportion so the route is still drawn at a usable size,
 * partly under the panels.
 */
fun fitPadding(panels: Panels, margin: Int): FitPadding {
    fun axis(size: Int, a: Int, b: Int): Pair<Int, Int> {
        val pa = max(a, 0) + margin
        val pb = max(b, 0) + margin
        val most = (size * (1 - MIN_FREE_SHARE)).toInt()
        val sum = pa + pb
        if (size <= 0 || sum <= most) return pa to pb
        return (pa.toLong() * most / sum).toInt() to (pb.toLong() * most / sum).toInt()
    }
    val (left, right) = axis(panels.width, panels.left, panels.right)
    val (top, bottom) = axis(panels.height, panels.top, panels.bottom)
    return FitPadding(left, top, right, bottom)
}

/**
 * Whether the map should move to show [target] when [visible] is what it
 * shows clear of the panels: when part of [target] is out of view, or it
 * fills less than [MIN_FILL] of the view both ways (zoomed out too far).
 * Otherwise the map stays still, so recalculating doesn't make it jump.
 */
fun needsFit(visible: GeoBounds?, target: GeoBounds): Boolean {
    if (visible == null || visible.latSpan <= 0 || visible.lonSpan <= 0) return true
    if (!visible.contains(target)) return true
    return target.latSpan < visible.latSpan * MIN_FILL && target.lonSpan < visible.lonSpan * MIN_FILL
}

/** Metres per degree of latitude. */
private const val METRES_PER_DEGREE = 111_195.0

/** The smallest span a route is fitted to: about a small town across. */
const val MIN_FIT_SPAN_M = 2_000.0

/** Share of the map that stays free for the route however large the
 * panels are. */
const val MIN_FREE_SHARE = 0.35

/** A route filling less than this share of the view both ways is shown
 * closer. */
const val MIN_FILL = 0.5
