// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto.debug

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import se.gangefors.moto.R
import se.gangefors.moto.RegionState
import se.gangefors.moto.Regions
import se.gangefors.moto.core.Engine
import se.gangefors.moto.core.Gravel
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.LoopOptions
import se.gangefors.moto.core.Route
import se.gangefors.moto.core.RoundTripTarget
import se.gangefors.moto.core.defaultRouteOptions
import se.gangefors.moto.arriveByOptions
import se.gangefors.moto.AVOID_ALL
import se.gangefors.moto.core.UnriddenMode
import se.gangefors.moto.core.SectionStore
import se.gangefors.moto.core.FavouritesMode
import se.gangefors.moto.core.Favourites
import se.gangefors.moto.core.Direction
import se.gangefors.moto.core.Rating
import se.gangefors.moto.core.SectionStatus
import se.gangefors.moto.core.TagStatus
import se.gangefors.moto.RoutePrefs
import se.gangefors.moto.SavedSections
import se.gangefors.moto.StoreState
import se.gangefors.moto.core.profileRegionOpen
import se.gangefors.moto.ROUTE_EXTRA_PERCENT
import se.gangefors.moto.routeOptions

private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

/** Phone, Android, app build and region: the report's first lines. Call off the main thread. */
fun deviceLines(context: Context): List<String> {
    val am = context.getSystemService(ActivityManager::class.java)
    val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    val info = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
    }.getOrNull()
    val active = Regions.active.value
    val region = when (val s = active.state) {
        is RegionState.Ready -> {
            val used = active.installed.filter { it.enabled }
            val names = used.joinToString(" + ") { "${it.name} ${mb(it.bytes)}" }.ifEmpty { "unnamed" }
            val date = s.engine.info().osmTimestamp.takeIf { it > 0 }?.let { ", oldest map data ${TIME.format(Instant.ofEpochSecond(it))}" } ?: ""
            "$names, ${s.engine.linkCount()} border links$date"
        }
        else -> s.toString()
    }
    return listOf(
        "Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "${Build.SUPPORTED_ABIS.firstOrNull()}, ${Runtime.getRuntime().availableProcessors()} cores, " +
            "RAM ${mb(mem.totalMem)} (${mb(mem.availMem)} free${if (mem.lowMemory) ", LOW" else ""}), " +
            "app heap limit ${am.memoryClass} MB",
        "App: ${info?.versionName ?: "?"} debug, commit ${context.getString(R.string.debug_commit)}, " +
            "installed ${info?.let { TIME.format(Instant.ofEpochMilli(it.lastUpdateTime)) } ?: "?"}",
        "Region: $region",
    )
}

/** What the app's process uses now, and its peak. Call off the main thread. */
fun memoryLines(): List<String> {
    val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
    fun stat(key: String) = mi.getMemoryStat(key)?.toLongOrNull()?.times(1000) ?: 0L
    val status = runCatching { procStatusKb(File("/proc/self/status").readText()) }.getOrDefault(emptyMap())
    val region = runCatching { smapsFor(File("/proc/self/smaps").readText(), ".region") }.getOrNull()
    val rt = Runtime.getRuntime()
    return listOfNotNull(
        "Total PSS ${mb(stat("summary.total-pss"))}: Java ${mb(stat("summary.java-heap"))}, " +
            "native ${mb(stat("summary.native-heap"))}, code ${mb(stat("summary.code"))}, " +
            "graphics ${mb(stat("summary.graphics"))}, stack ${mb(stat("summary.stack"))}, " +
            "other ${mb(stat("summary.private-other"))}, system ${mb(stat("summary.system"))}",
        "RSS ${mb((status["VmRSS"] ?: 0) * 1000)}, peak RSS ${mb((status["VmHWM"] ?: 0) * 1000)}, " +
            "swap ${mb((status["VmSwap"] ?: 0) * 1000)}",
        "Native heap in use ${mb(Debug.getNativeHeapAllocatedSize())} of ${mb(Debug.getNativeHeapSize())}; " +
            "Java heap ${mb(rt.totalMemory() - rt.freeMemory())} of max ${mb(rt.maxMemory())}",
        region?.let { "Region file in memory: RSS ${mb(it.rssKb * 1000)}, PSS ${mb(it.pssKb * 1000)} (page cache, shared with the system)" },
    )
}

/** The region check, twice, step by step, on each downloaded region. Call off the main thread. */
fun regionCheckLines(context: Context): List<String> {
    val files = Regions.installedFiles(context).filter { it.isFile }
    if (files.isEmpty()) return listOf("No downloaded region.")
    return files.flatMap { file ->
        listOf(file.name) + (1..2).flatMap { run ->
            val steps = profileRegionOpen(file.path)
            listOf("Run $run: ${ms(steps.sumOf { it.ms })}") + steps.map { "  ${it.name}: ${ms(it.ms)}" }
        }
    }
}

/**
 * Runs [BENCH_CASES] on [engine], each twice (cold, then warm); the cases
 * on the rider's data use [store] (skipped without it). [starting] hears
 * which case runs next, [progress] gets each result as it comes. Call off
 * the main thread.
 */
fun runBenchmark(
    engine: Engine,
    store: SectionStore?,
    starting: (index: Int, case: BenchCase) -> Unit,
    progress: (BenchResult) -> Unit,
) {
    // The rider's overlay for the cases on their data: built once, before
    // the first of them is timed, and freed at the end.
    var overlay: Favourites? = null
    try {
        for ((i, case) in BENCH_CASES.withIndex()) {
            starting(i, case)
            val yours = (case as? BenchCase.Route)?.yourData == true || (case as? BenchCase.Loop)?.yourData == true
            if (yours && overlay == null) overlay = runCatching { store?.favourites(engine) }.getOrNull()
            val runs = mutableListOf<Double>()
            var last = ""
            var ok = true
            repeat(2) {
                if (!ok) return@repeat
                val (record, _) = DebugTools.measured(case.label, "", { it: String -> it }) {
                    runCase(engine, store, case) { overlay }
                }
                runs += record.ms
                last = record.result + String.format(java.util.Locale.ROOT, ", native +%.1f MB", record.nativePeakMb)
                ok = record.ok
            }
            progress(BenchResult(case.label, runs, last, ok))
        }
    } finally {
        overlay?.destroy()
    }
}

/** Runs one case; what it found, in a few words. */
private fun runCase(engine: Engine, store: SectionStore?, case: BenchCase, overlay: () -> Favourites?): String = when (case) {
    is BenchCase.Route -> {
        val base = defaultRouteOptions()
        val gravel = Gravel.valueOf(case.gravel)
        val favourites = FavouritesMode.valueOf(case.favourites)
        val unridden = UnriddenMode.valueOf(case.unridden)
        val opts = case.arriveMinutes?.let {
            arriveByOptions(base, 0L, it * 60L, gravel, AVOID_ALL, favourites, unridden)
        } ?: routeOptions(base, ROUTE_EXTRA_PERCENT, gravel, AVOID_ALL, favourites, unridden)
        val fav = if (case.yourData) overlay() ?: error("no store") else null
        describe(engine.routeChoices(case.from.latLon(), emptyList(), case.to.latLon(), opts, fav))
    }
    is BenchCase.Loop -> {
        val opts = routeOptions(
            defaultRouteOptions(), ROUTE_EXTRA_PERCENT, Gravel.valueOf(case.gravel), AVOID_ALL,
            FavouritesMode.PREFER, UnriddenMode.valueOf(case.unridden),
        )
        val fav = if (case.yourData) overlay() ?: error("no store") else null
        describe(
            engine.roundTrip(
                case.start.latLon(), RoundTripTarget.DistanceM(case.km * 1000.0), opts, fav,
                LoopOptions(seed = case.seed.toUInt(), bearing = case.bearing),
            ),
        )
    }
    is BenchCase.Snaps -> {
        // The same points every run: a ring of taps about 1–10 km out.
        var snapped = 0
        for (k in 0 until case.count) {
            val angle = k * 2.399963 // golden angle: spread evenly
            val r = 0.01 + 0.08 * k / case.count
            val p = LatLon(case.around.lat + r * kotlin.math.cos(angle), case.around.lon + 1.8 * r * kotlin.math.sin(angle))
            if (runCatching { engine.snap(p) }.isSuccess) snapped++
        }
        "$snapped of ${case.count} on a road"
    }
    BenchCase.Overlay -> {
        val built = (store ?: error("no store")).favourites(engine)
        try {
            "${built.edgeCount()} favourite edges, ${built.riddenEdgeCount()} ridden edges"
        } finally {
            built.destroy()
        }
    }
    BenchCase.MatchLongestRide -> {
        val s = store ?: error("no store")
        val ride = s.listTracks().filter { it.endedAt != null }.maxByOrNull { it.distanceM } ?: error("no rides")
        val points = s.trackPoints(ride.id)?.map { it.position } ?: error("ride gone")
        val m = engine.matchTrack(points)
        String.format(
            java.util.Locale.ROOT,
            "%d fixes, %.0f km, %.0f km matched in %d pieces",
            points.size, ride.distanceM / 1000, m.pieces.sumOf { it.distanceM } / 1000, m.pieces.size,
        )
    }
}

private fun BenchPoint.latLon() = LatLon(lat, lon)

/** "3 routes, 412–488 km; curvy 41/30/25 %, favourites 22/0/5 %,
 * unridden 64/80/100 %", the shares route by route in the order found. */
fun describe(routes: List<Route>): String {
    if (routes.isEmpty()) return "none found"
    val d = routes.map { it.distanceM }
    val span = if (d.size == 1) km(d[0]) else "${km(d.min()).removeSuffix(" km")}–${km(d.max())}"
    return "${routes.size} ${if (routes.size == 1) "route" else "routes"}, $span; " +
        listOf(
            sharesLine("curvy", routes.map { it.curvyShare }),
            sharesLine("favourites", routes.map { it.favouriteShare }),
            sharesLine("unridden", routes.map { it.unriddenShare }),
        ).joinToString(", ")
}

/** The rider's data in numbers, from the store. Call off the main thread. */
fun riderData(context: Context): RiderData? {
    val store = (SavedSections.open(context) as? StoreState.Ready)?.store ?: return null
    val sections = store.list(null)
    val ridden = runCatching { store.ridden() }.getOrDefault(emptyList())
    val tracks = store.listTracks()
    val finished = tracks.filter { it.endedAt != null }
    val routes = store.listRoutes()
    fun lengthKm(line: List<LatLon>) =
        line.zipWithNext { a, b -> metersApart(a.lat, a.lon, b.lat, b.lon) }.sum() / 1000.0
    return RiderData(
        sections = sections.size,
        good = sections.count { it.rating == Rating.GOOD },
        great = sections.count { it.rating == Rating.GREAT },
        epic = sections.count { it.rating == Rating.EPIC },
        oneWay = sections.count { it.direction == Direction.FORWARD },
        sectionKm = sections.sumOf { lengthKm(it.geometry) },
        unmatched = sections.count { it.status == SectionStatus.UNMATCHED },
        waiting = sections.count { it.status == SectionStatus.NEEDS_REMATCH },
        ridden = ridden.count { it.times > 0u },
        rides = finished.size,
        ridesKm = finished.sumOf { it.distanceM } / 1000.0,
        longestRideKm = (finished.maxOfOrNull { it.distanceM } ?: 0.0) / 1000.0,
        unfinished = tracks.size - finished.size,
        routes = routes.count { !it.isLoop },
        loops = routes.count { it.isLoop },
        tagsPending = store.listTags(TagStatus.PENDING).size,
    )
}

/** The route and loop settings in effect. */
fun settingsInEffect(context: Context): SettingsInEffect {
    val avoid = RoutePrefs.avoid(context)
    val zooms = RoutePrefs.locateZooms(context)
    return SettingsInEffect(
        gravel = RoutePrefs.gravel(context).name,
        favourites = RoutePrefs.favourites(context).name,
        unridden = RoutePrefs.unridden(context).name,
        allowed = listOfNotNull(
            "motorways".takeIf { !avoid.motorways },
            "ferries".takeIf { !avoid.ferries },
            "tolls".takeIf { !avoid.tolls },
        ),
        loopLength = RoutePrefs.loopChoice(context).key,
        loopDirection = RoutePrefs.loopDirection(context).name,
        zoomClose = zooms.close,
        zoomArea = zooms.area,
        keepScreenOn = RoutePrefs.keepScreenOn(context),
    )
}
