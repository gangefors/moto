// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto.debug

import java.util.Locale

// Pure logic of the debug tools (debug builds only): what is recorded,
// how it is summed up and how the report reads. Kept free of Android
// types so it can be unit tested.

/** One timed call into the core: a route, a loop set, a snap… */
data class QueryRecord(
    /** What was asked, e.g. "route choices" or "loop". */
    val kind: String,
    /** The request, e.g. "312 km apart, 1 waypoint, gravel ALLOW". */
    val detail: String,
    /** Wall-clock time. */
    val ms: Double,
    /** CPU time of the calling thread. */
    val cpuMs: Double,
    /** Highest native heap in use while it ran, over what was in use before. */
    val nativePeakMb: Double,
    /** What came back, e.g. "3 routes, 412 km", or the error. */
    val result: String,
    val ok: Boolean,
    /** When it finished, milliseconds since the process started. */
    val atMs: Long,
)

/** Count and spread of the times of one kind of query. */
data class QueryStats(val kind: String, val count: Int, val medianMs: Double, val p90Ms: Double, val maxMs: Double)

/** Stats per kind, in the order kinds first appear. Failed queries are left out. */
fun queryStats(records: List<QueryRecord>): List<QueryStats> =
    records.filter { it.ok }.groupBy { it.kind }.map { (kind, list) ->
        val ms = list.map { it.ms }.sorted()
        QueryStats(kind, ms.size, percentile(ms, 50.0), percentile(ms, 90.0), ms.last())
    }

/** Nearest-rank percentile of sorted [values]; 0 for none. */
fun percentile(sorted: List<Double>, p: Double): Double {
    if (sorted.isEmpty()) return 0.0
    val rank = kotlin.math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}

fun ms(v: Double): String =
    if (v >= 1000) String.format(Locale.ROOT, "%.2f s", v / 1000) else String.format(Locale.ROOT, "%.0f ms", v)

fun mb(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)

/** Great-circle distance in metres. */
fun metersApart(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dLat / 2).let { it * it } +
        kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
        kotlin.math.sin(dLon / 2).let { it * it }
    return 2 * r * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
}

fun km(meters: Double): String = String.format(Locale.ROOT, "%.0f km", meters / 1000)

/** "route choices: 1.23 s (cpu 1.20 s, native +12.3 MB) 3 routes, 412 km | 312 km apart". */
fun queryLine(r: QueryRecord): String = buildString {
    append(r.kind).append(": ").append(ms(r.ms))
    append(" (cpu ").append(ms(r.cpuMs))
    append(String.format(Locale.ROOT, ", native +%.1f MB)", r.nativePeakMb))
    append(if (r.ok) " " else " FAILED ").append(r.result)
    if (r.detail.isNotEmpty()) append(" | ").append(r.detail)
}

fun statsLine(s: QueryStats): String =
    "${s.kind}: ${s.count}×, median ${ms(s.medianMs)}, p90 ${ms(s.p90Ms)}, max ${ms(s.maxMs)}"

/** "region open: 7.41 s (at 8.02 s)". */
fun startupLine(name: String, ms: Long?, sinceStartMs: Long): String {
    fun s(v: Long) = String.format(Locale.ROOT, "%.2f s", v / 1000.0)
    return if (ms == null) "$name (at ${s(sinceStartMs)})" else "$name: ${s(ms)} (at ${s(sinceStartMs)})"
}

/** Values in kB from /proc/self/status, e.g. VmRSS and VmHWM. */
fun procStatusKb(text: String): Map<String, Long> =
    text.lineSequence().mapNotNull { line ->
        val (key, rest) = line.split(':', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        val parts = rest.trim().split(Regex("\\s+"))
        if (parts.size == 2 && parts[1] == "kB") parts[0].toLongOrNull()?.let { key to it } else null
    }.toMap()

/** Resident and proportional size (kB) of the mappings in /proc/self/smaps whose file ends with [suffix]. */
data class MappedKb(val rssKb: Long, val pssKb: Long)

fun smapsFor(text: String, suffix: String): MappedKb {
    val header = Regex("^[0-9a-f]+-[0-9a-f]+ ")
    var inFile = false
    var rss = 0L
    var pss = 0L
    for (line in text.lineSequence()) {
        if (header.containsMatchIn(line)) {
            inFile = line.trimEnd().endsWith(suffix)
            continue
        }
        if (!inFile) continue
        val kb = procStatusKb(line)
        rss += kb["Rss"] ?: 0
        pss += kb["Pss"] ?: 0
    }
    return MappedKb(rss, pss)
}

/** A point for the benchmark. */
data class BenchPoint(val name: String, val lat: Double, val lon: Double)

/** One fixed benchmark request. */
sealed interface BenchCase {
    val label: String

    /** Route choices, as the route sheet asks for them; [arriveMinutes]
     * makes it an arrive-by route with that much time in all. With
     * [yourData] the rider's own favourites and rides are used, so these
     * cases compare runs on one phone, not builds on two. */
    data class Route(
        val from: BenchPoint,
        val to: BenchPoint,
        val gravel: String,
        val favourites: String = "PREFER",
        val unridden: String = "ANY",
        val arriveMinutes: Int? = null,
        val yourData: Boolean = false,
    ) : BenchCase {
        override val label = buildString {
            append("route ${from.name} → ${to.name}, gravel $gravel")
            arriveMinutes?.let { append(", arrive by +$it min") }
            if (yourData) append(", your data, favourites $favourites, unridden $unridden")
        }
    }

    /** A loop set; [seed] as Shuffle gives, [bearing] as a picked direction. */
    data class Loop(
        val start: BenchPoint,
        val km: Int,
        val gravel: String,
        val seed: Int = 0,
        val bearing: Double? = null,
        val unridden: String = "ANY",
        val yourData: Boolean = false,
    ) : BenchCase {
        override val label = buildString {
            append("loop $km km from ${start.name}, gravel $gravel")
            if (seed != 0) append(", shuffle $seed")
            bearing?.let { append(", heading ${it.toInt()}°") }
            if (yourData) append(", your data, unridden $unridden")
        }
    }

    /** [count] taps on the map around [around], each snapped to a road. */
    data class Snaps(val around: BenchPoint, val count: Int) : BenchCase {
        override val label = "$count snaps around ${around.name}"
    }

    /** The rider's routing overlay built from the store: favourites and
     * ridden roads (ADR-0010). */
    data object Overlay : BenchCase {
        override val label = "overlay build, your data"
    }

    /** The rider's longest saved ride matched to the roads. */
    data object MatchLongestRide : BenchCase {
        override val label = "match the longest ride, your data"
    }
}

private val MALMO = BenchPoint("Malmö", 55.6050, 13.0038)
private val LUND = BenchPoint("Lund", 55.7047, 13.1910)
private val GOTEBORG = BenchPoint("Göteborg", 57.7089, 11.9746)
private val STOCKHOLM = BenchPoint("Stockholm", 59.3293, 18.0686)
private val KIRUNA = BenchPoint("Kiruna", 67.8558, 20.2253)
private val OSTERSUND = BenchPoint("Östersund", 63.1792, 14.6357)

/**
 * The same requests on every build and phone, without favourites, so
 * numbers compare; then cases on the rider's own data (favourites and
 * rides), which compare runs on one phone. Cases outside the installed
 * region fail and say so.
 */
val BENCH_CASES: List<BenchCase> = listOf(
    BenchCase.Route(MALMO, LUND, "AVOID"),
    BenchCase.Route(MALMO, GOTEBORG, "AVOID"),
    BenchCase.Route(MALMO, STOCKHOLM, "AVOID"),
    BenchCase.Route(MALMO, KIRUNA, "AVOID"),
    BenchCase.Route(MALMO, KIRUNA, "PREFER"),
    BenchCase.Route(MALMO, GOTEBORG, "AVOID", arriveMinutes = 240),
    BenchCase.Loop(LUND, 100, "AVOID"),
    BenchCase.Loop(LUND, 200, "AVOID"),
    BenchCase.Loop(LUND, 200, "AVOID", seed = 1),
    BenchCase.Loop(LUND, 200, "AVOID", bearing = 0.0),
    BenchCase.Loop(LUND, 400, "AVOID"),
    BenchCase.Loop(LUND, 400, "PREFER"),
    BenchCase.Loop(OSTERSUND, 400, "AVOID"),
    BenchCase.Snaps(LUND, 50),
    BenchCase.Overlay,
    BenchCase.MatchLongestRide,
    BenchCase.Route(MALMO, GOTEBORG, "AVOID", yourData = true),
    BenchCase.Route(MALMO, GOTEBORG, "AVOID", favourites = "AVOID", yourData = true),
    BenchCase.Route(MALMO, GOTEBORG, "AVOID", unridden = "PREFER", yourData = true),
    BenchCase.Loop(LUND, 200, "AVOID", yourData = true),
    BenchCase.Loop(LUND, 200, "AVOID", unridden = "PREFER", yourData = true),
)

/** "Benchmark 3 of 21: route Malmö → Stockholm, gravel AVOID". */
fun benchProgress(index: Int, total: Int, label: String): String = "Benchmark ${index + 1} of $total: $label"

/** A benchmark case's result: each run's time (first cold, then warm), or why it failed. */
data class BenchResult(val label: String, val runsMs: List<Double>, val result: String, val ok: Boolean)

fun benchLine(b: BenchResult): String =
    if (b.ok) {
        "${b.label}: ${b.runsMs.joinToString(" / ") { ms(it) }} — ${b.result}"
    } else {
        "${b.label}: FAILED ${b.result}"
    }

/** A titled block of the report. */
data class ReportSection(val title: String, val lines: List<String>)

fun reportText(header: String, sections: List<ReportSection>): String = buildString {
    appendLine(header)
    for (s in sections) {
        if (s.lines.isEmpty()) continue
        appendLine()
        appendLine("== ${s.title} ==")
        s.lines.forEach { appendLine(it) }
    }
}

/** The rider's data in numbers (Rider data block). */
data class RiderData(
    val sections: Int,
    val good: Int,
    val great: Int,
    val epic: Int,
    val oneWay: Int,
    val sectionKm: Double,
    /** Sections that no longer fit the map. */
    val unmatched: Int,
    /** Sections waiting for a match: their region is off or a match is due. */
    val waiting: Int,
    /** Sections ridden at least once on the saved rides. */
    val ridden: Int,
    val rides: Int,
    val ridesKm: Double,
    val longestRideKm: Double,
    /** Rides not finished: being recorded, or cut short. */
    val unfinished: Int,
    val routes: Int,
    val loops: Int,
    val tagsPending: Int,
)

fun riderDataLines(d: RiderData): List<String> = listOf(
    String.format(
        Locale.ROOT,
        "Favourite sections: %d (good %d, great %d, epic %d), %.0f km, %d one-way",
        d.sections, d.good, d.great, d.epic, d.sectionKm, d.oneWay,
    ),
    "Needing a look: ${d.unmatched} no longer fit the map, ${d.waiting} waiting for a match",
    "Ridden: ${d.ridden} of ${d.sections} sections at least once",
    String.format(
        Locale.ROOT,
        "Rides: %d finished, %.0f km, longest %.0f km; %d not finished",
        d.rides, d.ridesKm, d.longestRideKm, d.unfinished,
    ),
    "Saved: ${d.routes} routes, ${d.loops} loops; tags waiting for review: ${d.tagsPending}",
)

/** The route and loop settings in effect (Settings block). */
data class SettingsInEffect(
    val gravel: String,
    val favourites: String,
    val unridden: String,
    /** Kinds of road allowed: none means motorways, ferries and tolls are avoided. */
    val allowed: List<String>,
    val loopLength: String,
    val loopDirection: String,
    val zoomClose: Int,
    val zoomArea: Int,
    val keepScreenOn: Boolean,
)

fun settingsLines(s: SettingsInEffect): List<String> = listOf(
    "Routes and loops: gravel ${s.gravel}, favourites ${s.favourites}, unridden roads ${s.unridden}, " +
        "allowed ${s.allowed.ifEmpty { listOf("none") }.joinToString(", ")}",
    "Loops: length ${s.loopLength}, direction default ${s.loopDirection}",
    "Map: locate zooms close ${s.zoomClose}, area ${s.zoomArea}; screen kept on while recording: ${if (s.keepScreenOn) "yes" else "no"}",
)

/** "curvy 41/30/25 %": one whole percent per route, in the order found. */
fun sharesLine(name: String, shares: List<Double>): String =
    "$name ${shares.joinToString("/") { Math.round(it.coerceIn(0.0, 1.0) * 100).toString() }} %"
