// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.TrackPoint

/**
 * Crash-safe buffer for the fixes of a ride that the core has not stored
 * yet (ADR-0006): each fix is appended as one text line to a small file in
 * app-private storage, so if the app process dies the fixes since the last
 * batch survive and are handed to the core on the next start. The file is
 * the app's own, but it is read back as untrusted input: lines that don't
 * parse or hold impossible values are skipped, and a half-written last line
 * is ignored.
 *
 * Not thread-safe; the recording service uses it from one thread.
 */
class PointBuffer(val file: File) {
    private var out: FileOutputStream? = null

    /** Appends one fix. The line reaches the OS at once (no fsync, which
     * would cost battery): it survives the app dying, not the phone. */
    @Throws(IOException::class)
    fun append(fix: TrackPoint) {
        val stream = out ?: FileOutputStream(file, true).also { out = it }
        stream.write(formatFix(fix).toByteArray(Charsets.US_ASCII))
        stream.flush()
    }

    /** Empties the buffer once the core has stored its fixes. */
    @Throws(IOException::class)
    fun clear() {
        close()
        FileOutputStream(file, false).close()
    }

    /** The buffered fixes, oldest first, at most [limit]. */
    fun read(limit: Int = MAX_BUFFERED_FIXES): List<TrackPoint> =
        if (!file.isFile) emptyList() else parseFixes(file.readBytes().toString(Charsets.US_ASCII), limit)

    fun close() {
        out?.close()
        out = null
    }

    fun delete() {
        close()
        file.delete()
    }

    companion object {
        /** File name of the buffer for track [trackId]. */
        fun fileName(trackId: Long): String = "track-$trackId.fixes"

        /** The track id in a buffer file name, or null for any other name. */
        fun trackIdOf(name: String): Long? =
            BUFFER_NAME.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }

        private val BUFFER_NAME = Regex("track-([0-9]{1,18})\\.fixes")
    }
}

/** Most fixes read back from one buffer: a track's limit in the core. */
const val MAX_BUFFERED_FIXES = 200_000

/** Most fixes the core takes per call. */
const val MAX_BATCH_FIXES = 10_000

/** One buffer line: time,lat,lon,accuracy,speed,bearing (empty when unknown). */
fun formatFix(p: TrackPoint): String {
    fun opt(v: Double?) = v?.let { String.format(Locale.ROOT, "%.2f", it) } ?: ""
    return String.format(
        Locale.ROOT,
        "%d,%.7f,%.7f,%s,%s,%s\n",
        p.timeMs,
        p.position.lat,
        p.position.lon,
        opt(p.accuracyM),
        opt(p.speedMps),
        opt(p.bearingDeg),
    )
}

/** Parses buffer text; see [PointBuffer]. */
fun parseFixes(text: String, limit: Int = MAX_BUFFERED_FIXES): List<TrackPoint> {
    val fixes = ArrayList<TrackPoint>()
    // Only lines ended by a newline are whole.
    val end = text.lastIndexOf('\n')
    if (end < 0) return fixes
    for (line in text.substring(0, end).splitToSequence('\n')) {
        if (fixes.size >= limit) break
        val f = line.split(',')
        if (f.size != 6) continue
        val time = f[0].toLongOrNull() ?: continue
        val lat = f[1].toDoubleOrNull() ?: continue
        val lon = f[2].toDoubleOrNull() ?: continue
        fun opt(s: String): Double? = if (s.isEmpty()) null else s.toDoubleOrNull() ?: Double.NaN
        val fix = checkedFix(time, lat, lon, opt(f[3]), opt(f[4]), opt(f[5])) ?: continue
        fixes += fix
    }
    return fixes
}

/**
 * A fix from the location provider, or null if it is unusable: the core
 * refuses a whole batch with an impossible value, so they are dropped
 * here. Optional values that are out of range become unknown.
 */
fun checkedFix(
    timeMs: Long,
    lat: Double,
    lon: Double,
    accuracyM: Double?,
    speedMps: Double?,
    bearingDeg: Double?,
): TrackPoint? {
    if (timeMs !in 0..MAX_FIX_TIME_MS) return null
    if (!(lat in -90.0..90.0 && lon in -180.0..180.0)) return null
    if (accuracyM != null && accuracyM > MAX_USEFUL_ACCURACY_M) return null
    return TrackPoint(
        timeMs = timeMs,
        position = LatLon(lat, lon),
        accuracyM = accuracyM?.takeIf { it in 0.0..MAX_USEFUL_ACCURACY_M },
        speedMps = speedMps?.takeIf { it in 0.0..MAX_SPEED_MPS },
        bearingDeg = bearingDeg?.takeIf { it >= 0.0 && it < 360.0 },
    )
}

/** Fixes worse than this are too vague to be part of a ride. */
const val MAX_USEFUL_ACCURACY_M = 100.0
private const val MAX_SPEED_MPS = 200.0
private const val MAX_FIX_TIME_MS = 4_102_444_800_000L

/**
 * When buffered fixes go to the core: after [maxFixes] fixes or when the
 * oldest has waited [maxAgeMs]. Batches keep database writes (and battery
 * use) low; the buffer file keeps the fixes in between safe.
 */
class FlushPolicy(private val maxFixes: Int = 30, private val maxAgeMs: Long = 30_000) {
    fun due(buffered: Int, oldestAgeMs: Long): Boolean =
        buffered >= maxFixes || (buffered > 0 && oldestAgeMs >= maxAgeMs)
}

/**
 * Live figures of a ride for the notification and the map: the length
 * (moves under [stepM] ignored, as the core counts it) and the line ridden,
 * thinned to the same step.
 */
class RideProgress(private val stepM: Double = 10.0) {
    var distanceM = 0.0
        private set
    private val points = ArrayList<LatLon>()

    val line: List<LatLon> get() = points.toList()

    fun add(p: LatLon) {
        val last = points.lastOrNull()
        if (last == null) {
            points += p
            return
        }
        val d = approxDistanceM(last, p)
        if (d >= stepM) {
            distanceM += d
            points += p
        }
    }
}

/** "1:05" for 65 minutes. */
fun formatDuration(ms: Long): String {
    val minutes = (ms.coerceAtLeast(0) / 60_000)
    return String.format(Locale.ROOT, "%d:%02d", minutes / 60, minutes % 60)
}

/**
 * Battery used per hour of recording, in percent, from the charge level at
 * the start and the end; null if unknown, the ride was too short to tell
 * (under [MIN_BATTERY_SAMPLE_MS]) or the phone was charging.
 */
fun batteryPerHour(startPercent: Int?, endPercent: Int?, durationMs: Long): Double? {
    if (startPercent == null || endPercent == null) return null
    if (startPercent !in 0..100 || endPercent !in 0..100) return null
    if (durationMs < MIN_BATTERY_SAMPLE_MS || endPercent > startPercent) return null
    return (startPercent - endPercent) * 3_600_000.0 / durationMs
}

/** Shorter rides don't give a meaningful battery figure (1 % steps). */
const val MIN_BATTERY_SAMPLE_MS = 20 * 60_000L

/** Splits [fixes] into batches the core accepts. */
fun batches(fixes: List<TrackPoint>): List<List<TrackPoint>> = fixes.chunked(MAX_BATCH_FIXES)
