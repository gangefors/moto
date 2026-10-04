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
    fun expandedSheetHidesInfoCardsOnlyInLandscape() {
        assertFalse(showInfoCardsWithPlan(landscape = true, expanded = true))
        assertTrue(showInfoCardsWithPlan(landscape = true, expanded = false))
        assertTrue(showInfoCardsWithPlan(landscape = false, expanded = true))
        assertTrue(showInfoCardsWithPlan(landscape = false, expanded = false))
    }

    @Test
    fun sheetGrowsHigherOnlyWhenExpandedInLandscape() {
        assertEquals(0.85f, sheetMaxShare(landscape = true, expanded = true), 0f)
        assertEquals(0.55f, sheetMaxShare(landscape = true, expanded = false), 0f)
        assertEquals(0.55f, sheetMaxShare(landscape = false, expanded = true), 0f)
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
