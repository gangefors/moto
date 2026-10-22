// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.gangefors.moto.core.SectionStore
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withLock
import org.maplibre.android.maps.MapLibreMap
import se.gangefors.moto.debug.DebugTools

/**
 * Runs [action] on the store off the main thread, then reloads the
 * sections and shows the message [action] returns. [after] then runs on
 * the main thread with whether [action] succeeded.
 */
internal fun MapScreenScope.changeSectionsThen(
    action: (SectionStore) -> String?,
    after: (ok: Boolean) -> Unit = {},
) {
    with(state) {
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
                    after(true)
                },
                onFailure = {
                    notify(resources.getString(R.string.sections_failed, it.message ?: it.toString()), long = true)
                    after(false)
                },
            )
        }
    }
}

/** One notice, with no action, when a map update removed [removed]
 * surface corrections that no longer matched it (ADR-0018). */
internal fun MapScreenScope.surfaceRemovedNotice(removed: ULong) {
    if (removed == 0uL) return
    val n = removed.count()
    state.noticeScope.launch {
        state.notices.showSnackbar(
            resources.getQuantityString(R.plurals.surface_removed_notice, n, n),
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
    }
}

/** Runs [action] on the store off the main thread, then reloads the sections. */
internal fun MapScreenScope.changeSections(done: String?, action: (SectionStore) -> Unit) = with(state) {
    changeSectionsThen(action = {
        action(it)
        done
    })
}

@Composable
internal fun MapScreenScope.StoreAndRegionEffects() {
    with(state) {
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
                        val sectionsTouched = report.checked > 0uL || report.offMap > 0uL
                        val correctionsTouched = report.surfaceChecked > 0uL || report.surfaceOffMap > 0uL
                        Triple(report, if (sectionsTouched) s.list(null) else null, correctionsTouched)
                    }
                }
            }
            result.fold(
                onSuccess = { (report, updated, correctionsTouched) ->
                    updated?.let { sections = it }
                    if (correctionsTouched) surfaceVersion++
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
                            // Then the corrections the update removed, once.
                            surfaceRemovedNotice(report.surfaceRemoved)
                        }
                    } else {
                        surfaceRemovedNotice(report.surfaceRemoved)
                    }
                },
                onFailure = { notify(resources.getString(R.string.sections_failed, it.message ?: it.toString()), long = true) },
            )
        }

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
        // The rider's own surface on the road tapped (ADR-0018), for the
        // last row of its card.
        LaunchedEffect(roadInfo, surfaceVersion, sections) {
            val info = roadInfo
            val s = (store as? StoreState.Ready)?.store
            if (info == null || s == null) {
                roadCorrection = null
                roadCorrectionFor = null
                return@LaunchedEffect
            }
            val found = withContext(Dispatchers.IO) { runCatching { s.surfaceCorrectionAt(info.point.position) } }
            roadCorrection = found.getOrNull()
            roadCorrectionFor = info.takeIf { found.isSuccess }
        }
        LaunchedEffect(store, region, sections, surfaceVersion, ridesVersion) {
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
    }
}

@Composable
internal fun MapScreenScope.FavouriteLayerEffects() {
    with(state) {
        LaunchedEffect(overlays, sections, sectionGravel, gravel) {
            val hidden = hiddenForGravel(sectionGravel, gravel)
            val shown = visibleSections(sections, showUnmatched = false).filterNot { it.id in hidden }
            overlays?.sections?.show(shown)
            overlays?.sections?.showGravel(gravelParts(sectionGravel, shown))
        }
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
                o.ridden.show(emptyList())
                return@LaunchedEffect
            }
            val hidden = hiddenForGravel(sectionGravel, gravel)
            val shown = visibleSections(sections, showUnmatched = false).filterNot { it.id in hidden }
            val hiddenIds = hiddenSectionIds(sections.map { it.id }, shown.map { it.id })
            val lines = withContext(Dispatchers.Default) { runCatching {
                    DebugTools.query("ridden roads", ::linesSummary) { f.riddenLines(engine, hiddenIds) }
                }.getOrDefault(emptyList()) }
            o.ridden.show(lines)
        }
    }
}
