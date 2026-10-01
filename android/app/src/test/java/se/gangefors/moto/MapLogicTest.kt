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
    fun laterLongPressesMoveTheEnd() {
        val picker = RoutePicker<String>()
        picker.onLongPress("a")
        assertEquals(RoutePicker.Step.Complete("a", "b"), picker.onLongPress("b"))
        assertEquals("a", picker.start)
        assertEquals(RoutePicker.Step.Complete("a", "c"), picker.onLongPress("c"))
        assertEquals(RoutePicker.Step.Complete("a", "d"), picker.onLongPress("d"))
        // Closing the route starts over.
        picker.reset()
        assertEquals(RoutePicker.Step.StartSet("e"), picker.onLongPress("e"))
    }

    @Test
    fun theSwitcherForgetsTheOldCountWhileFinding() {
        assertEquals(1 to 3, choiceCount(found = true, failed = false, position = 1, count = 3))
        assertEquals(-1 to 0, choiceCount(found = false, failed = true, position = 1, count = 3))
        // A new set on its way: the old count no longer applies.
        assertNull(choiceCount(found = false, failed = false, position = 1, count = 3))
    }

    @Test
    fun theSwitcherRowStaysWhileANewSetIsFound() {
        // Routes: a row for several choices, kept while finding where the
        // set before had one, none for a single route or a new sheet.
        assertTrue(showsSwitcher(0 to 4, failed = false, kept = 0, shuffle = false))
        assertFalse(showsSwitcher(0 to 1, failed = false, kept = 4, shuffle = false))
        assertTrue(showsSwitcher(null, failed = false, kept = 4, shuffle = false))
        assertFalse(showsSwitcher(null, failed = false, kept = 1, shuffle = false))
        assertFalse(showsSwitcher(null, failed = false, kept = 0, shuffle = false))
        assertTrue(showsSwitcher(-1 to 0, failed = true, kept = 0, shuffle = false))
        // Loops (Shuffle): always.
        assertTrue(showsSwitcher(null, failed = false, kept = 0, shuffle = true))
    }

    @Test
    fun keptChoicesFollowTheLastSetShown() {
        assertEquals(4, keptChoices(kept = 0, count = 4))
        // A search right after another: the set before both still counts.
        assertEquals(4, keptChoices(kept = 4, count = 0))
        assertEquals(2, keptChoices(kept = 4, count = 2))
        assertEquals(0, keptChoices(kept = 0, count = 0))
    }

    @Test
    fun aPulledUpSheetKeepsItsTopUnlessItTakesHalf() {
        assertTrue(fixesTop(headerPx = 0, roomPx = 1000f))
        assertTrue(fixesTop(headerPx = 400, roomPx = 1000f))
        assertTrue(fixesTop(headerPx = 500, roomPx = 1000f))
        // Very large fonts: the whole sheet scrolls instead.
        assertFalse(fixesTop(headerPx = 501, roomPx = 1000f))
    }

    @Test
    fun aFailedSearchGoesBackStaysOrCloses() {
        // The end moved where no route reaches: back to the last routes.
        assertEquals(FailedSearch.GO_BACK, failedSearch("a" to "b", "a" to "c"))
        // A new route has nothing to go back to: the sheet closes.
        assertEquals(FailedSearch.CLOSE, failedSearch(null, "a" to "c"))
        // The same ends failing (a setting changed): the sheet stays and
        // says why; no going back, so no loop.
        assertEquals(FailedSearch.STAY, failedSearch("a" to "b", "a" to "b"))
    }

    @Test
    fun theSummaryRowShowsOnlyWhatDiffersFromTheUsual() {
        val usual = loopSummaryItems(LoopChoice.Minutes(120), LoopDirection.ANY, se.gangefors.moto.core.Gravel.AVOID, se.gangefors.moto.core.FavouritesMode.PREFER)
        assertEquals(listOf<SummaryItem>(SummaryItem.Length(LoopChoice.Minutes(120))), usual)
        val loop = loopSummaryItems(LoopChoice.Km(100), LoopDirection.EAST, se.gangefors.moto.core.Gravel.PREFER, se.gangefors.moto.core.FavouritesMode.AVOID)
        assertEquals(
            listOf(
                SummaryItem.Length(LoopChoice.Km(100)),
                SummaryItem.Heading(LoopDirection.EAST),
                SummaryItem.GravelRoads(se.gangefors.moto.core.Gravel.PREFER),
                SummaryItem.FavouritesAvoided,
            ),
            loop,
        )
        assertEquals(emptyList<SummaryItem>(), routeSummaryItems(null, 0, se.gangefors.moto.core.Gravel.AVOID, se.gangefors.moto.core.FavouritesMode.PREFER))
        val at = SummaryItem.ArrivesAt(Arrival(1_000, late = true), by = 900)
        assertEquals(
            listOf(at, SummaryItem.Waypoints(2), SummaryItem.GravelRoads(se.gangefors.moto.core.Gravel.ALLOW)),
            routeSummaryItems(at, 2, se.gangefors.moto.core.Gravel.ALLOW, se.gangefors.moto.core.FavouritesMode.PREFER),
        )
    }

    @Test
    fun theMapDropsItsCachesOnlyInTheBackground() {
        // ComponentCallbacks2 levels: UI hidden 20, background 40, complete 80.
        assertFalse(dropsMapCaches(20))
        assertTrue(dropsMapCaches(40))
        assertTrue(dropsMapCaches(80))
        assertFalse(dropsMapCaches(15))
    }

    @Test
    fun aStartGivenByTheAppActsLikeALongPress() {
        // "Route here from my position": the long-pressed point becomes the
        // end, the rider's position the start, and further long-presses
        // move the end.
        val picker = RoutePicker<String>()
        picker.onLongPress("goal")
        picker.reset()
        picker.startAt("me")
        assertEquals("me", picker.start)
        assertEquals(RoutePicker.Step.Complete("me", "other"), picker.onLongPress("other"))
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
    fun oneViaPointIsRemoved() {
        assertEquals(listOf("a", "c"), removeVia(listOf("a", "b", "c"), 1))
        assertEquals(listOf("b"), removeVia(listOf("a", "b"), 0))
        assertEquals(emptyList<String>(), removeVia(listOf("a"), 0))
        assertEquals(listOf("a", "b"), removeVia(listOf("a", "b"), 5))
        assertEquals(listOf("a", "b"), removeVia(listOf("a", "b"), -1))
    }

    @Test
    fun theLastOfSeveralChoicesIsTheFastest() {
        assertEquals(3, fastestChoice(4))
        assertEquals(1, fastestChoice(2))
        assertNull(fastestChoice(1))
        assertNull(fastestChoice(0))
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
    fun theFiguresLineAlwaysHasFavouritesAndCurvy() {
        assertEquals(
            listOf(RouteStat(RouteStatKind.FAVOURITES, zero = true), RouteStat(RouteStatKind.CURVY, zero = true)),
            routeStats(RouteSummary(12.3, 15)),
        )
    }

    @Test
    fun theFiguresLineShowsWhatTheRouteHas() {
        val all = RouteSummary(64.2, 58, favouritePercent = 2, extraMinutes = 32, curvyPercent = 31, gravelKm = 4.2, tollKm = 3.1)
        assertEquals(
            listOf(
                RouteStat(RouteStatKind.EXTRA),
                RouteStat(RouteStatKind.FAVOURITES),
                RouteStat(RouteStatKind.CURVY),
                RouteStat(RouteStatKind.GRAVEL),
                RouteStat(RouteStatKind.TOLL),
            ),
            routeStats(all),
        )
        // The fastest says so instead of its minutes over itself.
        assertEquals(RouteStat(RouteStatKind.FASTEST), routeStats(all.copy(fastest = true)).first())
        assertEquals(RouteStat(RouteStatKind.FAVOURITES), routeStats(all.copy(extraMinutes = 0)).first())
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
        // Km on toll roads the same way.
        assertEquals(12.6, summarize(40_000.0, 1800.0, tollM = 12_560.0).tollKm, 1e-9)
        assertEquals(0.0, summarize(40_000.0, 1800.0).tollKm, 1e-9)
        assertEquals(1.0, summarize(1_000.0, 90.0, tollM = 5_000.0).tollKm, 1e-9)
        assertEquals(0.0, summarize(1_000.0, 90.0, tollM = Double.NaN).tollKm, 1e-9)
    }

    @Test
    fun routesGetTheFixedExtraTime() {
        assertEquals(80, ROUTE_EXTRA_PERCENT)
        val base = se.gangefors.moto.core.RouteOptions(
            se.gangefors.moto.core.Avoid(motorways = true, ferries = false),
            se.gangefors.moto.core.TimeBudget.Extra(0.4),
            1.0,
            true,
            se.gangefors.moto.core.Gravel.AVOID,
            se.gangefors.moto.core.FavouritesMode.AVOID,
            se.gangefors.moto.core.UnriddenMode.ANY,
        )
        val o = routeOptions(base, 20)
        assertEquals(se.gangefors.moto.core.TimeBudget.Extra(0.2), o.budget)
        // Roads to avoid: all of them unless the rider allows some.
        assertEquals(AVOID_ALL, o.avoid)
        val ferries = se.gangefors.moto.core.Avoid(motorways = true, ferries = false, tolls = true)
        assertEquals(ferries, routeOptions(base, 20, avoid = ferries).avoid)
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
        assertTrue(gravel.avoid.motorways && gravel.avoid.ferries && gravel.avoid.tolls)
        val arrive = arriveByOptions(base, 0L, 3600L, se.gangefors.moto.core.Gravel.AVOID, ferries)
        assertEquals(ferries, arrive.avoid)
        // Favourites: preferred unless this route avoids them.
        val prefer = se.gangefors.moto.core.FavouritesMode.PREFER
        val avoidFavs = se.gangefors.moto.core.FavouritesMode.AVOID
        assertEquals(listOf(prefer, avoidFavs), FAVOURITES_CHOICES)
        assertEquals(prefer, routeOptions(base, 40).favourites)
        assertEquals(avoidFavs, routeOptions(base, 40, favourites = avoidFavs).favourites)
        assertEquals(prefer, arrive.favourites)
        assertEquals(
            avoidFavs,
            arriveByOptions(base, 0L, 3600L, se.gangefors.moto.core.Gravel.AVOID, favourites = avoidFavs).favourites,
        )
    }

    @Test
    fun favouritesChoicesAreStoredByName() {
        val prefer = se.gangefors.moto.core.FavouritesMode.PREFER
        val avoid = se.gangefors.moto.core.FavouritesMode.AVOID
        for (f in FAVOURITES_CHOICES) assertEquals(f, favouritesOf(favouritesKey(f)))
        assertEquals("avoid", favouritesKey(avoid))
        // Nothing or anything else stored: preferred.
        assertEquals(prefer, favouritesOf(null))
        assertEquals(prefer, favouritesOf("AVOID"))
        assertEquals(prefer, favouritesOf("../x"))
    }

    @Test
    fun theUnriddenShareShowsFromOnePercent() {
        val base = RouteSummary(64.2, 58, favouritePercent = 2, curvyPercent = 31, gravelKm = 4.2)
        // Under 1 % (all ridden), and on a summary without it: not shown.
        assertFalse(routeStats(base).any { it.kind == RouteStatKind.UNRIDDEN })
        assertFalse(routeStats(base.copy(unriddenPercent = 0)).any { it.kind == RouteStatKind.UNRIDDEN })
        // From 1 %: after curvy, before gravel.
        assertEquals(
            listOf(RouteStatKind.FAVOURITES, RouteStatKind.CURVY, RouteStatKind.UNRIDDEN, RouteStatKind.GRAVEL),
            routeStats(base.copy(unriddenPercent = 1)).map { it.kind },
        )
        // All of it (also before the first ride): shown, with its value.
        assertTrue(routeStats(base.copy(unriddenPercent = 100)).any { it.kind == RouteStatKind.UNRIDDEN })
        assertEquals(64, summarize(1_000.0, 60.0, unriddenShare = 0.6449).unriddenPercent)
        assertEquals(0, summarize(1_000.0, 60.0, unriddenShare = 0.004).unriddenPercent)
        assertEquals(100, summarize(1_000.0, 60.0, unriddenShare = 1.0).unriddenPercent)
        assertEquals(0, summarize(1_000.0, 60.0, unriddenShare = Double.NaN).unriddenPercent)
        // Not given: not shown.
        assertEquals(0, summarize(1_000.0, 60.0).unriddenPercent)
    }

    @Test
    fun theRiddenRoadsButtonShowsWhilePlanningNearEnough() {
        val min = 7.8f
        assertTrue(riddenButtonShown(planning = true, sheetExpanded = false, hasRidden = true, zoom = 10f, minZoom = min))
        assertTrue(riddenButtonShown(true, false, true, min, min))
        // Not without a route or loop, with the sheet pulled up, before
        // any ride has been on this map's roads, or zoomed too far out
        // for the layer.
        assertFalse(riddenButtonShown(false, false, true, 10f, min))
        assertFalse(riddenButtonShown(true, true, true, 10f, min))
        assertFalse(riddenButtonShown(true, false, false, 10f, min))
        assertFalse(riddenButtonShown(true, false, true, 7f, min))
    }

    @Test
    fun unriddenRoadsArePreferredOnlyWhenAsked() {
        val any = se.gangefors.moto.core.UnriddenMode.ANY
        val prefer = se.gangefors.moto.core.UnriddenMode.PREFER
        assertEquals(listOf(any, prefer), UNRIDDEN_CHOICES)
        for (u in UNRIDDEN_CHOICES) assertEquals(u, unriddenOf(unriddenKey(u)))
        assertEquals("prefer", unriddenKey(prefer))
        // Nothing or anything else stored: any.
        assertEquals(any, unriddenOf(null))
        assertEquals(any, unriddenOf("PREFER"))
        assertEquals(any, unriddenOf("../x"))
        val base = se.gangefors.moto.core.RouteOptions(
            se.gangefors.moto.core.Avoid(motorways = true, ferries = false),
            se.gangefors.moto.core.TimeBudget.Extra(0.4),
            1.0,
            true,
            se.gangefors.moto.core.Gravel.AVOID,
            se.gangefors.moto.core.FavouritesMode.PREFER,
            any,
        )
        assertEquals(any, routeOptions(base, 40).unridden)
        assertEquals(prefer, routeOptions(base, 40, unridden = prefer).unridden)
        assertEquals(prefer, arriveByOptions(base, 0L, 3600L, se.gangefors.moto.core.Gravel.AVOID, unridden = prefer).unridden)
        // The chip shows only while unridden roads are preferred.
        val gravel = se.gangefors.moto.core.Gravel.AVOID
        val favs = se.gangefors.moto.core.FavouritesMode.PREFER
        assertEquals(emptyList<SummaryItem>(), routeSummaryItems(null, 0, gravel, favs, any))
        assertEquals(listOf<SummaryItem>(SummaryItem.UnriddenPreferred), routeSummaryItems(null, 0, gravel, favs, prefer))
        assertEquals(
            listOf(SummaryItem.Length(LoopChoice.Km(100)), SummaryItem.UnriddenPreferred),
            loopSummaryItems(LoopChoice.Km(100), LoopDirection.ANY, gravel, favs, prefer),
        )
    }

    @Test
    fun roadsToAvoidAreStoredAsTheAllowedKinds() {
        // Nothing stored: everything avoided (the default).
        assertEquals(AVOID_ALL, avoidOf(null))
        assertEquals(AVOID_ALL, avoidOf(""))
        assertEquals("", avoidKey(AVOID_ALL))
        assertEquals(emptyList<AvoidKind>(), allowedKinds(AVOID_ALL))
        // Every combination survives a round trip.
        for (bits in 0 until 8) {
            val a = se.gangefors.moto.core.Avoid(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0)
            assertEquals(a, avoidOf(avoidKey(a)))
        }
        val ferriesAndTolls = withAvoided(withAvoided(AVOID_ALL, AvoidKind.FERRIES, false), AvoidKind.TOLLS, false)
        assertEquals("ferries,tolls", avoidKey(ferriesAndTolls))
        assertEquals(listOf(AvoidKind.FERRIES, AvoidKind.TOLLS), allowedKinds(ferriesAndTolls))
        assertTrue(avoids(ferriesAndTolls, AvoidKind.MOTORWAYS))
        assertFalse(avoids(ferriesAndTolls, AvoidKind.FERRIES))
        // Allow chips: all off with nothing stored (first install); a chip
        // is on exactly while its kind is allowed, and tapping flips only it.
        AvoidKind.entries.forEach { assertFalse(allows(avoidOf(null), it)) }
        assertTrue(allows(ferriesAndTolls, AvoidKind.FERRIES))
        assertFalse(allows(ferriesAndTolls, AvoidKind.MOTORWAYS))
        val motorwaysOn = withAllowed(AVOID_ALL, AvoidKind.MOTORWAYS, true)
        assertEquals(listOf(AvoidKind.MOTORWAYS), allowedKinds(motorwaysOn))
        assertEquals("motorways", avoidKey(motorwaysOn))
        assertEquals(AVOID_ALL, withAllowed(motorwaysOn, AvoidKind.MOTORWAYS, false))
        // Read back as untrusted: unknown keys ignored, spaces trimmed.
        assertEquals(
            withAvoided(AVOID_ALL, AvoidKind.MOTORWAYS, false),
            avoidOf("x, motorways ,,<script>,MOTORWAYS\u0000"),
        )
    }

    @Test
    fun namesRouteExports() {
        val stockholm = java.time.ZoneId.of("Europe/Stockholm")
        // 2026-09-24 16:30 UTC is 18:30 in Stockholm.
        val at = 1_790_267_400L
        assertEquals("moto-route-2026-09-24-1830.gpx", routeFileName(at, stockholm))
        assertEquals("moto 2026-09-24 18:30, 57.4 km", routeGpxName(at, stockholm, 57.44))
        assertTrue(isRouteFileName(routeFileName(at, stockholm)))
        // A ride shared the same way.
        assertTrue(isRouteFileName(rideFileName(at, stockholm)))
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

    @Test
    fun savedRoutesGetADatedDefaultName() {
        val utc = java.time.ZoneOffset.UTC
        assertEquals("Route 2026-09-24 18:30, 57.4 km", defaultRouteName(1_790_274_600, utc, 57.43, isLoop = false))
        assertEquals("Loop 2026-09-24 18:30, 120.0 km", defaultRouteName(1_790_274_600, utc, 120.0, isLoop = true))
    }

    @Test
    fun typedRouteNamesAreCleaned() {
        assertEquals("Kullaberg", cleanRouteName("  Kullaberg \n"))
        assertEquals("a b", cleanRouteName("a\nb"))
        assertEquals("ab", cleanRouteName("a\u0000b\u007f"))
        assertEquals(null, cleanRouteName(" \t\n "))
        assertEquals(null, cleanRouteName(""))
        assertEquals(MAX_ROUTE_NAME_CHARS, cleanRouteName("é".repeat(500))!!.length)
        // Characters outside the BMP count once and are never cut in half.
        val emoji = "\uD83C\uDFCD" // motorcycle
        val long = cleanRouteName(emoji.repeat(300))!!
        assertEquals(MAX_ROUTE_NAME_CHARS, long.codePointCount(0, long.length))
        assertEquals(MAX_ROUTE_NAME_CHARS * 2, long.length)
    }

    @Test
    fun viaPointsGoWhereTheyAddTheLeast() {
        fun p(lon: Double) = se.gangefors.moto.core.LatLon(55.7, lon)
        val (start, end) = p(13.0) to p(14.0)
        // The first via point goes between start and end.
        assertEquals(listOf(p(13.5)), insertVia(start, emptyList(), end, p(13.5)))
        // Later ones fall into the leg they belong to, whatever the order
        // they were added in.
        val two = insertVia(start, listOf(p(13.5)), end, p(13.2))
        assertEquals(listOf(p(13.2), p(13.5)), two)
        assertEquals(listOf(p(13.2), p(13.5), p(13.8)), insertVia(start, two, end, p(13.8)))
        // Beyond the end: before the end, as the last via point.
        assertEquals(listOf(p(13.5), p(14.2)), insertVia(start, listOf(p(13.5)), end, p(14.2)))
    }

    @Test
    fun arriveByTurnsTheTimeLeftIntoATotalBudget() {
        val base = se.gangefors.moto.core.RouteOptions(
            se.gangefors.moto.core.Avoid(motorways = true, ferries = false),
            se.gangefors.moto.core.TimeBudget.Extra(0.4),
            1.0,
            true,
            se.gangefors.moto.core.Gravel.AVOID,
            se.gangefors.moto.core.FavouritesMode.AVOID,
            se.gangefors.moto.core.UnriddenMode.ANY,
        )
        val o = arriveByOptions(base, 1_000, 1_000 + 3_600, se.gangefors.moto.core.Gravel.PREFER)
        assertEquals(se.gangefors.moto.core.TimeBudget.Total(3_240.0), o.budget)
        assertEquals(0.0, o.minGain, 0.0)
        assertEquals(se.gangefors.moto.core.Gravel.PREFER, o.gravel)
        assertTrue(o.curvy)
        // Already too late: no time at all, so the fastest route.
        assertEquals(se.gangefors.moto.core.TimeBudget.Total(0.0), arriveByOptions(base, 5_000, 1_000, o.gravel).budget)
    }

    @Test
    fun arrivalTimesAreTheNextOnTheClock() {
        val utc = java.time.ZoneOffset.UTC
        val now = 1_790_274_600L // 2026-09-24 18:30 UTC
        assertEquals(now + 90 * 60, nextTimeOfDay(now, utc, 20, 0))
        assertEquals(now + 24 * 3600 - 30 * 60, nextTimeOfDay(now, utc, 18, 0))
        assertEquals(now + 24 * 3600, nextTimeOfDay(now, utc, 18, 30))
        assertEquals("18:30", clockTime(now, utc))
        assertEquals(Arrival(now + 3_600, late = false), arrival(now, 3_599.6, now + 3_600))
        assertEquals(Arrival(now + 3_601, late = true), arrival(now, 3_601.0, now + 3_600))
    }

    @Test
    fun scaleLabelsSitOnTheirPointsInsideTheScale() {
        // 300 px scale, labels 30 px wide at 0 %, 50 % and 100 %.
        val shown = scaleLabels(listOf(0, 150, 300), listOf(30, 30, 30), total = 300, gap = 8)
        assertEquals(listOf(0 to 0, 1 to 135, 2 to 270), shown)
    }

    @Test
    fun scaleLabelsThatWouldTouchAreLeftOut() {
        // Large fonts: 70 px labels every 50 px (1 h to 7 h); every
        // second one doesn't fit either, every third does: 1, 4 and 7 h.
        val centres = (0..6).map { it * 50 }
        val shown = scaleLabels(centres, List(7) { 70 }, total = 300, gap = 8)
        assertEquals(listOf(0, 3, 6), shown.map { it.first })
        // Narrower labels: every second one, 1, 3, 5 and 7 h.
        assertEquals(listOf(0, 2, 4, 6), scaleLabels(centres, List(7) { 40 }, total = 300, gap = 8).map { it.first })
        shown.zipWithNext().forEach { (a, b) -> assertTrue(b.second >= a.second + 70 + 8) }
        shown.forEach { assertTrue(it.second in 0..230) }
        // A label wider than the whole scale never shows.
        assertEquals(emptyList<Pair<Int, Int>>(), scaleLabels(listOf(10), listOf(400), total = 300, gap = 8))
    }

    @Test
    fun firstFittingPicksTheLargestSizeThatFits() {
        assertEquals(0, firstFitting(listOf(200, 180, 150), 240))
        assertEquals(1, firstFitting(listOf(260, 230, 190), 240))
        assertEquals(2, firstFitting(listOf(300, 260, 240), 240))
        assertNull(firstFitting(listOf(300, 280, 250), 240))
        assertNull(firstFitting(emptyList(), 240))
    }

    @Test
    fun segmentsFitOnlyWhenEveryLabelHasRoom() {
        // Avoid / Allow / Prefer: the widest label decides, all segments
        // are equally wide.
        assertTrue(segmentsFit(widestLabelPx = 60, count = 3, segmentPaddingPx = 28, availablePx = 264))
        assertFalse(segmentsFit(widestLabelPx = 60, count = 3, segmentPaddingPx = 28, availablePx = 263))
        // Largest fonts: chips instead.
        assertFalse(segmentsFit(widestLabelPx = 140, count = 3, segmentPaddingPx = 28, availablePx = 320))
        assertFalse(segmentsFit(widestLabelPx = 10, count = 0, segmentPaddingPx = 28, availablePx = 320))
    }

    @Test
    fun theFastestIsGreyOnlyAmongSeveralAndWhenNotAlsoTheSuggestion() {
        assertTrue(fastestIsDull(count = 3, fastestSuggested = false))
        assertFalse(fastestIsDull(count = 2, fastestSuggested = true))
        // A single route is never grey (nor says it is the fastest).
        assertFalse(fastestIsDull(count = 1, fastestSuggested = false))
        assertFalse(fastestIsDull(count = 1, fastestSuggested = true))
        assertEquals(null, fastestChoice(1))
    }

    @Test
    fun theThemeFollowsThePhoneUntilChosen() {
        assertTrue(themeIsDark(chosen = null, phoneDark = true))
        assertFalse(themeIsDark(chosen = null, phoneDark = false))
        assertTrue(themeIsDark(chosen = true, phoneDark = false))
        assertFalse(themeIsDark(chosen = false, phoneDark = true))
    }
}
