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

    @Test
    fun landscapePinsOnlyTheTitleWhenPulledUp() {
        assertEquals(SheetPinned.TITLE, sheetPinned(true, true, headerPx = 100, roomPx = 1000f))
        // Even a tall header scrolls: the settings get the whole height.
        assertEquals(SheetPinned.TITLE, sheetPinned(true, true, headerPx = 900, roomPx = 1000f))
    }

    @Test
    fun uprightPinsTheHeaderOnlyWhenItFitsHalfTheRoom() {
        assertEquals(SheetPinned.HEADER, sheetPinned(false, true, headerPx = 500, roomPx = 1000f))
        assertEquals(SheetPinned.NONE, sheetPinned(false, true, headerPx = 501, roomPx = 1000f))
    }

    @Test
    fun nothingIsPinnedAtRest() {
        assertEquals(SheetPinned.NONE, sheetPinned(true, false, 100, 1000f))
        assertEquals(SheetPinned.NONE, sheetPinned(false, false, 100, 1000f))
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
    fun showingARouteOrRideClearsTheMapOfTheOtherCards() {
        listOf(InfoCard.RIDE, InfoCard.SAVED_ROUTE, InfoCard.SECTION).forEach { opening ->
            assertEquals(InfoCard.entries.toSet() - opening, infoCardsToClose(opening))
        }
    }

    @Test
    fun tappingNeverClosesAShownRouteOrRide() {
        listOf(InfoCard.ROAD, InfoCard.FAVOURITE).forEach { opening ->
            assertTrue(infoCardsToClose(opening).none { it in SHOWN_CARDS })
            assertFalse(opening in infoCardsToClose(opening))
            // A saved route's card and a tapped one stack.
            assertEquals(
                setOf(InfoCard.SAVED_ROUTE, opening),
                infoCardsAfterOpening(setOf(InfoCard.SAVED_ROUTE), opening),
            )
            assertEquals(setOf(InfoCard.RIDE, opening), infoCardsAfterOpening(setOf(InfoCard.RIDE), opening))
        }
    }

    @Test
    fun aTappedCardReplacesTheOtherTappedOne() {
        TAPPED_CARDS.forEach { open ->
            listOf(InfoCard.ROAD, InfoCard.FAVOURITE).forEach { opening ->
                assertEquals(
                    setOf(InfoCard.RIDE, opening),
                    infoCardsAfterOpening(setOf(InfoCard.RIDE, open), opening),
                )
            }
        }
        assertEquals(
            setOf(InfoCard.FAVOURITE, InfoCard.SAVED_ROUTE),
            infoCardsAfterOpening(setOf(InfoCard.ROAD, InfoCard.SAVED_ROUTE), InfoCard.FAVOURITE),
        )
    }

    @Test
    fun theGroupsAreSeparate() {
        assertEquals(emptySet<InfoCard>(), TAPPED_CARDS.intersect(SHOWN_CARDS))
        assertEquals(InfoCard.entries.toSet(), TAPPED_CARDS + SHOWN_CARDS)
    }

    @Test
    fun aPlanStartingClosesAllTheInfoCards() {
        // A favourite, a favourite section's, a road's, a ride's and a saved route's alike.
        val closed = infoCardsToCloseOnPlanStart(planOpen = false)
        assertEquals(InfoCard.entries.toSet(), closed)
        assertTrue(InfoCard.SAVED_ROUTE in closed)
    }

    @Test
    fun aPlanAlreadyOpenLeavesTheInfoCardsAlone() {
        assertEquals(emptySet<InfoCard>(), infoCardsToCloseOnPlanStart(planOpen = true))
    }

    @Test
    fun aShownRouteOrRideReplacesAnyOpenCard() {
        InfoCard.entries.forEach { open ->
            listOf(InfoCard.RIDE, InfoCard.SAVED_ROUTE).forEach { opening ->
                assertEquals(setOf(opening), infoCardsAfterOpening(setOf(open), opening))
            }
        }
        assertEquals(setOf(InfoCard.ROAD), infoCardsAfterOpening(emptySet(), InfoCard.ROAD))
        assertEquals(setOf(InfoCard.RIDE), infoCardsAfterOpening(InfoCard.entries.toSet(), InfoCard.RIDE))
    }

    @Test
    fun theCardStackIsCappedToTheRoomBetweenTheLimits() {
        assertEquals(2400 - 100 - 200 - 32, cardStackMaxHeightPx(2400, 100, 200, 16))
        assertEquals(0, cardStackMaxHeightPx(300, 200, 200, 16))
        assertEquals(1000 - 32, cardStackMaxHeightPx(1000, -5, -5, 16))
    }

    @Test
    fun reopeningTheSameCardKeepsIt() {
        assertEquals(setOf(InfoCard.SECTION), infoCardsAfterOpening(setOf(InfoCard.SECTION), InfoCard.SECTION))
    }

    private fun panels(landscape: Boolean, rightCardsTop: Int = Int.MAX_VALUE, sheetTop: Int = Int.MAX_VALUE, buttonsTop: Int = Int.MAX_VALUE) =
        fitPanels(
            landscape, 1000, 2000, insetLeft = 10, insetTop = 80, insetRight = 20, insetBottom = 50,
            topPanelBottom = 0, columnRight = if (landscape) 400 else 0, buttonsTop = buttonsTop,
            sheetTop = sheetTop, cardsTop = Int.MAX_VALUE, rightCardsTop = rightCardsTop,
        )

    @Test
    fun portraitFitKeepsClearOfTheSheetAndButtons() {
        val none = panels(false)
        assertEquals(Panels(1000, 2000, left = 10, top = 80, right = 20, bottom = 50), none)
        val sheet = panels(false, sheetTop = 1500, buttonsTop = 1350)
        assertEquals(650, sheet.bottom)
        assertEquals(0 + 10, sheet.left)
    }

    @Test
    fun landscapeFitKeepsRightOfTheColumnAndAboveTheRightCard() {
        val none = panels(true)
        assertEquals(400, none.left)
        assertEquals(50, none.bottom)
        val card = panels(true, rightCardsTop = 1400, buttonsTop = 1300)
        assertEquals(700, card.bottom)
        assertEquals(80, card.top)
        assertEquals(400, card.left)
    }

    @Test
    fun rideColumnIsHalfTheWindowRightOfTheInset() {
        assertEquals(400f, rideColumnWidthDp(800, 0f, 0f), 0.01f)
        assertEquals(400f, rideColumnWidthDp(915, 0f, 0f), 0.01f)
        assertEquals(352f, rideColumnWidthDp(800, 48f, 0f), 0.01f)
        assertEquals(400f, rideColumnWidthDp(800, 0f, 48f), 0.01f)
        assertEquals(320f, rideColumnWidthDp(640, 0f, 0f), 0.01f)
    }

    @Test
    fun rideColumnNeverPassesTheMiddle() {
        for (width in 640..1400) {
            for (left in listOf(0f, 24f, 48f)) {
                val cardRight = left + rideColumnWidthDp(width, left, 0f) - 8f
                assertTrue("$width/$left", cardRight <= width / 2f)
            }
        }
    }

    @Test
    fun rideColumnHasAFloorAndFitsTheRoom() {
        assertEquals(280f, rideColumnWidthDp(640, 48f, 0f), 0.01f)
        assertEquals(280f, rideColumnWidthDp(412, 0f, 0f), 0.01f)
        assertEquals(250f, rideColumnWidthDp(250, 0f, 0f), 0.01f)
        assertEquals(0f, rideColumnWidthDp(-5, 0f, 0f), 0.01f)
    }

    @Test
    fun rideCardStopsAboveTheTagButton() {
        assertEquals(160, cardStackMaxHeightPx(360, 24, rideCardBottomLimitPx(360, 24, 40, 200, Int.MAX_VALUE), 8))
        assertEquals(160, rideCardBottomLimitPx(360, 24, 40, 200, Int.MAX_VALUE))
    }

    @Test
    fun rideCardStopsAboveTheLogoBandWithoutTheTagButton() {
        assertEquals(64, rideCardBottomLimitPx(360, 24, 40, Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(64, rideCardBottomLimitPx(360, -10, 64, Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun rideCardStopsAboveCardsAtTheBottomLeft() {
        assertEquals(210, rideCardBottomLimitPx(360, 24, 40, 200, 150))
    }

    @Test
    fun compassGoesUnderTheRideCardOnlyUpright() {
        assertTrue(compassUnderRideCard(rideMode = true, landscape = false))
        assertFalse(compassUnderRideCard(rideMode = true, landscape = true))
        assertFalse(compassUnderRideCard(rideMode = false, landscape = false))
        assertFalse(compassUnderRideCard(rideMode = false, landscape = true))
    }
}
