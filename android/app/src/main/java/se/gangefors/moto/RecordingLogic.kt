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

    /** The next point starts after a gap: no distance is counted to it. */
    private var gap = false

    fun add(p: LatLon) {
        val last = points.lastOrNull()
        if (last == null || gap) {
            points += p
            gap = false
            return
        }
        val d = approxDistanceM(last, p)
        if (d >= stepM) {
            distanceM += d
            points += p
        }
    }

    /** Recording carries on after a gap (a pause where the rider moved
     * away): the distance doesn't bridge it. */
    fun breakLine() {
        if (points.isNotEmpty()) gap = true
    }
}

/** Moving farther than this while recording was paused starts a new
 * segment when it carries on; nearer, the line just continues. */
const val PAUSE_JOIN_M = 50.0

/** Whether a recording paused at [pausedAt] starts a new segment when it
 * carries on at [now]: the rider moved more than [PAUSE_JOIN_M] away.
 * Without both positions it carries on as one line. */
fun breaksAfterPause(pausedAt: LatLon?, now: LatLon?): Boolean =
    pausedAt != null && now != null && approxDistanceM(pausedAt, now) > PAUSE_JOIN_M

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

/** "2026-09-24 07:30", a ride's start in local time, for lists and GPX names. */
fun rideTitle(startedAtSec: Long, zone: java.time.ZoneId): String =
    java.time.Instant.ofEpochSecond(startedAtSec).atZone(zone)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT))

/** A ride's name: the rider's own, else its start in local time. */
fun rideName(name: String?, startedAtSec: Long, zone: java.time.ZoneId): String =
    name?.takeIf { it.isNotBlank() } ?: rideTitle(startedAtSec, zone)

/** The day ride [name] was ridden, shown beside it ("2026-09-24"); null
 * when it has no name of the rider's, as its name is then its start. */
fun rideDate(name: String?, startedAtSec: Long, zone: java.time.ZoneId): String? =
    if (name.isNullOrBlank()) {
        null
    } else {
        java.time.Instant.ofEpochSecond(startedAtSec).atZone(zone)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT))
    }

/** A ride's line under its name: the day it was ridden (when not in its
 * name already) and [summary]. */
fun rideLine(name: String?, startedAtSec: Long, zone: java.time.ZoneId, summary: String): String =
    listOfNotNull(rideDate(name, startedAtSec, zone), summary).joinToString(" · ")

/** Suggested export file name: "moto-ride-2026-09-24-0730.gpx". */
fun rideFileName(startedAtSec: Long, zone: java.time.ZoneId): String =
    "moto-ride-" + java.time.Instant.ofEpochSecond(startedAtSec).atZone(zone)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.ROOT)) + ".gpx"

/** Largest section file read for import; the core's own limit. */
const val MAX_IMPORT_FILE_BYTES = 32 * 1024 * 1024

/** Largest GPX file read to import as a ride; the core's own limit. */
const val MAX_GPX_FILE_BYTES = 64 * 1024 * 1024

/**
 * Up to [limit] bytes from [input], or null if there are more (the file is
 * too large). Stops reading at the limit, whatever the file claims.
 */
fun readCapped(input: java.io.InputStream, limit: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > limit) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** Suggested export file name: "moto-favourite-sections-2026-09-24.zip". */
fun sectionsFileName(atSec: Long, zone: java.time.ZoneId, extension: String): String =
    "moto-favourite-sections-" + java.time.Instant.ofEpochSecond(atSec).atZone(zone)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)) + "." + extension

/** At most this speed (m/s, about 2 km/h) a fix may count as standing
 * still. */
const val STILL_MPS = 0.5

/** A still fix is skipped within this of the last one kept, metres, or
 * within the worse accuracy of the two (up to [STILL_MAX_M]): indoor
 * jitter at speed 0 jumps 10–20 m (the rider's test, 2026-10-03). */
const val STILL_M = 25.0
const val STILL_MAX_M = 50.0

/**
 * Whether [fix] is the rider standing still where [lastKept] was (at a
 * fuel stop, say): slow (a speed of at most [STILL_MPS]) and near it, so
 * the ride keeps no pile of GPS jitter there (2026-10-03). Without
 * a speed or a fix kept before, it is kept.
 */
fun standingStill(lastKept: TrackPoint?, fix: TrackPoint): Boolean {
    val last = lastKept ?: return false
    val speed = fix.speedMps?.takeIf { it.isFinite() } ?: return false
    if (speed > STILL_MPS) return false
    val accuracy = maxOf(fix.accuracyM?.takeIf { it.isFinite() } ?: 0.0, last.accuracyM?.takeIf { it.isFinite() } ?: 0.0)
    val within = accuracy.coerceIn(STILL_M, STILL_MAX_M)
    return approxDistanceM(last.position, fix.position) < within
}

/** A fix less accurate than this (metres) isn't kept in a ride: GPS
 * warming up indoors, a tunnel's mouth. The map and following still use it. */
const val KEEP_MAX_ACCURACY_M = 30.0

/** Whether [fix] is accurate enough to keep in a ride (no accuracy given
 * counts as enough). */
fun accurateEnough(fix: TrackPoint): Boolean =
    fix.accuracyM?.let { it.isFinite() && it <= KEEP_MAX_ACCURACY_M } ?: true
