// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionLogicTest {
    @Test
    fun onlyRegionFileNamesMakeUrls() {
        assertEquals(REGIONS_BASE_URL + "regions-v1.manifest", regionUrl("regions-v1.manifest"))
        assertEquals(REGIONS_BASE_URL + "sweden-v1.region.gz", regionUrl("sweden-v1.region.gz"))
        assertTrue(REGIONS_BASE_URL.startsWith("https://"))
        for (bad in listOf("regions-v1.json", "../x.manifest", "a/b.region.gz", "x.apk", "", "https://evil/x.json", "X.json", "a.json?x")) {
            assertThrows(bad, IllegalArgumentException::class.java) { regionUrl(bad) }
        }
    }

    @Test
    fun resumesWhereTheDownloadStopped() {
        assertNull(resumeRange(0, 100))
        assertEquals("bytes=40-", resumeRange(40, 100))
        // Complete or longer than listed: nothing to resume.
        assertNull(resumeRange(100, 100))
        assertNull(resumeRange(150, 100))
        assertEquals(ResumeAction.APPEND, resumeAction(206, 40))
        // The server ignored the range: start again.
        assertEquals(ResumeAction.RESTART, resumeAction(200, 40))
        assertEquals(ResumeAction.RESTART, resumeAction(200, 0))
        // A partial answer to a request that asked for everything is wrong.
        assertEquals(ResumeAction.FAIL, resumeAction(206, 0))
        assertEquals(ResumeAction.FAIL, resumeAction(416, 40))
        assertEquals(ResumeAction.FAIL, resumeAction(404, 0))
    }

    @Test
    fun needsRoomForTheRestAndTheInstalledRegion() {
        val gz = 155_000_000L
        val region = 413_000_000L
        assertTrue(hasRoomFor(gz, 0, region, gz + region + REGION_SPACE_MARGIN))
        assertFalse(hasRoomFor(gz, 0, region, gz + region + REGION_SPACE_MARGIN - 1))
        // Half downloaded: only the rest is needed.
        assertTrue(hasRoomFor(gz, gz / 2, region, gz / 2 + region + REGION_SPACE_MARGIN))
        assertFalse(hasRoomFor(gz, 0, region, 0))
    }

    @Test
    fun anUpdateIsNewerDataForTheInstalledRegion() {
        assertTrue(isUpdate(100, 200))
        assertFalse(isUpdate(200, 200))
        assertFalse(isUpdate(300, 200))
        assertFalse(isUpdate(null, 200))
        assertEquals(155.1, mb(155_056_303), 0.0)
        assertEquals("2026-09-27", osmDate(1_790_471_426, java.time.ZoneOffset.UTC))
    }

    @Test
    fun opensByFingerprintAndFallsBackToTheFullCheck() {
        val calls = mutableListOf<String>()
        val fast = { fp: String -> calls += "fast $fp"; if (fp == "good") "region" else error("mismatch") }
        val full = { calls += "full"; "region" }

        assertEquals(OpenedRegion("region", needsFingerprint = false), openRegion("good", fast, full))
        assertEquals(listOf("fast good"), calls)

        calls.clear()
        assertEquals(OpenedRegion("region", needsFingerprint = true), openRegion("stale", fast, full))
        assertEquals(listOf("fast stale", "full"), calls)

        calls.clear()
        assertEquals(OpenedRegion("region", needsFingerprint = true), openRegion(null, fast, full))
        assertEquals(listOf("full"), calls)

        // A file that fails the full check is not opened.
        assertThrows(IllegalStateException::class.java) {
            openRegion<String>("stale", fast) { error("corrupt") }
        }
    }

    @Test
    fun offerRowsOfferTheRightAction() {
        assertEquals(OfferAction.DOWNLOAD, offerAction(null, null, "sweden", 200))
        assertEquals(OfferAction.DOWNLOAD, offerAction("skane", 300, "sweden", 200))
        assertEquals(OfferAction.UPDATE, offerAction("sweden", 100, "sweden", 200))
        assertEquals(OfferAction.INSTALLED, offerAction("sweden", 200, "sweden", 200))
        assertEquals(OfferAction.INSTALLED, offerAction("sweden", 300, "sweden", 200))
    }

    @Test
    fun downloadShareStaysInRange() {
        assertEquals(0.5f, downloadShare(50, 100))
        assertEquals(1f, downloadShare(150, 100))
        assertEquals(0f, downloadShare(-5, 100))
        assertEquals(0f, downloadShare(10, 0))
    }
}
