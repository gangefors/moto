// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugReportTest {
    private fun record(kind: String, ms: Double, ok: Boolean = true) =
        QueryRecord(kind, "", ms, ms, 0.0, "", ok, 0)

    @Test
    fun startupLinesSayHowLongAndWhen() {
        assertEquals("region open: 7.41 s (at 8.02 s)", startupLine("region open", 7_410, 8_020))
        assertEquals("map style loaded (at 1.50 s)", startupLine("map style loaded", null, 1_500))
    }

    @Test
    fun percentilesAreNearestRank() {
        val v = (1..10).map { it.toDouble() }
        assertEquals(5.0, percentile(v, 50.0), 0.0)
        assertEquals(9.0, percentile(v, 90.0), 0.0)
        assertEquals(10.0, percentile(v, 100.0), 0.0)
        assertEquals(1.0, percentile(listOf(1.0), 90.0), 0.0)
        assertEquals(0.0, percentile(emptyList(), 50.0), 0.0)
    }

    @Test
    fun statsPerKindLeaveOutFailures() {
        val stats = queryStats(
            listOf(record("loops", 100.0), record("snap", 2.0), record("loops", 300.0), record("loops", 9_000.0, ok = false)),
        )
        assertEquals(listOf("loops", "snap"), stats.map { it.kind })
        assertEquals(QueryStats("loops", 2, 100.0, 300.0, 300.0), stats[0])
        assertEquals("loops: 2×, median 100 ms, p90 300 ms, max 300 ms", statsLine(stats[0]))
    }

    @Test
    fun linesShowTimesMemoryAndFailures() {
        val ok = QueryRecord("route choices", "312 km straight line", 1234.0, 1200.0, 12.34, "3 routes, 400–450 km", true, 0)
        assertEquals(
            "route choices: 1.23 s (cpu 1.20 s, native +12.3 MB) 3 routes, 400–450 km | 312 km straight line",
            queryLine(ok),
        )
        val failed = ok.copy(ok = false, result = "no route", detail = "")
        assertEquals("route choices: 1.23 s (cpu 1.20 s, native +12.3 MB) FAILED no route", queryLine(failed))
    }

    @Test
    fun readsProcStatus() {
        val kb = procStatusKb("Name:\tmoto\nVmHWM:\t  812345 kB\nVmRSS:\t 700000 kB\nThreads:\t42\n")
        assertEquals(812_345L, kb["VmHWM"])
        assertEquals(700_000L, kb["VmRSS"])
        assertEquals(null, kb["Threads"])
        assertEquals(emptyMap<String, Long>(), procStatusKb("garbage\n:\nVmRSS: x kB"))
    }

    @Test
    fun sumsTheRegionMappingsInSmaps() {
        val smaps = """
            7000000000-7010000000 r--s 00000000 fd:30 123 /data/user/0/se.gangefors.moto/files/regions/downloaded.region
            Size:             262144 kB
            Rss:              200000 kB
            Pss:              150000 kB
            7010000000-7020000000 r-xp 00000000 fd:30 456 /apex/libc.so
            Rss:                 900 kB
            Pss:                 300 kB
            7020000000-7030000000 r--s 01000000 fd:30 123 /data/user/0/se.gangefors.moto/files/regions/downloaded.region
            Rss:               1000 kB
            Pss:                500 kB
        """.trimIndent()
        assertEquals(MappedKb(201_000, 150_500), smapsFor(smaps, ".region"))
        assertEquals(MappedKb(0, 0), smapsFor("", ".region"))
    }

    @Test
    fun distancesAreGreatCircle() {
        // Malmö to Lund, about 16 km.
        val d = metersApart(55.6050, 13.0038, 55.7047, 13.1910)
        assertTrue("$d", d in 15_000.0..17_000.0)
        assertEquals(0.0, metersApart(55.0, 13.0, 55.0, 13.0), 0.0)
    }

    @Test
    fun benchmarkCasesAreFixedAndLabelled() {
        assertTrue(BENCH_CASES.size >= 8)
        assertEquals(BENCH_CASES.size, BENCH_CASES.map { it.label }.toSet().size)
        assertEquals("route Malmö → Kiruna, gravel AVOID", BENCH_CASES[3].label)
        assertEquals(
            "loop 400 km from Lund, gravel PREFER: 4.10 s / 3.20 s — 3 routes",
            benchLine(BenchResult("loop 400 km from Lund, gravel PREFER", listOf(4100.0, 3200.0), "3 routes", true)),
        )
        assertEquals("x: FAILED outside the region", benchLine(BenchResult("x", listOf(5.0), "outside the region", false)))
    }

    @Test
    fun reportSkipsEmptySections() {
        val text = reportText("head", listOf(ReportSection("A", listOf("a1")), ReportSection("B", emptyList())))
        assertEquals("head\n\n== A ==\na1\n", text)
    }
}
