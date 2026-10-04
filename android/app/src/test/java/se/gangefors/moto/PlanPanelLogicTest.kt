// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanPanelLogicTest {
    @Test
    fun landscapeBelow600DpUsesThePanel() {
        assertTrue(usesPlanningPanel(891, 411))
        assertTrue(usesPlanningPanel(640, 360))
    }

    @Test
    fun heightBoundaryIs600() {
        assertTrue(usesPlanningPanel(1000, 599))
        assertFalse(usesPlanningPanel(1000, 600))
        assertFalse(usesPlanningPanel(1280, 800))
    }

    @Test
    fun equalSidesAndPortraitKeepTheSheet() {
        assertFalse(usesPlanningPanel(500, 500))
        assertFalse(usesPlanningPanel(411, 891))
        assertFalse(usesPlanningPanel(360, 500))
    }

    @Test
    fun panelIs40PercentOfTheWidth() {
        assertEquals(356.4f, planningPanelWidthDp(891), 0.01f)
        assertEquals(256f, planningPanelWidthDp(640), 0.01f)
    }

    @Test
    fun panelIsAtMost360Dp() {
        assertEquals(360f, planningPanelWidthDp(900), 0.01f)
        assertEquals(360f, planningPanelWidthDp(1200), 0.01f)
        assertEquals(0f, planningPanelWidthDp(-5), 0.01f)
    }

    @Test
    fun panelStartsAGapPastTheCutout() {
        assertEquals(8 + 356, panelRightEdge(insetLeft = 0, panelWidth = 356, gap = 8))
        assertEquals(48 + 8 + 356, panelRightEdge(insetLeft = 48, panelWidth = 356, gap = 8))
    }

    @Test
    fun routesAreFittedRightOfThePanel() {
        assertEquals(48 + 8 + 356 + 8, panelFitLeft(insetLeft = 48, panelWidth = 356, gap = 8))
    }

    @Test
    fun controlsShiftByTheWidthAndAGap() {
        assertEquals(364, panelControlsShift(panelWidth = 356, gap = 8))
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
