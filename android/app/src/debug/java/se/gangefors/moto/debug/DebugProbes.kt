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
 * Runs [BENCH_CASES] on [engine], each twice (cold, then warm), without
 * favourites; [progress] gets each result as it comes. Call off the main
 * thread.
 */
fun runBenchmark(engine: Engine, progress: (BenchResult) -> Unit) {
    for (case in BENCH_CASES) {
        val runs = mutableListOf<Double>()
        var last = ""
        var ok = true
        repeat(2) {
            if (!ok) return@repeat
            val (record, _) = DebugTools.measured(case.label, "", ::describe) { runCase(engine, case) }
            runs += record.ms
            last = record.result + String.format(java.util.Locale.ROOT, ", native +%.1f MB", record.nativePeakMb)
            ok = record.ok
        }
        progress(BenchResult(case.label, runs, last, ok))
    }
}

private fun runCase(engine: Engine, case: BenchCase): List<Route> = when (case) {
    is BenchCase.Route -> engine.routeChoices(
        case.from.latLon(), emptyList(), case.to.latLon(),
        routeOptions(defaultRouteOptions(), ROUTE_EXTRA_PERCENT, Gravel.valueOf(case.gravel)), null,
    )
    is BenchCase.Loop -> engine.roundTrip(
        case.start.latLon(), RoundTripTarget.DistanceM(case.km * 1000.0),
        routeOptions(defaultRouteOptions(), ROUTE_EXTRA_PERCENT, Gravel.valueOf(case.gravel)), null,
        LoopOptions(seed = 0u, bearing = null),
    )
}

private fun BenchPoint.latLon() = LatLon(lat, lon)

/** "3 routes, 412–488 km". */
fun describe(routes: List<Route>): String {
    if (routes.isEmpty()) return "none found"
    val d = routes.map { it.distanceM }
    val span = if (d.size == 1) km(d[0]) else "${km(d.min()).removeSuffix(" km")}–${km(d.max())}"
    return "${routes.size} ${if (routes.size == 1) "route" else "routes"}, $span"
}
