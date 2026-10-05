// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The single map screen (ADR-0002): OpenFreeMap tiles (ADR-0003), attribution
 * visible, and the rider's GPS position, with the loaded region outlined.
 * The rider's saved sections are drawn coloured by rating; tapping one opens
 * a sheet to change or delete it, and "mark section" mode proposes a new one
 * between two tapped points. Otherwise tapping the map snaps the point to
 * the nearest road and marks it; two long-presses pick a start and an end
 * and draw the route between them, over favourite sections where the
 * detour budget allows. The map only picks, draws and hit-tests; routing,
 * snapping and proposing sections belong to the Rust core.
 *
 * This is the composition root (ADR-0014): it makes the state and the
 * per-composition scope, runs the effects in order and lays out the root
 * Box. The rest lives beside it, in the same package:
 * - MapScreenState.kt: `MapScreenState`, `MapScreenScope`, `notify`.
 * - MapSetup.kt: the map view, style, controls and region status.
 * - SystemBars.kt: status and navigation bar icons that follow the map.
 * - MapCamera.kt: position, permissions, fitting and the locate button.
 * - MapStore.kt: the section store and the favourite layers.
 * - MapMarking.kt: marking new sections and reviewing tags.
 * - MapPlanning.kt: routes, loops, route settings, sharing and saving.
 * - MapShown.kt: shown rides, saved routes and sections.
 * - MapRide.kt: riding and recording, ride mode and the ride camera.
 * - MapGestures.kt: the tap and long-press listeners.
 * - MapButtons.kt, MapCards.kt, PlanSheet.kt: the pieces of the Box.
 * - MapDialogs.kt: the dialogs, sheets and pages over the map.
 */
@Composable
fun MapScreen() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val mapView = rememberMapViewWithLifecycle()
    val noticeScope = rememberCoroutineScope()
    val scope = rememberCoroutineScope()
    val cardExpandedState = rememberSaveable { mutableStateOf(false) }
    val state = remember { MapScreenState(context, noticeScope, scope, cardExpandedState) }
    with(state) {
        // The routing region: the downloaded one, if any (ADR-0008).
        val activeRegion by Regions.active.collectAsState()
        val regionDownload by Regions.download.collectAsState()
        val region = activeRegion.state
        val darkMap = LocalTheme.current.dark
        // The map draws edge to edge, but its controls must never sit under the
        // status bar, navigation bar or a display cutout.
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val safe = WindowInsets.safeDrawing
        val insets = SafeInsets(
            left = safe.getLeft(density, layoutDirection),
            top = safe.getTop(density),
            right = safe.getRight(density, layoutDirection),
            bottom = safe.getBottom(density),
        )
        // On its side (landscape) the cards (the planning sheet, the info and
        // start cards) are the same as upright but sit in a column at the
        // bottom left, no wider than columnWidthDp.
        val windowConfig = LocalConfiguration.current
        val landscape = isLandscape(windowConfig.screenWidthDp, windowConfig.screenHeightDp)
        val columnWidthDp = leftColumnWidthDp(windowConfig.screenWidthDp)
        val columnRight = if (landscape) max(sheetRight, cardsRight) else 0
        // Rides finished, imported or deleted: their roads are matched (once
        // per ride and map) and the set is built again (ADR-0010).
        val ridesVersionState = RideChanges.version.collectAsState()
        val ridesVersion by ridesVersionState
        // Riding a route (ADR-0011): the route and its state, from the
        // recording service.
        val following = (Recording.state.collectAsState().value as? Recording.State.Active)?.following
        val riding = following != null
        val ridingNow = rememberUpdatedState(riding)
        // Hidden while riding, as while planning.
        val riddenOn = riddenShown(showRidden, riddenWhilePlanning) && !riding
        // Layers in drawing order: saved sections, the ride being recorded, a
        // proposed section, the route, the snap marker.
        val overlays = remember(style) {
            style?.let { s ->
                Overlays(
                    SectionOverlay(s, density.density, darkMap),
                    RideOverlay(s, darkMap),
                    FollowOverlay(s, darkMap),
                    SectionDraftOverlay(s),
                    RouteOverlay(s, density.density, darkMap),
                    RiddenOverlay(s, darkMap),
                    SnapMarker(s),
                )
            }
        }
        // Ride recording (RecordingService): the line so far, and what to say.
        val recordingState = Recording.state.collectAsState()
        val recording by recordingState
        val recordPermissions = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { granted ->
            if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                hasLocation = true
                val ride = pendingRide
                val resume = pendingResume
                pendingRide = null
                pendingResume = null
                when {
                    resume != null -> RecordingService.resume(context, resume)
                    ride != null -> RecordingService.ride(context, ride)
                    else -> {
                        RecordingService.start(context)
                        Toasts.show(resources.getString(R.string.recording_started))
                    }
                }
            } else {
                notify(resources.getString(R.string.recording_no_permission), long = true)
            }
        }
        val loopChoice = loopLength.choice
        // Planning a route or loop: the sheet shows at the bottom, and the tag
        // and map buttons step aside (nobody tags while planning).
        val planning = routeEnds != null || loopStart != null
        // Recording without a route, with nothing else in hand: ride mode too,
        // with the recording card (2026-10-03). Planning, a picked
        // start or marking pause it; riding a plan swaps in the ride card.
        val (freeRiding, rideMode, pauseWanted) = rideModeOf((recording as? Recording.State.Active)?.trackId != null, riding, planning, startPicked != null, marking)
        val rideModeNow = rememberUpdatedState(rideMode)
        // The map's own controls (compass, logo, attribution) stay clear of
        // the system bars, the buttons and the sheet.
        val riddenButton = riddenButtonShown(planning, hasRidden)
        val shownSection = shownSectionId?.let { id -> sections.firstOrNull { it.id == id } }
        val favouriteInfo = favouriteInfoId?.let { id -> sections.firstOrNull { it.id == id } }
        val screen = MapScreenScope(
            state = state,
            context = context,
            resources = resources,
            mapView = mapView,
            region = region,
            darkMap = darkMap,
            density = density,
            insets = insets,
            windowConfig = windowConfig,
            landscape = landscape,
            columnWidthDp = columnWidthDp,
            columnRight = columnRight,
            overlays = overlays,
            following = following,
            riding = riding,
            ridingNow = ridingNow,
            riddenOn = riddenOn,
            riddenButton = riddenButton,
            recordPermissions = recordPermissions,
            loopChoice = loopChoice,
            planning = planning,
            freeRiding = freeRiding,
            rideMode = rideMode,
            rideModeNow = rideModeNow,
            pauseWanted = pauseWanted,
            shownSection = shownSection,
            favouriteInfo = favouriteInfo,
            recordingState = recordingState,
            ridesVersionState = ridesVersionState,
        )
        with(screen) {
            MapSetupEffects()

            StoreAndRegionEffects()
            RideEffects()

            LaunchedEffect(store) { refreshPendingTags() }

            LaunchedEffect(routeEnds, vias) { selectedVia = null }
            FavouriteLayerEffects()
            DisposableEffect(Unit) { onDispose { loopsAhead.clear() } }

            RideModeEffects()

            // The planning sheet reaches down behind the navigation bar: its
            // buttons then contrast with the sheet, not the system theme.
            NavigationBarIconsFollow(
                // (Not in landscape: the sheet is at the left, the map shows behind the rest of the bar.)
                if (planning && !landscape) MaterialTheme.colorScheme.surfaceColorAtElevation(PLAN_SHEET_ELEVATION) else null,
            )
            // A new start or new route ends: the sheet starts at rest, so the map
            // shows what was found.
            LaunchedEffect(loopStart) { if (loopStart != null) cardExpanded = false }
            // A new route sheet starts at rest; moving the end of an open route
            // leaves the sheet as it is, pulled up or not, as a setting's change
            // does (the rider): keyed on whether a route is open, not on its ends.
            LaunchedEffect(routeEnds != null) { if (routeEnds != null) cardExpanded = false }
            // Planning over: the ridden roads follow the setting again.
            LaunchedEffect(planning) { if (!planning) riddenWhilePlanning = null }
            ControlsPlacementEffect()
            ShownEffects()

            PlanningEffects()
            MapGestures()

            LocationEffects()

            RideCameraEffects()

            Box(Modifier.fillMaxSize().onSizeChanged { mapSize = it }) {
                AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
                // Theme-coloured scrim keeps the navigation bar icons readable over any
                // part of the map; the icons follow the same theme (MainActivity). The
                // status bar has no scrim: its icons switch to contrast with the map
                // pixels sampled behind it (StatusBarIconsFollowMap).
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .windowInsetsBottomHeight(WindowInsets.navigationBars)
                        .background(if (LocalTheme.current.dark) DARK_SCRIM else LIGHT_SCRIM),
                )
                // What the app is working on, just below the top panels (not in
                // them, so the map doesn't refit when it shows); after 300 ms only.
                val installing = (regionDownload as? DownloadState.Installing)?.let { R.string.busy_region }
                BusyPill(
                    busy.current ?: installing ?: R.string.region_loading.takeIf { region is RegionState.Loading },
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset { IntOffset(0, max(topPanelBottom, insets.top) + 8.dp.roundToPx()) },
                )
                // The menu (top left) and ride settings (top right), clear of the
                // status bar and cutouts; they step aside while planning or marking,
                // like the buttons at the bottom.
                val topButtons = !marking && !planning && !rideMode
                TopButtons(screen, topButtons)
                RideCards(screen)
                // The scale (2026-10-03): at the bottom, a little above
                // the navigation bar or the planning sheet, just right of the map's
                // logo and attribution and below the tag button, clear of the
                // position button; sits above the cards, as the logo does.
                val scaleAbove = with(density) {
                    controlsBottomPx(landscape, insets.bottom, mapSize.height, sheetTop, cardsTop, infoRightTop).toDp()
                }
                ScaleBar(
                    metresPerDp = metresPerDp,
                    darkMap = darkMap,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .padding(
                            start = SCALE_START + with(density) { (leftClearance(landscape, insets.left, columnRight) - insets.left).toDp() },
                            bottom = scaleAbove + SCALE_BOTTOM,
                        ),
                )
                // Notices across the top, the whole width below the top buttons
                // when they show; on wide screens no wider than TOP_BOX_MAX_WIDTH.
                val belowTopButtons = if (topButtons) 8.dp + TOP_BUTTON_SIZE + 8.dp else 8.dp
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .safeDrawingPadding()
                        .padding(top = belowTopButtons, start = 8.dp, end = 8.dp)
                        .widthIn(max = TOP_BOX_MAX_WIDTH)
                        .fillMaxWidth()
                        .onGloballyPositioned { topPanelBottom = it.boundsInRoot().bottom.roundToInt() },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SnackbarHost(notices)
                }
                // A task in hand, the road tapped, or a ride or route shown: cards
                // at the bottom, above the planning sheet when there is one.
                val offerLoop = startPicked != null && !marking
                val via = selectedVia?.takeIf { it in vias.indices }
                // In landscape with a plan open the info cards are in a column at the
                // right; the left one is the sheet's, with the task cards.
                val infoAtRight = infoCardsAtRight(landscape, planning)
                // The buttons at the bottom step aside for them, as for planning.
                val (taskCard, infoCardOpen, cardsShown, leftCardsShown) = cardsOpen(
                    message != null, marking, offerLoop, via != null, shownRide != null, shownSaved != null,
                    shownSection != null, roadInfo != null, favouriteInfo != null, rideMode, infoAtRight,
                )
                PlanButtons(screen)
                // The cards: what was tapped (a favourite, a favourite section or a
                // road; at most one) sits on top of what is shown (a ride or a saved
                // route; at most one); whatever is in hand (the start card, a task,
                // a message) stays below them, nearest the sheet.
                CardColumns(screen, leftCardsShown, infoAtRight, infoCardOpen, taskCard, offerLoop, via)
                // Back steps back through what is on the map before it leaves the
                // app (a pulled-up sheet handles Back itself first).
                fun currentBackStep() = backStep(
                    marking, addingVia, selectedVia != null, roadInfo != null, favouriteInfoId != null, routeEnds != null,
                    loopStart != null, startPicked != null, shownSaved != null, shownSectionId != null, shownRide != null,
                )
                BackHandler(enabled = currentBackStep() != null) {
                    when (currentBackStep()) {
                        BackStep.MARK -> markBack()
                        BackStep.VIA_PICK -> {
                            addingVia = false
                            message = null
                        }
                        BackStep.VIA_SELECTED -> selectedVia = null
                        BackStep.ROAD -> {
                            roadInfo = null
                            overlays?.snap?.clear()
                        }
                        BackStep.FAVOURITE -> favouriteInfoId = null
                        BackStep.ROUTE -> closeRoute()
                        BackStep.LOOP -> closeLoop()
                        BackStep.START -> {
                            startPicked = null
                            picker.reset()
                            overlays?.route?.show(null, null, null)
                            message = null
                        }
                        BackStep.SAVED_ROUTE -> {
                            shownSaved = null
                            overlays?.route?.show(null, null, null)
                        }
                        BackStep.SECTION -> hideSection()
                        BackStep.RIDE -> shownRide = null
                        null -> Unit
                    }
                }
                PlanSheet(screen)
                MapButtonColumn(screen, cardsShown)
                QuickTagButton(screen, cardsShown)
                ToastHost(Modifier.align(Alignment.BottomCenter))
                // The menu, over everything on the map.
                MenuDrawer(
                    open = menuOpen,
                    dark = LocalTheme.current.dark,
                    onToggleTheme = LocalTheme.current.toggle,
                    onClose = { menuOpen = false },
                    onPick = { topic ->
                        when (topic) {
                            MenuTopic.LIBRARY -> dataPage = DataPage.LIBRARY
                            MenuTopic.SECTIONS -> dataPage = DataPage.SECTIONS
                            MenuTopic.REGION -> dataPage = DataPage.REGION
                            MenuTopic.HELP -> showHelp = true
                            MenuTopic.BACKUP -> showBackup = true
                            MenuTopic.ABOUT -> showAbout = true
                            MenuTopic.DEBUG -> showDebug = true
                        }
                    },
                )
            }
            MapDialogs()
        }
    }
}
