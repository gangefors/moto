// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gangefors.moto.core.MotoException

class MapLogicTest {
    @Test
    fun firstLongPressSetsTheStart() {
        val picker = RoutePicker<String>()
        assertEquals(RoutePicker.Step.StartSet("a"), picker.onLongPress("a"))
        assertEquals("a", picker.start)
    }

    @Test
    fun secondLongPressCompletesTheRouteAndThirdStartsOver() {
        val picker = RoutePicker<String>()
        picker.onLongPress("a")
        assertEquals(RoutePicker.Step.Complete("a", "b"), picker.onLongPress("b"))
        assertNull(picker.start)
        assertEquals(RoutePicker.Step.StartSet("c"), picker.onLongPress("c"))
    }

    @Test
    fun resetForgetsTheStart() {
        val picker = RoutePicker<String>()
        picker.onLongPress("a")
        picker.reset()
        assertNull(picker.start)
        assertEquals(RoutePicker.Step.StartSet("b"), picker.onLongPress("b"))
    }

    @Test
    fun classifiesCoreErrors() {
        assertEquals(CoreProblem.OUTSIDE_REGION, classify(MotoException.OutsideRegion("x")))
        assertEquals(CoreProblem.NO_ROAD_NEARBY, classify(MotoException.NoRoadNearby("x")))
        assertEquals(CoreProblem.NO_ROUTE, classify(MotoException.NoRoute("x")))
        assertEquals(CoreProblem.OTHER, classify(MotoException.Region("x")))
        assertEquals(CoreProblem.OTHER, classify(IllegalStateException("x")))
    }

    @Test
    fun summarizesRoutes() {
        assertEquals(RouteSummary(12.3, 15), summarize(12_345.0, 900.0))
        assertEquals(RouteSummary(0.0, 0), summarize(0.0, 0.0))
        assertEquals(RouteSummary(1.0, 1), summarize(960.0, 89.0))
    }

    @Test
    fun installsWhenMissingOrFromAnotherBuild() {
        assertTrue(needsInstall(installedExists = false, installedStamp = "1", currentStamp = "1"))
        assertTrue(needsInstall(installedExists = true, installedStamp = null, currentStamp = "1"))
        assertTrue(needsInstall(installedExists = true, installedStamp = "1", currentStamp = "2"))
        assertFalse(needsInstall(installedExists = true, installedStamp = "2", currentStamp = "2"))
    }
}
