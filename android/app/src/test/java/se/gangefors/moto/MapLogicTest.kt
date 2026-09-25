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
    fun aLoopTakesTheStart() {
        val picker = RoutePicker<String>()
        assertNull(picker.takeStart())
        picker.onLongPress("a")
        assertEquals("a", picker.takeStart())
        assertNull(picker.start)
        // The next long-press starts a new pick, not an end.
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
        assertEquals(RouteSummary(12.3, 15, 42), summarize(12_345.0, 900.0, 0.4239))
        assertEquals(0, summarize(1.0, 1.0, 0.004).favouritePercent)
        assertEquals(100, summarize(1.0, 1.0, 1.2).favouritePercent)
        assertEquals(0, summarize(1.0, 1.0, Double.NaN).favouritePercent)
        // Minutes over the fastest route.
        assertEquals(5, summarize(57_400.0, 3000.0, 0.65, 2730.0).extraMinutes)
        assertEquals(0, summarize(1.0, 900.0).extraMinutes)
        assertEquals(0, summarize(1.0, 900.0, 0.0, 950.0).extraMinutes)
        assertEquals(31, summarize(1.0, 900.0, 0.0, 900.0, 0.305).curvyPercent)
        assertEquals(0, summarize(1.0, 900.0, 0.0, 900.0, Double.NaN).curvyPercent)
        // Km on gravel, one decimal, never more than the route.
        assertEquals(4.3, summarize(20_000.0, 900.0, unpavedM = 4_260.0).gravelKm, 1e-9)
        assertEquals(0.0, summarize(20_000.0, 900.0).gravelKm, 1e-9)
        assertEquals(0.0, summarize(20_000.0, 900.0, unpavedM = 40.0).gravelKm, 1e-9)
        assertEquals(1.0, summarize(1_000.0, 90.0, unpavedM = 5_000.0).gravelKm, 1e-9)
        assertEquals(0.0, summarize(1_000.0, 90.0, unpavedM = Double.NaN).gravelKm, 1e-9)
        assertEquals(0.0, summarize(1_000.0, 90.0, unpavedM = -5.0).gravelKm, 1e-9)
    }

    @Test
    fun installsWhenMissingOrFromAnotherBuild() {
        assertTrue(needsInstall(installedExists = false, installedStamp = "1", currentStamp = "1"))
        assertTrue(needsInstall(installedExists = true, installedStamp = null, currentStamp = "1"))
        assertTrue(needsInstall(installedExists = true, installedStamp = "1", currentStamp = "2"))
        assertFalse(needsInstall(installedExists = true, installedStamp = "2", currentStamp = "2"))
    }

    @Test
    fun budgetsComeFromTheChoices() {
        assertEquals(40, budgetPercentOf(null))
        assertEquals(20, budgetPercentOf(20))
        assertEquals(0, budgetPercentOf(0))
        assertEquals(40, budgetPercentOf(35))
        assertEquals(40, budgetPercentOf(-1))
        assertTrue(DEFAULT_BUDGET_PERCENT in BUDGET_CHOICES)
        val base = se.gangefors.moto.core.RouteOptions(
            se.gangefors.moto.core.Avoid(motorways = true, ferries = false),
            se.gangefors.moto.core.TimeBudget.Extra(0.4),
            1.0,
            true,
            se.gangefors.moto.core.Gravel.AVOID,
        )
        val o = routeOptions(base, 20)
        assertEquals(se.gangefors.moto.core.TimeBudget.Extra(0.2), o.budget)
        assertEquals(base.avoid, o.avoid)
        assertEquals(1.0, o.minGain, 0.0)
        assertTrue(o.curvy)
        // Gravel: avoided unless allowed; the other avoid options stay.
        assertEquals(se.gangefors.moto.core.Gravel.AVOID, routeOptions(base, 40).gravel)
        val gravel = routeOptions(base, 40, se.gangefors.moto.core.Gravel.ALLOW)
        assertEquals(se.gangefors.moto.core.Gravel.ALLOW, gravel.gravel)
        assertEquals(
            se.gangefors.moto.core.Gravel.PREFER,
            routeOptions(base, 40, se.gangefors.moto.core.Gravel.PREFER).gravel,
        )
        assertTrue(gravel.avoid.motorways)
        assertFalse(gravel.avoid.ferries)
    }

    @Test
    fun namesRouteExports() {
        val stockholm = java.time.ZoneId.of("Europe/Stockholm")
        // 2026-09-24 16:30 UTC is 18:30 in Stockholm.
        val at = 1_790_267_400L
        assertEquals("moto-route-2026-09-24-1830.gpx", routeFileName(at, stockholm))
        assertEquals("moto 2026-09-24 18:30, 57.4 km", routeGpxName(at, stockholm, 57.44))
        assertTrue(isRouteFileName(routeFileName(at, stockholm)))
        for (other in listOf("moto.db", "moto-route-2026-09-24-1830.gpx.tmp", "../moto-route-2026-09-24-1830.gpx", "x.gpx", "")) {
            assertFalse(other, isRouteFileName(other))
        }
    }

    @Test
    fun gravelChoicesAreStoredByKey() {
        GRAVEL_CHOICES.forEach { assertEquals(it, gravelOf(gravelKey(it))) }
        assertEquals(listOf("avoid", "allow", "prefer"), GRAVEL_CHOICES.map(::gravelKey))
    }

    @Test
    fun unknownGravelFallsBackToTheOldSwitchOrAvoid() {
        val avoid = se.gangefors.moto.core.Gravel.AVOID
        val allow = se.gangefors.moto.core.Gravel.ALLOW
        assertEquals(avoid, gravelOf(null))
        assertEquals(avoid, gravelOf("PREFER"))
        assertEquals(avoid, gravelOf(""))
        assertEquals(allow, gravelOf(null, legacyAllow = true))
        assertEquals(allow, gravelOf("junk", legacyAllow = true))
        // A stored choice wins over the old switch.
        assertEquals(avoid, gravelOf("avoid", legacyAllow = true))
    }
}
