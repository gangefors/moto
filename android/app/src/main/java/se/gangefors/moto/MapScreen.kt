// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
    // The routing region: a downloaded one, else the bundled one (ADR-0008).
    val activeRegion by Regions.active.collectAsState()
    val regionDownload by Regions.download.collectAsState()
    // What the app is waiting for, shown after a moment (BusyPill).
    val busy = remember { BusyTasks() }
    val region = activeRegion.state
    var message by remember { mutableStateOf<String?>(null) }
    // The road the rider last tapped, shown until closed.
    var roadInfo by remember { mutableStateOf<RoadInfo?>(null) }

    // Open the region off the main thread (installing the bundled one on
    // first start); the hint again whenever another region takes over.
    LaunchedEffect(Unit) { Regions.load(context.applicationContext) }
    LaunchedEffect(region) {
        if (region is RegionState.Loading) return@LaunchedEffect
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
    fun fitPaddingNow(): FitPadding {
        val (w, h) = mapSize.width to mapSize.height
        val panels = Panels(
            width = w,
            height = h,
            left = insets.left,
            top = max(topPanelBottom, insets.top),
            right = max(w - buttonsLeft, insets.right),
            bottom = maxOf(h - tagTop, h - sheetTop, insets.bottom),
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
        val result = busy.run(R.string.busy_sections) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val report = DebugTools.startup("sections re-match") { s.rematch(engine) }
                    report to if (report.checked > 0uL) s.list(null) else null
                }
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
        withContext(Dispatchers.IO) {
            runCatching { DebugTools.startup("favourites") { s.favourites(engine).let { it to it.gravel() } } }
        }
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
            Overlays(SectionOverlay(s, density.density), RideOverlay(s), SectionDraftOverlay(s), RouteOverlay(s, density.density), SnapMarker(s))
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
            val result = busy.run(R.string.busy_section) {
                withContext(Dispatchers.Default) {
                    runCatching {
                        val track = tag.trackId?.let { ready.store.trackPoints(it) }
                        engine.suggestSection(tag, track)
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
                    val result = busy.run(R.string.busy_section) {
                        withContext(Dispatchers.Default) {
                            runCatching { ready.engine.sectionBetween(st.start.toLatLon(), st.end.toLatLon()) }
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
    var routeIndex by remember { mutableIntStateOf(0) }
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
     * figures, the others faint; the fastest of several grey. */
    fun showRouteChoice(start: LatLng, end: LatLng, set: List<Route>, index: Int, opts: RouteOptions) {
        val r = set.getOrNull(index) ?: return
        val fastest = fastestChoice(set.size)
        val others = set.mapIndexedNotNull { i, l -> if (i == index) null else i to l.geometry }
        overlays?.route?.show(
            start, end, r.geometry, r.favouriteParts, r.unpavedParts, vias,
            others = others, dull = index == fastest, dullOther = fastest,
        )
        routeIndex = index
        routeSummary = summarize(r.distanceM, r.durationS, r.favouriteShare, r.fastestDurationS, r.curvyShare, r.unpavedM)
            .copy(fastest = index == fastest)
        shownRoute = r to opts
    }

    /** Shows loop [index] of [set] from [start], the others faint. */
    fun showLoop(start: LatLng, set: List<Route>, index: Int) {
        val r = set.getOrNull(index) ?: return
        val others = set.mapIndexedNotNull { i, l -> if (i == index) null else i to l.geometry }
        overlays?.route?.show(start, null, r.geometry, r.favouriteParts, r.unpavedParts, others = others)
    }
    // Why the last search found no loop (shown on the loop card instead of
    // figures), or null.
    var loopProblem by remember { mutableStateOf<String?>(null) }
    // What the map was last fitted to (a loop start, or a route's ends): a
    // new one is always fitted, a recalculated one only when it needs to be.
    var fittedFor by remember { mutableStateOf<Any?>(null) }
    // Whether the route and loop cards show all their choices or only the
    // figures (collapsed, to see more of the map); kept for new routes.
    var cardExpanded by rememberSaveable { mutableStateOf(false) }
    // Planning a route or loop: the sheet shows at the bottom, and the tag
    // and map buttons step aside (nobody tags while planning).
    val planning = routeEnds != null || loopStart != null
    // The planning sheet reaches down behind the navigation bar: its
    // buttons then contrast with the sheet, not the system theme.
    NavigationBarIconsFollow(
        if (planning) MaterialTheme.colorScheme.surfaceColorAtElevation(PLAN_SHEET_ELEVATION) else null,
    )
    // A new start or new route ends: the sheet starts at rest, so the map
    // shows what was found.
    LaunchedEffect(loopStart) { if (loopStart != null) cardExpanded = false }
    LaunchedEffect(routeEnds) { if (routeEnds != null) cardExpanded = false }
    // The map's own controls (compass, logo, attribution) stay clear of
    // the system bars, the buttons and the sheet.
    LaunchedEffect(map, insets, planning, sheetTop, mapSize) {
        val m = map ?: return@LaunchedEffect
        val sheet = if (planning && sheetTop < mapSize.height) mapSize.height - sheetTop else 0
        with(density) {
            applyControlMargins(
                m,
                insets.copy(bottom = max(insets.bottom, sheet)),
                CONTROL_MARGIN.roundToPx(),
                ATTRIBUTION_OFFSET.roundToPx(),
                compassRight = if (planning) CONTROL_MARGIN.roundToPx() else (FAB_PADDING + FAB_SIZE + COMPASS_GAP).roundToPx(),
                compassBottom = if (planning) CONTROL_MARGIN.roundToPx() else (FAB_PADDING + (FAB_SIZE - COMPASS_SIZE) / 2).roundToPx(),
            )
        }
    }
    // While a route or loop is shown, the sections fade so the route is the
    // one strong line (its favourite stretches glow; see RouteOverlay).
    // A saved route the rider asked to see (My data > Saved routes > Show),
    // and a route or loop being saved (its name is asked first).
    var shownSaved by remember { mutableStateOf<ShownSavedRoute?>(null) }

    // The location button (LocateLogic): whether the overview of the plan
    // is on the map, untouched, and the zoom the map had on the rider
    // before it.
    var overviewShown by remember { mutableStateOf(false) }
    // The location button's zoom levels (My data).
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

    fun closeRoute() {
        routeEnds = null
        picker.reset()
        vias = emptyList()
        addingVia = false
        arriveBy = null
        overlays?.route?.show(null, null, null)
    }

    fun closeLoop() {
        loopStart = null
        loopsAhead.clear()
        overlays?.route?.show(null, null, null)
    }

    /** Back while marking: the last point placed goes, or marking (or the
     * tag review) stops when nothing is placed. */
    fun markBack() {
        if (reviewTag != null) {
            endReview(resources.getString(R.string.map_hint))
            return
        }
        // A proposal still being found for the old points is dropped.
        markSession++
        proposing = false
        draft = null
        when (marker.back()) {
            SectionMarker.State.Off -> {
                stopMarking()
                message = resources.getString(R.string.map_hint)
                return
            }
            SectionMarker.State.PickStart -> message = resources.getString(R.string.section_pick_start)
            is SectionMarker.State.PickEnd -> message = resources.getString(R.string.section_pick_end)
            is SectionMarker.State.Proposed -> Unit
        }
        showDraft()
    }
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
        val opts = routeOptions(defaultRouteOptions(), ROUTE_EXTRA_PERCENT, gravel)
        val choice = loopChoice
        val shape = LoopOptions(seed = loopSeed, bearing = loopDirection.bearing)
        val request = LoopRequest(start, choice, opts, favs, shape)
        loopProblem = null
        // No loop this time: the start and the card stay, with why, so the
        // rider can Shuffle or change the length or direction from here.
        fun noLoop(why: String) {
            loopsAhead.clear()
            nextSeed = shuffleSeed()
            loopProblem = why
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
        // Found ahead (Shuffle), or found now. A newer request cancels
        // this one; its result is then dropped.
        val ahead = loopsAhead.take(request)
        val result = busy.run(R.string.busy_loops) { ahead?.await() ?: withContext(Dispatchers.Default) { find(request, false) } }
        result.fold(
            onSuccess = { found ->
                val first = found.firstOrNull()
                if (first == null) {
                    noLoop(resources.getString(R.string.loop_none))
                } else {
                    loops = found
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
    LaunchedEffect(routeEnds, vias, arriveBy, gravel, favourites, overlays) {
        val (start, end) = routeEnds ?: return@LaunchedEffect
        val o = overlays ?: return@LaunchedEffect
        val ready = region as? RegionState.Ready ?: return@LaunchedEffect
        routeSummary = null
        shownRoute = null
        routeChoices = emptyList()
        routeIndex = 0
        val favs = favourites
        val now = System.currentTimeMillis() / 1000
        val by = arriveBy
        val opts = if (by != null) {
            arriveByOptions(defaultRouteOptions(), now, by, gravel)
        } else {
            routeOptions(defaultRouteOptions(), ROUTE_EXTRA_PERCENT, gravel)
        }
        // A newer request cancels this one; its result is then dropped.
        val result = busy.run(R.string.busy_routes) {
            withContext(Dispatchers.Default) {
                runCatching {
                    val via = vias.map { it.toLatLon() }
                    DebugTools.routes(start.toLatLon(), via, end.toLatLon(), opts, favs) {
                        ready.engine.routeChoices(start.toLatLon(), via, end.toLatLon(), opts, favs)
                    }
                }
            }
        }
        result.fold(
            onSuccess = { found ->
                if (found.isEmpty()) return@fold
                routeChoices = found
                routeFoundAt = now
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
                    routeEnds = null
                }
                message = coreErrorMessage(resources, it)
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
        ready?.let { showRegionOutline(s, it.engine.info(), it.engine.coverage()) }
        val onClick = MapLibreMap.OnMapClickListener { tap ->
            if (marking) {
                if (ready == null) message = regionStatus(resources, region) else onMarkTap(ready, tap)
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
            if (hit != null) {
                editing = hit
            } else if (ready == null) {
                message = regionStatus(resources, region)
            } else {
                try {
                    val info = DebugTools.query("road info") { ready.engine.roadAt(tap.toLatLon()) }
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
                    val problem = runCatching { DebugTools.query("snap") { ready.engine.snap(point.toLatLon()) } }.exceptionOrNull()
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
                    // A new route, or the end moved: via points and an
                    // arrival time stay only for the same start.
                    if (routeEnds?.first != step.start) {
                        vias = emptyList()
                        arriveBy = null
                    }
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
                .background(if (isSystemInDarkTheme()) DARK_SCRIM else LIGHT_SCRIM),
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
        // Messages and the route card, across the top (the compass sits at
        // the bottom right, so nothing else is up here); on wide screens no
        // wider than TOP_BOX_MAX_WIDTH.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeDrawingPadding()
                .padding(top = 8.dp, start = 16.dp, end = 16.dp)
                .widthIn(max = TOP_BOX_MAX_WIDTH)
                .fillMaxWidth()
                .onGloballyPositioned { topPanelBottom = it.boundsInRoot().bottom.roundToInt() },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val offerLoop = startPicked != null && !marking
            val via = selectedVia?.takeIf { it in vias.indices }
            if (message != null || marking || offerLoop || via != null) Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    message?.let { Text(it) }
                    if (via != null) {
                        // The bin removes it; a tap anywhere else on the map
                        // lets it go.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.route_via_selected, via + 1), Modifier.weight(1f))
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
                    }
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
                        shareLine(s.line, s.route.name, routeOptions(defaultRouteOptions(), ROUTE_EXTRA_PERCENT, gravel))
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
        }
        // Back steps back through what is on the map before it leaves the
        // app (a pulled-up sheet handles Back itself first).
        BackHandler(enabled = marking || addingVia || selectedVia != null || roadInfo != null || planning ||
            startPicked != null || shownRide != null || shownSaved != null) {
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
                routeEnds != null -> closeRoute()
                loopStart != null -> closeLoop()
                startPicked != null -> {
                    startPicked = null
                    picker.reset()
                    overlays?.route?.show(null, null, null)
                    message = resources.getString(R.string.map_hint)
                }
                shownSaved != null -> {
                    shownSaved = null
                    overlays?.route?.show(null, null, null)
                }
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
                            onClose = { closeRoute() },
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
                        )
                    }
                    loopStart?.let {
                        val shown = loops.getOrNull(loopIndex)
                        LoopCard(
                            expanded = cardExpanded,
                            onExpandedChange = { cardExpanded = it },
                            maxHeight = sheetMaxHeight,
                            summary = shown?.let { r ->
                                summarize(r.distanceM, r.durationS, r.favouriteShare, r.durationS, r.curvyShare, r.unpavedM)
                            },
                            problem = loopProblem,
                            position = loopIndex,
                            count = loops.size,
                            onShuffle = { loopSeed = nextSeed },
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
                            onChoice = { c ->
                                loopChoice = c
                                RoutePrefs.setLoopChoice(context, c)
                            },
                            gravel = gravel,
                            onGravel = { g ->
                                gravel = g
                                RoutePrefs.setGravel(context, g)
                            },
                            onClose = { closeLoop() },
                            onShare = { if (shown != null) loopOpts?.let { opts -> shareRoute(shown, opts) } },
                            onSave = { shown?.let { savingRoute = it to true } },
                        )
                    }
            }
        }
        if (!marking && !planning) {
            DisposableEffect(Unit) { onDispose { buttonsLeft = Int.MAX_VALUE } }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .safeDrawingPadding()
                    .padding(16.dp)
                    .onGloballyPositioned { buttonsLeft = it.boundsInRoot().left.roundToInt() },
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
                    FloatingActionButton(onClick = { onLocateTap() }) {
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
        if (store is StoreState.Ready && !marking && !planning) {
            DisposableEffect(Unit) { onDispose { tagTop = Int.MAX_VALUE } }
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
                    .onGloballyPositioned { tagTop = it.boundsInRoot().top.roundToInt() }
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
                        showOnMap(listOf(line), always = true)
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
                        showOnMap(listOf(line), always = true)
                    }
                }
            },
            zooms = locateZooms,
            onZooms = { z ->
                locateZooms = z
                RoutePrefs.setLocateZooms(context, z)
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

/**
 * Sets the navigation bar's buttons to contrast with [panel], the colour
 * of an app panel behind the bar, or to the system theme's when there is
 * none (the theme-coloured scrim is behind them then).
 */
@Composable
private fun NavigationBarIconsFollow(panel: Color?) {
    val window = LocalActivity.current?.window ?: return
    val systemDark = isSystemInDarkTheme()
    DisposableEffect(window, panel, systemDark) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightNavigationBars =
            navigationIconsDark(panel?.luminance(), systemDark, controller.isAppearanceLightNavigationBars)
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
