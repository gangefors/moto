// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import se.gangefors.moto.debug.DebugTools
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.gangefors.moto.core.Avoid
import se.gangefors.moto.core.Engine
import se.gangefors.moto.core.Gravel
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.Section
import se.gangefors.moto.core.defaultRouteOptions
import se.gangefors.moto.core.ExportFormat
import se.gangefors.moto.core.ImportItem
import se.gangefors.moto.core.ImportReport
import se.gangefors.moto.core.SectionStore
import se.gangefors.moto.core.Track
import se.gangefors.moto.core.SavedRoute
import se.gangefors.moto.core.exportExtension

/** The topics of the menu that are pages of the rider's data. */
enum class DataPage { LIBRARY, SECTIONS, REGION }

/**
 * A page of the rider's data, opened from the menu: [DataPage.LIBRARY],
 * Routes & rides, saved routes and recorded or imported rides in one
 * list, newest first, all or only one kind (filter chips), each shown on
 * the map with a tap ([onShowRoute], [onShow]), renamed, shared (to a
 * nav app) and deleted (the bin, tapped twice), plus Import GPX for
 * rides in the page's ⋮ menu; [DataPage.SECTIONS], the saved [sections] as a list (see
 * [SectionsList]), with import and export (GeoJSON, plain or compressed)
 * in the page's ⋮ menu ([engine] fits imports to the map,
 * [onSectionsChanged] reloads them); [DataPage.REGION], the
 * map region. Files are written and read only where the rider picks with
 * the system file picker: no storage permission, and nothing leaves the
 * phone unless the rider sends it. What was done shows as a toast; a
 * failure as a notice at the bottom of the page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RidesSheet(
    page: DataPage,
    store: SectionStore,
    engine: Engine?,
    onSectionsChanged: () -> Unit,
    onDismiss: () -> Unit,
    onShow: (Track) -> Unit,
    onShowRoute: (SavedRoute) -> Unit,
    /** For the route points of an exported route (the rider's settings). */
    gravel: Gravel,
    avoid: Avoid,
    /** The saved sections, for the Sections page. */
    sections: List<Section> = emptyList(),
    /** Open Sections showing only those that need attention. */
    sectionFilter: SectionFilter = SectionFilter(),
    /** The Sections page's filter chips changed. */
    onSectionFilter: (SectionFilter) -> Unit = {},
    /** The rider's position, to list sections nearest first. */
    here: LatLon? = null,
    onShowSection: (Section) -> Unit = {},
    onDeleteSections: (List<Long>) -> Unit = {},
    sectionActions: @Composable (section: Section, close: () -> Unit) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    // What happened: done (exported, imported, saved) as a toast; a failure
    // as a notice at the bottom of the page that stays until closed, so
    // its reason can be read.
    val notices = remember { SnackbarHostState() }
    fun done(text: String) = Toasts.show(text)
    fun failed(text: String) {
        scope.launch {
            notices.currentSnackbarData?.dismiss()
            notices.showSnackbar(text, withDismissAction = true, duration = SnackbarDuration.Indefinite)
        }
    }
    val zone = remember { ZoneId.systemDefault() }
    var tracks by remember { mutableStateOf<List<Track>?>(null) }
    // Routes & rides: the one being renamed, and which kind is listed.
    var renaming by remember { mutableStateOf<LibraryItem?>(null) }
    var libraryFilter by rememberSaveable { mutableStateOf(RoutePrefs.libraryFilter(context)) }

    var routes by remember { mutableStateOf<List<SavedRoute>?>(null) }

    suspend fun reload() {
        tracks = withContext(Dispatchers.IO) { runCatching { store.listTracks() }.getOrDefault(emptyList()) }
        routes = withContext(Dispatchers.IO) { runCatching { store.listRoutes() }.getOrDefault(emptyList()) }
    }
    LaunchedEffect(store) { reload() }

    /** The GPX of a route or ride, named as listed. Call off the main thread. */
    fun gpxOf(item: LibraryItem): String = DebugTools.query("saved GPX", ::bytesSummary) {
        when (item) {
            is LibraryItem.Ride ->
                store.exportTrackGpx(item.track.id, libraryTitle(item, zone))
                    ?: error(resources.getString(R.string.rides_gone))
            is LibraryItem.Route -> {
                val e = engine ?: error(resources.getString(R.string.region_missing))
                val line = store.routeGeometry(item.route.id) ?: error(resources.getString(R.string.rides_gone))
                e.routeGpx(line, item.route.name, routeOptions(defaultRouteOptions(), ROUTE_EXTRA_PERCENT, gravel, avoid))
            }
        }
    }

    fun fileNameOf(item: LibraryItem): String = when (item) {
        is LibraryItem.Ride -> rideFileName(item.track.startedAt, zone)
        is LibraryItem.Route -> routeFileName(item.route.createdAt, zone)
    }

    val actions = object : LibraryActions {
        override fun show(item: LibraryItem) = when (item) {
            is LibraryItem.Route -> onShowRoute(item.route)
            is LibraryItem.Ride -> onShow(item.track)
        }

        override fun rename(item: LibraryItem) {
            renaming = item
        }

        override fun share(item: LibraryItem) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        RouteShare.prepare(
                            context,
                            gpxOf(item),
                            fileNameOf(item),
                            resources.getString(R.string.route_share_title),
                        )
                    }
                }
                result.fold(
                    onSuccess = { context.startActivity(it) },
                    onFailure = { failed(resources.getString(R.string.route_share_failed, it.message ?: it.toString())) },
                )
            }
        }

        override fun delete(item: LibraryItem) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        when (item) {
                            is LibraryItem.Route -> store.deleteRoute(item.route.id)
                            is LibraryItem.Ride -> store.deleteTrack(item.track.id).also { RideChanges.changed() }
                        }
                    }
                }
                reload()
            }
        }
    }

    // Sections: export in the chosen format, import any supported file.
    var exportFormat by remember { mutableStateOf<ExportFormat?>(null) }
    var formatMenu by remember { mutableStateOf(false) }
    var libraryMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // The report of the last import, until OK.
    var importReport by remember { mutableStateOf<ImportReportContent?>(null) }
    val saveSections = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        val format = exportFormat ?: return@rememberLauncherForActivityResult
        exportFormat = null
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = DebugTools.query("favourites export", { "${it.size} bytes" }) { store.exportSections(format) }
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: error(resources.getString(R.string.rides_cannot_write))
                }
            }
            busy = false
            result.fold(
                onSuccess = { done(resources.getString(R.string.sections_exported)) },
                onFailure = { failed(resources.getString(R.string.rides_export_failed, it.message ?: it.toString())) },
            )
        }
    }
    // Rides: import GPX files from another app or an earlier export, one
    // ride per file; several can be picked at once.
    val openRides = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        if (uris.size > MAX_GPX_FILES) {
            failed(resources.getString(R.string.rides_import_too_many, MAX_GPX_FILES))
            return@rememberLauncherForActivityResult
        }
        busy = true
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    val name = shownFileName(displayName(context.contentResolver, uri), resources.getString(R.string.rides_import_unnamed))
                    runCatching {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error(resources.getString(R.string.sections_cannot_read))
                        val bytes = input.use { readCapped(it, MAX_GPX_FILE_BYTES) }
                            ?: error(resources.getString(R.string.sections_file_too_large, MAX_GPX_FILE_BYTES shr 20))
                        val got = DebugTools.query("rides import", { "${it.rides.size} rides, ${it.alreadySaved} already saved" }) {
                            store.importTracksGpx(bytes)
                        }
                        // Each new ride named from where it went, as routes
                        // and loops are, when the map has names there.
                        val names = if (engine != null) got.rides.mapNotNull { store.nameRide(resources, engine, it) } else emptyList()
                        RideImport.Imported(name, got.rides.map { it.distanceM }, got.alreadySaved.toInt(), names)
                    }.getOrElse { RideImport.Failed(name, it.message ?: it.toString()) }
                }
            }
            busy = false
            reload()
            val summary = rideImportSummary(results)
            if (summary.imported > 0) RideChanges.changed()
            if (rideImportNeedsReport(results)) {
                importReport = rideImportReport(resources, results)
            } else if (summary.imported == 0) {
                done(resources.getQuantityString(R.plurals.rides_import_all_saved, summary.alreadySaved, summary.alreadySaved))
            } else {
                done(resources.getQuantityString(R.plurals.rides_imported_many, summary.imported, summary.imported, summary.km))
            }
        }
    }
    val openSections = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error(resources.getString(R.string.sections_cannot_read))
                    val bytes = input.use { readCapped(it, MAX_IMPORT_FILE_BYTES) }
                        ?: error(resources.getString(R.string.sections_file_too_large, MAX_IMPORT_FILE_BYTES shr 20))
                    DebugTools.query("favourites import", { "${it.added} added, ${it.skipped} skipped, ${it.replaced} replaced, ${it.unmatched} unmatched" }) {
                        store.importSections(bytes, engine)
                    }
                }
            }
            busy = false
            result.onSuccess { onSectionsChanged() }
            result.fold(
                onSuccess = { r ->
                    if (sectionImportNeedsReport(r)) importReport = sectionImportReport(resources, r) else done(importSummary(resources, r))
                },
                onFailure = { failed(resources.getString(R.string.sections_import_failed, it.message ?: it.toString())) },
            )
        }
    }

    val title = stringResource(
        when (page) {
            DataPage.LIBRARY -> R.string.library_title
            DataPage.SECTIONS -> R.string.sections_title
            DataPage.REGION -> R.string.region_title
        },
    )
    FullPage(
        title,
        onBack = onDismiss,
        notices = notices,
        actions = {
            if (page == DataPage.LIBRARY) {
                // Import is occasional: behind the page's ⋮, as on Sections.
                Box {
                    IconButton(onClick = { libraryMenu = true }, enabled = !busy) {
                        Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.library_menu))
                    }
                    DropdownMenu(expanded = libraryMenu, onDismissRequest = { libraryMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rides_import)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_import), contentDescription = null) },
                            onClick = {
                                libraryMenu = false
                                openRides.launch(arrayOf("*/*"))
                            },
                        )
                    }
                }
            }
            if (page == DataPage.SECTIONS) {
                // Import and export are occasional: behind the page's ⋮.
                Box {
                    IconButton(onClick = { formatMenu = true }, enabled = !busy) {
                        Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.sections_menu))
                    }
                    DropdownMenu(expanded = formatMenu, onDismissRequest = { formatMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sections_import_menu)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_import), contentDescription = null) },
                            onClick = {
                                formatMenu = false
                                openSections.launch(arrayOf("*/*"))
                            },
                        )
                        EXPORT_FORMATS.forEach { (format, label) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sections_export_as, stringResource(label))) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_export), contentDescription = null) },
                                onClick = {
                                    formatMenu = false
                                    exportFormat = format
                                    saveSections.launch(sectionsFileName(System.currentTimeMillis() / 1000, zone, exportExtension(format)))
                                },
                            )
                        }
                    }
                }
            }
        },
    ) {
        if (page == DataPage.SECTIONS) {
            SectionsList(
                sections = sections,
                store = store,
                engine = engine,
                here = here,
                filter = sectionFilter,
                onFilter = onSectionFilter,
                onShow = onShowSection,
                onDelete = onDeleteSections,
                rowActions = sectionActions,
            )
            return@FullPage
        }
        val listState = rememberLazyListState()
        LazyColumn(
            Modifier.fillMaxWidth().scrollHints(listState),
            state = listState,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        ) {
            val library = libraryItems(routes, tracks)
            if (page == DataPage.LIBRARY && !library.isNullOrEmpty()) item(key = "library-filters") {
                // All, only routes or only rides (kept between visits), and
                // what tells them apart (the same line as How to use).
                Column(Modifier.padding(top = 12.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val routeCount = library.count { it is LibraryItem.Route }
                        LibraryFilter.entries.forEach { f ->
                            FilterChip(
                                selected = libraryFilter == f,
                                onClick = {
                                    libraryFilter = f
                                    RoutePrefs.setLibraryFilter(context, f)
                                },
                                label = {
                                    OneLine(
                                        when (f) {
                                            LibraryFilter.ALL -> stringResource(R.string.library_filter_all)
                                            LibraryFilter.ROUTES -> stringResource(R.string.library_filter_routes, routeCount)
                                            LibraryFilter.RIDES -> stringResource(R.string.library_filter_rides, library.size - routeCount)
                                        },
                                    )
                                },
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.library_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (page == DataPage.LIBRARY) when {
                library == null -> item(key = "library-loading") {
                    Text(stringResource(R.string.rides_loading), Modifier.padding(vertical = 16.dp))
                }
                library.isEmpty() -> item(key = "library-none") {
                    Text(stringResource(R.string.library_none), Modifier.padding(vertical = 16.dp))
                }
                else -> items(filterLibrary(library, libraryFilter), key = { it.key }) { item ->
                    LibraryRow(item, zone, actions)
                }
            }
            if (page == DataPage.REGION) item(key = "region") { RegionSection() }
        }
    }
    importReport?.let { ImportReportDialog(it, onDismiss = { importReport = null }) }
    renaming?.let { item ->
        RouteNameDialog(
            title = stringResource(
                if (item is LibraryItem.Ride) R.string.ride_rename_title else R.string.saved_route_rename_title,
            ),
            initial = libraryTitle(item, zone),
            onDismiss = { renaming = null },
            onSave = { name ->
                renaming = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            when (item) {
                                is LibraryItem.Route -> store.renameRoute(item.route.id, name)
                                is LibraryItem.Ride -> store.renameTrack(item.track.id, name)
                            }
                        }
                    }
                    reload()
                }
            },
        )
    }
}

/** A ride's distance and riding time ("Recording" and its GPS fixes while
 * it goes on), with times written as everywhere else ("1 h 5 min"). */
internal fun rideSummary(res: android.content.res.Resources, t: Track): String {
    val ended = t.endedAt ?: run {
        val count = t.pointCount.coerceAtMost(Int.MAX_VALUE.toULong()).toInt()
        return res.getString(R.string.rides_recording, res.getQuantityString(R.plurals.gps_fixes, count, count))
    }
    return res.getString(R.string.rides_summary, sectionKm(t.distanceM), durationText(res, ((ended - t.startedAt) / 60).toInt()))
}

/** Export formats offered, with their labels. */
private val EXPORT_FORMATS = listOf(
    ExportFormat.GEO_JSON to R.string.format_geojson,
    ExportFormat.ZIP to R.string.format_zip,
    ExportFormat.GZIP to R.string.format_gzip,
)

/** Short enough for a toast; sections that don't fit only when there are some. */
/** The name the picker gives the file at [uri], if any (untrusted: shown
 * only through [shownFileName]). */
private fun displayName(resolver: android.content.ContentResolver, uri: Uri): String? = runCatching {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()

/** The report of a ride import (mockup Import GPX report): a summary,
 * then the files not imported (each with why), those with rides already
 * in the app, and those imported with the names their rides got. */
private fun rideImportReport(res: android.content.res.Resources, results: List<RideImport>): ImportReportContent {
    val s = rideImportSummary(results)
    val g = rideImportGroups(results)
    val summary = listOfNotNull(
        s.imported.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.rides_report_imported, it, it, s.km) },
        s.alreadySaved.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.rides_report_already, it, it) },
        s.failed.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.rides_report_failed, it, it) },
    ).joinToString(" · ")
    return ImportReportContent(
        title = res.getString(R.string.rides_import_title),
        summary = summary,
        groups = listOf(
            ReportGroup(
                res.getString(R.string.report_not_imported, g.failed.size),
                error = true,
                rows = g.failed.map { ReportRow(it.name, it.reason) },
            ),
            ReportGroup(
                res.getString(R.string.report_already, g.alreadySaved.size),
                error = false,
                rows = g.alreadySaved.map { f ->
                    val total = f.alreadySaved + f.distancesM.size
                    ReportRow(
                        f.name,
                        if (total == f.alreadySaved) {
                            res.getQuantityString(R.plurals.rides_report_rides, total, total)
                        } else {
                            res.getQuantityString(R.plurals.rides_report_some_saved, total, f.alreadySaved, total)
                        },
                    )
                },
            ),
            ReportGroup(
                res.getString(R.string.report_imported, g.imported.size),
                error = false,
                rows = g.imported.map { f ->
                    val n = f.distancesM.size
                    ReportRow(
                        f.name,
                        res.getQuantityString(R.plurals.rides_report_rides_km, n, n, sectionKm(f.distancesM.sum())),
                        f.rideNames.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                    )
                },
            ),
        ),
    )
}

/** The report of a favourites import: a summary, then the favourites
 * that don't fit the map, those already saved, the saved ones replaced
 * by longer ones, and those added, each by name, rating and length. */
private fun sectionImportReport(res: android.content.res.Resources, r: ImportReport): ImportReportContent {
    fun count(n: ULong) = n.toInt()
    val summary = listOfNotNull(
        count(r.added - r.unmatched).takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.sections_report_added, it, it) },
        count(r.unmatched).takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.sections_report_unmatched, it, it) },
        count(r.skipped).takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.sections_report_skipped, it, it) },
        count(r.replaced).takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.sections_report_replaced, it, it) },
    ).joinToString(" · ")
    fun rows(items: List<ImportItem>) = items.map { i ->
        ReportRow(
            i.name.ifBlank { res.getString(R.string.sections_report_unnamed) },
            res.getString(R.string.sections_report_item, res.getString(ratingLabel(i.rating)), sectionKm(i.lengthM)),
        )
    }
    return ImportReportContent(
        title = res.getString(R.string.sections_import_title),
        summary = summary,
        groups = listOf(
            ReportGroup(res.getString(R.string.report_unmatched, r.unmatchedItems.size), error = true, rows = rows(r.unmatchedItems)),
            ReportGroup(res.getString(R.string.report_already_saved, r.skippedItems.size), error = false, rows = rows(r.skippedItems)),
            ReportGroup(res.getString(R.string.report_replaced, r.replacedItems.size), error = false, rows = rows(r.replacedItems)),
            ReportGroup(res.getString(R.string.report_added, r.addedItems.size), error = false, rows = rows(r.addedItems)),
        ),
    )
}

private fun importSummary(res: android.content.res.Resources, r: ImportReport): String {
    val text = res.getString(R.string.sections_imported, r.added.toLong(), r.skipped.toLong(), r.replaced.toLong())
    return if (r.unmatched > 0uL) res.getString(R.string.sections_imported_unmatched, text, r.unmatched.toLong()) else text
}

