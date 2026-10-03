// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.content.res.Configuration
import android.content.ComponentCallbacks2
import se.gangefors.moto.debug.DebugTools
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import android.graphics.PointF
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.ZoneId
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.widgets.CompassView
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import se.gangefors.moto.core.Avoid
import se.gangefors.moto.core.Description
import se.gangefors.moto.core.Favourites
import se.gangefors.moto.core.SavedRoute
import se.gangefors.moto.core.Track
import se.gangefors.moto.core.FavouritesMode
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.MotoException
import se.gangefors.moto.core.NewSection
import se.gangefors.moto.core.Rating
import se.gangefors.moto.core.RoadInfo
import se.gangefors.moto.core.RouteOptions
import se.gangefors.moto.core.LoopOptions
import se.gangefors.moto.core.Route
import se.gangefors.moto.core.Section
import se.gangefors.moto.core.SectionDraft
import se.gangefors.moto.core.SectionGravel
import se.gangefors.moto.core.SectionSource
import se.gangefors.moto.core.SectionStatus
import se.gangefors.moto.core.SectionStore
import se.gangefors.moto.core.SectionUpdate
import se.gangefors.moto.core.Tag
import se.gangefors.moto.core.TagStatus
import se.gangefors.moto.core.TrackPoint
import se.gangefors.moto.core.UnriddenMode
import se.gangefors.moto.core.defaultRouteOptions

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
 */
@Composable
fun MapScreen() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val mapView = rememberMapViewWithLifecycle()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var hasLocation by remember { mutableStateOf(hasLocationPermission(context)) }
    // The routing region: the downloaded one, if any (ADR-0008).
    val activeRegion by Regions.active.collectAsState()
    val regionDownload by Regions.download.collectAsState()
    // What the app is waiting for, shown after a moment (BusyPill).
    val busy = remember { BusyTasks() }
    val region = activeRegion.state
    // The step of a task in hand (e.g. "Long-press where you want to
    // go"), shown until the step changes.
    var message by remember { mutableStateOf<String?>(null) }
    // Short notices: a snackbar at the top that goes by itself; errors
    // stay longer and can be closed.
    val notices = remember { SnackbarHostState() }
    val noticeScope = rememberCoroutineScope()
    fun notify(text: String, long: Boolean = false) {
        noticeScope.launch {
            notices.currentSnackbarData?.dismiss()
            notices.showSnackbar(
                text,
                withDismissAction = long,
                duration = if (long) SnackbarDuration.Long else SnackbarDuration.Short,
            )
        }
    }
    // The road the rider last tapped, shown until closed.
    var roadInfo by remember { mutableStateOf<RoadInfo?>(null) }

    // Open the downloaded region off the main thread.
    LaunchedEffect(Unit) { Regions.load(context.applicationContext) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted -> hasLocation = granted.values.any { it } }

    LaunchedEffect(Unit) {
        if (!hasLocation) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    // The map's own style follows the app's theme: OpenFreeMap's light or
    // dark map. A change loads the other style; everything drawn on the map
    // is redrawn on it (the overlays are made anew for each style).
    val darkMap = LocalTheme.current.dark
    LaunchedEffect(map, darkMap) {
        val m = map ?: return@LaunchedEffect
        val url = mapStyleUrl(resources, darkMap)
        if (m.style?.uri != url) m.setStyle(url) { s -> style = s }
    }
    // Load the style once the map is ready.
    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.isLogoEnabled = true
            m.cameraPosition = initialCamera(resources)
            m.setStyle(mapStyleUrl(resources, darkMap)) { s ->
                map = m
                style = s
                DebugTools.mark("map style loaded")
            }
        }
    }

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
    // Status bar icons follow the brightness of the map behind them.
    StatusBarIconsFollowMap(mapView, map, WindowInsets.statusBars.getTop(density))

    // Where the panels over the map end, in pixels, measured as they are
    // laid out: the card at the top, the buttons at the right, the tag
    // button at the bottom. Routes are fitted clear of them.
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    var topPanelBottom by remember { mutableIntStateOf(0) }
    var buttonsLeft by remember { mutableIntStateOf(Int.MAX_VALUE) }
    var tagTop by remember { mutableIntStateOf(Int.MAX_VALUE) }
    var sheetTop by remember { mutableIntStateOf(Int.MAX_VALUE) }
    var cardsTop by remember { mutableIntStateOf(Int.MAX_VALUE) }
    // The section edit sheet's top edge while it is open.
    var editTop by remember { mutableIntStateOf(Int.MAX_VALUE) }
    fun fitPaddingNow(): FitPadding {
        val (w, h) = mapSize.width to mapSize.height
        val panels = Panels(
            width = w,
            height = h,
            left = insets.left,
            top = max(topPanelBottom, insets.top),
            right = max(w - buttonsLeft, insets.right),
            bottom = maxOf(h - tagTop, h - sheetTop, h - cardsTop, h - editTop, insets.bottom),
        )
        return fitPadding(panels, with(density) { FIT_MARGIN.roundToPx() })
    }

    /** What the map shows clear of the panels, or `null` before it is laid out. */
    fun visibleBounds(m: MapLibreMap, pad: FitPadding): GeoBounds? {
        val (w, h) = mapSize.width.toFloat() to mapSize.height.toFloat()
        val (l, t, r, b) = listOf(pad.left, pad.top, w - pad.right, h - pad.bottom).map { it.toFloat() }
        if (r <= l || b <= t) return null
        val corners = listOf(PointF(l, t), PointF(r, t), PointF(l, b), PointF(r, b))
            .map { m.projection.fromScreenLocation(it) }
            .map { LatLon(it.latitude, it.longitude) }
        return boundsOf(listOf(corners))
    }

    /**
     * Moves the map to show all of [lines] clear of the panels, zoomed in
     * as far as they allow (never closer than [minSpanM] across). Unless
     * [always], only when part of them is out of view or they are small in
     * it, so recalculating doesn't make the map jump.
     */
    fun showOnMap(lines: List<List<LatLon>>, always: Boolean, minSpanM: Double = MIN_FIT_SPAN_M) {
        val m = map ?: return
        val target = boundsOf(lines)?.withMinSpan(minSpanM) ?: return
        val pad = fitPaddingNow()
        if (!always && !needsFit(visibleBounds(m, pad), target)) return
        // Following the rider's position would pull the map straight back;
        // the location button turns it on again.
        m.locationComponent.takeIf { it.isLocationComponentActivated }?.cameraMode = CameraMode.NONE
        val bounds = LatLngBounds.Builder()
            .include(LatLng(target.north, target.east))
            .include(LatLng(target.south, target.west))
            .build()
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad.left, pad.top, pad.right, pad.bottom))
    }

    // The rider's saved sections (ADR-0006), opened off the main thread.
    var store by remember { mutableStateOf<StoreState>(StoreState.Loading) }
    var sections by remember { mutableStateOf<List<Section>>(emptyList()) }
    // The Sections page opens showing only sections that need attention
    // (from the notice after a map update).
    var sectionFilter by remember { mutableStateOf(SectionFilter()) }
    // A section shown from the Sections page, with the page's filter then:
    // deleting it goes back to the page as it was, to carry on tidying up.
    var shownFromPage by remember { mutableStateOf<Pair<Long, SectionFilter>?>(null) }
    // The menu topic open as a page, if any.
    var dataPage by remember { mutableStateOf<DataPage?>(null) }
    LaunchedEffect(Unit) {
        store = withContext(Dispatchers.IO) {
            DebugTools.startup("store open") { SavedSections.open(context.applicationContext) }
        }
        when (val s = store) {
            is StoreState.Ready -> withContext(Dispatchers.IO) {
                // Save a ride the app died in the middle of.
                DebugTools.startup("recording recovered") { Recording.recover(context.applicationContext, s.store) }
                runCatching { DebugTools.startup("favourites loaded") { s.store.list(null) } }
            }
                .onSuccess { sections = it }
                .onFailure { notify(resources.getString(R.string.sections_failed, it.message ?: it.toString()), long = true) }
            is StoreState.Failed -> notify(resources.getString(R.string.sections_failed, s.message), long = true)
            StoreState.Loading -> Unit
        }
    }

    val scope = rememberCoroutineScope()

    // After a map update, fit the saved sections to the new roads (M1 step 7).
    // Quick when the map hasn't changed; sections that no longer fit are kept
    // and drawn grey.
    LaunchedEffect(store, region) {
        val s = (store as? StoreState.Ready)?.store ?: return@LaunchedEffect
        val engine = (region as? RegionState.Ready)?.engine ?: return@LaunchedEffect
        val result = busy.run(R.string.busy_sections) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val report = DebugTools.startup("sections re-match") { s.rematch(engine) }
                    // Reloaded when any section was looked at or now waits
                    // off the open map (a region switched off or removed).
                    report to if (report.checked > 0uL || report.offMap > 0uL) s.list(null) else null
                }
            }
        }
        result.fold(
            onSuccess = { (report, updated) ->
                updated?.let { sections = it }
                if (report.unmatched > 0uL) {
                    // Show opens Sections with only those that need a look.
                    noticeScope.launch {
                        notices.currentSnackbarData?.dismiss()
                        val n = report.unmatched.toInt()
                        val answer = notices.showSnackbar(
                            resources.getQuantityString(R.plurals.sections_unmatched, n, n),
                            actionLabel = resources.getString(R.string.sections_unmatched_action),
                            withDismissAction = true,
                            duration = SnackbarDuration.Indefinite,
                        )
                        if (answer == SnackbarResult.ActionPerformed) {
                            sectionFilter = SectionFilter(attention = true)
                            dataPage = DataPage.SECTIONS
                        }
                    }
                }
            },
            onFailure = { notify(resources.getString(R.string.sections_failed, it.message ?: it.toString()), long = true) },
        )
    }

    // The saved sections as the router sees them (M2a): rebuilt off the main
    // thread whenever they change, re-matched ones included. Until the first
    // build is done, routes are the fastest ones.
    var favourites by remember { mutableStateOf<Favourites?>(null) }
    // Where the favourites run on gravel: drawn dashed, and mostly-gravel
    // sections hidden while gravel is avoided.
    var sectionGravel by remember { mutableStateOf<List<SectionGravel>>(emptyList()) }
    // One favourites build at a time: the region and the sections often
    // change together at start, and a build can't be stopped once it runs,
    // so two ran side by side, each holding its own set.
    val favouritesBuild = remember { Mutex() }
    // Rides finished, imported or deleted: their roads are matched (once
    // per ride and map) and the set is built again (ADR-0010).
    val ridesVersion by RideChanges.version.collectAsState()
    // Rides finished after the app died mid-ride (Recording.recover) get a
    // name from where they went, as one finished normally does, once the
    // map is open.
    LaunchedEffect(store, region, ridesVersion) {
        val s = (store as? StoreState.Ready)?.store ?: return@LaunchedEffect
        val engine = (region as? RegionState.Ready)?.engine ?: return@LaunchedEffect
        val ids = Recording.takeFinished()
        if (ids.isEmpty()) return@LaunchedEffect
        val named = withContext(Dispatchers.IO) {
            ids.count { id -> s.getTrack(id)?.let { s.nameRide(resources, engine, it) } != null }
        }
        if (named > 0) RideChanges.changed()
    }
    LaunchedEffect(store, region, sections, ridesVersion) {
        val s = (store as? StoreState.Ready)?.store ?: return@LaunchedEffect
        val engine = (region as? RegionState.Ready)?.engine ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            favouritesBuild.withLock {
                val built = runCatching {
                    DebugTools.ridesMatched(DebugTools.startup("rides matched") { s.matchRides(engine) })
                    DebugTools.startup("favourites") { s.favourites(engine) }
                        .also { DebugTools.overlayBuilt(it) }
                        .let { it to it.gravel() }
                }
                // Superseded while it ran: nobody will use it.
                if (!isActive) built.getOrNull()?.first?.destroy()
                built
            }
        }
            .onSuccess { (f, g) ->
                // The set replaced is freed now, not when the garbage
                // collector gets to it.
                favourites?.let { NativeRelease.later(it) }
                favourites = f
                sectionGravel = g
            }
            .onFailure { notify(resources.getString(R.string.sections_failed, it.message ?: it.toString()), long = true) }
    }

    // "Mark section" mode: tap start, tap end, adjust, save.
    val marker = remember { SectionMarker<LatLng> { a, b -> approxDistanceM(a.toLatLon(), b.toLatLon()) } }
    var marking by remember { mutableStateOf(false) }
    // Counts marking sessions, so a slow proposal from a cancelled one is dropped.
    var markSession by remember { mutableIntStateOf(0) }
    var draft by remember { mutableStateOf<SectionDraft?>(null) }
    var proposing by remember { mutableStateOf(false) }
    var savingDraft by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Section?>(null) }
    // The menu, and the page it opened.
    var menuOpen by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }
    // Ride settings, one tap from the map.
    var showSettings by remember { mutableStateOf(false) }
    // How to use the map, as a notice on the first few starts (after that
    // it is under About); no region: a notice that stays, with a way to
    // download one.
    LaunchedEffect(region) {
        when (val r = region) {
            RegionState.Loading -> Unit
            is RegionState.Ready -> if (RoutePrefs.takeMapHint(context)) notify(resources.getString(R.string.map_hint), long = true)
            RegionState.Missing -> noticeScope.launch {
                notices.currentSnackbarData?.dismiss()
                val result = notices.showSnackbar(
                    resources.getString(R.string.region_missing),
                    actionLabel = resources.getString(R.string.region_missing_action),
                    withDismissAction = true,
                    duration = SnackbarDuration.Indefinite,
                )
                if (result == SnackbarResult.ActionPerformed) dataPage = DataPage.REGION
            }
            is RegionState.Failed -> notify(regionStatus(resources, r), long = true)
        }
    }
    var keepScreenOn by remember { mutableStateOf(RoutePrefs.keepScreenOn(context)) }
    // The roads the rides have been on, drawn as dashes (ADR-0010).
    var showRidden by remember { mutableStateOf(RoutePrefs.showRidden(context)) }
    // The ridden roads button's choice while a route or loop is planned:
    // for that plan only, never saved; null follows the setting.
    var riddenWhilePlanning by remember { mutableStateOf<Boolean?>(null) }
    // Riding a route (ADR-0011): the route and its state, from the
    // recording service.
    val following = (Recording.state.collectAsState().value as? Recording.State.Active)?.following
    val riding = following != null
    val ridingNow = rememberUpdatedState(riding)
    // Ride settings: the map turns with the rider; an alert off the route.
    var turnMap by remember { mutableStateOf(RoutePrefs.turnMap(context)) }
    // How close the map zooms while riding (Ride settings).
    var rideZoomStep by remember { mutableIntStateOf(RoutePrefs.rideZoomStep(context)) }
    // Zoom levels the rider added with + and − on this ride.
    var rideZoomNudge by remember { mutableDoubleStateOf(0.0) }
    var offRouteAlert by remember { mutableStateOf(RoutePrefs.offRouteAlert(context)) }
    // The rider moved the map while riding: until Recentre.
    // Where the ride card ends (px from the top), so the compass sits under it.
    var rideCardBottom by remember { mutableIntStateOf(0) }
    var ridePanned by remember { mutableStateOf(false) }
    LaunchedEffect(riding) {
        if (!riding) {
            ridePanned = false
            rideZoomNudge = 0.0
        }
    }
    // Hidden while riding, as while planning.
    val riddenOn = riddenShown(showRidden, riddenWhilePlanning) && !riding
    // Sections that no longer fit the map are hidden unless the rider asks.
    // Quick-tags waiting for review, and the review in progress (it runs in
    // "mark section" mode, starting from each tag's suggested section).
    var pendingTags by remember { mutableIntStateOf(0) }
    var review by remember { mutableStateOf<TagReview?>(null) }
    var reviewTag by remember { mutableStateOf<Tag?>(null) }

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
    val recording by Recording.state.collectAsState()
    // Ride settings: the screen stays on while a ride records, for a phone
    // on a handlebar mount.
    val view = LocalView.current
    val screenOn = keepScreenOn && recording is Recording.State.Active
    DisposableEffect(view, screenOn) {
        view.keepScreenOn = screenOn
        onDispose { view.keepScreenOn = false }
    }
    // A saved ride the rider asked to see (Menu > Routes & rides > Show), to
    // mark sections along it; the ride being recorded takes its place.
    var shownRide by remember { mutableStateOf<ShownRide?>(null) }
    // The shown ride being renamed from its card.
    var renamingRide by remember { mutableStateOf<Track?>(null) }
    /** Shares ride [t] as GPX, as Routes & rides does. */
    fun shareRide(t: Track) {
        val s = (store as? StoreState.Ready)?.store ?: return
        scope.launch {
            val zone = ZoneId.systemDefault()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val gpx = DebugTools.query("saved GPX", ::bytesSummary) {
                        s.exportTrackGpx(t.id, rideName(t.name, t.startedAt, zone)) ?: error(resources.getString(R.string.rides_gone))
                    }
                    RouteShare.prepare(context, gpx, rideFileName(t.startedAt, zone), resources.getString(R.string.route_share_title))
                }
            }
            result.fold(
                onSuccess = { context.startActivity(it) },
                onFailure = { notify(resources.getString(R.string.route_share_failed, it.message ?: it.toString()), long = true) },
            )
        }
    }
    /** Deletes ride [t] from its card: the card closes and the ride leaves
     * the map. */
    fun deleteShownRide(t: Track) {
        val s = (store as? StoreState.Ready)?.store ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { s.deleteTrack(t.id) } }
            result.onFailure { notify(resources.getString(R.string.ride_delete_failed, it.message ?: it.toString()), long = true) }
            if (result.isSuccess) {
                RideChanges.changed()
                if (shownRide?.track?.id == t.id) shownRide = null
            }
        }
    }
    LaunchedEffect(overlays, recording, shownRide) {
        overlays?.ride?.show((recording as? Recording.State.Active)?.line?.let { listOf(it) } ?: shownRide?.segments)
    }
    // The route being ridden: ahead and behind, and the way back to it.
    LaunchedEffect(overlays, following?.route) { overlays?.follow?.show(following?.route) }
    LaunchedEffect(overlays, following?.state?.segment, following?.state?.segmentT) {
        val st = following?.state ?: return@LaunchedEffect
        overlays?.follow?.at(st.segment.toInt(), st.segmentT)
    }
    LaunchedEffect(overlays, following?.back) { overlays?.follow?.back(following?.back) }
    LaunchedEffect(recording) {
        when (val r = recording) {
            // Short, so the toast stays clear of the buttons; the figures
            // (battery use too) go to the debug tools.
            is Recording.State.Finished -> {
                Toasts.show(resources.getString(R.string.recording_saved))
                val minutes = (((r.track.endedAt ?: r.track.startedAt) - r.track.startedAt) / 60).toInt()
                DebugTools.rideEnded(context, sectionKm(r.track.distanceM), minutes, r.batteryPerHour)
                // Named from where it went, as an imported ride is.
                val s = (store as? StoreState.Ready)?.store
                val engine = (region as? RegionState.Ready)?.engine
                if (s != null && engine != null && r.track.name == null) {
                    withContext(Dispatchers.IO) { s.nameRide(resources, engine, r.track) }
                    RideChanges.changed()
                }
            }
            is Recording.State.Failed -> notify(resources.getString(R.string.recording_failed, r.message), long = true)
            else -> Unit
        }
    }
    // A ride waiting for the permissions (Ride, or Carry on).
    var pendingRide by remember { mutableStateOf<RideRoute?>(null) }
    var pendingResume by remember { mutableStateOf<Long?>(null) }
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

    fun showDraft() {
        val o = overlays ?: return
        when (val st = marker.state) {
            SectionMarker.State.Off, SectionMarker.State.PickStart -> o.draft.show(null, null, null)
            is SectionMarker.State.PickEnd -> o.draft.show(st.start, null, null)
            is SectionMarker.State.Proposed -> o.draft.show(st.start, st.end, draft?.geometry)
        }
    }

    fun stopMarking() {
        marker.cancel()
        marking = false
        draft = null
        savingDraft = false
        // The marking steps ("10 km. Tap near an end…") end with it.
        message = null
        showDraft()
    }

    fun draftMessage(d: SectionDraft) = resources.getString(R.string.section_proposed, sectionKm(d.distanceM))

    fun refreshPendingTags() {
        val ready = store as? StoreState.Ready ?: return
        scope.launch {
            pendingTags = withContext(Dispatchers.IO) {
                runCatching { ready.store.listTags(TagStatus.PENDING).size }.getOrDefault(0)
            }
        }
    }
    LaunchedEffect(store) { refreshPendingTags() }

    /** Quick-tag: saves the rider's current fix as a tag and buzzes. */
    fun quickTag() {
        val ready = store as? StoreState.Ready ?: return
        val active = recording as? Recording.State.Active
        val fix = chooseTagFix(active?.lastFix, mapFix(map), System.currentTimeMillis())
        if (fix == null) {
            buzz(context, ok = false)
            notify(resources.getString(R.string.tag_no_fix), long = true)
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { ready.store.addTag(newTag(fix, active?.trackId)) } }
            buzz(context, ok = result.isSuccess)
            result.fold(
                onSuccess = { Toasts.show(resources.getString(R.string.tag_saved)) },
                onFailure = { notify(resources.getString(R.string.tag_failed, it.message ?: it.toString()), long = true) },
            )
            refreshPendingTags()
        }
    }

    fun endReview(text: String?) {
        stopMarking()
        review = null
        reviewTag = null
        message = null
        text?.let { notify(it) }
        refreshPendingTags()
    }

    /** Shows [tag]'s suggested section in "mark section" mode, ready to trim or save. */
    fun showTag(tag: Tag?) {
        val r = review
        val ready = store as? StoreState.Ready
        val engine = (region as? RegionState.Ready)?.engine
        if (tag == null || r == null || ready == null || engine == null) {
            val skipped = r?.skipped ?: 0
            endReview(
                if (skipped > 0) {
                    resources.getQuantityString(R.plurals.tag_review_done_skipped, skipped, skipped)
                } else {
                    resources.getString(R.string.tag_review_done)
                },
            )
            return
        }
        stopMarking()
        reviewTag = tag
        marking = true
        markSession++
        val session = markSession
        proposing = true
        message = resources.getString(R.string.tag_review_loading, r.position, r.size)
        scope.launch {
            val result = busy.run(R.string.busy_section) {
                withContext(Dispatchers.Default) {
                    runCatching {
                        val track = tag.trackId?.let { ready.store.trackPoints(it) }
                        DebugTools.query("favourite suggestion") { engine.suggestSection(tag, track) }
                    }
                }
            }
            proposing = false
            if (session != markSession) return@launch
            result.fold(
                onSuccess = { d ->
                    val first = d.geometry.first()
                    val last = d.geometry.last()
                    marker.propose(LatLng(first.lat, first.lon), LatLng(last.lat, last.lon))
                    draft = d
                    message = resources.getString(R.string.tag_review_suggested, r.position, r.size, sectionKm(d.distanceM))
                    showOnMap(listOf(d.geometry), always = true, minSpanM = TAG_REVIEW_SPAN_M)
                },
                onFailure = { e ->
                    marker.begin()
                    message = resources.getString(R.string.tag_review_none, r.position, r.size, e.message ?: e.toString())
                    showOnMap(listOf(listOf(tag.position)), always = true, minSpanM = TAG_REVIEW_SPAN_M)
                },
            )
            showDraft()
        }
    }

    fun startReview() {
        val ready = store as? StoreState.Ready ?: return
        scope.launch {
            val tags = withContext(Dispatchers.IO) {
                runCatching { ready.store.listTags(TagStatus.PENDING) }.getOrDefault(emptyList())
            }
            review = TagReview(tags)
            showTag(review?.current)
        }
    }

    /** Leaves the tag under review pending and moves on to the next one. */
    fun skipTag() {
        if (reviewTag == null) return
        showTag(review?.skip())
    }

    /** Marks the tag under review and moves on to the next one. */
    fun finishTag(status: TagStatus) {
        val tag = reviewTag ?: return
        val ready = store as? StoreState.Ready ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { ready.store.setTagStatus(tag.id, status) } }
            showTag(review?.next())
        }
    }

    /** A tap in "mark section" mode: set the start, the end, or move the nearer end. */
    fun onMarkTap(ready: RegionState.Ready, point: LatLng) {
        if (proposing) return
        when (val st = marker.onTap(point)) {
            is SectionMarker.State.PickEnd -> {
                // Check the start lies on a road before keeping it.
                val problem = runCatching { DebugTools.query("snap") { ready.engine.snap(point.toLatLon()) } }.exceptionOrNull()
                if (problem != null) {
                    marker.rejectLast()
                    notify(coreErrorMessage(resources, problem), long = true)
                } else {
                    message = resources.getString(R.string.section_pick_end)
                }
                showDraft()
            }
            is SectionMarker.State.Proposed -> {
                proposing = true
                overlays?.draft?.show(st.start, st.end, draft?.geometry)
                message = resources.getString(R.string.section_proposing)
                val session = markSession
                scope.launch {
                    val result = busy.run(R.string.busy_section) {
                        withContext(Dispatchers.Default) {
                            runCatching {
                                DebugTools.query("favourite draft") { ready.engine.sectionBetween(st.start.toLatLon(), st.end.toLatLon()) }
                            }
                        }
                    }
                    proposing = false
                    if (!marking || session != markSession) return@launch
                    result.fold(
                        onSuccess = { d ->
                            draft = d
                            message = draftMessage(d)
                        },
                        onFailure = { e ->
                            marker.rejectLast()
                            message = resources.getString(R.string.section_pick_end)
                            notify(coreErrorMessage(resources, e), long = true)
                        },
                    )
                    showDraft()
                }
            }
            else -> Unit
        }
    }

    // Tap → snap → marker, as a debugging aid; a tap on a saved section opens
    // its sheet. Long-press → route start; the next long-press → route end,
    // and the route is computed and drawn. In "mark section" mode, taps pick
    // the section instead.
    val picker = remember { RoutePicker<LatLng>() }
    // The route between the picked points and the extra time the rider gives
    // it; it is found again when either changes (or the favourites do).
    var routeEnds by remember { mutableStateOf<Pair<LatLng, LatLng>?>(null) }
    // Points the route must pass, in order, and whether the next
    // long-press adds one (Add via point on the route card).
    var vias by remember { mutableStateOf<List<LatLng>>(emptyList()) }
    var addingVia by remember { mutableStateOf(false) }
    // A loop through the via points (Sections: Loop through it): the route
    // sheet then shows loops from the start through them and back, and,
    // when true, through them the other way round too. Null: a route.
    var routeThrough by remember { mutableStateOf<Boolean?>(null) }
    // The via points before the last one was added, until the route
    // through it is found (restored if it can't be).
    var viasBefore by remember { mutableStateOf<List<LatLng>?>(null) }
    // The via point the rider tapped, to remove just that one.
    var selectedVia by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(routeEnds, vias) { selectedVia = null }
    // The time to arrive by (seconds since the epoch) in place of the
    // extra-time choice, and when the route shown was found.
    var arriveBy by remember { mutableStateOf<Long?>(null) }
    var routeFoundAt by remember { mutableLongStateOf(0L) }
    var routeSummary by remember { mutableStateOf<RouteSummary?>(null) }
    // The route shown and the options it was found with, for sharing.
    var shownRoute by remember { mutableStateOf<Pair<Route, RouteOptions>?>(null) }
    // The routes to choose from (the fastest last) and which is shown.
    var routeChoices by remember { mutableStateOf<List<Route>>(emptyList()) }
    // The last routes found, kept while new ones are found for a moved end:
    // when no route reaches the new end, they come back at once with the
    // end they had (Stefan), instead of the sheet closing.
    var lastFound by remember { mutableStateOf<FoundRoutes?>(null) }
    // Set when going back to [lastFound]: the route search shows it again
    // instead of searching.
    var restoring by remember { mutableStateOf<FoundRoutes?>(null) }
    var routeIndex by remember { mutableIntStateOf(0) }
    // How many route choices the sheet had before the search now running
    // (0 for a new sheet): its rows stay, dimmed, while a setting's
    // change finds new routes, so nothing moves.
    var routeKept by remember { mutableIntStateOf(0) }
    // Why the last search after a change to an open route found nothing:
    // the sheet stays, saying so, instead of closing (Stefan).
    var routeProblem by remember { mutableStateOf<String?>(null) }
    var gravel by remember { mutableStateOf(RoutePrefs.gravel(context)) }
    // Favourites preferred, or avoided to find new roads.
    var favouritesMode by remember { mutableStateOf(RoutePrefs.favourites(context)) }
    fun changeFavourites(f: FavouritesMode) {
        favouritesMode = f
        RoutePrefs.setFavourites(context, f)
    }
    // Roads the rider's rides have been on count like any, or unridden
    // ones are preferred (ADR-0010).
    var unriddenMode by remember { mutableStateOf(RoutePrefs.unridden(context)) }
    fun changeUnridden(u: UnriddenMode) {
        unriddenMode = u
        RoutePrefs.setUnridden(context, u)
    }
    // Motorways, ferries and toll roads: all avoided until allowed.
    var avoid by remember { mutableStateOf(RoutePrefs.avoid(context)) }
    fun changeAvoid(a: Avoid) {
        avoid = a
        RoutePrefs.setAvoid(context, a)
    }
    LaunchedEffect(overlays, sections, sectionGravel, gravel) {
        val hidden = hiddenForGravel(sectionGravel, gravel)
        val shown = visibleSections(sections, showUnmatched = false).filterNot { it.id in hidden }
        overlays?.sections?.show(shown)
        overlays?.sections?.showGravel(gravelParts(sectionGravel, shown))
    }
    // The ridden roads, when shown: from the routing overlay, less the
    // favourites drawn (those hidden for gravel don't cut the dashes),
    // and not when the map shows more than 70 km across.
    val screenWidthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp().value.toDouble() }
    val riddenMinZoom = zoomForSpan(RIDDEN_MAX_SPAN_M, screenWidthDp, SETTINGS_LATITUDE).toFloat()
    // Whether any ride has been on this map's roads: the ridden roads
    // button shows only then.
    var hasRidden by remember { mutableStateOf(false) }
    // The map's zoom once it comes to rest, for what shows only near enough.
    var mapZoom by remember { mutableFloatStateOf(0f) }
    DisposableEffect(map) {
        val m = map ?: return@DisposableEffect onDispose {}
        val idle = MapLibreMap.OnCameraIdleListener { mapZoom = m.cameraPosition.zoom.toFloat() }
        m.addOnCameraIdleListener(idle)
        mapZoom = m.cameraPosition.zoom.toFloat()
        onDispose { m.removeOnCameraIdleListener(idle) }
    }
    // Metres a dp shows at the map's middle, for the scale bar (MapLibre's
    // "pixels" here are density-independent: they already are dp).
    var metresPerDp by remember { mutableDoubleStateOf(0.0) }
    DisposableEffect(map) {
        val m = map ?: return@DisposableEffect onDispose {}
        fun measure() {
            metresPerDp = m.projection.getMetersPerPixelAtLatitude(m.cameraPosition.target?.latitude ?: 0.0)
        }
        val move = MapLibreMap.OnCameraMoveListener { measure() }
        val idle = MapLibreMap.OnCameraIdleListener { measure() }
        m.addOnCameraMoveListener(move)
        m.addOnCameraIdleListener(idle)
        measure()
        onDispose {
            m.removeOnCameraMoveListener(move)
            m.removeOnCameraIdleListener(idle)
        }
    }
    LaunchedEffect(favourites) {
        val f = favourites
        hasRidden = f != null && withContext(Dispatchers.Default) { runCatching { f.riddenEdgeCount() > 0u }.getOrDefault(false) }
    }
    LaunchedEffect(overlays, favourites, sections, sectionGravel, gravel, riddenOn) {
        val o = overlays ?: return@LaunchedEffect
        val engine = (region as? RegionState.Ready)?.engine
        val f = favourites
        if (!riddenOn || engine == null || f == null) {
            o.ridden.show(emptyList(), 0f)
            return@LaunchedEffect
        }
        val hidden = hiddenForGravel(sectionGravel, gravel)
        val shown = visibleSections(sections, showUnmatched = false).filterNot { it.id in hidden }
        val hiddenIds = hiddenSectionIds(sections.map { it.id }, shown.map { it.id })
        val lines = withContext(Dispatchers.Default) { runCatching {
                DebugTools.query("ridden roads", ::linesSummary) { f.riddenLines(engine, hiddenIds) }
            }.getOrDefault(emptyList()) }
        o.ridden.show(lines, riddenMinZoom)
    }
    // A start picked and waiting for an end, or for "Loop from here".
    var startPicked by remember { mutableStateOf<LatLng?>(null) }
    // Round trips (M3) from a start: the loops found (empty while they are
    // being found), the one shown, the options they were found with, and
    // the length the rider wants. Found again when the length, the gravel
    // setting or the favourites change.
    var loopStart by remember { mutableStateOf<LatLng?>(null) }
    var loops by remember { mutableStateOf<List<Route>>(emptyList()) }
    // How many loops the sheet had before the search now running (0 for
    // a new sheet or after none were found): its rows stay, dimmed, while
    // a setting's change finds new loops, so nothing moves.
    var loopKept by remember { mutableIntStateOf(0) }
    var loopIndex by remember { mutableIntStateOf(0) }
    var loopOpts by remember { mutableStateOf<RouteOptions?>(null) }
    // The last loop length picked (loop sheet or Ride settings); new
    // loops start at it.
    val loopLength = remember { LoopLength(RoutePrefs.loopChoice(context)) { RoutePrefs.setLoopChoice(context, it) } }
    val loopChoice = loopLength.choice
    // 0: the standard loops; Shuffle picks another seed. A new start goes
    // back to the standard loops.
    var loopSeed by remember { mutableStateOf(0u) }
    // Which way every new loop heads at first (Ride settings), and which
    // way these loops head: the default again for each new start; a
    // change on the loop sheet is for that loop alone.
    var defaultDirection by remember { mutableStateOf(RoutePrefs.loopDirection(context)) }
    var loopDirection by remember { mutableStateOf(defaultDirection) }
    // The seed the next Shuffle uses, and its loops, found in the
    // background while the rider looks at these so Shuffle is instant.
    // One set ahead at most, only while the loop card is open; dropped
    // when anything they depend on changes.
    var nextSeed by remember { mutableStateOf(shuffleSeed()) }
    val loopsAhead = remember {
        OneAhead<LoopRequest, Deferred<Result<List<Route>>>> { it.cancel() }
    }
    DisposableEffect(Unit) { onDispose { loopsAhead.clear() } }
    /** Shows route choice [index] of [set] (found with [opts]) with its
     * figures, the others faint; the fastest of several says so, and is
     * grey unless it is also the suggested road. */
    fun showRouteChoice(start: LatLng, end: LatLng, set: List<Route>, index: Int, opts: RouteOptions) {
        val r = set.getOrNull(index) ?: return
        // Loops through a section have no fastest one.
        val fastest = if (routeThrough != null) -1 else fastestChoice(set.size)
        val dull = fastest?.takeIf { fastestIsDull(set.size, set.last().suggested) }
        val others = set.mapIndexedNotNull { i, l -> if (i == index) null else i to l.geometry }
        overlays?.route?.show(
            start, end, r.geometry, r.favouriteParts, r.unpavedParts, vias,
            others = others, dull = index == dull, dullOther = dull, favouriteRatings = r.favouriteRatings,
        )
        routeIndex = index
        // Going back to these routes shows the one picked last.
        lastFound?.takeIf { it.choices === set }?.let { lastFound = it.copy(index = index) }
        routeSummary = summarize(r.distanceM, r.durationS, r.favouriteShare, r.fastestDurationS, r.curvyShare, r.unpavedM, r.tollM, r.unriddenShare)
            .copy(fastest = index == fastest)
        shownRoute = r to opts
    }

    /** Shows loop [index] of [set] from [start], the others faint. */
    fun showLoop(start: LatLng, set: List<Route>, index: Int) {
        val r = set.getOrNull(index) ?: return
        val others = set.mapIndexedNotNull { i, l -> if (i == index) null else i to l.geometry }
        overlays?.route?.show(
            start, null, r.geometry, r.favouriteParts, r.unpavedParts,
            others = others, favouriteRatings = r.favouriteRatings,
        )
    }
    // Why the last search found no loop (shown on the loop card instead of
    // figures), or null.
    var loopProblem by remember { mutableStateOf<String?>(null) }
    // What the map was last fitted to (a loop start, or a route's ends): a
    // new one is always fitted, a recalculated one only when it needs to be.
    var fittedFor by remember { mutableStateOf<Any?>(null) }
    // Whether the route and loop cards show all their choices or only the
    // figures (collapsed, to see more of the map). A new route or loop
    // starts at rest; a moved end or a changed setting leaves it as it is.
    var cardExpanded by rememberSaveable { mutableStateOf(false) }
    // Planning a route or loop: the sheet shows at the bottom, and the tag
    // and map buttons step aside (nobody tags while planning).
    val planning = routeEnds != null || loopStart != null

    /** Forgets the last loops, so a new loop sheet starts empty (finding
     * loops) instead of keeping their rows, dimmed, as a setting's change
     * does. */
    fun clearLoops() {
        loops = emptyList()
        loopIndex = 0
        loopProblem = null
        loopKept = 0
    }

    /** Loops from [start], heading the default way: the standard set
     * (seed 0), or the set of [seed]. */
    fun startLoop(start: LatLng, seed: UInt = 0u) {
        startPicked = null
        // A plan replaces the step that led to it ("Point set…").
        message = null
        loopSeed = seed
        loopDirection = defaultDirection
        clearLoops()
        loopStart = start
    }

    /**
     * Where the rider is, to plan from: their newest fix, with a note when
     * it is an older one; null, saying it waits for GPS, without one.
     */
    fun riderStart(): LatLng? {
        val active = recording as? Recording.State.Active
        val fix = when (val p = riderPosition(active?.lastFix, mapFix(map), System.currentTimeMillis())) {
            is RiderPosition.Fresh -> p.fix
            is RiderPosition.LastKnown -> {
                notify(resources.getString(R.string.plan_last_known))
                p.fix
            }
            RiderPosition.None -> {
                notify(resources.getString(R.string.plan_waiting_gps))
                return null
            }
        }
        return LatLng(fix.position.lat, fix.position.lon)
    }
    // The planning sheet reaches down behind the navigation bar: its
    // buttons then contrast with the sheet, not the system theme.
    NavigationBarIconsFollow(
        if (planning) MaterialTheme.colorScheme.surfaceColorAtElevation(PLAN_SHEET_ELEVATION) else null,
    )
    // A new start or new route ends: the sheet starts at rest, so the map
    // shows what was found.
    LaunchedEffect(loopStart) { if (loopStart != null) cardExpanded = false }
    // A new route sheet starts at rest; moving the end of an open route
    // leaves the sheet as it is, pulled up or not, as a setting's change
    // does (Stefan): keyed on whether a route is open, not on its ends.
    LaunchedEffect(routeEnds != null) { if (routeEnds != null) cardExpanded = false }
    // The map's own controls (compass, logo, attribution) stay clear of
    // the system bars, the buttons and the sheet.
    val riddenButton = riddenButtonShown(planning, cardExpanded, hasRidden, mapZoom, riddenMinZoom)
    // Planning over: the ridden roads follow the setting again.
    LaunchedEffect(planning) { if (!planning) riddenWhilePlanning = null }
    // The compass at the top right, under Ride settings' button when that
    // shows, else in the corner: clear of the cards and the sheet at the
    // bottom (Stefan, 2026-10-02).
    LaunchedEffect(map, insets, planning, marking, sheetTop, mapSize, riding, rideCardBottom) {
        val m = map ?: return@LaunchedEffect
        val sheet = if (planning && sheetTop < mapSize.height) mapSize.height - sheetTop else 0
        with(density) {
            // While riding, under the ride card.
            val compassTop = if (riding && rideCardBottom > 0) {
                rideCardBottom - insets.top + 8.dp.roundToPx()
            } else {
                compassTopDp(settingsButtonShown = !marking && !planning).dp.roundToPx()
            }
            applyControlMargins(
                m,
                insets.copy(bottom = max(insets.bottom, sheet)),
                CONTROL_MARGIN.roundToPx(),
                ATTRIBUTION_OFFSET.roundToPx(),
                compassRight = TOP_BUTTON_MARGIN.roundToPx(),
                compassTop = compassTop,
            )
        }
    }
    // While a route or loop is shown, the sections fade so the route is the
    // one strong line (its favourite stretches glow; see RouteOverlay).
    // A saved route the rider asked to see (Menu > Routes & rides > Show),
    // and a route or loop being saved (its name is asked first).
    var shownSaved by remember { mutableStateOf<ShownSavedRoute?>(null) }
    // The shown saved route being renamed from its card.
    var renamingSaved by remember { mutableStateOf<SavedRoute?>(null) }
    /** Deletes saved route [r] from its card: the card closes and the
     * route leaves the map. */
    fun deleteShownSaved(r: SavedRoute) {
        val s = (store as? StoreState.Ready)?.store ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { s.deleteRoute(r.id) } }
            result.onFailure { notify(resources.getString(R.string.saved_route_delete_failed, it.message ?: it.toString()), long = true) }
            if (result.isSuccess && shownSaved?.route?.id == r.id) {
                shownSaved = null
                overlays?.route?.show(null, null, null)
            }
        }
    }
    // A saved route keeps only its line: its favourite stretches are
    // worked out from the favourites as they are now, when it shows and
    // whenever they change, and glow on it as on a new route (ADR-0011).
    LaunchedEffect(shownSaved?.route?.id, shownSaved?.line, sections, store) {
        val shown = shownSaved ?: return@LaunchedEffect
        val s = (store as? StoreState.Ready)?.store ?: return@LaunchedEffect
        val found = withContext(Dispatchers.IO) {
            runCatching {
                DebugTools.query("favourites on saved route", { "${it.parts.size} parts" }) { s.favouritePartsAlong(shown.line) }
            }.getOrNull()
        } ?: return@LaunchedEffect
        val now = shownSaved
        if (now == null || now.route.id != shown.route.id) return@LaunchedEffect
        shownSaved = now.copy(favouriteParts = found.parts, favouriteRatings = found.ratings)
        val start = LatLng(now.line.first().lat, now.line.first().lon)
        val end = if (now.route.isLoop) null else LatLng(now.line.last().lat, now.line.last().lon)
        overlays?.route?.show(start, end, now.line, favourites = found.parts, favouriteRatings = found.ratings)
    }
    // A saved section the rider asked to see (Menu > Sections, a row): by
    // id, so a change of its rating shows at once.
    var shownSectionId by remember { mutableStateOf<Long?>(null) }
    val shownSection = shownSectionId?.let { id -> sections.firstOrNull { it.id == id } }
    // A favourite tapped while something else is open: only its facts.
    var favouriteInfoId by remember { mutableStateOf<Long?>(null) }
    val favouriteInfo = favouriteInfoId?.let { id -> sections.firstOrNull { it.id == id } }
    fun hideSection() {
        if (shownSectionId == null) return
        shownSectionId = null
        overlays?.route?.show(null, null, null)
    }
    /**
     * Shows saved section [s] with its card (the same whether picked on
     * the map or in Menu > Sections): in its rating's colour, wider and
     * edged, so it stands out from the other sections (faded meanwhile)
     * and one that no longer fits the map shows too (grey); the map moves
     * to it when [fit] (from the
     * page), not when it was tapped where the rider looks.
     */
    fun showSection(s: Section, fit: Boolean) {
        routeEnds = null
        loopStart = null
        startPicked = null
        picker.reset()
        shownSaved = null
        roadInfo = null
        overlays?.snap?.clear()
        shownSectionId = s.id
        favouriteInfoId = null
        // What was on the route layer (a saved route or ride) goes.
        overlays?.route?.show(null, null, null)
        if (fit) showOnMap(listOf(s.geometry), always = true)
    }
    // While the shown section is edited: the direction the sheet's one-way
    // switch and turn-round toggle would give it (null: as saved).
    var editPreview by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    // The shown section, or else the favourite whose facts are open, is
    // drawn standing out, so the rider sees which one the card is about.
    val shownForEdit = (shownSectionId ?: favouriteInfoId)?.let { id -> sections.firstOrNull { it.id == id } }
    LaunchedEffect(overlays, shownForEdit, editPreview, darkMap) {
        val s = shownForEdit
        if (s == null) {
            overlays?.sections?.showSelected(null, null)
            return@LaunchedEffect
        }
        val (line, arrows) = shownSectionLine(s.geometry, isOneWay(s.direction), editPreview)
        val look = shownSectionLook(s.rating, fitsTheMap(s.status), darkMap)
        overlays?.sections?.showSelected(line, look, arrows)
    }
    // Another section shown (or none): no longer the one from the page.
    LaunchedEffect(shownSectionId) {
        if (shownFromPage?.first != shownSectionId) shownFromPage = null
    }
    // Planning takes the map over: the shown section goes.
    LaunchedEffect(routeEnds, loopStart) {
        if (routeEnds != null || loopStart != null) shownSectionId = null
    }

    // The location button (LocateLogic): whether the overview of the plan
    // is on the map, untouched, and the zoom the map had on the rider
    // before it.
    var overviewShown by remember { mutableStateOf(false) }
    // The location button's zoom levels (Ride settings).
    var locateZooms by remember { mutableStateOf(RoutePrefs.locateZooms(context)) }
    var zoomBeforeOverview by remember { mutableStateOf<Double?>(null) }

    fun mapWidthDp(): Double = (mapSize.width / density.density).toDouble().coerceAtLeast(1.0)

    /** The route, loops, saved route or ride on the map, if any. */
    fun planLines(): List<List<LatLon>> = when {
        loopStart != null -> loops.map { it.geometry }
        routeEnds != null -> routeChoices.map { it.geometry }
        else -> listOfNotNull(shownSaved?.line ?: shownRide?.line)
    }

    /** What the map shows now, for the location button. */
    fun locateView(m: MapLibreMap, here: android.location.Location?): LocateView {
        if (overviewShown) return LocateView.OVERVIEW
        val cam = m.cameraPosition
        val target = cam.target ?: return LocateView.ELSEWHERE
        if (here == null) {
            val tracking = m.locationComponent.takeIf { it.isLocationComponentActivated }?.cameraMode
            return if (tracking == CameraMode.TRACKING) LocateView.ON_RIDER else LocateView.ELSEWHERE
        }
        val offset = FloatArray(1)
        android.location.Location.distanceBetween(target.latitude, target.longitude, here.latitude, here.longitude, offset)
        val width = spanAtZoom(cam.zoom, mapWidthDp(), target.latitude)
        return if (isCentredOnRider(offset[0].toDouble(), width)) LocateView.ON_RIDER else LocateView.ELSEWHERE
    }

    fun onLocateTap() {
        val m = map ?: return
        val here = m.locationComponent.takeIf { it.isLocationComponentActivated }?.lastKnownLocation
        val plan = planLines().filter { it.isNotEmpty() }
        val zoom = m.cameraPosition.zoom
        when (val action = onLocateTap(locateView(m, here), zoom, plan.isNotEmpty(), zoomBeforeOverview, locateZooms)) {
            is LocateAction.Follow -> {
                overviewShown = false
                followRider(m, action.zoom)
            }
            LocateAction.ShowPlan -> {
                zoomBeforeOverview = zoom
                val rider = here?.let { listOf(LatLon(it.latitude, it.longitude)) }
                showOnMap(plan + listOfNotNull(rider), always = true)
                overviewShown = true
            }
        }
    }

    /** Forgets the last route choices, so a new route sheet starts empty
     * (finding a route) instead of keeping their rows, dimmed, as a moved
     * end or a setting's change does. */
    fun clearRoutes() {
        routeSummary = null
        shownRoute = null
        routeChoices = emptyList()
        routeIndex = 0
        routeKept = 0
        routeProblem = null
        lastFound = null
        restoring = null
    }

    fun closeRoute() {
        routeEnds = null
        clearRoutes()
        routeThrough = null
        picker.reset()
        vias = emptyList()
        addingVia = false
        arriveBy = null
        overlays?.route?.show(null, null, null)
    }

    fun closeLoop() {
        loopStart = null
        clearLoops()
        loopsAhead.clear()
        overlays?.route?.show(null, null, null)
    }

    /** Before a saved route or ride is shown from Routes & rides: what
     * else was on the map goes (a plan, a picked start, a shown section,
     * route or ride, and the road's and a favourite's cards), so its card
     * is the only one. */
    fun clearForShown() {
        if (routeEnds != null) closeRoute()
        if (loopStart != null) closeLoop()
        startPicked = null
        picker.reset()
        message = null
        hideSection()
        shownSaved = null
        shownRide = null
        roadInfo = null
        overlays?.snap?.clear()
        favouriteInfoId = null
        overlays?.route?.show(null, null, null)
    }

    /** Rides [route] (ADR-0011): planning ends, recording starts (or the
     * ride being recorded follows it), asking for the permissions first. */
    fun beginRide(route: RideRoute) {
        if (routeEnds != null) closeRoute()
        if (loopStart != null) closeLoop()
        shownSaved = null
        shownRide = null
        overlays?.route?.show(null, null, null)
        startPicked = null
        if (recording is Recording.State.Active) {
            RecordingService.ride(context, route)
        } else {
            pendingRide = route
            recordPermissions.launch(recordingPermissions())
        }
    }

    /** Rides recorded ride [r] again (Stefan, 2026-10-02): its line, a
     * loop when it ended where it started, its favourites as they are
     * now, in the time it took. The new recording gets the usual name. */
    fun rideAgain(r: ShownRide) {
        val line = rideAgainLine(r.segments)
        if (line == null) {
            notify(resources.getString(R.string.ride_again_too_short))
            return
        }
        val s = (store as? StoreState.Ready)?.store
        scope.launch {
            val found = s?.let {
                withContext(Dispatchers.IO) {
                    runCatching {
                        DebugTools.query("favourites on ride", { "${it.parts.size} parts" }) { s.favouritePartsAlong(line) }
                    }.getOrNull()
                }
            }
            beginRide(
                RideRoute(
                    rideName(r.track.name, r.track.startedAt, ZoneId.systemDefault()),
                    rideAgainIsLoop(line),
                    rideAgainDurationS(r.track.startedAt, r.track.endedAt),
                    line,
                    found?.parts ?: emptyList(),
                    found?.ratings ?: emptyList(),
                ),
            )
        }
    }

    /** Rides a planned route or loop, named from where it goes, as a saved
     * one is ("Loop from Höör via Linderöd"). */
    fun startRide(route: Route, isLoop: Boolean) {
        val engine = (region as? RegionState.Ready)?.engine
        scope.launch {
            val named = engine?.let { e ->
                withContext(Dispatchers.Default) {
                    runCatching {
                        val far = if (isLoop) farthestPoint(route.geometry)?.let { p -> e.describe(listOf(p, p)) } else null
                        planName(isLoop, e.describe(route.geometry), far)
                    }.getOrNull()
                }
            }
            val name = named?.let { planNameText(resources, it) }
                ?: resources.getString(if (isLoop) R.string.ride_name_loop else R.string.ride_name_route)
            beginRide(rideRouteOf(route, name, isLoop))
        }
    }

    /** Back while marking: the last point placed goes, or marking (or the
     * tag review) stops when nothing is placed. */
    fun markBack() {
        if (reviewTag != null) {
            endReview(null)
            return
        }
        // A proposal still being found for the old points is dropped.
        markSession++
        proposing = false
        draft = null
        when (marker.back()) {
            SectionMarker.State.Off -> {
                stopMarking()
                message = null
                return
            }
            SectionMarker.State.PickStart -> message = resources.getString(R.string.section_pick_start)
            is SectionMarker.State.PickEnd -> message = resources.getString(R.string.section_pick_end)
            is SectionMarker.State.Proposed -> Unit
        }
        showDraft()
    }
    var savingRoute by remember { mutableStateOf<Pair<Route, Boolean>?>(null) }
    LaunchedEffect(overlays, routeEnds, loopStart, shownSaved, shownSectionId) {
        val routeShown = routeEnds != null || loopStart != null || shownSaved != null || shownSectionId != null
        overlays?.sections?.setLook(sectionLook(routeShown = routeShown, darkMap = darkMap))
    }
    LaunchedEffect(loopStart, loopChoice, loopSeed, loopDirection, gravel, avoid, favouritesMode, unriddenMode, favourites, overlays) {
        val start = loopStart ?: return@LaunchedEffect
        val o = overlays ?: return@LaunchedEffect
        val ready = region as? RegionState.Ready ?: return@LaunchedEffect
        val favs = favourites
        val opts = routeOptions(defaultRouteOptions(), ROUTE_EXTRA_PERCENT, gravel, avoid, favouritesMode, unriddenMode)
        val choice = loopChoice
        val shape = LoopOptions(seed = loopSeed, bearing = loopDirection.bearing)
        val request = LoopRequest(start, choice, opts, favs, shape)
        // Found ahead (Shuffle), or found now. A newer request cancels
        // this one; its result is then dropped.
        val ahead = loopsAhead.take(request)
        // A new set on its way: the old loops and their figures go, so the
        // sheet says it is finding them. Loops Shuffle already has replace
        // the old ones straight away, without "finding" in between.
        if (ahead?.isCompleted != true) {
            loopKept = keptChoices(loopKept, loops.size)
            loops = emptyList()
            loopIndex = 0
            o.route.show(start, null, null)
        }
        loopProblem = null
        // No loop this time: the start and the card stay, with why, so the
        // rider can Shuffle or change the length or direction from here.
        fun noLoop(why: String) {
            loopsAhead.clear()
            nextSeed = shuffleSeed()
            loopProblem = why
            loopKept = 0
            cardExpanded = true
            o.route.show(start, null, null)
        }
        val find = { r: LoopRequest, ahead: Boolean ->
            runCatching {
                DebugTools.loops(r.start.toLatLon(), r.choice.target, r.opts, r.favourites, r.shape, ahead) {
                    ready.engine.roundTrip(r.start.toLatLon(), r.choice.target, r.opts, r.favourites, r.shape)
                }
            }
        }
        val result = busy.run(R.string.busy_loops) { ahead?.await() ?: withContext(Dispatchers.Default) { find(request, false) } }
        result.fold(
            onSuccess = { found ->
                val first = found.firstOrNull()
                if (first == null) {
                    noLoop(resources.getString(R.string.loop_none))
                } else {
                    loops = found
                    // A new set starts at its first loop, also when Shuffle
                    // had it ready and the old set was on another one.
                    loopIndex = 0
                    loopOpts = opts
                    showLoop(start, found, 0)
                    // All the loops of the set, so Next doesn't move the map.
                    showOnMap(found.map { it.geometry } + listOf(listOf(start.toLatLon())), always = fittedFor != start)
                    fittedFor = start
                    val next = shuffleSeed()
                    nextSeed = next
                    val nextRequest = request.copy(shape = shape.copy(seed = next))
                    loopsAhead.hold(nextRequest, scope.async(Dispatchers.Default) { find(nextRequest, true) })
                }
            },
            onFailure = {
                noLoop(
                    if (classify(it) == CoreProblem.NO_ROUTE) {
                        resources.getString(R.string.loop_none)
                    } else {
                        coreErrorMessage(resources, it)
                    },
                )
            },
        )
    }
    LaunchedEffect(routeEnds, vias, arriveBy, gravel, avoid, favouritesMode, unriddenMode, favourites, overlays, routeThrough) {
        val (start, end) = routeEnds ?: return@LaunchedEffect
        val o = overlays ?: return@LaunchedEffect
        val ready = region as? RegionState.Ready ?: return@LaunchedEffect
        restoring?.takeIf { it.ends == (start to end) }?.let { back ->
            restoring = null
            routeChoices = back.choices
            routeFoundAt = back.at
            showRouteChoice(start, end, back.choices, back.index, back.opts)
            showOnMap(back.choices.map { it.geometry }, always = fittedFor != (start to end))
            fittedFor = start to end
            return@LaunchedEffect
        }
        restoring = null
        routeProblem = null
        routeKept = keptChoices(routeKept, routeChoices.size)
        routeSummary = null
        shownRoute = null
        routeChoices = emptyList()
        routeIndex = 0
        val favs = favourites
        val now = System.currentTimeMillis() / 1000
        val by = arriveBy
        val opts = if (by != null) {
            arriveByOptions(defaultRouteOptions(), now, by, gravel, avoid, favouritesMode, unriddenMode)
        } else {
            routeOptions(defaultRouteOptions(), ROUTE_EXTRA_PERCENT, gravel, avoid, favouritesMode, unriddenMode)
        }
        // A newer request cancels this one; its result is then dropped.
        val result = busy.run(R.string.busy_routes) {
            withContext(Dispatchers.Default) {
                runCatching {
                    val via = vias.map { it.toLatLon() }
                    val bothWays = routeThrough
                    if (bothWays != null) {
                        DebugTools.query("there and back", ::routesSummary) {
                            ready.engine.roundTripVia(start.toLatLon(), via, bothWays, opts, favs)
                        }
                    } else {
                        DebugTools.routes(start.toLatLon(), via, end.toLatLon(), opts, favs) {
                            ready.engine.routeChoices(start.toLatLon(), via, end.toLatLon(), opts, favs)
                        }
                    }
                }
            }
        }
        result.fold(
            onSuccess = { found ->
                if (found.isEmpty()) return@fold
                routeChoices = found
                routeFoundAt = now
                lastFound = FoundRoutes(start to end, found, 0, opts, now)
                viasBefore = null
                showRouteChoice(start, end, found, 0, opts)
                // All the choices, so switching doesn't move the map.
                showOnMap(found.map { it.geometry }, always = fittedFor != (start to end))
                fittedFor = start to end
            },
            onFailure = {
                val before = viasBefore
                if (before != null) {
                    // The new via point can't be reached: keep the route without it.
                    viasBefore = null
                    vias = before
                } else {
                    val back = lastFound
                    when (failedSearch(back?.ends, start to end)) {
                        FailedSearch.GO_BACK -> {
                            // The end moved somewhere no route reaches: back to
                            // where it was, with its routes, the sheet as it is.
                            restoring = back
                            routeEnds = checkNotNull(back).ends
                        }
                        FailedSearch.STAY -> {
                            // Same ends, something else changed: the sheet
                            // stays and says why, for the rider to change it
                            // again or close it.
                            routeProblem = coreErrorMessage(resources, it)
                            o.route.show(start, end, null)
                            return@fold
                        }
                        FailedSearch.CLOSE -> routeEnds = null
                    }
                }
                notify(coreErrorMessage(resources, it), long = true)
            },
        )
    }
    // When the card grows or shrinks (expanded, collapsed, a message), the
    // route or loops shown stay in view.
    LaunchedEffect(cardExpanded, topPanelBottom, sheetTop, mapSize) {
        // Once the sheet has settled: a fit while it still grows would
        // aim for the space above it as it was, not as it ends up.
        delay(SETTLE_MS)
        val lines = when {
            loopStart != null -> loops.map { it.geometry }
            routeEnds != null -> routeChoices.map { it.geometry }
            else -> emptyList()
        }
        if (lines.isNotEmpty()) showOnMap(lines, always = false)
    }
    DisposableEffect(map, overlays, region) {
        val m = map
        val o = overlays
        val s = style
        if (m == null || o == null || s == null) return@DisposableEffect onDispose {}
        val ready = region as? RegionState.Ready
        ready?.let { showRegionOutline(s, it.engine.info(), it.engine.coverage(), darkMap) }
        val onClick = MapLibreMap.OnMapClickListener { tap ->
            // While riding a route the map only shows; taps do nothing.
            if (ridingNow.value) return@OnMapClickListener false
            if (marking) {
                if (ready == null) notify(regionStatus(resources, region), long = true) else onMarkTap(ready, tap)
                return@OnMapClickListener true
            }
            // A via point: select it, to remove just that one; any other
            // tap lets it go.
            val tappedVia = if (routeEnds != null && vias.isNotEmpty()) o.route.viaAt(m, tap) else null
            if (tappedVia != null && tappedVia in vias.indices) {
                selectedVia = tappedVia
                return@OnMapClickListener true
            }
            selectedVia = null
            // Another loop of the set, or route to choose, drawn faint:
            // show it.
            val start = loopStart
            val other = if (start != null && loops.size > 1) o.route.otherAt(m, tap) else null
            if (start != null && other != null && other in loops.indices) {
                loopIndex = other
                showLoop(start, loops, other)
                return@OnMapClickListener true
            }
            val ends = routeEnds
            val opts = shownRoute?.second
            val choice = if (ends != null && routeChoices.size > 1) o.route.otherAt(m, tap) else null
            if (ends != null && opts != null && choice != null && choice in routeChoices.indices) {
                showRouteChoice(ends.first, ends.second, routeChoices, choice, opts)
                return@OnMapClickListener true
            }
            val hit = o.sections.sectionAt(m, tap)?.let { id -> sections.firstOrNull { it.id == id } }
            val tapped = hit?.let {
                favouriteTap(
                    planning = routeEnds != null || loopStart != null || startPicked != null,
                    routeShown = shownSaved != null || shownRide != null,
                )
            }
            if (hit != null && tapped == FavouriteTap.OPEN) {
                showSection(hit, fit = false)
            } else if (hit != null) {
                // Beside what is open, which stays as it is; it replaces
                // a road's card, as one tapped thing's card does another's.
                roadInfo = null
                o.snap.clear()
                favouriteInfoId = hit.id
            } else if (ready == null) {
                notify(regionStatus(resources, region), long = true)
            } else {
                try {
                    val info = DebugTools.query("road info") { ready.engine.roadAt(tap.toLatLon()) }
                    o.snap.show(tap, LatLng(info.point.position.lat, info.point.position.lon))
                    // One card for what was tapped: the road's replaces a section's.
                    hideSection()
                    favouriteInfoId = null
                    roadInfo = info
                    message = null
                } catch (e: MotoException) {
                    o.snap.show(tap, null)
                    roadInfo = null
                    message = null
                    notify(coreErrorMessage(resources, e))
                }
            }
            true
        }
        val onLongClick = MapLibreMap.OnMapLongClickListener { point ->
            if (ridingNow.value) return@OnMapLongClickListener false
            if (marking) return@OnMapLongClickListener false
            if (ready == null) {
                notify(regionStatus(resources, region), long = true)
                return@OnMapLongClickListener true
            }
            val ends = routeEnds
            if (addingVia && ends != null) {
                addingVia = false
                message = null
                val added = insertVia(ends.first.toLatLon(), vias.map { it.toLatLon() }, ends.second.toLatLon(), point.toLatLon())
                viasBefore = vias
                vias = added.map { LatLng(it.lat, it.lon) }
                return@OnMapLongClickListener true
            }
            when (val step = picker.onLongPress(point)) {
                is RoutePicker.Step.StartSet -> {
                    routeEnds = null
                    routeThrough = null
                    loopStart = null
                    shownSaved = null
                    startPicked = null
                    // New start: check it lies on a road before keeping it.
                    val problem = runCatching { DebugTools.query("snap") { ready.engine.snap(point.toLatLon()) } }.exceptionOrNull()
                    if (problem != null) {
                        picker.reset()
                        message = null
                        notify(coreErrorMessage(resources, problem), long = true)
                    } else {
                        o.route.show(point, null, null)
                        startPicked = point
                        message = resources.getString(if (hasLocation) R.string.route_pick_end_or_me else R.string.route_pick_end)
                    }
                }
                is RoutePicker.Step.Complete -> {
                    // An end with no road near it changes nothing: the route,
                    // the sheet and its choices stay, and the rider picks
                    // another end (Stefan).
                    val problem = runCatching { DebugTools.query("snap") { ready.engine.snap(point.toLatLon()) } }.exceptionOrNull()
                    if (problem != null) {
                        notify(coreErrorMessage(resources, problem), long = true)
                        return@OnMapLongClickListener true
                    }
                    startPicked = null
                    // A new end: a route again, not a loop.
                    routeThrough = null
                    o.route.show(step.start, step.end, null)
                    message = null
                    // A new route, or the end moved: via points and an
                    // arrival time stay only for the same start.
                    if (routeEnds?.first != step.start) {
                        vias = emptyList()
                        arriveBy = null
                    }
                    // A new route starts its sheet empty; when the end moves
                    // the rows stay, dimmed, as for a setting (Stefan).
                    if (routeEnds == null) clearRoutes()
                    routeEnds = step.start to step.end
                }
            }
            true
        }
        m.addOnMapClickListener(onClick)
        m.addOnMapLongClickListener(onLongClick)
        onDispose {
            m.removeOnMapClickListener(onClick)
            m.removeOnMapLongClickListener(onLongClick)
        }
    }

    // Show the GPS position as soon as both the style and the permission
    // are there, following it. A pan or pinch leaves an overview of the
    // plan (the location button then starts from its first step).
    DisposableEffect(map, style, hasLocation) {
        val m = map
        val s = style
        if (m == null || s == null || !hasLocation) return@DisposableEffect onDispose {}
        enableLocation(context, m, s)
        // Start at the area's zoom, on the rider.
        followRider(m, locateZooms.area.toDouble())
        val moved = MapLibreMap.OnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) overviewShown = false
        }
        m.addOnCameraMoveStartedListener(moved)
        onDispose { m.removeOnCameraMoveStartedListener(moved) }
    }

    // Riding a route (ADR-0011): the map follows the rider, the way they
    // are going up (or north up), the rider low on the screen; a pan or
    // pinch stops it until Recentre. MapLibre's compass shows while the map
    // is turned, but takes no taps while riding: a tap would fix north up
    // with no way back (Stefan, 2026-10-03; a switch for that comes later).
    DisposableEffect(map, riding) {
        val compass = if (map != null) mapView.findCompass() else null
        compass?.isClickable = !riding
        onDispose { compass?.isClickable = true }
    }
    DisposableEffect(map, style, hasLocation, riding, turnMap, ridePanned, mapSize) {
        val m = map
        val s = style
        if (m == null || s == null || !hasLocation || !riding) return@DisposableEffect onDispose {}
        enableLocation(context, m, s)
        val lc = m.locationComponent
        lc.renderMode = RenderMode.GPS
        if (!ridePanned) {
            m.moveCamera(CameraUpdateFactory.paddingTo(0.0, riderTopPadding(mapSize.height).toDouble(), 0.0, 0.0))
            lc.cameraMode = if (turnMap) CameraMode.TRACKING_GPS else CameraMode.TRACKING
            if (!turnMap) m.animateCamera(CameraUpdateFactory.bearingTo(0.0))
        }
        val dismissed = object : OnCameraTrackingChangedListener {
            override fun onCameraTrackingDismissed() {
                if (ridingNow.value) ridePanned = true
            }

            override fun onCameraTrackingChanged(currentMode: Int) = Unit
        }
        lc.addOnCameraTrackingChangedListener(dismissed)
        onDispose { lc.removeOnCameraTrackingChangedListener(dismissed) }
    }
    // Riding over: the map's padding goes and the position shows as before.
    LaunchedEffect(map, riding) {
        val m = map ?: return@LaunchedEffect
        if (riding) return@LaunchedEffect
        m.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0))
        m.locationComponent.takeIf { it.isLocationComponentActivated }?.renderMode = RenderMode.COMPASS
    }
    // Zoom by speed while followed: closer when slow, wider at speed.
    val rideZoomTarget = if (riding) {
        Math.round(rideZoom((recording as? Recording.State.Active)?.lastFix?.speedMps, rideZoomOffset(rideZoomStep) + rideZoomNudge) * 4) / 4.0
    } else {
        0.0
    }
    LaunchedEffect(map, riding, ridePanned, rideZoomTarget) {
        val m = map ?: return@LaunchedEffect
        if (!riding || ridePanned) return@LaunchedEffect
        m.locationComponent.takeIf { it.isLocationComponentActivated }?.zoomWhileTracking(rideZoomTarget, 1_500)
    }
    /**
     * Runs [action] on the store off the main thread, then reloads the
     * sections and shows the message [action] returns.
     */
    fun changeSectionsThen(action: (SectionStore) -> String?) {
        val ready = store as? StoreState.Ready ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val done = action(ready.store)
                    done to ready.store.list(null)
                }
            }
            result.fold(
                onSuccess = { (done, list) ->
                    sections = list
                    // Saved or updated: a toast, like the menu pages (a
                    // delete has none: the bin already said "Deleted").
                    done?.let { Toasts.show(it) }
                },
                onFailure = { notify(resources.getString(R.string.sections_failed, it.message ?: it.toString()), long = true) },
            )
        }
    }

    /** Runs [action] on the store off the main thread, then reloads the sections. */
    fun changeSections(done: String?, action: (SectionStore) -> Unit) = changeSectionsThen {
        action(it)
        done
    }

    /** Hands [line] to a nav app as GPX named [gpxName]; [opts] place its
     * route points (see `Engine.routeGpx`). */
    fun shareLine(line: List<LatLon>, gpxName: String, opts: RouteOptions) {
        val engine = (region as? RegionState.Ready)?.engine ?: return
        scope.launch {
            val now = System.currentTimeMillis() / 1000
            val zone = ZoneId.systemDefault()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val gpx = DebugTools.query("route GPX", ::bytesSummary) { engine.routeGpx(line, gpxName, opts) }
                    RouteShare.prepare(
                        context,
                        gpx,
                        routeFileName(now, zone),
                        resources.getString(R.string.route_share_title),
                    )
                }
            }
            result.fold(
                onSuccess = { context.startActivity(it) },
                onFailure = {
                    notify(resources.getString(R.string.route_share_failed, it.message ?: it.toString()), long = true)
                },
            )
        }
    }

    /** Hands [r] to a nav app as GPX through the share sheet (PRD R9);
     * [opts] are the options it was found with. */
    fun shareRoute(r: Route, opts: RouteOptions) {
        val now = System.currentTimeMillis() / 1000
        shareLine(r.geometry, routeGpxName(now, ZoneId.systemDefault(), r.distanceM / 1000.0), opts)
    }

    /**
     * Plans a ride of section [s] from the rider's position: a loop out
     * through it and back ([loop]; both ways round for a two-way section),
     * or a route to its nearer end and along it. Shown in the route sheet.
     */
    fun rideSection(s: Section, loop: Boolean) {
        val from = riderStart() ?: return
        val oneWay = isOneWay(s.direction)
        val (near, far) = sectionEnds(s.geometry, oneWay, from.toLatLon()) ?: return
        dataPage = null
        sectionFilter = SectionFilter()
        hideSection()
        shownSaved = null
        roadInfo = null
        overlays?.snap?.clear()
        loopStart = null
        startPicked = null
        message = null
        arriveBy = null
        picker.reset()
        picker.startAt(from)
        val ends = LatLng(near.lat, near.lon) to LatLng(far.lat, far.lon)
        clearRoutes()
        if (loop) {
            vias = listOf(ends.first, ends.second)
            routeThrough = !oneWay
            overlays?.route?.show(from, null, null)
            routeEnds = from to from
        } else {
            vias = listOf(ends.first)
            routeThrough = null
            overlays?.route?.show(from, ends.second, null)
            routeEnds = from to ends.second
        }
    }

    /** Saves [r] (a loop when [isLoop]) as [name] in Routes & rides. */
    fun saveRoute(r: Route, isLoop: Boolean, name: String) {
        val ready = store as? StoreState.Ready ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { ready.store.saveRoute(name, isLoop, r) } }
            result.fold(
                onSuccess = { Toasts.show(resources.getString(R.string.route_saved, it.name)) },
                onFailure = { notify(resources.getString(R.string.route_save_failed, it.message ?: it.toString()), long = true) },
            )
        }
    }

    /** The task card's X: leaves the task in hand, as Back would. */
    fun cancelTask() {
        when {
            marking -> if (reviewTag != null) endReview(null) else {
                stopMarking()
                message = null
            }
            addingVia -> {
                addingVia = false
                message = null
            }
            selectedVia != null -> selectedVia = null
            startPicked != null -> {
                startPicked = null
                picker.reset()
                overlays?.route?.show(null, null, null)
                message = null
            }
            else -> message = null
        }
    }

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
        val topButtons = !marking && !planning && !riding
        if (topButtons) {
            if (store is StoreState.Ready) {
                TopMapButton(
                    icon = R.drawable.ic_menu,
                    description = stringResource(R.string.menu_open),
                    onClick = { menuOpen = true },
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
            TopMapButton(
                icon = R.drawable.ic_settings,
                description = stringResource(R.string.ride_settings_open),
                onClick = { showSettings = true },
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        // Riding a route: the card at the top, the map's compass below it
        // on the right (ADR-0011).
        if (following != null) {
            RideCard(
                following = following,
                darkMap = darkMap,
                onStopFollowing = { RecordingService.unfollow(context) },
                onStopNow = { RecordingService.stop(context) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .safeDrawingPadding()
                    .padding(top = 8.dp, start = 8.dp, end = 8.dp)
                    .widthIn(max = TOP_BOX_MAX_WIDTH)
                    .fillMaxWidth()
                    .onGloballyPositioned { rideCardBottom = it.boundsInRoot().bottom.roundToInt() },
            )
        }
        // The scale (Stefan, 2026-10-03): at the bottom, a little above
        // the navigation bar or the planning sheet, just right of the map's
        // logo and attribution and below the tag button, clear of the
        // position button; cards drawn later cover it.
        val scaleAbove = with(density) {
            val sheet = if (planning && sheetTop < mapSize.height) mapSize.height - sheetTop else 0
            max(insets.bottom, sheet).toDp()
        }
        ScaleBar(
            metresPerDp = metresPerDp,
            darkMap = darkMap,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(start = SCALE_START, bottom = scaleAbove + SCALE_BOTTOM),
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
        val taskCard = message != null || marking || offerLoop || via != null
        // The buttons at the bottom step aside for them, as for planning.
        val cardsShown = taskCard || shownRide != null || shownSaved != null || shownSection != null || roadInfo != null ||
            (favouriteInfo != null && !riding)
        // The ridden roads button, above the sheet on the right: switches
        // the layer, and Ride settings' switch with it. Cards above the
        // sheet take its place.
        if (riddenButton && !cardsShown) {
            val aboveSheet = with(density) {
                if (sheetTop < mapSize.height) (mapSize.height - sheetTop).toDp() else 0.dp
            }
            RiddenButton(
                on = riddenOn,
                onChange = { riddenWhilePlanning = it },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(end = FAB_PADDING, bottom = aboveSheet + FAB_PADDING),
            )
        }
        if (cardsShown) {
            DisposableEffect(Unit) { onDispose { cardsTop = Int.MAX_VALUE } }
            val aboveSheet = with(density) {
                if (planning && sheetTop < mapSize.height) (mapSize.height - sheetTop).toDp() else 0.dp
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .then(if (planning) Modifier else Modifier.safeDrawingPadding())
                    .padding(start = 8.dp, end = 8.dp, bottom = aboveSheet + 8.dp)
                    .widthIn(max = TOP_BOX_MAX_WIDTH)
                    .fillMaxWidth()
                    .onGloballyPositioned { cardsTop = it.boundsInRoot().top.roundToInt() },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // A favourite's facts sit on top: it was looked at beside
                // whatever is in hand, which stays nearest the sheet.
                if (!riding) favouriteInfo?.let { f ->
                    FavouriteInfoCard(
                        f,
                        engine = (region as? RegionState.Ready)?.engine,
                        darkMap = darkMap,
                        onClose = { favouriteInfoId = null },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (taskCard) Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 3.dp,
                    shadowElevation = 3.dp,
                ) {
                    Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
                        // What to do next, with the X at the top right (like
                        // the route and loop cards): it leaves the task.
                        Row(verticalAlignment = Alignment.Top) {
                            IconText(
                                message ?: via?.let { stringResource(R.string.route_via_selected, it + 1) } ?: "",
                                modifier = Modifier.weight(1f).padding(top = 12.dp, end = 4.dp),
                            )
                            if (via != null && message == null) {
                                // The bin removes the waypoint; X (or a tap
                                // elsewhere on the map) lets it go.
                                IconButton(
                                    onClick = {
                                        selectedVia = null
                                        vias = removeVia(vias, via)
                                    },
                                    colors = IconButtonDefaults.iconButtonColors(contentColor = DELETE_COLOR),
                                ) {
                                    Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.route_via_remove))
                                }
                            }
                            IconButton(onClick = { cancelTask() }) {
                                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.task_close))
                            }
                        }
                        if (offerLoop) {
                            FlowRow(
                                Modifier.padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                // Loop first (Stefan), then route from the
                                // rider's position.
                                IconTextButton(R.drawable.ic_loop, stringResource(R.string.loop_from_here)) {
                                    val start = picker.takeStart() ?: return@IconTextButton
                                    message = null
                                    startLoop(start)
                                }
                                if (hasLocation) {
                                    // The long-pressed point becomes the end; the
                                    // rider's position the start. A later
                                    // long-press moves the end, as usual.
                                    IconTextButton(R.drawable.ic_directions, stringResource(R.string.route_from_me)) {
                                        val from = riderStart() ?: return@IconTextButton
                                        val to = picker.takeStart() ?: return@IconTextButton
                                        picker.startAt(from)
                                        startPicked = null
                                        message = null
                                        overlays?.route?.show(from, to, null)
                                        vias = emptyList()
                                        arriveBy = null
                                        if (routeEnds == null) clearRoutes()
                                        routeEnds = from to to
                                    }
                                }
                            }
                        }
                        if (marking) {
                            val tag = reviewTag
                            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (tag != null) {
                                    // Discards this tag (second tap) and moves on;
                                    // X or Back ends the review, leaving the tags
                                    // not yet handled pending.
                                    var confirming by remember(tag.id) { mutableStateOf(false) }
                                    DeleteButton(
                                        confirming = confirming,
                                        onArm = { confirming = true },
                                        onDelete = { finishTag(TagStatus.DISCARDED) },
                                        enabled = !proposing,
                                    )
                                }
                                // The buttons wrap onto a second line when there is
                                // no room, instead of squeezing each other.
                                FlowRow(
                                    Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    if (tag != null) {
                                        // Leaves this one pending and moves on.
                                        OutlinedButton(onClick = { skipTag() }) {
                                            OneLine(stringResource(R.string.tag_review_skip))
                                        }
                                    }
                                    Button(
                                        onClick = { savingDraft = true },
                                        enabled = draft != null && !proposing,
                                    ) { OneLine(stringResource(R.string.section_save_ellipsis)) }
                                }
                            }
                        }
                    }
                }
                shownRide?.let {
                    ShownRideCard(
                        it,
                        onClose = { shownRide = null },
                        onRename = { renamingRide = it.track },
                        onShare = { shareRide(it.track) },
                        onDelete = { deleteShownRide(it.track) },
                        modifier = Modifier.fillMaxWidth(),
                        onRide = if (it.track.endedAt != null) ({ rideAgain(it) }) else null,
                    )
                }
                shownSaved?.let { s ->
                    SavedRouteCard(
                        s,
                        onShare = {
                            shareLine(s.line, s.route.name, routeOptions(defaultRouteOptions(), ROUTE_EXTRA_PERCENT, gravel, avoid))
                        },
                        onRename = { renamingSaved = s.route },
                        onDelete = { deleteShownSaved(s.route) },
                        onClose = {
                            shownSaved = null
                            overlays?.route?.show(null, null, null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        onRide = {
                            beginRide(
                                RideRoute(s.route.name, s.route.isLoop, s.route.durationS, s.line, s.favouriteParts, s.favouriteRatings),
                            )
                        },
                    )
                }
                shownSection?.let { s ->
                    ShownSectionCard(
                        s,
                        engine = (region as? RegionState.Ready)?.engine,
                        onLoop = if (hasLocation) ({ rideSection(s, loop = true) }) else null,
                        onRide = if (hasLocation) ({ rideSection(s, loop = false) }) else null,
                        onEdit = { editing = s },
                        onClose = { hideSection() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                roadInfo?.let {
                    RoadInfoCard(
                        it,
                        onClose = {
                            roadInfo = null
                            overlays?.snap?.clear()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        // Back steps back through what is on the map before it leaves the
        // app (a pulled-up sheet handles Back itself first).
        BackHandler(enabled = marking || addingVia || selectedVia != null || roadInfo != null || favouriteInfoId != null || planning ||
            startPicked != null || shownRide != null || shownSaved != null || shownSectionId != null) {
            when {
                marking -> markBack()
                addingVia -> {
                    addingVia = false
                    message = null
                }
                selectedVia != null -> selectedVia = null
                roadInfo != null -> {
                    roadInfo = null
                    overlays?.snap?.clear()
                }
                favouriteInfoId != null -> favouriteInfoId = null
                routeEnds != null -> closeRoute()
                loopStart != null -> closeLoop()
                startPicked != null -> {
                    startPicked = null
                    picker.reset()
                    overlays?.route?.show(null, null, null)
                    message = null
                }
                shownSaved != null -> {
                    shownSaved = null
                    overlays?.route?.show(null, null, null)
                }
                shownSectionId != null -> hideSection()
                shownRide != null -> shownRide = null
            }
        }
        // Planning a route or loop: the sheet at the bottom; the map fits
        // what it plans in the space above it.
        if (planning) {
            DisposableEffect(Unit) { onDispose { sheetTop = Int.MAX_VALUE } }
            val sheetMaxHeight = with(density) {
                if (mapSize.height > 0) (mapSize.height * SHEET_MAX_SHARE).toDp() else 600.dp
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = TOP_BOX_MAX_WIDTH)
                    .fillMaxWidth()
                    .onGloballyPositioned { sheetTop = it.boundsInRoot().top.roundToInt() },
            ) {
                    routeEnds?.let {
                        RouteCard(
                            expanded = cardExpanded,
                            onExpandedChange = { cardExpanded = it },
                            maxHeight = sheetMaxHeight,
                            summary = routeSummary,
                            gravel = gravel,
                            onGravel = { g ->
                                gravel = g
                                RoutePrefs.setGravel(context, g)
                            },
                            favourites = favouritesMode,
                            onFavourites = { changeFavourites(it) },
                            unridden = unriddenMode,
                            onUnridden = { changeUnridden(it) },
                            avoid = avoid,
                            onAvoid = { changeAvoid(it) },
                            onClose = { closeRoute() },
                            onShare = { shownRoute?.let { (r, opts) -> shareRoute(r, opts) } },
                            onSave = { shownRoute?.let { (r, _) -> savingRoute = r to (routeThrough != null) } },
                            onRide = { shownRoute?.let { (r, _) -> startRide(r, routeThrough != null) } },
                            viaCount = vias.size,
                            onAddVia = {
                                addingVia = true
                                message = resources.getString(R.string.route_pick_via)
                            },
                            onClearVia = {
                                vias = emptyList()
                                addingVia = false
                            },
                            arriveBy = arriveBy,
                            arrivalNote = arriveBy?.let { by ->
                                shownRoute?.let { (r, _) ->
                                    val zone = ZoneId.systemDefault()
                                    val a = arrival(routeFoundAt, r.durationS, by)
                                    if (a.late) {
                                        stringResource(R.string.route_arrives_late, clockTime(by, zone), clockTime(a.atSec, zone))
                                    } else {
                                        stringResource(R.string.route_arrives, clockTime(a.atSec, zone))
                                    }
                                }
                            },
                            arrival = arriveBy?.let { by ->
                                shownRoute?.let { (r, _) -> SummaryItem.ArrivesAt(arrival(routeFoundAt, r.durationS, by), by) }
                            },
                            onArriveBy = { arriveBy = it },
                            position = routeIndex,
                            count = routeChoices.size,
                            onPrevious = {
                                val opts = shownRoute?.second
                                if (opts != null) {
                                    showRouteChoice(it.first, it.second, routeChoices, previousLoop(routeIndex, routeChoices.size), opts)
                                }
                            },
                            onNext = {
                                val opts = shownRoute?.second
                                if (opts != null) {
                                    showRouteChoice(it.first, it.second, routeChoices, nextLoop(routeIndex, routeChoices.size), opts)
                                }
                            },
                            kept = routeKept,
                            problem = routeProblem,
                        )
                    }
                    loopStart?.let {
                        val shown = loops.getOrNull(loopIndex)
                        LoopCard(
                            expanded = cardExpanded,
                            onExpandedChange = { cardExpanded = it },
                            maxHeight = sheetMaxHeight,
                            summary = shown?.let { r ->
                                summarize(r.distanceM, r.durationS, r.favouriteShare, r.durationS, r.curvyShare, r.unpavedM, r.tollM, r.unriddenShare)
                            },
                            problem = loopProblem,
                            position = loopIndex,
                            count = loops.size,
                            onShuffle = { loopSeed = nextSeed },
                            kept = loopKept,
                            direction = loopDirection,
                            onDirection = { loopDirection = it },
                            onPrevious = {
                                loopIndex = previousLoop(loopIndex, loops.size)
                                showLoop(it, loops, loopIndex)
                            },
                            onNext = {
                                loopIndex = nextLoop(loopIndex, loops.size)
                                showLoop(it, loops, loopIndex)
                            },
                            choice = loopChoice,
                            onChoice = { c -> loopLength.pick(c) },
                            gravel = gravel,
                            onGravel = { g ->
                                gravel = g
                                RoutePrefs.setGravel(context, g)
                            },
                            favourites = favouritesMode,
                            onFavourites = { changeFavourites(it) },
                            unridden = unriddenMode,
                            onUnridden = { changeUnridden(it) },
                            avoid = avoid,
                            onAvoid = { changeAvoid(it) },
                            onClose = { closeLoop() },
                            onShare = { if (shown != null) loopOpts?.let { opts -> shareRoute(shown, opts) } },
                            onSave = { shown?.let { savingRoute = it to true } },
                            onRide = { shown?.let { startRide(it, true) } },
                        )
                    }
            }
        }
        if (!marking && !planning && !cardsShown) {
            DisposableEffect(Unit) { onDispose { buttonsLeft = Int.MAX_VALUE } }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .safeDrawingPadding()
                    .padding(16.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Loops from where the rider is, in one tap: a new set
                // each time. Not while riding a route.
                if (region is RegionState.Ready && hasLocation && !riding) {
                    FloatingActionButton(onClick = {
                        val start = riderStart() ?: return@FloatingActionButton
                        picker.reset()
                        startLoop(start, shuffleSeed())
                    }) {
                        Icon(painterResource(R.drawable.ic_loop), contentDescription = stringResource(R.string.loop_from_me))
                    }
                if (store is StoreState.Ready && region is RegionState.Ready) {
                    FloatingActionButton(onClick = {
                        marker.begin()
                        markSession++
                        marking = true
                        draft = null
                        showDraft()
                        message = resources.getString(R.string.section_pick_start)
                    }) {
                        Icon(painterResource(R.drawable.ic_add_road), contentDescription = stringResource(R.string.section_mark))
                    }
                }
                }
                // While riding or recording: + and −, as a pinch is hard
                // with gloves. Followed on a route they change its zoom for
                // the rest of the ride; otherwise they zoom the map.
                if (recording is Recording.State.Active) {
                    ZoomButtons(
                        onZoom = { by ->
                            if (riding && !ridePanned) {
                                rideZoomNudge = nudgeRideZoom(rideZoomNudge, by)
                            } else {
                                map?.animateCamera(CameraUpdateFactory.zoomBy(by))
                            }
                        },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                // The bottom row: the position button left of Record (or
                // Stop), so neither moves when the position button hides
                // (Stefan, 2026-10-03). It is the location button, or while
                // riding Recentre after a pan or pinch (hidden while the map
                // follows the rider).
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
                    if (hasLocation && !riding) {
                        FloatingActionButton(onClick = { onLocateTap() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_my_location),
                                contentDescription = stringResource(R.string.my_location),
                            )
                        }
                    } else if (hasLocation && riding && ridePanned) {
                        FloatingActionButton(onClick = { ridePanned = false }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_my_location),
                                contentDescription = stringResource(R.string.ride_recentre),
                            )
                        }
                    }
                    // Record: a red dot. While recording: a stop square with
                    // a red arc running round the button, the same size as
                    // the others; the distance is in the notification.
                    // Riding to a route's start records nothing yet: Record
                    // is still there, to record the way there too.
                    // Maps fit left of this column (the position button
                    // beside Record sits low, where little is fitted).
                    val active = (recording as? Recording.State.Active)?.takeIf { it.trackId != null }
                    Box(Modifier.onGloballyPositioned { buttonsLeft = it.boundsInRoot().left.roundToInt() }) {
                        if (active != null) {
                            RecordingButton(
                                onStop = { RecordingService.stop(context) },
                                description = stringResource(R.string.record_stop_description, sectionKm(active.distanceM)),
                            )
                        } else {
                            FloatingActionButton(onClick = { recordPermissions.launch(recordingPermissions()) }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_record_dot),
                                    contentDescription = stringResource(R.string.record_start),
                                    tint = RECORD_RED,
                                )
                            }
                        }
                    }
                }
            }
        }
        // Quick-tag (PRD R3): one big button, usable with gloves, whenever the
        // map is open. Bottom left, above the map's logo and attribution;
        // the tags waiting for review above it (the right-hand column has
        // no room to spare in landscape).
        if (store is StoreState.Ready && !marking && !planning && !cardsShown) {
            DisposableEffect(Unit) { onDispose { tagTop = Int.MAX_VALUE } }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .safeDrawingPadding()
                    .padding(start = 16.dp, bottom = 40.dp)
                    .onGloballyPositioned { tagTop = it.boundsInRoot().top.roundToInt() },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (pendingTags > 0 && recording !is Recording.State.Active && region is RegionState.Ready) {
                    // A flag (the spots tagged on rides) with how many wait,
                    // the count on the button's corner, clear of the flag.
                    BadgedBox(badge = { Badge { Text(badgeCount(pendingTags)) } }) {
                        FloatingActionButton(onClick = { startReview() }) {
                            Icon(
                                painterResource(R.drawable.ic_flag),
                                contentDescription = pluralStringResource(R.plurals.tags_review, pendingTags, pendingTags),
                            )
                        }
                    }
                }
                LargeFloatingActionButton(
                    onClick = { quickTag() },
                    shape = CircleShape,
                    containerColor = TAG_COLOR,
                    contentColor = Color.White,
                    modifier = Modifier
                        .size(TAG_BUTTON_SIZE)
                        .semantics { contentDescription = resources.getString(R.string.tag_button_description) },
                ) {
                    Text(stringResource(R.string.tag_button), style = MaterialTheme.typography.titleLarge)
                }
            }
        }
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
                    MenuTopic.ABOUT -> showAbout = true
                    MenuTopic.DEBUG -> showDebug = true
                }
            },
        )
    }
    if (showHelp) HelpPage(onDismiss = { showHelp = false })
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
    if (showDebug) DebugTools.Page(onDismiss = { showDebug = false })

    // Rate and save the proposed section, and name it if the rider likes
    // (else it goes by where it runs, suggested in the empty name field).
    val proposed = draft
    var draftWords by remember { mutableStateOf<Description?>(null) }
    LaunchedEffect(savingDraft, proposed) {
        draftWords = null
        val line = proposed?.geometry?.takeIf { savingDraft } ?: return@LaunchedEffect
        val engine = (region as? RegionState.Ready)?.engine ?: return@LaunchedEffect
        draftWords = withContext(Dispatchers.Default) { runCatching {
            DebugTools.query("draft name", ::descriptionSummary) { engine.describe(line) }
        }.getOrNull() }
    }
    if (savingDraft && proposed != null) {
        val tag = reviewTag
        SectionSheet(
            title = stringResource(R.string.section_new_title, sectionKm(proposed.distanceM)),
            initial = SectionChoice(rating = Rating.GOOD, oneWay = false),
            suggestion = sectionSuggestedName(draftWords),
            onDismiss = { savingDraft = false },
            onSave = { choice ->
                if (tag == null) stopMarking() else savingDraft = false
                val name = sectionNameToStore(choice.name).ifEmpty {
                    autoSectionName(
                        fromTag = tag != null,
                        savedAtSec = System.currentTimeMillis() / 1000,
                        distanceM = proposed.distanceM,
                        zone = ZoneId.systemDefault(),
                    )
                }
                changeSectionsThen { st ->
                    val result = st.add(
                        NewSection(
                            name = name,
                            rating = choice.rating,
                            direction = directionOf(choice.oneWay),
                            source = if (tag != null) SectionSource.TAG else SectionSource.MAP,
                            ways = proposed.ways,
                            geometry = proposed.geometry,
                        ),
                    )
                    when (val outcome = addOutcome(result)) {
                        AddOutcome.Covered -> resources.getString(R.string.section_covered)
                        is AddOutcome.Saved -> if (outcome.replaced == 0) {
                            resources.getString(R.string.section_saved)
                        } else {
                            resources.getQuantityString(R.plurals.section_saved_replacing, outcome.replaced, outcome.replaced)
                        }
                    }
                }
                if (tag != null) finishTag(TagStatus.USED)
            },
        )
    }

    // The name offered for a route or loop being saved: from where it runs
    // ("Lund → Höör", "Loop from Lund via Höör") when the region has
    // names, else its time and length. Found off the main thread first.
    var savingName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(savingRoute) {
        savingName = null
        val (r, isLoop) = savingRoute ?: return@LaunchedEffect
        val engine = (region as? RegionState.Ready)?.engine
        val named = engine?.let { e ->
            withContext(Dispatchers.Default) {
                runCatching {
                    DebugTools.query("plan name", { n -> if (n == null) "no name" else "named" }) {
                        val far = if (isLoop) farthestPoint(r.geometry)?.let { p -> e.describe(listOf(p, p)) } else null
                        planName(isLoop, e.describe(r.geometry), far)
                    }
                }.getOrNull()
            }
        }
        savingName = named?.let { planNameText(resources, it) }
            ?: defaultRouteName(System.currentTimeMillis() / 1000, ZoneId.systemDefault(), r.distanceM / 1000.0, isLoop)
    }
    savingRoute?.let { (r, isLoop) ->
        val initial = savingName ?: return@let
        RouteNameDialog(
            title = stringResource(R.string.route_save_title),
            initial = initial,
            onDismiss = { savingRoute = null },
            onSave = { name ->
                savingRoute = null
                saveRoute(r, isLoop, name)
            },
        )
    }
    renamingRide?.let { t ->
        RouteNameDialog(
            title = stringResource(R.string.ride_rename_title),
            initial = rideName(t.name, t.startedAt, ZoneId.systemDefault()),
            onDismiss = { renamingRide = null },
            onSave = { name ->
                renamingRide = null
                val s = (store as? StoreState.Ready)?.store ?: return@RouteNameDialog
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { s.renameTrack(t.id, name) } }
                    result.onFailure { notify(resources.getString(R.string.route_save_failed, it.message ?: it.toString()), long = true) }
                    val now = shownRide
                    if (result.isSuccess && now != null && now.track.id == t.id) {
                        shownRide = now.copy(track = now.track.copy(name = name))
                    }
                }
            },
        )
    }
    renamingSaved?.let { r ->
        RouteNameDialog(
            title = stringResource(R.string.saved_route_rename_title),
            initial = r.name,
            onDismiss = { renamingSaved = null },
            onSave = { name ->
                renamingSaved = null
                val s = (store as? StoreState.Ready)?.store ?: return@RouteNameDialog
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { s.renameRoute(r.id, name) } }
                    result.onFailure { notify(resources.getString(R.string.route_save_failed, it.message ?: it.toString()), long = true) }
                    val now = shownSaved
                    if (result.isSuccess && now != null && now.route.id == r.id) {
                        shownSaved = now.copy(route = now.route.copy(name = name))
                    }
                }
            },
        )
    }

    // Recorded rides: export as GPX or delete.
    val readyStore = store as? StoreState.Ready
    val page = dataPage
    if (page != null && readyStore != null) {
        RidesSheet(
            page = page,
            store = readyStore.store,
            engine = (region as? RegionState.Ready)?.engine,
            onSectionsChanged = {
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { readyStore.store.list(null) } }
                        .onSuccess { sections = it }
                }
            },
            onDismiss = {
                dataPage = null
                sectionFilter = SectionFilter()
            },
            onShowRoute = { saved ->
                dataPage = null
                scope.launch {
                    val line = withContext(Dispatchers.IO) {
                        runCatching { readyStore.store.routeGeometry(saved.id) }.getOrNull()
                    }
                    if (line.isNullOrEmpty()) {
                        notify(resources.getString(R.string.rides_gone), long = true)
                    } else {
                        clearForShown()
                        shownSaved = ShownSavedRoute(saved, line)
                        val start = LatLng(line.first().lat, line.first().lon)
                        val end = if (saved.isLoop) null else LatLng(line.last().lat, line.last().lon)
                        overlays?.route?.show(start, end, line)
                        showOnMap(listOf(line), always = true, minSpanM = SHOWN_FIT_SPAN_M)
                    }
                }
            },
            onShow = { track ->
                dataPage = null
                scope.launch {
                    // Split where recording started again after a gap.
                    val segments = withContext(Dispatchers.IO) {
                        runCatching { readyStore.store.trackSegments(track.id) }.getOrNull()
                    }?.map { s -> s.map { it.position } }
                    val line = segments?.flatten()
                    if (line.isNullOrEmpty()) {
                        notify(resources.getString(R.string.rides_gone), long = true)
                    } else {
                        clearForShown()
                        shownRide = ShownRide(track, line, segments)
                        showOnMap(listOf(line), always = true, minSpanM = SHOWN_FIT_SPAN_M)
                    }
                }
            },
            gravel = gravel,
            avoid = avoid,
            sections = sections,
            sectionFilter = sectionFilter,
            onSectionFilter = { sectionFilter = it },
            here = mapFix(map)?.position,
            onShowSection = { s ->
                dataPage = null
                shownFromPage = s.id to sectionFilter
                sectionFilter = SectionFilter()
                showSection(s, fit = true)
            },
            sectionActions = { s, close ->
                if (hasLocation) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.section_loop_through)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_loop), contentDescription = null) },
                        onClick = {
                            close()
                            rideSection(s, loop = true)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.section_ride_from_here)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_directions), contentDescription = null) },
                        onClick = {
                            close()
                            rideSection(s, loop = false)
                        },
                    )
                }
            },
            onDeleteSections = { ids ->
                if (shownSectionId in ids) hideSection()
                changeSections(null) { st -> ids.forEach { st.delete(it) } }
            },
        )
    }
    // A ride that followed a route when Android stopped the app: carry on
    // into it, or save it (ADR-0011).
    val interrupted by Recording.interrupted.collectAsState()
    var interruptedName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(interrupted, store) {
        val id = interrupted
        val s = (store as? StoreState.Ready)?.store
        interruptedName = if (id == null || s == null) {
            null
        } else {
            withContext(Dispatchers.IO) { runCatching { s.followedRoute(id)?.name }.getOrNull() } ?: ""
        }
    }
    val waiting = interrupted
    val waitingName = interruptedName
    if (waiting != null && waitingName != null && recording !is Recording.State.Active) {
        ResumeRideDialog(
            name = waitingName,
            onCarryOn = {
                Recording.answered()
                pendingResume = waiting
                recordPermissions.launch(recordingPermissions())
            },
            onSave = {
                Recording.answered()
                val s = (store as? StoreState.Ready)?.store
                val engine = (region as? RegionState.Ready)?.engine
                if (s != null) {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                s.finishTrack(waiting)?.let { t -> if (engine != null) s.nameRide(resources, engine, t) }
                            }
                        }
                        RideChanges.changed()
                        Toasts.show(resources.getString(R.string.recording_saved))
                    }
                }
            },
        )
    }
    if (showSettings) {
        RideSettingsPage(
            settings = RideSettings(
                loopChoice, defaultDirection, gravel, favouritesMode, unriddenMode, avoid, locateZooms, keepScreenOn, showRidden,
                turnMap = turnMap,
                rideZoomStep = rideZoomStep,
                offRouteAlert = offRouteAlert,
            ),
            onChange = { new ->
                if (new.gravel != gravel) {
                    gravel = new.gravel
                    RoutePrefs.setGravel(context, new.gravel)
                }
                if (new.favourites != favouritesMode) changeFavourites(new.favourites)
                if (new.unridden != unriddenMode) changeUnridden(new.unridden)
                if (new.avoid != avoid) changeAvoid(new.avoid)
                loopLength.pick(new.loopLength)
                if (new.loopDirection != defaultDirection) {
                    defaultDirection = new.loopDirection
                    RoutePrefs.setLoopDirection(context, new.loopDirection)
                }
                if (new.zooms != locateZooms) {
                    locateZooms = new.zooms
                    RoutePrefs.setLocateZooms(context, new.zooms)
                }
                if (new.keepScreenOn != keepScreenOn) {
                    keepScreenOn = new.keepScreenOn
                    RoutePrefs.setKeepScreenOn(context, new.keepScreenOn)
                }
                if (new.showRidden != showRidden) {
                    showRidden = new.showRidden
                    RoutePrefs.setShowRidden(context, new.showRidden)
                }
                if (new.rideZoomStep != rideZoomStep) {
                    rideZoomStep = new.rideZoomStep
                    RoutePrefs.setRideZoomStep(context, new.rideZoomStep)
                }
                if (new.turnMap != turnMap) {
                    turnMap = new.turnMap
                    RoutePrefs.setTurnMap(context, new.turnMap)
                }
                if (new.offRouteAlert != offRouteAlert) {
                    offRouteAlert = new.offRouteAlert
                    RoutePrefs.setOffRouteAlert(context, new.offRouteAlert)
                }
            },
            onDismiss = { showSettings = false },
        )
    }

    // Change or delete a saved section: the map fits it above the sheet
    // once the sheet has come up (its top edge settles). The direction
    // shown goes back to the saved one when the sheet is left, or once
    // the saved sections have reloaded after Save (so a turned section
    // doesn't flip back and forth).
    LaunchedEffect(sections, shownSectionId) { if (editing == null) editPreview = null }
    LaunchedEffect(editing?.id, editTop) {
        val section = editing
        if (section == null) {
            editTop = Int.MAX_VALUE
            return@LaunchedEffect
        }
        if (editTop >= mapSize.height) return@LaunchedEffect
        delay(EDIT_FIT_SETTLE_MS)
        showOnMap(listOf(section.geometry), always = true)
    }
    editing?.let { section ->
        SectionSheet(
            title = stringResource(R.string.section_edit_title, sectionKm(lengthM(section.geometry))),
            initial = SectionChoice(section.rating, isOneWay(section.direction), riderName(section.name) ?: ""),
            suggestion = sectionSuggestedName(SectionDescriptions.cached((region as? RegionState.Ready)?.engine, section)),
            onDismiss = {
                editing = null
                editPreview = null
            },
            canReverse = true,
            onPreview = { oneWay, turn -> editPreview = oneWay to turn },
            onTop = { editTop = it },
            onSave = { choice ->
                editing = null
                changeSections(resources.getString(R.string.section_updated)) { st ->
                    st.update(
                        section.id,
                        SectionUpdate(
                            name = nameUpdate(section.name, choice.name),
                            rating = choice.rating,
                            direction = directionOf(choice.oneWay),
                            reverse = choice.reverse,
                        ),
                    )
                }
            },
            onDelete = {
                editing = null
                if (shownSectionId == section.id) hideSection()
                changeSections(null) { st ->
                    st.delete(section.id)
                }
                // Shown from the Sections page: back to it, as it was.
                pageAfterDelete(shownFromPage, section.id)?.let {
                    sectionFilter = it
                    dataPage = DataPage.SECTIONS
                }
                shownFromPage = null
            },
        )
    }
}

/** The map's last known location as a fix, if it has one. */
private fun mapFix(map: MapLibreMap?): TrackPoint? {
    val lc = map?.locationComponent ?: return null
    if (!lc.isLocationComponentActivated) return null
    val l = lc.lastKnownLocation ?: return null
    return checkedFix(
        timeMs = l.time,
        lat = l.latitude,
        lon = l.longitude,
        accuracyM = if (l.hasAccuracy()) l.accuracy.toDouble() else null,
        speedMps = if (l.hasSpeed()) l.speed.toDouble() else null,
        bearingDeg = if (l.hasBearing()) l.bearing.toDouble() else null,
    )
}

/** The round buttons at the top of the map. */
private val TOP_BUTTON_SIZE = 48.dp
private val TOP_BUTTON_MARGIN = 16.dp

/** A round button at the top of the map (menu, ride settings). */
@Composable
private fun TopMapButton(icon: Int, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .safeDrawingPadding()
            .padding(top = 8.dp, start = TOP_BUTTON_MARGIN, end = TOP_BUTTON_MARGIN)
            .size(TOP_BUTTON_SIZE)
            .semantics { contentDescription = description },
        shape = CircleShape,
        // The accent colour, as the other map buttons (Material's floating
        // action buttons): lilac on the light theme, deep purple on the dark.
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null)
        }
    }
}

/**
 * Shows or hides the ridden roads (ADR-0010) while planning: the size of
 * the top buttons, in the accent colour when on (as the other map
 * buttons), the plain surface when off. A screen reader says "Show ridden
 * roads" with the switch's state.
 */
@Composable
private fun RiddenButton(on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.settings_show_ridden)
    Surface(
        checked = on,
        onCheckedChange = onChange,
        modifier = modifier
            .size(TOP_BUTTON_SIZE)
            .semantics { contentDescription = description },
        shape = CircleShape,
        color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_ridden), contentDescription = null)
        }
    }
}

/** Quick-tag button: large enough to hit with gloves on. */
private val TAG_BUTTON_SIZE: Dp = 96.dp
internal val TAG_COLOR = Color(0xFFE8710A)

/** The map layers the screen draws into, created once per style. */
private class Overlays(
    val sections: SectionOverlay,
    val ride: RideOverlay,
    val follow: FollowOverlay,
    val draft: SectionDraftOverlay,
    val route: RouteOverlay,
    val ridden: RiddenOverlay,
    val snap: SnapMarker,
)

/** What to say while the region is not ready to use. */
private fun regionStatus(res: Resources, region: RegionState): String = when (region) {
    RegionState.Loading -> res.getString(R.string.region_loading)
    RegionState.Missing -> res.getString(R.string.region_missing)
    is RegionState.Failed -> res.getString(R.string.region_failed, region.message)
    is RegionState.Ready -> res.getString(R.string.map_hint)
}

/** A short message for an error from the core. */
private fun coreErrorMessage(res: Resources, e: Throwable): String = when (classify(e)) {
    CoreProblem.OUTSIDE_REGION -> res.getString(R.string.region_outside)
    CoreProblem.NO_ROUTE -> res.getString(R.string.route_none)
    CoreProblem.NO_ROAD_NEARBY -> e.message ?: e.toString()
    CoreProblem.OTHER -> res.getString(R.string.snap_error, e.message ?: e.toString())
}

private fun LatLng.toLatLon() = LatLon(latitude, longitude)

/** MapLibre's compass in this map, once it has made one. */
private fun MapView.findCompass(): CompassView? {
    fun find(v: android.view.View): CompassView? = when (v) {
        is CompassView -> v
        is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
        else -> null
    }
    return find(this)
}

/**
 * Samples the map pixels behind the status bar and switches the status bar
 * icons between light and dark to contrast with them: whenever the map
 * becomes idle, and at most every [SAMPLE_INTERVAL_MS] while the camera moves.
 */
@Composable
private fun StatusBarIconsFollowMap(mapView: MapView, map: MapLibreMap?, statusBarHeight: Int) {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(mapView, map, statusBarHeight) {
        if (map == null || statusBarHeight <= 0) return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val handler = Handler(Looper.getMainLooper())
        val sample = createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT)
        val pixels = IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)
        var lastSampleAt = 0L

        fun update() {
            val surface = mapView.findSurfaceView() ?: return
            if (surface.width <= 0 || !surface.holder.surface.isValid) return
            lastSampleAt = SystemClock.uptimeMillis()
            // PixelCopy scales the status bar strip down into the small bitmap.
            val strip = Rect(0, 0, surface.width, statusBarHeight.coerceAtMost(surface.height))
            PixelCopy.request(surface, strip, sample, { result ->
                if (result != PixelCopy.SUCCESS) return@request
                sample.getPixels(pixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT)
                controller.isAppearanceLightStatusBars =
                    wantsDarkIcons(averageLuma(pixels), controller.isAppearanceLightStatusBars)
            }, handler)
        }

        val onIdle = MapView.OnDidBecomeIdleListener { update() }
        val onMove = MapLibreMap.OnCameraMoveListener {
            if (SystemClock.uptimeMillis() - lastSampleAt >= SAMPLE_INTERVAL_MS) update()
        }
        mapView.addOnDidBecomeIdleListener(onIdle)
        map.addOnCameraMoveListener(onMove)
        update()
        onDispose {
            mapView.removeOnDidBecomeIdleListener(onIdle)
            map.removeOnCameraMoveListener(onMove)
            handler.removeCallbacksAndMessages(null)
        }
    }
}

/**
 * Sets the navigation bar's buttons to contrast with [panel], the colour
 * of an app panel behind the bar, or to the app's theme when there is
 * none (the theme-coloured scrim is behind them then).
 */
@Composable
private fun NavigationBarIconsFollow(panel: Color?) {
    val window = LocalActivity.current?.window ?: return
    val appDark = LocalTheme.current.dark
    DisposableEffect(window, panel, appDark) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightNavigationBars =
            navigationIconsDark(panel?.luminance(), appDark, controller.isAppearanceLightNavigationBars)
        onDispose {}
    }
}

/** The SurfaceView MapLibre renders into, if it uses one. */
private fun View.findSurfaceView(): SurfaceView? = when (this) {
    is SurfaceView -> this
    is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findSurfaceView() }
    else -> null
}

/** Size of the downscaled status bar sample, in pixels. */
private const val SAMPLE_WIDTH = 64
private const val SAMPLE_HEIGHT = 8

/** Minimum time between samples while the camera moves. */
private const val SAMPLE_INTERVAL_MS = 500L

/** System-bar and cutout insets in pixels. */
private data class SafeInsets(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** The planning sheet pulled up covers at most this share of the map. */
private const val SHEET_MAX_SHARE = 0.55f

/** How long the panels must stay still before the map fits to them. */
private const val SETTLE_MS = 150L

/** Room between a fitted route and the panels around it. */
private val FIT_MARGIN = 24.dp

/** Tag review shows a tag or the section proposed for it close up, as
 * wide as a few streets. */
private const val TAG_REVIEW_SPAN_M = 600.0

/** Translucent scrims behind the navigation bar, per system theme. */
private val LIGHT_SCRIM = Color.White.copy(alpha = 0.7f)
private val DARK_SCRIM = Color.Black.copy(alpha = 0.7f)

/** The record symbol's red. */
internal val RECORD_RED = Color(0xFFD93025)

/** Widest the messages and route card get (tablets, landscape). */
private val TOP_BOX_MAX_WIDTH: Dp = 640.dp

/** MapLibre's default control margin. */
private val CONTROL_MARGIN: Dp = 4.dp

/** The scale bar's space above the navigation bar or the sheet. */
private val SCALE_BOTTOM: Dp = 10.dp

/** MapLibre's default attribution offset from the left, which keeps it clear of the logo. */
private val ATTRIBUTION_OFFSET: Dp = 92.dp

/** The scale bar starts this far from the left: past the attribution. */
private val SCALE_START: Dp = ATTRIBUTION_OFFSET + 32.dp

/** The bottom-right buttons' distance from the safe edges. */
private val FAB_PADDING: Dp = 16.dp

/** Moves the compass, logo and attribution inside the safe area; px
 * arguments. The compass is placed from the top right corner. */
private fun applyControlMargins(
    map: MapLibreMap,
    insets: SafeInsets,
    margin: Int,
    attributionOffset: Int,
    compassRight: Int,
    compassTop: Int,
) {
    map.uiSettings.apply {
        compassGravity = Gravity.TOP or Gravity.END
        setCompassMargins(0, insets.top + compassTop, insets.right + compassRight, 0)
        setLogoMargins(insets.left + margin, 0, 0, insets.bottom + margin)
        setAttributionMargins(insets.left + attributionOffset, 0, 0, insets.bottom + margin)
    }
}

/** A [MapView] that follows the composition's lifecycle, as MapLibre requires. */
@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        // When the system needs memory back while the app is in the
        // background (music and other apps in front), the map drops its
        // tile cache; tiles load again on return.
        val trim = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (dropsMapCaches(level)) mapView.onLowMemory()
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = mapView.onLowMemory()
        }
        context.registerComponentCallbacks(trim)
        onDispose {
            context.unregisterComponentCallbacks(trim)
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
    return mapView
}

private fun initialCamera(res: Resources): CameraPosition =
    CameraPosition.Builder()
        .target(
            LatLng(
                res.getString(R.string.map_initial_lat).toDouble(),
                res.getString(R.string.map_initial_lon).toDouble(),
            ),
        )
        .zoom(res.getInteger(R.integer.map_initial_zoom).toDouble())
        .build()

/** What recording a ride asks for: precise location, and on Android 13+
 * permission to show the recording notification. */
private fun recordingPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun hasLocationPermission(context: Context): Boolean =
    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

@SuppressLint("MissingPermission") // Only called after hasLocationPermission().
private fun enableLocation(context: Context, map: MapLibreMap, style: Style) {
    map.locationComponent.apply {
        if (!isLocationComponentActivated) {
            activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style)
                    .useDefaultLocationEngine(true)
                    .build(),
            )
        }
        isLocationComponentEnabled = true
        renderMode = RenderMode.COMPASS
    }
}

/**
 * Follows the rider's position in the middle of the map, at [zoom] if
 * given. Fitting a route leaves its padding on the camera, which would
 * keep the position off centre. So the map first glides to the last known
 * position with no padding, in one move, and following starts when it
 * gets there (a separate padding change would be cut short by following's
 * own move). Without a known position the padding goes at once. A pan
 * during the glide leaves following off, like a pan while following.
 */
private fun followRider(map: MapLibreMap, zoom: Double? = null) {
    val location = map.locationComponent
    val here = location.takeIf { it.isLocationComponentActivated }?.lastKnownLocation
    if (here == null) {
        map.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0))
        zoom?.let { map.moveCamera(CameraUpdateFactory.zoomTo(it)) }
        location.cameraMode = CameraMode.TRACKING
        return
    }
    // Not following during the glide, so the two moves don't fight.
    location.cameraMode = CameraMode.NONE
    val centred = CameraPosition.Builder()
        .target(LatLng(here.latitude, here.longitude))
        .padding(0.0, 0.0, 0.0, 0.0)
        .apply { zoom?.let { zoom(it) } }
        .build()
    map.animateCamera(
        CameraUpdateFactory.newCameraPosition(centred),
        FOLLOW_GLIDE_MS,
        object : MapLibreMap.CancelableCallback {
            override fun onFinish() {
                location.cameraMode = CameraMode.TRACKING
            }

            override fun onCancel() = Unit
        },
    )
}

/** How long the map takes to glide to the rider's position. */
private const val FOLLOW_GLIDE_MS = 500

/** What a set of loops is found for: the same request gives the same
 * loops, so loops found ahead for it can be shown. */
private data class LoopRequest(
    val start: LatLng,
    val choice: LoopChoice,
    val opts: RouteOptions,
    val favourites: Favourites?,
    val shape: LoopOptions,
)

/** How long the section sheet's top edge must stay put before the map
 * fits the section above it, ms (the sheet slides up first). */
private const val EDIT_FIT_SETTLE_MS = 250L

/** OpenFreeMap's light or dark map style (config.xml). */
private fun mapStyleUrl(resources: android.content.res.Resources, dark: Boolean): String =
    resources.getString(if (dark) R.string.map_style_url_dark else R.string.map_style_url)

/** Routes found between [ends]: the [choices], the one shown ([index]),
 * the options they were found with and when ([at], epoch seconds). */
private data class FoundRoutes(
    val ends: Pair<LatLng, LatLng>,
    val choices: List<Route>,
    val index: Int,
    val opts: RouteOptions,
    val at: Long,
)
