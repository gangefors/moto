// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import se.gangefors.moto.core.Favourites
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
    var region by remember { mutableStateOf<RegionState>(RegionState.Loading) }
    var message by remember { mutableStateOf<String?>(null) }
    // The road the rider last tapped, shown until closed.
    var roadInfo by remember { mutableStateOf<RoadInfo?>(null) }

    // Install (first start only) and open the bundled region off the main thread.
    LaunchedEffect(Unit) {
        region = withContext(Dispatchers.IO) { BundledRegion.open(context.applicationContext) }
        message = when (val r = region) {
            is RegionState.Ready -> resources.getString(R.string.map_hint)
            else -> regionStatus(resources, r)
        }
    }

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

    // Load the style once the map is ready.
    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.isLogoEnabled = true
            m.cameraPosition = initialCamera(resources)
            m.setStyle(resources.getString(R.string.map_style_url)) { s ->
                map = m
                style = s
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
    LaunchedEffect(map, insets) {
        val m = map ?: return@LaunchedEffect
        with(density) {
            applyControlMargins(
                m,
                insets,
                CONTROL_MARGIN.roundToPx(),
                ATTRIBUTION_OFFSET.roundToPx(),
                compassRight = (FAB_PADDING + FAB_SIZE + COMPASS_GAP).roundToPx(),
                compassBottom = (FAB_PADDING + (FAB_SIZE - COMPASS_SIZE) / 2).roundToPx(),
            )
        }
    }

    // Status bar icons follow the brightness of the map behind them.
    StatusBarIconsFollowMap(mapView, map, WindowInsets.statusBars.getTop(density))

    // The rider's saved sections (ADR-0006), opened off the main thread.
    var store by remember { mutableStateOf<StoreState>(StoreState.Loading) }
    var sections by remember { mutableStateOf<List<Section>>(emptyList()) }
    LaunchedEffect(Unit) {
        store = withContext(Dispatchers.IO) { SavedSections.open(context.applicationContext) }
        when (val s = store) {
            is StoreState.Ready -> withContext(Dispatchers.IO) {
                // Save a ride the app died in the middle of.
                Recording.recover(context.applicationContext, s.store)
                runCatching { s.store.list(null) }
            }
                .onSuccess { sections = it }
                .onFailure { message = resources.getString(R.string.sections_failed, it.message ?: it.toString()) }
            is StoreState.Failed -> message = resources.getString(R.string.sections_failed, s.message)
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
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val report = s.rematch(engine)
                report to if (report.checked > 0uL) s.list(null) else null
            }
        }
        result.fold(
            onSuccess = { (report, updated) ->
                updated?.let { sections = it }
                if (report.unmatched > 0uL) {
                    message = resources.getQuantityString(
                        R.plurals.sections_unmatched,
                        report.unmatched.toInt(),
                        report.unmatched.toInt(),
                    )
                }
            },
            onFailure = { message = resources.getString(R.string.sections_failed, it.message ?: it.toString()) },
        )
    }

    // The saved sections as the router sees them (M2a): rebuilt off the main
    // thread whenever they change, re-matched ones included. Until the first
    // build is done, routes are the fastest ones.
    var favourites by remember { mutableStateOf<Favourites?>(null) }
    // Where the favourites run on gravel: drawn dashed, and mostly-gravel
    // sections hidden while gravel is avoided.
    var sectionGravel by remember { mutableStateOf<List<SectionGravel>>(emptyList()) }
    LaunchedEffect(store, region, sections) {
        val s = (store as? StoreState.Ready)?.store ?: return@LaunchedEffect
        val engine = (region as? RegionState.Ready)?.engine ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { s.favourites(engine).let { it to it.gravel() } } }
            .onSuccess { (f, g) ->
                favourites = f
                sectionGravel = g
            }
            .onFailure { message = resources.getString(R.string.sections_failed, it.message ?: it.toString()) }
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
    var showRides by remember { mutableStateOf(false) }
    // Sections that no longer fit the map are hidden unless the rider asks.
    var showUnmatched by remember { mutableStateOf(false) }
    var confirmDeleteUnmatched by remember { mutableStateOf(false) }
    // Quick-tags waiting for review, and the review in progress (it runs in
    // "mark section" mode, starting from each tag's suggested section).
    var pendingTags by remember { mutableIntStateOf(0) }
    var review by remember { mutableStateOf<TagReview?>(null) }
    var reviewTag by remember { mutableStateOf<Tag?>(null) }

    // Layers in drawing order: saved sections, the ride being recorded, a
    // proposed section, the route, the snap marker.
    val overlays = remember(style) {
        style?.let { s ->
            Overlays(SectionOverlay(s, density.density), RideOverlay(s), SectionDraftOverlay(s), RouteOverlay(s), SnapMarker(s))
        }
    }

    // Ride recording (RecordingService): the line so far, and what to say.
    val recording by Recording.state.collectAsState()
    // A saved ride the rider asked to see (My data > Rides > Show), to
    // mark sections along it; the ride being recorded takes its place.
    var shownRide by remember { mutableStateOf<ShownRide?>(null) }
    LaunchedEffect(overlays, recording, shownRide) {
        overlays?.ride?.show((recording as? Recording.State.Active)?.line ?: shownRide?.line)
    }
    LaunchedEffect(recording) {
        when (val r = recording) {
            is Recording.State.Finished -> {
                val km = sectionKm(r.track.distanceM)
                val time = formatDuration(((r.track.endedAt ?: r.track.startedAt) - r.track.startedAt) * 1000)
                message = r.batteryPerHour?.let { resources.getString(R.string.recording_saved_battery, km, time, it) }
                    ?: resources.getString(R.string.recording_saved, km, time)
            }
            is Recording.State.Failed -> message = resources.getString(R.string.recording_failed, r.message)
            else -> Unit
        }
    }
    val recordPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            hasLocation = true
            RecordingService.start(context)
            message = resources.getString(R.string.recording_started)
        } else {
            message = resources.getString(R.string.recording_no_permission)
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
            message = resources.getString(R.string.tag_no_fix)
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { ready.store.addTag(newTag(fix, active?.trackId)) } }
            buzz(context, ok = result.isSuccess)
            message = result.fold(
                onSuccess = { resources.getString(R.string.tag_saved) },
                onFailure = { resources.getString(R.string.tag_failed, it.message ?: it.toString()) },
            )
            refreshPendingTags()
        }
    }

    fun endReview(text: String?) {
        stopMarking()
        review = null
        reviewTag = null
        text?.let { message = it }
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
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val track = tag.trackId?.let { ready.store.trackPoints(it) }
                    engine.suggestSection(tag, track)
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
                    map?.let { m -> fitTo(m, d.geometry, density.density) }
                },
                onFailure = { e ->
                    marker.begin()
                    message = resources.getString(R.string.tag_review_none, r.position, r.size, e.message ?: e.toString())
                    map?.let { m -> fitTo(m, listOf(tag.position), density.density) }
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
                val problem = runCatching { ready.engine.snap(point.toLatLon()) }.exceptionOrNull()
                if (problem != null) {
                    marker.rejectLast()
                    message = coreErrorMessage(resources, problem)
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
                    val result = withContext(Dispatchers.Default) {
                        runCatching { ready.engine.sectionBetween(st.start.toLatLon(), st.end.toLatLon()) }
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
                            message = coreErrorMessage(resources, e)
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
    // The via points before the last one was added, until the route
    // through it is found (restored if it can't be).
    var viasBefore by remember { mutableStateOf<List<LatLng>?>(null) }
    // The time to arrive by (seconds since the epoch) in place of the
    // extra-time choice, and when the route shown was found.
    var arriveBy by remember { mutableStateOf<Long?>(null) }
    var routeFoundAt by remember { mutableLongStateOf(0L) }
    var routeSummary by remember { mutableStateOf<RouteSummary?>(null) }
    // The route shown and the options it was found with, for sharing.
    var shownRoute by remember { mutableStateOf<Pair<Route, RouteOptions>?>(null) }
    var budgetPercent by remember { mutableIntStateOf(RoutePrefs.budgetPercent(context)) }
    var gravel by remember { mutableStateOf(RoutePrefs.gravel(context)) }
    LaunchedEffect(overlays, sections, showUnmatched, sectionGravel, gravel) {
        val hidden = hiddenForGravel(sectionGravel, gravel)
        val shown = visibleSections(sections, showUnmatched).filterNot { it.id in hidden }
        overlays?.sections?.show(shown)
        overlays?.sections?.showGravel(gravelParts(sectionGravel, shown))
    }
    // A start picked and waiting for an end, or for "Loop from here".
    var startPicked by remember { mutableStateOf<LatLng?>(null) }
    // Round trips (M3) from a start: the loops found (empty while they are
    // being found), the one shown, the options they were found with, and
    // the length the rider wants. Found again when the length, the gravel
    // setting or the favourites change.
    var loopStart by remember { mutableStateOf<LatLng?>(null) }
    var loops by remember { mutableStateOf<List<Route>>(emptyList()) }
    var loopIndex by remember { mutableIntStateOf(0) }
    var loopOpts by remember { mutableStateOf<RouteOptions?>(null) }
    var loopChoice by remember { mutableStateOf(RoutePrefs.loopChoice(context)) }
    // 0: the standard loops; Shuffle picks another seed. A new start goes
    // back to the standard loops.
    var loopSeed by remember { mutableStateOf(0u) }
    // Which way the loops should head; any way again for a new start.
    var loopDirection by remember { mutableStateOf(LoopDirection.ANY) }
    // Whether the route and loop cards show all their choices or only the
    // figures (collapsed, to see more of the map); kept for new routes.
    var cardExpanded by rememberSaveable { mutableStateOf(true) }
    // While a route or loop is shown, the sections fade so the route is the
    // one strong line (its favourite stretches glow; see RouteOverlay).
    // A saved route the rider asked to see (My data > Saved routes > Show),
    // and a route or loop being saved (its name is asked first).
    var shownSaved by remember { mutableStateOf<ShownSavedRoute?>(null) }
    var savingRoute by remember { mutableStateOf<Pair<Route, Boolean>?>(null) }
    LaunchedEffect(overlays, routeEnds, loopStart, shownSaved) {
        val routeShown = routeEnds != null || loopStart != null || shownSaved != null
        overlays?.sections?.setLook(sectionLook(routeShown = routeShown))
    }
    LaunchedEffect(loopStart, loopChoice, loopSeed, loopDirection, gravel, favourites, overlays) {
        val start = loopStart ?: return@LaunchedEffect
        val o = overlays ?: return@LaunchedEffect
        val ready = region as? RegionState.Ready ?: return@LaunchedEffect
        loops = emptyList()
        loopIndex = 0
        o.route.show(start, null, null)
        val favs = favourites
        val opts = routeOptions(defaultRouteOptions(), budgetPercent, gravel)
        val choice = loopChoice
        val shape = LoopOptions(seed = loopSeed, bearing = loopDirection.bearing)
        // A newer request cancels this one; its result is then dropped.
        val result = withContext(Dispatchers.Default) {
            runCatching { ready.engine.roundTrip(start.toLatLon(), choice.target, opts, favs, shape) }
        }
        result.fold(
            onSuccess = { found ->
                val first = found.firstOrNull()
                if (first == null) {
                    loopStart = null
                    o.route.show(null, null, null)
                    message = resources.getString(R.string.loop_none)
                } else {
                    loops = found
                    loopOpts = opts
                    o.route.show(start, null, first.geometry, first.favouriteParts, first.unpavedParts)
                }
            },
            onFailure = {
                loopStart = null
                o.route.show(null, null, null)
                message = if (classify(it) == CoreProblem.NO_ROUTE) {
                    resources.getString(R.string.loop_none)
                } else {
                    coreErrorMessage(resources, it)
                }
            },
        )
    }
    LaunchedEffect(routeEnds, vias, budgetPercent, arriveBy, gravel, favourites, overlays) {
        val (start, end) = routeEnds ?: return@LaunchedEffect
        val o = overlays ?: return@LaunchedEffect
        val ready = region as? RegionState.Ready ?: return@LaunchedEffect
        routeSummary = null
        shownRoute = null
        val favs = favourites
        val now = System.currentTimeMillis() / 1000
        val by = arriveBy
        val opts = if (by != null) {
            arriveByOptions(defaultRouteOptions(), now, by, gravel)
        } else {
            routeOptions(defaultRouteOptions(), budgetPercent, gravel)
        }
        // A newer request cancels this one; its result is then dropped.
        val result = withContext(Dispatchers.Default) {
            runCatching { ready.engine.route(start.toLatLon(), vias.map { it.toLatLon() }, end.toLatLon(), opts, favs) }
        }
        result.fold(
            onSuccess = { r ->
                o.route.show(start, end, r.geometry, r.favouriteParts, r.unpavedParts, vias)
                routeSummary = summarize(r.distanceM, r.durationS, r.favouriteShare, r.fastestDurationS, r.curvyShare, r.unpavedM)
                shownRoute = r to opts
                routeFoundAt = now
                viasBefore = null
            },
            onFailure = {
                val before = viasBefore
                if (before != null) {
                    // The new via point can't be reached: keep the route without it.
                    viasBefore = null
                    vias = before
                } else {
                    routeEnds = null
                }
                message = coreErrorMessage(resources, it)
            },
        )
    }
    DisposableEffect(map, overlays, region) {
        val m = map
        val o = overlays
        val s = style
        if (m == null || o == null || s == null) return@DisposableEffect onDispose {}
        val ready = region as? RegionState.Ready
        ready?.let { showRegionOutline(s, it.engine.info()) }
        val onClick = MapLibreMap.OnMapClickListener { tap ->
            if (marking) {
                if (ready == null) message = regionStatus(resources, region) else onMarkTap(ready, tap)
                return@OnMapClickListener true
            }
            val hit = o.sections.sectionAt(m, tap)?.let { id -> sections.firstOrNull { it.id == id } }
            if (hit != null) {
                editing = hit
            } else if (ready == null) {
                message = regionStatus(resources, region)
            } else {
                try {
                    val info = ready.engine.roadAt(tap.toLatLon())
                    o.snap.show(tap, LatLng(info.point.position.lat, info.point.position.lon))
                    roadInfo = info
                    message = null
                } catch (e: MotoException) {
                    o.snap.show(tap, null)
                    roadInfo = null
                    message = coreErrorMessage(resources, e)
                }
            }
            true
        }
        val onLongClick = MapLibreMap.OnMapLongClickListener { point ->
            if (marking) return@OnMapLongClickListener false
            if (ready == null) {
                message = regionStatus(resources, region)
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
                    loopStart = null
                    shownSaved = null
                    startPicked = null
                    // New start: check it lies on a road before keeping it.
                    val problem = runCatching { ready.engine.snap(point.toLatLon()) }.exceptionOrNull()
                    if (problem != null) {
                        picker.reset()
                        message = coreErrorMessage(resources, problem)
                    } else {
                        o.route.show(point, null, null)
                        startPicked = point
                        message = resources.getString(R.string.route_pick_end)
                    }
                }
                is RoutePicker.Step.Complete -> {
                    startPicked = null
                    o.route.show(step.start, step.end, null)
                    message = null
                    vias = emptyList()
                    arriveBy = null
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

    // Show the GPS position as soon as both the style and the permission are there.
    LaunchedEffect(map, style, hasLocation) {
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        if (hasLocation) enableLocation(context, m, s)
    }

    /**
     * Runs [action] on the store off the main thread, then reloads the
     * sections and shows the message [action] returns.
     */
    fun changeSectionsThen(action: (SectionStore) -> String) {
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
                    message = done
                },
                onFailure = { message = resources.getString(R.string.sections_failed, it.message ?: it.toString()) },
            )
        }
    }

    /** Runs [action] on the store off the main thread, then reloads the sections. */
    fun changeSections(done: String, action: (SectionStore) -> Unit) = changeSectionsThen {
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
                    val gpx = engine.routeGpx(line, gpxName, opts)
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
                    message = resources.getString(R.string.route_share_failed, it.message ?: it.toString())
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

    /** Saves [r] (a loop when [isLoop]) as [name] in My data. */
    fun saveRoute(r: Route, isLoop: Boolean, name: String) {
        val ready = store as? StoreState.Ready ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { ready.store.saveRoute(name, isLoop, r) } }
            message = result.fold(
                onSuccess = { resources.getString(R.string.route_saved, it.name) },
                onFailure = { resources.getString(R.string.route_save_failed, it.message ?: it.toString()) },
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
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
                .background(if (isSystemInDarkTheme()) DARK_SCRIM else LIGHT_SCRIM),
        )
        // Messages and the route card, across the top (the compass sits at
        // the bottom right, so nothing else is up here); on wide screens no
        // wider than TOP_BOX_MAX_WIDTH.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeDrawingPadding()
                .padding(top = 8.dp, start = 16.dp, end = 16.dp)
                .widthIn(max = TOP_BOX_MAX_WIDTH)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val offerLoop = startPicked != null && !marking
            if (message != null || marking || offerLoop) Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    message?.let { Text(it) }
                    if (offerLoop) {
                        OutlinedButton(
                            onClick = {
                                val start = picker.takeStart()
                                startPicked = null
                                message = null
                                loopSeed = 0u
                                loopDirection = LoopDirection.ANY
                                loopStart = start
                            },
                            modifier = Modifier.padding(top = 4.dp),
                        ) { OneLine(stringResource(R.string.loop_from_here)) }
                    }
                    if (marking) {
                        // The banner is narrow (it keeps clear of the map controls),
                        // so the buttons wrap onto a second line instead of
                        // squeezing each other.
                        FlowRow(
                            Modifier.padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (reviewTag != null) {
                                // Stop ends the review; tags not yet handled
                                // stay pending. Skip leaves this one pending
                                // and moves on to the next.
                                OutlinedButton(onClick = { endReview(resources.getString(R.string.map_hint)) }) {
                                    OneLine(stringResource(R.string.tag_review_stop))
                                }
                                OutlinedButton(onClick = { skipTag() }) {
                                    OneLine(stringResource(R.string.tag_review_skip))
                                }
                                OutlinedButton(
                                    onClick = { finishTag(TagStatus.DISCARDED) },
                                    enabled = !proposing,
                                ) { OneLine(stringResource(R.string.tag_review_discard)) }
                            } else {
                                OutlinedButton(onClick = {
                                    stopMarking()
                                    message = resources.getString(R.string.map_hint)
                                }) { OneLine(stringResource(R.string.cancel)) }
                            }
                            Button(
                                onClick = { savingDraft = true },
                                enabled = draft != null && !proposing,
                            ) { OneLine(stringResource(R.string.section_save_ellipsis)) }
                        }
                    }
                }
            }
            shownRide?.let {
                ShownRideCard(it, onClose = { shownRide = null }, modifier = Modifier.fillMaxWidth())
            }
            shownSaved?.let { s ->
                SavedRouteCard(
                    s,
                    onShare = {
                        shareLine(s.line, s.route.name, routeOptions(defaultRouteOptions(), budgetPercent, gravel))
                    },
                    onClose = {
                        shownSaved = null
                        overlays?.route?.show(null, null, null)
                    },
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
            routeEnds?.let {
                RouteCard(
                    modifier = Modifier.fillMaxWidth(),
                    expanded = cardExpanded,
                    onToggleExpanded = { cardExpanded = !cardExpanded },
                    summary = routeSummary,
                    budgetPercent = budgetPercent,
                    onBudget = { percent ->
                        budgetPercent = percent
                        RoutePrefs.setBudgetPercent(context, percent)
                    },
                    gravel = gravel,
                    onGravel = { g ->
                        gravel = g
                        RoutePrefs.setGravel(context, g)
                    },
                    onClose = {
                        routeEnds = null
                        vias = emptyList()
                        addingVia = false
                        arriveBy = null
                        overlays?.route?.show(null, null, null)
                    },
                    onShare = { shownRoute?.let { (r, opts) -> shareRoute(r, opts) } },
                    onSave = { shownRoute?.let { (r, _) -> savingRoute = r to false } },
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
                    onArriveBy = { arriveBy = it },
                )
            }
            loopStart?.let {
                val shown = loops.getOrNull(loopIndex)
                LoopCard(
                    modifier = Modifier.fillMaxWidth(),
                    expanded = cardExpanded,
                    onToggleExpanded = { cardExpanded = !cardExpanded },
                    summary = shown?.let { r ->
                        summarize(r.distanceM, r.durationS, r.favouriteShare, r.durationS, r.curvyShare, r.unpavedM)
                    },
                    position = loopIndex,
                    count = loops.size,
                    onShuffle = { loopSeed = shuffleSeed() },
                    direction = loopDirection,
                    onDirection = { loopDirection = it },
                    onNext = {
                        loopIndex = nextLoop(loopIndex, loops.size)
                        loops.getOrNull(loopIndex)?.let { r ->
                            overlays?.route?.show(it, null, r.geometry, r.favouriteParts, r.unpavedParts)
                        }
                    },
                    choice = loopChoice,
                    onChoice = { c ->
                        loopChoice = c
                        RoutePrefs.setLoopChoice(context, c)
                    },
                    gravel = gravel,
                    onGravel = { g ->
                        gravel = g
                        RoutePrefs.setGravel(context, g)
                    },
                    onClose = {
                        loopStart = null
                        overlays?.route?.show(null, null, null)
                    },
                    onShare = { if (shown != null) loopOpts?.let { opts -> shareRoute(shown, opts) } },
                    onSave = { shown?.let { savingRoute = it to true } },
                )
            }
        }
        if (!marking) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .safeDrawingPadding()
                    .padding(16.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val unmatched = sections.count { !fitsTheMap(it.status) }
                val deletable = sections.count { it.status == SectionStatus.UNMATCHED }
                if (showUnmatched && deletable > 0) {
                    // Batch delete, confirmed by a second tap.
                    ExtendedFloatingActionButton(
                        onClick = {
                            if (!confirmDeleteUnmatched) {
                                confirmDeleteUnmatched = true
                            } else {
                                confirmDeleteUnmatched = false
                                showUnmatched = false
                                changeSections(resources.getString(R.string.unmatched_deleted)) { it.deleteUnmatched() }
                            }
                        },
                        containerColor = if (confirmDeleteUnmatched) DELETE_COLOR else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (confirmDeleteUnmatched) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = stringResource(
                                    if (confirmDeleteUnmatched) R.string.delete_confirm else R.string.delete,
                                ),
                            )
                        },
                        text = {
                            Text(
                                if (confirmDeleteUnmatched) {
                                    pluralStringResource(R.plurals.unmatched_delete_confirm, deletable, deletable)
                                } else {
                                    pluralStringResource(R.plurals.unmatched_delete, deletable, deletable)
                                },
                            )
                        },
                    )
                }
                if (unmatched > 0) {
                    ExtendedFloatingActionButton(onClick = {
                        showUnmatched = !showUnmatched
                        confirmDeleteUnmatched = false
                    }) {
                        Text(
                            if (showUnmatched) {
                                stringResource(R.string.unmatched_hide)
                            } else {
                                pluralStringResource(R.plurals.unmatched_show, unmatched, unmatched)
                            },
                        )
                    }
                }
                if (pendingTags > 0 && recording !is Recording.State.Active && region is RegionState.Ready) {
                    ExtendedFloatingActionButton(onClick = { startReview() }) {
                        Text(stringResource(R.string.tags_review, pendingTags))
                    }
                }
                if (store is StoreState.Ready) {
                    FloatingActionButton(onClick = { showRides = true }) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.rides_open))
                    }
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
                // Record: a red dot. While recording: a red stop square with
                // the distance so far, readable at a glance on the bike.
                val active = recording as? Recording.State.Active
                if (active != null) {
                    val km = sectionKm(active.distanceM)
                    val stopDescription = stringResource(R.string.record_stop_description, km)
                    ExtendedFloatingActionButton(
                        onClick = { RecordingService.stop(context) },
                        icon = { Icon(painterResource(R.drawable.ic_stop), contentDescription = null, tint = RECORD_RED) },
                        text = { OneLine(stringResource(R.string.record_stop, km)) },
                        modifier = Modifier.semantics { contentDescription = stopDescription },
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
                if (hasLocation) {
                    FloatingActionButton(onClick = { map?.locationComponent?.cameraMode = CameraMode.TRACKING }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_my_location),
                            contentDescription = stringResource(R.string.my_location),
                        )
                    }
                }
            }
        }
        // Quick-tag (PRD R3): one big button, usable with gloves, whenever the
        // map is open. Bottom left, above the map's logo and attribution.
        if (store is StoreState.Ready && !marking) {
            LargeFloatingActionButton(
                onClick = { quickTag() },
                shape = CircleShape,
                containerColor = TAG_COLOR,
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .safeDrawingPadding()
                    .padding(start = 16.dp, bottom = 40.dp)
                    .size(TAG_BUTTON_SIZE)
                    .semantics { contentDescription = resources.getString(R.string.tag_button_description) },
            ) {
                Text(stringResource(R.string.tag_button), style = MaterialTheme.typography.titleLarge)
            }
        }
    }

    // Rate and save the proposed section (its name is generated).
    val proposed = draft
    if (savingDraft && proposed != null) {
        val tag = reviewTag
        SectionSheet(
            title = stringResource(R.string.section_new_title),
            initial = SectionChoice(rating = Rating.GOOD, oneWay = false),
            onDismiss = { savingDraft = false },
            onSave = { choice ->
                if (tag == null) stopMarking() else savingDraft = false
                val name = autoSectionName(
                    fromTag = tag != null,
                    savedAtSec = System.currentTimeMillis() / 1000,
                    distanceM = proposed.distanceM,
                    zone = ZoneId.systemDefault(),
                )
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

    savingRoute?.let { (r, isLoop) ->
        val initial = remember(r) {
            defaultRouteName(System.currentTimeMillis() / 1000, ZoneId.systemDefault(), r.distanceM / 1000.0, isLoop)
        }
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

    // Recorded rides: export as GPX or delete.
    val readyStore = store as? StoreState.Ready
    if (showRides && readyStore != null) {
        RidesSheet(
            store = readyStore.store,
            engine = (region as? RegionState.Ready)?.engine,
            onSectionsChanged = {
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { readyStore.store.list(null) } }
                        .onSuccess { sections = it }
                }
            },
            onMessage = { message = it },
            onDismiss = { showRides = false },
            onShowRoute = { saved ->
                showRides = false
                scope.launch {
                    val line = withContext(Dispatchers.IO) {
                        runCatching { readyStore.store.routeGeometry(saved.id) }.getOrNull()
                    }
                    if (line.isNullOrEmpty()) {
                        message = resources.getString(R.string.rides_gone)
                    } else {
                        routeEnds = null
                        loopStart = null
                        startPicked = null
                        picker.reset()
                        shownSaved = ShownSavedRoute(saved, line)
                        val start = LatLng(line.first().lat, line.first().lon)
                        val end = if (saved.isLoop) null else LatLng(line.last().lat, line.last().lon)
                        overlays?.route?.show(start, end, line)
                        map?.let { m -> fitTo(m, line, density.density) }
                    }
                }
            },
            onShow = { track ->
                showRides = false
                scope.launch {
                    val points = withContext(Dispatchers.IO) {
                        runCatching { readyStore.store.trackPoints(track.id) }.getOrNull()
                    }
                    val line = points?.map { it.position }
                    if (line.isNullOrEmpty()) {
                        message = resources.getString(R.string.rides_gone)
                    } else {
                        shownRide = ShownRide(track, line)
                        map?.let { m -> fitTo(m, line, density.density) }
                    }
                }
            },
            gravel = gravel,
            onGravel = { g ->
                gravel = g
                RoutePrefs.setGravel(context, g)
            },
        )
    }

    // Change or delete a saved section.
    editing?.let { section ->
        SectionSheet(
            title = stringResource(R.string.section_edit_title, sectionKm(lengthM(section.geometry))),
            initial = SectionChoice(section.rating, isOneWay(section.direction)),
            onDismiss = { editing = null },
            onSave = { choice ->
                editing = null
                changeSections(resources.getString(R.string.section_updated)) { st ->
                    st.update(
                        section.id,
                        SectionUpdate(name = null, rating = choice.rating, direction = directionOf(choice.oneWay)),
                    )
                }
            },
            onDelete = {
                editing = null
                changeSections(resources.getString(R.string.section_deleted)) { st ->
                    st.delete(section.id)
                }
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

/** Moves the camera to show [points], clear of the panels at the top and bottom. */
private fun fitTo(map: MapLibreMap, points: List<LatLon>, density: Float) {
    if (points.isEmpty()) return
    val update = if (points.size == 1) {
        CameraUpdateFactory.newLatLngZoom(LatLng(points[0].lat, points[0].lon), 15.0)
    } else {
        val bounds = LatLngBounds.Builder().includes(points.map { LatLng(it.lat, it.lon) }).build()
        val pad = (48 * density).toInt()
        CameraUpdateFactory.newLatLngBounds(bounds, pad, (160 * density).toInt(), pad, (120 * density).toInt())
    }
    map.animateCamera(update)
}

/** Quick-tag button: large enough to hit with gloves on. */
private val TAG_BUTTON_SIZE: Dp = 96.dp
private val TAG_COLOR = Color(0xFFE8710A)

/** The map layers the screen draws into, created once per style. */
private class Overlays(
    val sections: SectionOverlay,
    val ride: RideOverlay,
    val draft: SectionDraftOverlay,
    val route: RouteOverlay,
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

/** Translucent scrims behind the navigation bar, per system theme. */
private val LIGHT_SCRIM = Color.White.copy(alpha = 0.7f)
private val DARK_SCRIM = Color.Black.copy(alpha = 0.7f)

/** The record symbol's red. */
private val RECORD_RED = Color(0xFFD93025)

/** Widest the messages and route card get (tablets, landscape). */
private val TOP_BOX_MAX_WIDTH: Dp = 640.dp

/** MapLibre's default control margin. */
private val CONTROL_MARGIN: Dp = 4.dp

/** MapLibre's default attribution offset from the left, which keeps it clear of the logo. */
private val ATTRIBUTION_OFFSET: Dp = 92.dp

/** The bottom-right buttons' distance from the safe edges, the size of the
 * my-position button at the bottom (a standard FAB), and the compass: it
 * sits just left of that button, clear of the messages and cards at the
 * top. */
private val FAB_PADDING: Dp = 16.dp
private val FAB_SIZE: Dp = 56.dp
private val COMPASS_SIZE: Dp = 48.dp
private val COMPASS_GAP: Dp = 8.dp

/** Moves the compass, logo and attribution inside the safe area; px
 * arguments. The compass is placed from the bottom right corner. */
private fun applyControlMargins(
    map: MapLibreMap,
    insets: SafeInsets,
    margin: Int,
    attributionOffset: Int,
    compassRight: Int,
    compassBottom: Int,
) {
    map.uiSettings.apply {
        compassGravity = Gravity.BOTTOM or Gravity.END
        setCompassMargins(0, 0, insets.right + compassRight, insets.bottom + compassBottom)
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
        onDispose {
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
        cameraMode = CameraMode.TRACKING
    }
}
