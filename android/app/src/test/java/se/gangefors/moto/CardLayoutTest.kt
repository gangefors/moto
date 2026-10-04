// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardLayoutTest {
    @Test
    fun landscapeIsWiderThanHigh() {
        assertTrue(isLandscape(891, 411))
        assertTrue(isLandscape(1280, 800))
        assertFalse(isLandscape(500, 500))
        assertFalse(isLandscape(411, 891))
    }

    @Test
    fun columnIsHalfTheWidth() {
        assertEquals(320f, leftColumnWidthDp(640), 0.01f)
        assertEquals(350f, leftColumnWidthDp(700), 0.01f)
    }

    @Test
    fun columnIsAtMost400Dp() {
        assertEquals(400f, leftColumnWidthDp(800), 0.01f)
        assertEquals(400f, leftColumnWidthDp(1280), 0.01f)
        assertEquals(0f, leftColumnWidthDp(-5), 0.01f)
    }

    @Test
    fun infoCardsMoveRightOnlyInLandscapeWithAPlan() {
        assertTrue(infoCardsAtRight(landscape = true, planning = true))
        assertFalse(infoCardsAtRight(landscape = true, planning = false))
        assertFalse(infoCardsAtRight(landscape = false, planning = true))
        assertFalse(infoCardsAtRight(landscape = false, planning = false))
    }

    @Test
    fun infoColumnUsesTheLeftColumnsCap() {
        assertEquals(400f, infoColumnWidthDp(1280, 0f, 0f), 0.01f)
        assertEquals(320f, infoColumnWidthDp(640, 0f, 0f), 0.01f)
    }

    @Test
    fun infoColumnLeavesTheSheetsColumnAndTheBars() {
        // 800 dp: the sheet's column is 400 + a 48 dp cutout; the bar is 24 dp.
        val w = infoColumnWidthDp(800, 48f, 24f)
        assertEquals(328f, w, 0.01f)
        assertTrue(48f + 400f + w + 24f <= 800f)
        assertEquals(0f, infoColumnWidthDp(300, 400f, 400f), 0f)
    }

    @Test
    fun rightCardsCoverFromTheirTop() {
        assertEquals(300, rightCardCover(1000, 700))
        assertEquals(0, rightCardCover(1000, Int.MAX_VALUE))
        assertEquals(0, rightCardCover(1000, 1000))
    }

    @Test
    fun expandedSheetInLandscapeFillsUpToTheStatusBar() {
        assertEquals(1000, sheetMaxHeightPx(landscape = true, expanded = true, mapHeight = 1080, insetTop = 80))
        assertEquals(1080, sheetMaxHeightPx(landscape = true, expanded = true, mapHeight = 1080, insetTop = 0))
        assertEquals(0, sheetMaxHeightPx(landscape = true, expanded = true, mapHeight = 50, insetTop = 80))
    }

    @Test
    fun restingSheetWrapsContentUpToTheRoomAndUprightPulledUpKeepsTheShare() {
        assertEquals(1000, sheetMaxHeightPx(landscape = true, expanded = false, mapHeight = 1080, insetTop = 80))
        assertEquals(1320, sheetMaxHeightPx(landscape = false, expanded = true, mapHeight = 2400, insetTop = 100))
        assertEquals(2300, sheetMaxHeightPx(landscape = false, expanded = false, mapHeight = 2400, insetTop = 100))
        assertEquals(0, sheetMaxHeightPx(landscape = false, expanded = false, mapHeight = -5, insetTop = 0))
    }

    private val none = Int.MAX_VALUE

    @Test
    fun buttonsSitAboveTheSheetUpright() {
        assertEquals(400, controlsBottomPx(false, 48, 2000, sheetTop = 1600, cardsTop = none, rightCardsTop = none))
    }

    @Test
    fun buttonsSitAboveTheCardsAboveTheSheetUpright() {
        assertEquals(700, controlsBottomPx(false, 48, 2000, sheetTop = 1600, cardsTop = 1300, rightCardsTop = none))
    }

    @Test
    fun controlsRiseAboveAnInfoCardUprightWithoutAPlan() {
        // The logo, scale and buttons share the rule: above the card, measured.
        assertEquals(500, controlsBottomPx(false, 48, 2000, sheetTop = none, cardsTop = 1500, rightCardsTop = none))
        assertEquals(48, controlsBottomPx(false, 48, 2000, sheetTop = none, cardsTop = none, rightCardsTop = none))
    }

    @Test
    fun buttonsDropBackWhenTheCardsClose() {
        assertEquals(400, controlsBottomPx(false, 48, 2000, sheetTop = 1600, cardsTop = none, rightCardsTop = none))
        assertEquals(48, controlsBottomPx(false, 48, 2000, sheetTop = none, cardsTop = none, rightCardsTop = none))
    }

    @Test
    fun buttonsInLandscapeSitAboveTheRightCards() {
        assertEquals(500, controlsBottomPx(true, 0, 1000, sheetTop = 600, cardsTop = 300, rightCardsTop = 500))
    }

    @Test
    fun buttonsInLandscapeIgnoreTheLeftColumn() {
        assertEquals(24, controlsBottomPx(true, 24, 1000, sheetTop = 600, cardsTop = 300, rightCardsTop = none))
    }

    @Test
    fun landscapeControlsAndRoutesClearTheColumn() {
        assertEquals(408, leftClearance(landscape = true, insetLeft = 0, columnRight = 408))
        assertEquals(408, leftClearance(landscape = true, insetLeft = 48, columnRight = 408))
    }

    @Test
    fun landscapeWithNoColumnKeepsTheInset() {
        assertEquals(48, leftClearance(landscape = true, insetLeft = 48, columnRight = 0))
    }

    @Test
    fun portraitIgnoresTheColumn() {
        assertEquals(0, leftClearance(landscape = false, insetLeft = 0, columnRight = 1080))
    }

    @Test
    fun openingAnInfoCardClosesTheOthers() {
        InfoCard.entries.forEach { opening ->
            val others = InfoCard.entries.toSet() - opening
            assertEquals(others, infoCardsToClose(opening))
            assertFalse(opening in infoCardsToClose(opening))
        }
    }

    @Test
    fun aPlanStartingClosesTheInfoCardsButASavedRoutes() {
        // A favourite, a favourite section's, a road's and a ride's card alike.
        val closed = infoCardsToCloseOnPlanStart(planOpen = false)
        assertEquals(InfoCard.entries.toSet() - InfoCard.SAVED_ROUTE, closed)
        assertFalse(InfoCard.SAVED_ROUTE in closed)
    }

    @Test
    fun aPlanAlreadyOpenLeavesTheInfoCardsAlone() {
        assertEquals(emptySet<InfoCard>(), infoCardsToCloseOnPlanStart(planOpen = true))
    }

    @Test
    fun theNewestInfoCardReplacesAnyOpenOne() {
        InfoCard.entries.forEach { open ->
            InfoCard.entries.forEach { opening ->
                assertEquals(setOf(opening), infoCardsAfterOpening(setOf(open), opening))
            }
        }
        assertEquals(setOf(InfoCard.ROAD), infoCardsAfterOpening(emptySet(), InfoCard.ROAD))
        assertEquals(setOf(InfoCard.RIDE), infoCardsAfterOpening(InfoCard.entries.toSet(), InfoCard.RIDE))
    }

    @Test
    fun reopeningTheSameCardKeepsIt() {
        assertEquals(setOf(InfoCard.SECTION), infoCardsAfterOpening(setOf(InfoCard.SECTION), InfoCard.SECTION))
    }
}
