// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import se.gangefors.moto.core.Description
import se.gangefors.moto.core.FavouriteNearby
import se.gangefors.moto.core.Favourites
import se.gangefors.moto.core.RoadInfo
import se.gangefors.moto.core.Route
import se.gangefors.moto.core.RouteOptions
import se.gangefors.moto.core.SavedRoute
import se.gangefors.moto.core.Section
import se.gangefors.moto.core.SectionDraft
import se.gangefors.moto.core.SectionGravel
import se.gangefors.moto.core.Tag
import se.gangefors.moto.core.Track

/**
 * The map screen's remembered state (ADR-0014), made once by [MapScreen]:
 * every value it keeps between compositions, with its initial value.
 */
internal class MapScreenState(
    context: Context,
    val noticeScope: CoroutineScope,
    val scope: CoroutineScope,
    cardExpandedState: MutableState<Boolean>,
) {
    var map by mutableStateOf<MapLibreMap?>(null)
    var style by mutableStateOf<Style?>(null)
    var hasLocation by mutableStateOf(hasLocationPermission(context))

    // What the app is waiting for, shown after a moment (BusyPill).
    val busy = BusyTasks()

    // The step of a task in hand (e.g. "Long-press where you want to
    // go"), shown until the step changes.
    var message by mutableStateOf<String?>(null)
    // Short notices: a snackbar at the top that goes by itself; errors
    // stay longer and can be closed.
    val notices = SnackbarHostState()

    // The road the rider last tapped, shown until closed.
    var roadInfo by mutableStateOf<RoadInfo?>(null)

    // Where the panels over the map end, in pixels, measured as they are
    // laid out: the card at the top, the buttons at the right, the tag
    // button at the bottom. Routes are fitted clear of them.
    var mapSize by mutableStateOf(IntSize.Zero)
    var topPanelBottom by mutableIntStateOf(0)
    var buttonsLeft by mutableIntStateOf(Int.MAX_VALUE)
    var tagTop by mutableIntStateOf(Int.MAX_VALUE)
    // The top of the round buttons above the sheet while planning.
    var planButtonsTop by mutableIntStateOf(Int.MAX_VALUE)
    var sheetTop by mutableIntStateOf(Int.MAX_VALUE)
    var cardsTop by mutableIntStateOf(Int.MAX_VALUE)
    // The top of the info cards' column at the right (landscape, planning).
    var infoRightTop by mutableIntStateOf(Int.MAX_VALUE)
    // The right edges of the sheet and the cards (0 while not shown): in
    // landscape the map's controls and fitted routes keep right of them.
    var sheetRight by mutableIntStateOf(0)
    var cardsRight by mutableIntStateOf(0)

    // The section edit sheet's top edge while it is open.
    var editTop by mutableIntStateOf(Int.MAX_VALUE)

    // The rider's saved sections (ADR-0006), opened off the main thread.
    var store by mutableStateOf<StoreState>(StoreState.Loading)
    var sections by mutableStateOf<List<Section>>(emptyList())
    // The Sections page opens showing only sections that need attention
    // (from the notice after a map update).
    var sectionFilter by mutableStateOf(SectionFilter())
    // A section shown from the Sections page, with the page's filter then:
    // deleting it goes back to the page as it was, to carry on tidying up.
    var shownFromPage by mutableStateOf<Pair<Long, SectionFilter>?>(null)
    // The menu topic open as a page, if any.
    var dataPage by mutableStateOf<DataPage?>(null)

    // The saved sections as the router sees them (M2a): rebuilt off the main
    // thread whenever they change, re-matched ones included. Until the first
    // build is done, routes are the fastest ones.
    var favourites by mutableStateOf<Favourites?>(null)
    // Where the favourites run on gravel: drawn dashed, and mostly-gravel
    // sections hidden while gravel is avoided.
    var sectionGravel by mutableStateOf<List<SectionGravel>>(emptyList())
    // One favourites build at a time: the region and the sections often
    // change together at start, and a build can't be stopped once it runs,
    // so two ran side by side, each holding its own set.
    val favouritesBuild = Mutex()

    // "Mark section" mode: tap start, tap end, adjust, save.
    val marker = SectionMarker<LatLng> { a, b -> approxDistanceM(a.toLatLon(), b.toLatLon()) }
    var marking by mutableStateOf(false)
    // Counts marking sessions, so a slow proposal from a cancelled one is dropped.
    var markSession by mutableIntStateOf(0)
    var draft by mutableStateOf<SectionDraft?>(null)
    var proposing by mutableStateOf(false)
    var savingDraft by mutableStateOf(false)
    var editing by mutableStateOf<Section?>(null)
    // The menu, and the page it opened.
    var menuOpen by mutableStateOf(false)
    var showAbout by mutableStateOf(false)
    var showBackup by mutableStateOf(false)
    var showHelp by mutableStateOf(false)
    var showDebug by mutableStateOf(false)
    // Ride settings, one tap from the map.
    var showSettings by mutableStateOf(false)

    var keepScreenOn by mutableStateOf(RoutePrefs.keepScreenOn(context))
    // The roads the rides have been on, drawn as dashes (ADR-0010).
    var showRidden by mutableStateOf(RoutePrefs.showRidden(context))
    // The ridden roads button's choice while a route or loop is planned:
    // for that plan only, never saved; null follows the setting.
    var riddenWhilePlanning by mutableStateOf<Boolean?>(null)

    // Ride settings: the map turns with the rider; an alert off the route.
    var turnMap by mutableStateOf(RoutePrefs.turnMap(context))
    // How close the map zooms while riding (Ride settings).
    var rideZoomStep by mutableIntStateOf(RoutePrefs.rideZoomStep(context))
    // Zoom levels the rider added with + and − on this ride.
    var rideZoomNudge by mutableDoubleStateOf(0.0)
    // When + or − was last tapped, so its zoom moves faster than speed's.
    var rideZoomTappedAt by mutableStateOf<Long?>(null)
    var offRouteAlert by mutableStateOf(RoutePrefs.offRouteAlert(context))
    // The ride card's X asked to leave the route: the confirmation shows.
    var leavingRoute by mutableStateOf(false)

    // Where the ride card ends (px from the top), so the compass sits under it.
    var rideCardBottom by mutableIntStateOf(0)
    // The rider moved the map in ride mode: until Recentre.
    var ridePanned by mutableStateOf(false)

    // Sections that no longer fit the map are hidden unless the rider asks.
    // Quick-tags waiting for review, and the review in progress (it runs in
    // "mark section" mode, starting from each tag's suggested section).
    var pendingTags by mutableIntStateOf(0)
    var review by mutableStateOf<TagReview?>(null)
    var reviewTag by mutableStateOf<Tag?>(null)

    // A saved ride the rider asked to see (Menu > Routes & rides > Show), to
    // mark sections along it; the ride being recorded takes its place.
    var shownRide by mutableStateOf<ShownRide?>(null)
    // The shown ride being renamed from its card.
    var renamingRide by mutableStateOf<Track?>(null)

    // A ride waiting for the permissions (Ride, or Carry on).
    var pendingRide by mutableStateOf<RideRoute?>(null)
    var pendingResume by mutableStateOf<Long?>(null)

    // Tap → snap → marker, as a debugging aid; a tap on a saved section opens
    // its sheet. Long-press → route start; the next long-press → route end,
    // and the route is computed and drawn. In "mark section" mode, taps pick
    // the section instead.
    val picker = RoutePicker<LatLng>()
    // The route between the picked points and the extra time the rider gives
    // it; it is found again when either changes (or the favourites do).
    var routeEnds by mutableStateOf<Pair<LatLng, LatLng>?>(null)
    // Points the route must pass, in order, and whether the next
    // long-press adds one (Add via point on the route card).
    var vias by mutableStateOf<List<LatLng>>(emptyList())
    var addingVia by mutableStateOf(false)
    // A loop through the via points (Sections: Loop through it): the route
    // sheet then shows loops from the start through them and back, and,
    // when true, through them the other way round too. Null: a route.
    var routeThrough by mutableStateOf<Boolean?>(null)
    // The via points before the last one was added, until the route
    // through it is found (restored if it can't be).
    var viasBefore by mutableStateOf<List<LatLng>?>(null)
    // The via point the rider tapped, to remove just that one.
    var selectedVia by mutableStateOf<Int?>(null)

    // The time to arrive by (seconds since the epoch) in place of the
    // extra-time choice, and when the route shown was found.
    var arriveBy by mutableStateOf<Long?>(null)
    var routeFoundAt by mutableLongStateOf(0L)
    var routeSummary by mutableStateOf<RouteSummary?>(null)
    // The route shown and the options it was found with, for sharing.
    var shownRoute by mutableStateOf<Pair<Route, RouteOptions>?>(null)
    // The routes to choose from (the fastest last) and which is shown.
    var routeChoices by mutableStateOf<List<Route>>(emptyList())
    // The last routes found, kept while new ones are found for a moved end:
    // when no route reaches the new end, they come back at once with the
    // end they had (the rider), instead of the sheet closing.
    var lastFound by mutableStateOf<FoundRoutes?>(null)
    // Set when going back to [lastFound]: the route search shows it again
    // instead of searching.
    var restoring by mutableStateOf<FoundRoutes?>(null)
    var routeIndex by mutableIntStateOf(0)
    // How many route choices the sheet had before the search now running
    // (0 for a new sheet): its rows stay, dimmed, while a setting's
    // change finds new routes, so nothing moves.
    var routeKept by mutableIntStateOf(0)
    // Why the last search after a change to an open route found nothing:
    // the sheet stays, saying so, instead of closing (the rider).
    var routeProblem by mutableStateOf<String?>(null)
    var gravel by mutableStateOf(RoutePrefs.gravel(context))
    // Favourites preferred, or avoided to find new roads.
    var favouritesMode by mutableStateOf(RoutePrefs.favourites(context))

    // Roads the rider's rides have been on count like any, or unridden
    // ones are preferred (ADR-0010).
    var unriddenMode by mutableStateOf(RoutePrefs.unridden(context))

    // Motorways, ferries and toll roads: all avoided until allowed.
    var avoid by mutableStateOf(RoutePrefs.avoid(context))

    // The ridden roads, when shown: from the routing overlay, less the
    // favourites drawn (those hidden for gravel don't cut the dashes),
    // at any zoom.
    // Whether any ride has been on this map's roads: the ridden roads
    // button shows only then.
    var hasRidden by mutableStateOf(false)
    // Metres a dp shows at the map's middle, for the scale bar (MapLibre's
    // "pixels" here are density-independent: they already are dp).
    var metresPerDp by mutableDoubleStateOf(0.0)

    // A start picked and waiting for an end, or for "Loop from here".
    var startPicked by mutableStateOf<LatLng?>(null)
    // Round trips (M3) from a start: the loops found (empty while they are
    // being found), the one shown, the options they were found with, and
    // the length the rider wants. Found again when the length, the gravel
    // setting or the favourites change.
    var loopStart by mutableStateOf<LatLng?>(null)
    var loops by mutableStateOf<List<Route>>(emptyList())
    // How many loops the sheet had before the search now running (0 for
    // a new sheet or after none were found): its rows stay, dimmed, while
    // a setting's change finds new loops, so nothing moves.
    var loopKept by mutableIntStateOf(0)
    var loopIndex by mutableIntStateOf(0)
    var loopOpts by mutableStateOf<RouteOptions?>(null)
    // The last loop length picked (loop sheet or Ride settings); new
    // loops start at it.
    val loopLength = LoopLength(RoutePrefs.loopChoice(context)) { RoutePrefs.setLoopChoice(context, it) }

    // 0: the standard loops; Shuffle picks another seed. A new start goes
    // back to the standard loops.
    var loopSeed by mutableStateOf(0u)
    // Which way every new loop heads at first (Ride settings), and which
    // way these loops head: the default again for each new start; a
    // change on the loop sheet is for that loop alone.
    var defaultDirection: LoopDirection by mutableStateOf(RoutePrefs.loopDirection(context))
    var loopDirection by mutableStateOf(defaultDirection)
    // The seed the next Shuffle uses, and its loops, found in the
    // background while the rider looks at these so Shuffle is instant.
    // One set ahead at most, only while the loop card is open; dropped
    // when anything they depend on changes.
    var nextSeed by mutableStateOf(shuffleSeed())
    val loopsAhead =
        OneAhead<LoopRequest, Deferred<Result<List<Route>>>> { it.cancel() }

    // Why the last search found no loop (shown on the loop card instead of
    // figures), or null.
    var loopProblem by mutableStateOf<String?>(null)
    // What the map was last fitted to (a loop start, or a route's ends): a
    // new one is always fitted, a recalculated one only when it needs to be.
    var fittedFor by mutableStateOf<Any?>(null)
    // Whether the route and loop cards show all their choices or only the
    // figures (collapsed, to see more of the map). A new route or loop
    // starts at rest; a moved end or a changed setting leaves it as it is.
    var cardExpanded by cardExpandedState

    // The recording card's favourites near the rider, at each fix.
    var nearby by mutableStateOf<List<FavouriteNearby>>(emptyList())

    // Which way the map is turned, for the recording card's arrows.
    var mapBearing by mutableFloatStateOf(0f)

    // While a route or loop is shown, the sections fade so the route is the
    // one strong line (its favourite stretches glow; see RouteOverlay).
    // A saved route the rider asked to see (Menu > Routes & rides > Show),
    // and a route or loop being saved (its name is asked first).
    var shownSaved by mutableStateOf<ShownSavedRoute?>(null)
    // The shown saved route being renamed from its card.
    var renamingSaved by mutableStateOf<SavedRoute?>(null)

    // A saved section the rider asked to see (Menu > Sections, a row): by
    // id, so a change of its rating shows at once.
    var shownSectionId by mutableStateOf<Long?>(null)

    // A favourite tapped while something else is open: only its facts.
    var favouriteInfoId by mutableStateOf<Long?>(null)

    // While the shown section is edited: the direction the sheet's one-way
    // switch and turn-round toggle would give it (null: as saved).
    var editPreview by mutableStateOf<Pair<Boolean, Boolean>?>(null)

    // The location button (LocateLogic): whether the overview of the plan
    // is on the map, untouched, and the zoom the map had on the rider
    // before it.
    var overviewShown by mutableStateOf(false)
    // The location button's zoom levels (Ride settings).
    var locateZooms by mutableStateOf(RoutePrefs.locateZooms(context))
    var zoomBeforeOverview by mutableStateOf<Double?>(null)
    // The map has gone to the rider once at the start, or the rider moved it first.
    var startSettled by mutableStateOf(false)

    var savingRoute by mutableStateOf<Pair<Route, Boolean>?>(null)

    var wasRideMode by mutableStateOf(false)

    var draftWords by mutableStateOf<Description?>(null)

    var savingName by mutableStateOf<String?>(null)

    var interruptedName by mutableStateOf<String?>(null)
}
