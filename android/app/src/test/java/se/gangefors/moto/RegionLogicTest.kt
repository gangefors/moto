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
    fun anUpdateIsNewerDataOrARebuiltFile() {
        assertTrue(isUpdate(100, 200, "a", "a"))
        assertFalse(isUpdate(200, 200, "a", "a"))
        assertFalse(isUpdate(300, 200, "a", "b"))
        assertFalse(isUpdate(null, 200, null, "a"))
        // The same map data built again: another file.
        assertTrue(isUpdate(200, 200, "a", "b"))
        // Installed before the app kept the checksum: offered once.
        assertTrue(isUpdate(200, 200, null, "a"))
        assertEquals(155.1, mb(155_056_303), 0.0)
        assertEquals("2026-09-27", osmDate(1_790_471_426, java.time.ZoneOffset.UTC))
    }

    @Test
    fun offerRowsOfferTheRightAction() {
        val se = InstalledRegion("sweden", "Sweden", 200, 400_000_000, enabled = true, gzSha256 = "s1")
        val no = InstalledRegion("norway", "Norway", 100, 470_000_000, enabled = false, gzSha256 = "n1")
        assertEquals(OfferAction.DOWNLOAD, offerAction(emptyList(), "sweden", 200, "s1"))
        assertEquals(OfferAction.DOWNLOAD, offerAction(listOf(no), "sweden", 200, "s1"))
        assertEquals(OfferAction.INSTALLED, offerAction(listOf(no, se), "sweden", 200, "s1"))
        assertEquals(OfferAction.INSTALLED, offerAction(listOf(se), "sweden", 150, "s0"))
        // A disabled region is still installed, and still offered updates.
        assertEquals(OfferAction.UPDATE, offerAction(listOf(se, no), "norway", 300, "n2"))
        // Rebuilt: same day, another file; its row says so.
        assertEquals(OfferAction.UPDATE, offerAction(listOf(se), "sweden", 200, "s2"))
        assertTrue(isRebuild(listOf(se), "sweden", 200))
        assertFalse(isRebuild(listOf(no), "norway", 300))
        assertFalse(isRebuild(emptyList(), "sweden", 200))
    }

    @Test
    fun updateAllTakesTheOffersThatUpdateARegionOnThePhone() {
        fun offer(id: String, ts: Long, sha: String) = se.gangefors.moto.core.RegionOffer(
            id = id, name = id, fileName = "$id.zst", gzBytes = 1uL, gzSha256 = sha, regionBytes = 2uL,
            osmTimestamp = ts, southWest = se.gangefors.moto.core.LatLon(55.0, 10.0),
            northEast = se.gangefors.moto.core.LatLon(56.0, 11.0),
        )
        val installed = listOf(
            InstalledRegion("sweden", "Sweden", 200, 1, enabled = true, gzSha256 = "s1"),
            InstalledRegion("norway", "Norway", 200, 1, enabled = false, gzSha256 = "n1"),
            InstalledRegion("denmark", "Denmark", 200, 1, enabled = true, gzSha256 = "d1"),
        )
        val offers = listOf(
            offer("sweden", 200, "s2"), // rebuilt
            offer("norway", 300, "n2"), // newer data, switched off
            offer("denmark", 200, "d1"), // the same file
            offer("finland", 200, "f1"), // not on the phone: Download, not Update
        )
        assertEquals(listOf("sweden", "norway"), offersToUpdate(installed, offers).map { it.id })
        assertEquals(emptyList<String>(), offersToUpdate(emptyList(), offers).map { it.id })
    }

    @Test
    fun theDebugReportTellsHowARegionOpened() {
        assertEquals(OpenCheck.CHECKSUM, openCheck("abc", "abc"))
        assertEquals(OpenCheck.FULL_NO_CHECKSUM, openCheck(null, "abc"))
        assertEquals(OpenCheck.FULL_CHECKSUM_DIFFERED, openCheck("abc", "def"))
        assertEquals(OpenCheck.FULL_CHECKSUM_DIFFERED, openCheck("abc", null))
    }

    @Test
    fun downloadShareStaysInRange() {
        assertEquals(0.5f, downloadShare(50, 100))
        assertEquals(1f, downloadShare(150, 100))
        assertEquals(0f, downloadShare(-5, 100))
        assertEquals(0f, downloadShare(10, 0))
    }

    @Test
    fun regionIdsNameFilesSafely() {
        assertTrue(isRegionId("sweden"))
        assertTrue(isRegionId("se-2"))
        for (bad in listOf("", "Sweden", "../x", "a/b", "a".repeat(33), "sv.region", "é")) {
            assertFalse(bad, isRegionId(bad))
        }
        assertEquals("finland.region", regionFileName("finland"))
        assertThrows(IllegalArgumentException::class.java) { regionFileName("../../prefs") }
    }

    @Test
    fun onlyEnabledRegionsOpenInAStableOrder() {
        val se = InstalledRegion("sweden", "Sweden", 1, 10, enabled = true)
        val dk = InstalledRegion("denmark", "Denmark", 1, 20, enabled = true)
        val no = InstalledRegion("norway", "Norway", 1, 30, enabled = false)
        assertEquals(listOf(dk, se), enabledRegions(listOf(se, no, dk)))
        assertEquals(60L, installedBytes(listOf(se, no, dk)))
        assertTrue(enabledRegions(listOf(no)).isEmpty())
    }

    @Test
    fun regionsFromAnOlderMonthAreNamed() {
        val day = 24L * 3600
        val se = InstalledRegion("sweden", "Sweden", 100 * day, 1, enabled = true)
        val no = InstalledRegion("norway", "Norway", 95 * day, 1, enabled = true)
        val dk = InstalledRegion("denmark", "Denmark", 60 * day, 1, enabled = true)
        val fi = InstalledRegion("finland", "Finland", 10 * day, 1, enabled = false)
        assertEquals(listOf(dk), olderNeighbours(listOf(se, no, dk, fi)))
        assertTrue(olderNeighbours(listOf(se, no)).isEmpty())
        assertTrue(olderNeighbours(listOf(se.copy(osmTimestamp = 0), dk)).isEmpty())
        assertTrue(olderNeighbours(emptyList()).isEmpty())
    }
}
