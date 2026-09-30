// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import se.gangefors.moto.core.Avoid
import se.gangefors.moto.core.FavouritesMode
import se.gangefors.moto.core.Gravel
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.MotoException
import se.gangefors.moto.core.RouteOptions
import se.gangefors.moto.core.TimeBudget

/**
 * Picking a route with long-presses: the first sets the start, every one
 * after it sets the end, moving it (Stefan: the start stays until the
 * route is closed, which [reset]s the pick). A round trip takes the start
 * instead of waiting for an end. Pure logic, independent of the map, so
 * it can be unit tested.
 */
class RoutePicker<P> {
    sealed interface Step<P> {
        data class StartSet<P>(val start: P) : Step<P>
        data class Complete<P>(val start: P, val end: P) : Step<P>
    }

    var start: P? = null
        private set

    fun onLongPress(point: P): Step<P> {
        val s = start
        return if (s == null) {
            start = point
            Step.StartSet(point)
        } else {
            Step.Complete(s, point)
        }
    }

    /** The start, forgotten: a loop from it takes the place of the end
     * (null when there is none). */
    fun takeStart(): P? = start.also { start = null }

    /** Makes [point] the start, as if it had been long-pressed: the next
     * long-press picks the end (a route from the rider's position). */
    fun startAt(point: P) {
        start = point
    }

    /** Forgets a start that turned out to be unusable. */
    fun reset() {
        start = null
    }
}

/** How the map screen should describe an error from the Rust core. */
enum class CoreProblem { OUTSIDE_REGION, NO_ROAD_NEARBY, NO_ROUTE, OTHER }

fun classify(e: Throwable): CoreProblem = when (e) {
    is MotoException.OutsideRegion -> CoreProblem.OUTSIDE_REGION
    is MotoException.NoRoadNearby -> CoreProblem.NO_ROAD_NEARBY
    is MotoException.NoRoute -> CoreProblem.NO_ROUTE
    else -> CoreProblem.OTHER
}

/**
 * Route summary for the route card: distance in km (one decimal), whole
 * minutes, whole minutes over the fastest route (never negative), the
 * whole percent of the distance on favourite sections and on curvy roads,
 * and the km on gravel and on toll roads (one decimal).
 */
data class RouteSummary(
    val km: Double,
    val minutes: Int,
    val favouritePercent: Int = 0,
    val extraMinutes: Int = 0,
    val curvyPercent: Int = 0,
    val gravelKm: Double = 0.0,
    val tollKm: Double = 0.0,
    /** The fastest of several routes to choose from (the dull option). */
    val fastest: Boolean = false,
)

/** [vias] without the one at [index] (all of them when there is none). */
fun <P> removeVia(vias: List<P>, index: Int): List<P> = vias.filterIndexed { i, _ -> i != index }

/** Which of [count] route choices is the fastest: the last, when there is
 * more than one to choose from (the core puts it last); `null` for a lone
 * route. */
fun fastestChoice(count: Int): Int? = if (count > 1) count - 1 else null

/**
 * Whether the fastest of [count] route choices is drawn grey (the dull
 * option): only when there are several and it isn't also a suggestion
 * ([fastestSuggested]: the best choice was the same road, offered once).
 */
fun fastestIsDull(count: Int, fastestSuggested: Boolean): Boolean =
    fastestChoice(count) != null && !fastestSuggested

fun summarize(
    distanceM: Double,
    durationS: Double,
    favouriteShare: Double = 0.0,
    fastestDurationS: Double = durationS,
    curvyShare: Double = 0.0,
    unpavedM: Double = 0.0,
    tollM: Double = 0.0,
): RouteSummary =
    RouteSummary(
        km = Math.round(distanceM / 100.0) / 10.0,
        minutes = Math.round(durationS / 60.0).toInt(),
        favouritePercent = percent(favouriteShare),
        extraMinutes = Math.round(((durationS - fastestDurationS) / 60.0).coerceAtLeast(0.0)).toInt(),
        curvyPercent = percent(curvyShare),
        gravelKm = tenthsOfKm(unpavedM, distanceM),
        tollKm = tenthsOfKm(tollM, distanceM),
    )

/** [m] metres of a route [distanceM] long, in km to one decimal. */
private fun tenthsOfKm(m: Double, distanceM: Double): Double =
    Math.round(m.coerceIn(0.0, distanceM.coerceAtLeast(0.0)) / 100.0) / 10.0

private fun percent(share: Double): Int = Math.round(share.coerceIn(0.0, 1.0) * 100.0).toInt()

/**
 * How much longer than the fastest route the routes to choose from may
 * take, in percent (Stefan: no slider; the rider picks among the
 * choices). Measured on 175 town pairs (2026-09-27): fun choices per
 * trip +40 % 1.9, +60 % 2.4, +80 % 2.6 (three for 132 of them), +100 %
 * 2.7; beyond +80 % hardly more, and the choices take a third longer
 * than the fastest route on average.
 */
const val ROUTE_EXTRA_PERCENT = 80

/** The gravel choices, in the order they are offered. */
val GRAVEL_CHOICES: List<Gravel> = listOf(Gravel.AVOID, Gravel.ALLOW, Gravel.PREFER)

/** How a gravel choice is stored in preferences. */
fun gravelKey(g: Gravel): String = g.name.lowercase()

/** A stored gravel choice; without one, [legacyAllow] (the old Allow
 * gravel switch) or else avoid. Preferences are read back as untrusted
 * input: anything else counts as unset. */
fun gravelOf(stored: String?, legacyAllow: Boolean = false): Gravel =
    GRAVEL_CHOICES.firstOrNull { gravelKey(it) == stored } ?: if (legacyAllow) Gravel.ALLOW else Gravel.AVOID

/** The favourites choices, in the order they are offered. */
val FAVOURITES_CHOICES: List<FavouritesMode> = listOf(FavouritesMode.PREFER, FavouritesMode.AVOID)

/**
 * [base] (the core's defaults) with [percent] extra time allowed, and
 * gravel (unpaved) roads avoided, allowed or preferred. Avoided means
 * "where possible": the core counts them as much slower, it doesn't ban
 * them; preferred means they pull the route within the extra time. The
 * rider's favourites are preferred, or avoided (where possible) to find
 * new roads.
 */
fun routeOptions(
    base: RouteOptions,
    percent: Int,
    gravel: Gravel = Gravel.AVOID,
    avoid: Avoid = AVOID_ALL,
    favourites: FavouritesMode = FavouritesMode.PREFER,
): RouteOptions =
    base.copy(
        budget = TimeBudget.Extra(percent / 100.0),
        gravel = gravel,
        avoid = avoid,
        favourites = favourites,
    )

/** The kinds of road a rider can avoid or allow, in the order offered. */
enum class AvoidKind(val key: String) {
    MOTORWAYS("motorways"),
    FERRIES("ferries"),
    TOLLS("tolls"),
}

/** Motorways, ferries and toll roads all avoided: the default (Stefan:
 * ride, don't travel). */
val AVOID_ALL = Avoid(motorways = true, ferries = true, tolls = true)

/** Whether [avoid] avoids roads of [kind]. */
fun avoids(avoid: Avoid, kind: AvoidKind): Boolean = when (kind) {
    AvoidKind.MOTORWAYS -> avoid.motorways
    AvoidKind.FERRIES -> avoid.ferries
    AvoidKind.TOLLS -> avoid.tolls
}

/** [avoid] with roads of [kind] avoided or not. */
fun withAvoided(avoid: Avoid, kind: AvoidKind, avoided: Boolean): Avoid = when (kind) {
    AvoidKind.MOTORWAYS -> avoid.copy(motorways = avoided)
    AvoidKind.FERRIES -> avoid.copy(ferries = avoided)
    AvoidKind.TOLLS -> avoid.copy(tolls = avoided)
}

/** Whether [avoid] allows roads of [kind]: its Allow chip is on. */
fun allows(avoid: Avoid, kind: AvoidKind): Boolean = !avoids(avoid, kind)

/** [avoid] with roads of [kind] allowed or not: its Allow chip tapped. */
fun withAllowed(avoid: Avoid, kind: AvoidKind, allowed: Boolean): Avoid = withAvoided(avoid, kind, !allowed)

/** The kinds [avoid] allows, in the order offered. */
fun allowedKinds(avoid: Avoid): List<AvoidKind> = AvoidKind.entries.filter { allows(avoid, it) }

/** How [avoid] is stored in preferences: the allowed kinds' keys. */
fun avoidKey(avoid: Avoid): String = allowedKinds(avoid).joinToString(",") { it.key }

/** A stored choice of roads to avoid; read back as untrusted: unknown
 * keys are ignored, and without one everything is avoided. */
fun avoidOf(stored: String?): Avoid {
    val allowed = stored.orEmpty().split(',').map { it.trim() }.toSet()
    return AvoidKind.entries.fold(AVOID_ALL) { a, k -> withAvoided(a, k, k.key !in allowed) }
}

/** Exported route files: "moto-route-2026-09-24-1830.gpx", in local time. */
fun routeFileName(atSec: Long, zone: java.time.ZoneId): String =
    "moto-route-" + java.time.Instant.ofEpochSecond(atSec).atZone(zone)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", java.util.Locale.ROOT)) + ".gpx"

/** Whether [name] is one of our shared route or ride files (only those
 * are cleaned up from the share folder). */
fun isRouteFileName(name: String): Boolean = ROUTE_FILE.matches(name)

private val ROUTE_FILE = Regex("moto-(route|ride)-[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{4}\\.gpx")

/** The name a route carries inside its GPX, shown by the nav app:
 * "moto 2026-09-24 18:30, 57.4 km". */
fun routeGpxName(atSec: Long, zone: java.time.ZoneId, km: Double): String =
    "moto " + java.time.Instant.ofEpochSecond(atSec).atZone(zone)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", java.util.Locale.ROOT)) +
        String.format(java.util.Locale.ROOT, ", %.1f km", km)


/** Longest saved-route name, in characters (the core's limit). */
const val MAX_ROUTE_NAME_CHARS = 200

/** The name a saved route gets unless the rider types one:
 * "Loop 2026-09-24 18:30, 57.4 km" (or "Route …"). */
fun defaultRouteName(atSec: Long, zone: java.time.ZoneId, km: Double, isLoop: Boolean): String =
    (if (isLoop) "Loop " else "Route ") + java.time.Instant.ofEpochSecond(atSec).atZone(zone)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", java.util.Locale.ROOT)) +
        String.format(java.util.Locale.ROOT, ", %.1f km", km)

/** A typed route name as the core accepts it: control characters dropped
 * (a pasted newline becomes a space), trimmed and cut to
 * [MAX_ROUTE_NAME_CHARS] characters; null when nothing is left. */
fun cleanRouteName(typed: String): String? {
    val text = typed.map { if (it == '\n' || it == '\t') ' ' else it }
        .filterNot { Character.isISOControl(it) }
        .joinToString("")
        .trim()
    if (text.isEmpty()) return null
    val cps = text.codePointCount(0, text.length)
    return if (cps <= MAX_ROUTE_NAME_CHARS) text else text.substring(0, text.offsetByCodePoints(0, MAX_ROUTE_NAME_CHARS)).trimEnd()
}

/** Most via points a route may pass (the core's limit). */
const val MAX_VIA_POINTS = 8

/**
 * [vias] with [p] put where it lengthens the trip [start] → vias → [end]
 * the least, measured in straight lines: a via point dropped anywhere on
 * the map lands in the leg it belongs to, however the rider added the
 * others.
 */
fun insertVia(start: LatLon, vias: List<LatLon>, end: LatLon, p: LatLon): List<LatLon> {
    val stops = listOf(start) + vias + listOf(end)
    val best = (0 until stops.size - 1).minBy { i ->
        approxDistanceM(stops[i], p) + approxDistanceM(p, stops[i + 1]) - approxDistanceM(stops[i], stops[i + 1])
    }
    return vias.subList(0, best) + p + vias.subList(best, vias.size)
}

/**
 * Share of the time until an arrival kept in hand for stops, traffic and
 * the nav app's own timing. Provisional: to be tuned against recorded
 * rides.
 */
const val ARRIVE_MARGIN = 0.10

/**
 * [base] set up to arrive by [arriveAtSec] when leaving at [nowSec]: the
 * time until then, less [ARRIVE_MARGIN], is the whole budget, and all of
 * it may be spent on favourites and curvy roads (guard off). Too little
 * time gives the fastest route.
 */
fun arriveByOptions(
    base: RouteOptions,
    nowSec: Long,
    arriveAtSec: Long,
    gravel: Gravel,
    avoid: Avoid = AVOID_ALL,
    favourites: FavouritesMode = FavouritesMode.PREFER,
): RouteOptions =
    base.copy(
        budget = TimeBudget.Total((arriveAtSec - nowSec).coerceAtLeast(0L) * (1.0 - ARRIVE_MARGIN)),
        minGain = 0.0,
        gravel = gravel,
        avoid = avoid,
        favourites = favourites,
    )

/** The next [hour]:[minute] after [nowSec] in [zone]: today, or tomorrow
 * once it has passed. */
fun nextTimeOfDay(nowSec: Long, zone: java.time.ZoneId, hour: Int, minute: Int): Long {
    val now = java.time.Instant.ofEpochSecond(nowSec).atZone(zone)
    var at = now.toLocalDate().atTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59)).atZone(zone)
    if (!at.isAfter(now)) at = now.toLocalDate().plusDays(1).atTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59)).atZone(zone)
    return at.toEpochSecond()
}

/** When a route found at [foundAtSec] taking [durationS] gets there, and
 * whether that is after [arriveBySec]. */
data class Arrival(val atSec: Long, val late: Boolean)

fun arrival(foundAtSec: Long, durationS: Double, arriveBySec: Long): Arrival {
    val at = foundAtSec + Math.round(durationS.coerceAtLeast(0.0))
    return Arrival(at, at > arriveBySec)
}

/** A time of day as "14:30". */
fun clockTime(atSec: Long, zone: java.time.ZoneId): String =
    java.time.Instant.ofEpochSecond(atSec).atZone(zone)
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.ROOT))

/**
 * Which labels of a slider's scale fit: each label is centred on its
 * point ([centres], px from the left of a scale [total] px wide) but kept
 * inside the scale, at least [gap] apart. When all don't fit (large fonts,
 * narrow screens) every second one is tried, then every third and so on,
 * so the scale stays even. Indices of the labels shown, with their left
 * edges; empty if not even one fits.
 */
fun scaleLabels(centres: List<Int>, widths: List<Int>, total: Int, gap: Int): List<Pair<Int, Int>> {
    for (stride in 1..centres.size) {
        val shown = mutableListOf<Pair<Int, Int>>()
        var nextFree = Int.MIN_VALUE
        var fits = true
        for (i in centres.indices step stride) {
            val w = widths[i]
            val left = (centres[i] - w / 2).coerceIn(0, (total - w).coerceAtLeast(0))
            if (w > total || left < nextFree) {
                fits = false
                break
            }
            shown += i to left
            nextFree = left + w + gap
        }
        if (fits) return shown
    }
    return emptyList()
}

/** The first of some text sizes (their [widths], largest first) that
 * fits in [availablePx]; null when none does. */
fun firstFitting(widths: List<Int>, availablePx: Int): Int? =
    widths.indexOfFirst { it <= availablePx }.takeIf { it >= 0 }

/** Whether [count] segments fit side by side in [availablePx]: each as
 * wide as the widest label ([widestLabelPx]) plus [segmentPaddingPx]
 * (segmented buttons share the width equally). */
fun segmentsFit(widestLabelPx: Int, count: Int, segmentPaddingPx: Int, availablePx: Int): Boolean =
    count > 0 && count * (widestLabelPx + segmentPaddingPx) <= availablePx
