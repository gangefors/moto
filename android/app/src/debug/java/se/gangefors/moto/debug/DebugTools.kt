// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto.debug

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import se.gangefors.moto.OneLine
import se.gangefors.moto.core.Favourites
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.LoopOptions
import se.gangefors.moto.core.Route
import se.gangefors.moto.core.RoundTripTarget
import se.gangefors.moto.core.RouteOptions
import se.gangefors.moto.core.TimeBudget

/**
 * Debug tools: timings and memory figures from the phone, to report
 * exact numbers. Debug builds only: release builds get the do-nothing
 * version in src/release, so nothing here reaches a rider. The app calls
 * only the functions of this object; everything else lives in this
 * package. Everything is also logged (tag "moto").
 */
object DebugTools {
    private val _startup = MutableStateFlow<List<String>>(emptyList())
    val startup: StateFlow<List<String>> = _startup.asStateFlow()

    private val _queries = MutableStateFlow<List<QueryRecord>>(emptyList())
    val queries: StateFlow<List<QueryRecord>> = _queries.asStateFlow()

    /** Runs [block] as start step [name] and records how long it took. */
    fun <T> startup(name: String, block: () -> T): T {
        val start = SystemClock.uptimeMillis()
        try {
            return block()
        } finally {
            mark(name, SystemClock.uptimeMillis() - start)
        }
    }

    /** Records that start step [name] happened. */
    fun mark(name: String) = mark(name, null)

    private fun mark(name: String, ms: Long?) {
        val line = startupLine(name, ms, sinceStart())
        Log.i("moto", "startup: $line")
        _startup.update { (it + line).takeLast(MAX_STARTUP) }
    }

    /** Times [block], a route choices request from [from] to [to]. */
    fun routes(
        from: LatLon,
        via: List<LatLon>,
        to: LatLon,
        opts: RouteOptions,
        favourites: Favourites?,
        block: () -> List<Route>,
    ): List<Route> = record("route choices", { routeDetail(from, via, to, opts, favourites) }, ::describe, block)

    /** Times [block], a loop set from [start]; [ahead] when found ahead for Shuffle. */
    fun loops(
        start: LatLon,
        target: RoundTripTarget,
        opts: RouteOptions,
        favourites: Favourites?,
        shape: LoopOptions,
        ahead: Boolean,
        block: () -> List<Route>,
    ): List<Route> = record(
        if (ahead) "loops (ahead)" else "loops",
        { loopDetail(target, opts, favourites, shape) },
        ::describe,
        block,
    )

    /** Times [block], a small call into the core such as a snap. */
    fun <T> query(kind: String, block: () -> T): T = record(kind, { "" }, { "ok" }, block)

    /** Runs and records [block]; failures are recorded and rethrown. */
    private fun <T> record(kind: String, detail: () -> String, summary: (T) -> String, block: () -> T): T {
        val (record, result) = measured(kind, runCatching(detail).getOrDefault("?"), summary, block)
        Log.i("moto", "query: ${queryLine(record)}")
        _queries.update { (it + record).takeLast(MAX_QUERIES) }
        return result.getOrThrow()
    }

    fun clearQueries() {
        _queries.value = emptyList()
    }

    /** Times [block]; the record, and what it returned or threw. */
    internal fun <T> measured(
        kind: String,
        detail: String,
        summary: (T) -> String,
        block: () -> T,
    ): Pair<QueryRecord, Result<T>> {
        val sampler = NativePeak().apply { start() }
        val cpu0 = SystemClock.currentThreadTimeMillis()
        val t0 = SystemClock.elapsedRealtimeNanos()
        val result = runCatching(block)
        val wall = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        val cpu = (SystemClock.currentThreadTimeMillis() - cpu0).toDouble()
        val peak = sampler.stop()
        val text = result.fold(
            onSuccess = { runCatching { summary(it) }.getOrElse { e -> "?" + e.message } },
            onFailure = { it.message ?: it.toString() },
        )
        return QueryRecord(kind, detail, wall, cpu, peak / 1e6, text, result.isSuccess, sinceStart()) to result
    }

    /** The "Debug tools" entry at the bottom of My data. */
    @Composable
    fun MenuEntry(modifier: Modifier = Modifier) {
        var open by remember { mutableStateOf(false) }
        TextButton(onClick = { open = true }, modifier = modifier.padding(top = 4.dp)) { OneLine("Debug tools") }
        if (open) DebugDialog(onDismiss = { open = false })
    }

    private fun sinceStart() = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()

    private const val MAX_STARTUP = 30
    private const val MAX_QUERIES = 200
}

private fun routeDetail(from: LatLon, via: List<LatLon>, to: LatLon, opts: RouteOptions, favourites: Favourites?): String {
    val points = listOf(from) + via + to
    val straight = points.zipWithNext { a, b -> metersApart(a.lat, a.lon, b.lat, b.lon) }.sum()
    return "${km(straight)} straight line, ${via.size} waypoints, gravel ${opts.gravel}, " +
        "${budget(opts)}, ${favouriteCount(favourites)}"
}

private fun loopDetail(target: RoundTripTarget, opts: RouteOptions, favourites: Favourites?, shape: LoopOptions): String {
    val length = when (target) {
        is RoundTripTarget.DistanceM -> km(target.meters)
        is RoundTripTarget.DurationS -> String.format(java.util.Locale.ROOT, "%.1f h", target.seconds / 3600)
    }
    val bearing = shape.bearing?.let { "heading ${it.toInt()}°" } ?: "any way"
    return "$length, $bearing, seed ${shape.seed}, gravel ${opts.gravel}, ${favouriteCount(favourites)}"
}

private fun budget(opts: RouteOptions): String = when (val b = opts.budget) {
    is TimeBudget.Extra -> "+${(b.ratio * 100).toInt()} % time"
    else -> b.toString()
}

private fun favouriteCount(favourites: Favourites?): String =
    favourites?.let { "${it.edgeCount()} favourite edges" } ?: "no favourites"

/**
 * Samples the native heap in use every few milliseconds while a query
 * runs; [stop] returns the highest value over the starting one (bytes).
 * The core's memory is native, so this is what a route or loop costs.
 */
private class NativePeak {
    @Volatile private var running = true
    @Volatile private var peak = 0L
    private val base = Debug.getNativeHeapAllocatedSize()
    private val thread = Thread {
        while (running) {
            peak = maxOf(peak, Debug.getNativeHeapAllocatedSize())
            try {
                Thread.sleep(SAMPLE_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }.apply { isDaemon = true; name = "moto-debug-mem" }

    fun start() = thread.start()

    fun stop(): Long {
        running = false
        thread.interrupt()
        thread.join()
        peak = maxOf(peak, Debug.getNativeHeapAllocatedSize())
        return (peak - base).coerceAtLeast(0)
    }

    private companion object {
        const val SAMPLE_MS = 10L
    }
}
