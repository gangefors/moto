// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.TrackPoint

class RecordingLogicTest {
    private fun fix(i: Int) = TrackPoint(
        timeMs = 1_790_000_000_000L + i * 1000L,
        position = LatLon(55.7 + i * 0.0002, 13.2),
        accuracyM = 4.5,
        speedMps = 21.25,
        bearingDeg = 359.5,
    )

    private fun tempFile(): File = Files.createTempFile("moto-buffer", ".fixes").toFile().apply { deleteOnExit() }

    @Test
    fun bufferKeepsFixesUntilCleared() {
        val file = tempFile()
        val buffer = PointBuffer(file)
        (0 until 5).forEach { buffer.append(fix(it)) }
        // Read back by a new instance, as after the app died.
        val read = PointBuffer(file).read()
        assertEquals(5, read.size)
        assertEquals(fix(3).timeMs, read[3].timeMs)
        assertEquals(fix(3).position.lat, read[3].position.lat, 1e-7)
        assertEquals(4.5, read[3].accuracyM!!, 0.0)
        assertEquals(359.5, read[3].bearingDeg!!, 0.0)

        buffer.clear()
        assertTrue(PointBuffer(file).read().isEmpty())
        buffer.append(fix(9))
        assertEquals(listOf(fix(9).timeMs), PointBuffer(file).read().map { it.timeMs })
        buffer.delete()
        assertFalse(file.exists())
        assertTrue(PointBuffer(file).read().isEmpty())
    }

    @Test
    fun unknownValuesStayUnknown() {
        val bare = TrackPoint(1_790_000_000_000L, LatLon(55.7, 13.2), null, null, null)
        val line = formatFix(bare)
        assertEquals("1790000000000,55.7000000,13.2000000,,,\n", line)
        assertEquals(listOf(bare), parseFixes(line))
    }

    @Test
    fun aHalfWrittenLastLineIsIgnored() {
        val text = formatFix(fix(0)) + formatFix(fix(1)) + formatFix(fix(2)).dropLast(8)
        assertEquals(2, parseFixes(text).size)
        assertTrue(parseFixes("1790000000000,55.7").isEmpty())
        assertTrue(parseFixes("").isEmpty())
    }

    @Test
    fun damagedLinesAreSkipped() {
        val good = formatFix(fix(0))
        val damaged = listOf(
            "garbage\n",
            "1,2,3\n",
            "1790000000000,55.7,13.2,1,2,3,4\n",
            "x,55.7,13.2,,,\n",
            "1790000000000,NaN,13.2,,,\n",
            "1790000000000,95.0,13.2,,,\n",
            "1790000000000,55.7,200.0,,,\n",
            "-5,55.7,13.2,,,\n",
            "1790000000000,55.7,13.2,500.0,,\n", // too vague to keep
            "\u0000\u0001\n",
        ).joinToString("")
        val read = parseFixes(damaged + good + damaged)
        assertEquals(listOf(fix(0).timeMs), read.map { it.timeMs })
        // Bad optional values become unknown instead of dropping the fix.
        val odd = parseFixes("1790000000000,55.7,13.2,-1,x,720\n").single()
        assertNull(odd.accuracyM)
        assertNull(odd.speedMps)
        assertNull(odd.bearingDeg)
    }

    @Test
    fun readingStopsAtTheLimit() {
        val text = (0 until 50).joinToString("") { formatFix(fix(it)) }
        assertEquals(20, parseFixes(text, limit = 20).size)
    }

    @Test
    fun randomBytesNeverThrow() {
        val rng = java.util.Random(42)
        repeat(200) {
            val bytes = ByteArray(rng.nextInt(400)) { (rng.nextInt(96) + 32).toByte() }
            val text = String(bytes, Charsets.US_ASCII).replace('~', '\n')
            parseFixes(text).forEach { p ->
                assertTrue(p.position.lat in -90.0..90.0 && p.position.lon in -180.0..180.0)
            }
        }
    }

    @Test
    fun bufferFileNamesCarryTheTrackId() {
        assertEquals("track-42.fixes", PointBuffer.fileName(42))
        assertEquals(42L, PointBuffer.trackIdOf("track-42.fixes"))
        assertNull(PointBuffer.trackIdOf("track-0.fixes"))
        assertNull(PointBuffer.trackIdOf("track-42.fixes.tmp"))
        assertNull(PointBuffer.trackIdOf("../track-42.fixes"))
        assertNull(PointBuffer.trackIdOf("track-99999999999999999999.fixes"))
        assertNull(PointBuffer.trackIdOf("moto.db"))
    }

    @Test
    fun checksFixesFromTheProvider() {
        assertNotNull(checkedFix(1_790_000_000_000L, 55.7, 13.2, 5.0, 20.0, 90.0))
        assertNull(checkedFix(-1, 55.7, 13.2, 5.0, null, null))
        assertNull(checkedFix(1_790_000_000_000L, Double.NaN, 13.2, null, null, null))
        assertNull(checkedFix(1_790_000_000_000L, 55.7, 13.2, 150.0, null, null))
        val odd = checkedFix(1_790_000_000_000L, 55.7, 13.2, Double.NaN, -3.0, 360.0)!!
        assertNull(odd.accuracyM)
        assertNull(odd.speedMps)
        assertNull(odd.bearingDeg)
    }

    @Test
    fun flushesByCountOrAge() {
        val policy = FlushPolicy(maxFixes = 30, maxAgeMs = 30_000)
        assertFalse(policy.due(0, 60_000))
        assertFalse(policy.due(29, 29_999))
        assertTrue(policy.due(30, 0))
        assertTrue(policy.due(1, 30_000))
    }

    @Test
    fun progressIgnoresStandstillJitter() {
        val p = RideProgress(stepM = 10.0)
        // North in 0.0002° (22 m) steps.
        (0..10).forEach { p.add(LatLon(55.7 + it * 0.0002, 13.2)) }
        assertEquals(222.4, p.distanceM, 1.0)
        assertEquals(11, p.line.size)
        // Jitter of 3 m adds nothing.
        repeat(50) { p.add(LatLon(55.702 + (it % 2) * 0.00003, 13.2)) }
        assertEquals(222.4, p.distanceM, 1.0)
        assertEquals(11, p.line.size)
    }

    @Test
    fun formatsDurations() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:59", formatDuration(59 * 60_000L + 59_000))
        assertEquals("1:05", formatDuration(65 * 60_000L))
        assertEquals("0:00", formatDuration(-5))
    }

    @Test
    fun batteryUsePerHour() {
        val hour = 3_600_000L
        assertEquals(6.0, batteryPerHour(90, 84, hour)!!, 1e-9)
        assertEquals(4.0, batteryPerHour(80, 78, 30 * 60_000L)!!, 1e-9)
        assertNull(batteryPerHour(80, 79, 10 * 60_000L)) // too short
        assertNull(batteryPerHour(50, 60, hour)) // charging
        assertNull(batteryPerHour(null, 60, hour))
        assertNull(batteryPerHour(120, 60, hour))
    }

    @Test
    fun splitsIntoBatchesTheCoreAccepts() {
        val many = (0 until 25_000).map { fix(it) }
        assertEquals(listOf(10_000, 10_000, 5_000), batches(many).map { it.size })
        assertTrue(batches(emptyList()).isEmpty())
    }

    @Test
    fun namesRidesByLocalStartTime() {
        val stockholm = java.time.ZoneId.of("Europe/Stockholm")
        // 2026-09-24 05:30 UTC is 07:30 in Stockholm (summer time).
        val start = 1_790_227_800L
        assertEquals("2026-09-24 07:30", rideTitle(start, stockholm))
        assertEquals("moto-ride-2026-09-24-0730.gpx", rideFileName(start, stockholm))
        assertEquals("2026-09-24 05:30", rideTitle(start, java.time.ZoneOffset.UTC))
    }

    @Test
    fun readsFilesUpToTheLimitOnly() {
        val small = ByteArray(100) { it.toByte() }
        assertEquals(small.toList(), readCapped(small.inputStream(), 100)!!.toList())
        assertNull(readCapped(ByteArray(101).inputStream(), 100))
        assertEquals(0, readCapped(ByteArray(0).inputStream(), 100)!!.size)
        // A stream that never ends is cut off at the limit.
        val endless = object : java.io.InputStream() {
            override fun read(): Int = 0
        }
        assertNull(readCapped(endless, 1_000_000))
    }

    @Test
    fun namesSectionExports() {
        val stockholm = java.time.ZoneId.of("Europe/Stockholm")
        assertEquals("moto-sections-2026-09-24.tar.gz", sectionsFileName(1_790_227_800L, stockholm, "tar.gz"))
    }
}
