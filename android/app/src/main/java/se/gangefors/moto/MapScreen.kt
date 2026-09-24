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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlin.math.roundToInt
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
import se.gangefors.moto.core.Section
import se.gangefors.moto.core.SectionDraft
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
            applyControlMargins(m, insets, CONTROL_MARGIN.roundToPx(), ATTRIBUTION_OFFSET.roundToPx())
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
    LaunchedEffect(store, region, sections) {
        val s = (store as? StoreState.Ready)?.store ?: return@LaunchedEffect
        val engine = (region as? RegionState.Ready)?.engine ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { s.favourites(engine) } }
            .onSuccess { favourites = it }
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
    LaunchedEffect(overlays, sections, showUnmatched) {
        overlays?.sections?.show(visibleSections(sections, showUnmatched))
    }

    // Ride recording (RecordingService): the line so far, and what to say.
    val recording by Recording.state.collectAsState()
    LaunchedEffect(overlays, recording) {
        overlays?.ride?.show((recording as? Recording.State.Active)?.line)
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
            endReview(resources.getString(R.string.tag_review_done))
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
    var routeSummary by remember { mutableStateOf<RouteSummary?>(null) }
    var budgetPercent by remember { mutableIntStateOf(RoutePrefs.budgetPercent(context)) }
    LaunchedEffect(routeEnds, budgetPercent, favourites, overlays) {
        val (start, end) = routeEnds ?: return@LaunchedEffect
        val o = overlays ?: return@LaunchedEffect
        val ready = region as? RegionState.Ready ?: return@LaunchedEffect
        routeSummary = null
        val favs = favourites
        val opts = routeOptions(defaultRouteOptions(), budgetPercent)
        // A newer request cancels this one; its result is then dropped.
        val result = withContext(Dispatchers.Default) {
            runCatching { ready.engine.route(start.toLatLon(), end.toLatLon(), opts, favs) }
        }
        result.fold(
            onSuccess = { r ->
                o.route.show(start, end, r.geometry, r.favouriteParts)
                routeSummary = summarize(r.distanceM, r.durationS, r.favouriteShare, r.fastestDurationS)
            },
            onFailure = {
                routeEnds = null
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
            } else {
                message = snapAndMark(resources, region, tap, o.snap)
            }
            true
        }
        val onLongClick = MapLibreMap.OnMapLongClickListener { point ->
            if (marking) return@OnMapLongClickListener false
            if (ready == null) {
                message = regionStatus(resources, region)
                return@OnMapLongClickListener true
            }
            when (val step = picker.onLongPress(point)) {
                is RoutePicker.Step.StartSet -> {
                    routeEnds = null
                    // New start: check it lies on a road before keeping it.
                    val problem = runCatching { ready.engine.snap(point.toLatLon()) }.exceptionOrNull()
                    if (problem != null) {
                        picker.reset()
                        message = coreErrorMessage(resources, problem)
                    } else {
                        o.route.show(point, null, null)
                        message = resources.getString(R.string.route_pick_end)
                    }
                }
                is RoutePicker.Step.Complete -> {
                    o.route.show(step.start, step.end, null)
                    message = null
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
        // Messages and the route card, top centre, clear of the map controls.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeDrawingPadding()
                .padding(top = 8.dp, start = 64.dp, end = 64.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (message != null || marking) Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    message?.let { Text(it) }
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
                                OutlinedButton(onClick = { endReview(resources.getString(R.string.map_hint)) }) {
                                    OneLine(stringResource(R.string.tag_review_later))
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
            routeEnds?.let {
                RouteCard(
                    summary = routeSummary,
                    budgetPercent = budgetPercent,
                    onBudget = { percent ->
                        budgetPercent = percent
                        RoutePrefs.setBudgetPercent(context, percent)
                    },
                    onClose = {
                        routeEnds = null
                        overlays?.route?.show(null, null, null)
                    },
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
                    ExtendedFloatingActionButton(onClick = { showRides = true }) {
                        Text(stringResource(R.string.rides_open))
                    }
                }
                if (store is StoreState.Ready && region is RegionState.Ready) {
                    ExtendedFloatingActionButton(onClick = {
                        marker.begin()
                        markSession++
                        marking = true
                        draft = null
                        showDraft()
                        message = resources.getString(R.string.section_pick_start)
                    }) { Text(stringResource(R.string.section_mark)) }
                }
                val active = recording as? Recording.State.Active
                ExtendedFloatingActionButton(onClick = {
                    if (active != null) {
                        RecordingService.stop(context)
                    } else {
                        recordPermissions.launch(recordingPermissions())
                    }
                }) {
                    Text(
                        if (active != null) {
                            stringResource(R.string.record_stop, sectionKm(active.distanceM))
                        } else {
                            stringResource(R.string.record_start)
                        },
                    )
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

/** Snaps [tap] with the region's engine, draws the result and returns a line to show. */
private fun snapAndMark(res: Resources, region: RegionState, tap: LatLng, marker: SnapMarker): String =
    when (region) {
        is RegionState.Ready -> try {
            val p = region.engine.snap(LatLon(tap.latitude, tap.longitude))
            marker.show(tap, LatLng(p.position.lat, p.position.lon))
            res.getString(
                R.string.snap_result,
                p.distanceM.roundToInt(),
                p.edge.toLong(),
                (p.offset * 100).roundToInt(),
            )
        } catch (e: MotoException) {
            marker.show(tap, null)
            coreErrorMessage(res, e)
        }
        else -> regionStatus(res, region)
    }

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

/** MapLibre's default control margin. */
private val CONTROL_MARGIN: Dp = 4.dp

/** MapLibre's default attribution offset from the left, which keeps it clear of the logo. */
private val ATTRIBUTION_OFFSET: Dp = 92.dp

/** Moves the compass, logo and attribution inside the safe area; px arguments. */
private fun applyControlMargins(map: MapLibreMap, insets: SafeInsets, margin: Int, attributionOffset: Int) {
    map.uiSettings.apply {
        setCompassMargins(0, insets.top + margin, insets.right + margin, 0)
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
