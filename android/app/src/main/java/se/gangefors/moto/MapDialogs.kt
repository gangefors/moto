// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import se.gangefors.moto.debug.DebugTools
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import se.gangefors.moto.core.NewSection
import se.gangefors.moto.core.Rating
import se.gangefors.moto.core.SectionSource
import se.gangefors.moto.core.SectionUpdate
import androidx.compose.runtime.getValue

@Composable
internal fun MapScreenScope.MapDialogs() {
    with(state) {
        if (showHelp) HelpPage(onDismiss = { showHelp = false })
        if (showAbout) AboutDialog(onDismiss = { showAbout = false })
        // Backup and restore (ADR-0012): its dialogs, and after a restore the
        // favourites, rides and the count of tags waiting for review read
        // again (so the flag shows restored tags at once).
        BackupFlow(
            open = showBackup,
            onClose = { showBackup = false },
            store = (store as? StoreState.Ready)?.store,
            engine = (region as? RegionState.Ready)?.engine,
            busy = busy,
            onRestored = {
                val s = (store as? StoreState.Ready)?.store
                if (s != null) {
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { s.list(null) } }.onSuccess { sections = it }
                    }
                }
                RideChanges.changed()
                refreshPendingTags()
            },
            onOpenRegions = { dataPage = DataPage.REGION },
        )
        if (showDebug) DebugTools.Page(onDismiss = { showDebug = false })

        // Rate and save the proposed section, and name it if the rider likes
        // (else it goes by where it runs, suggested in the empty name field).
        val proposed = draft
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
                    // Whether the tag was deleted; set off the main thread
                    // before `after` runs.
                    var removed = false
                    changeSectionsThen(
                        action = { st ->
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
                            val text = when (val outcome = addOutcome(result)) {
                                AddOutcome.Covered -> resources.getString(R.string.section_covered)
                                is AddOutcome.Saved -> if (outcome.replaced == 0) {
                                    resources.getString(R.string.section_saved)
                                } else {
                                    resources.getQuantityString(R.plurals.section_saved_replacing, outcome.replaced, outcome.replaced)
                                }
                            }
                            // Saved, or a favourite covers it already: the tag
                            // is reviewed, so it goes (false if already gone).
                            // A failed delete doesn't fail the save: the
                            // favourite is kept and shown, and the tag stays.
                            removed = tag != null && runCatching { st.deleteTag(tag.id) }.isSuccess
                            text
                        },
                        // A failed save or delete keeps the tag, counted as
                        // skipped.
                        after = { ok -> if (tag != null) tagHandled(tag, removed = ok && removed) },
                    )
                },
            )
        }

        // The name offered for a route or loop being saved: from where it runs
        // ("Lund → Höör", "Loop from Lund via Höör") when the region has
        // names, else its time and length. Found off the main thread first.
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
        if (leavingRoute) {
            LeaveRouteDialog(
                recording = (recording as? Recording.State.Active)?.trackId != null,
                onEndRide = {
                    leavingRoute = false
                    RecordingService.stop(context)
                },
                onKeepRecording = {
                    leavingRoute = false
                    RecordingService.unfollow(context)
                },
                onDismiss = { leavingRoute = false },
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
}
