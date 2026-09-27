// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import se.gangefors.moto.core.Engine
import se.gangefors.moto.core.defaultRouteOptions
import se.gangefors.moto.core.ExportFormat
import se.gangefors.moto.core.ImportReport
import se.gangefors.moto.core.SectionStore
import se.gangefors.moto.core.Track
import se.gangefors.moto.core.Gravel
import se.gangefors.moto.core.SavedRoute
import se.gangefors.moto.core.exportExtension

/**
 * The rider's settings and data, in this order: what routes do with
 * gravel roads ([gravel], the same setting as on the route and loop
 * cards); Routes & rides, saved routes and recorded or imported rides in
 * one list, newest first, each shown on the map ([onShowRoute], [onShow]),
 * renamed, shared (to a nav app), saved as a GPX file or deleted (tapped
 * twice), and a ride also saved as a route to ride again, plus Import GPX
 * for rides; all saved sections, exported as GeoJSON (plain or
 * compressed) or imported from such a file ([engine] fits them to the
 * map, [onSectionsChanged] reloads them); and the map region. Files are
 * written and read only where the rider picks with the system file picker:
 * no storage permission, and nothing leaves the phone unless the rider
 * sends it. [onMessage] reports what happened. At the bottom, About and
 * licences opens [AboutDialog].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RidesSheet(
    store: SectionStore,
    engine: Engine?,
    onSectionsChanged: () -> Unit,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit,
    onShow: (Track) -> Unit,
    onShowRoute: (SavedRoute) -> Unit,
    gravel: Gravel,
    onGravel: (Gravel) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    var tracks by remember { mutableStateOf<List<Track>?>(null) }
    // Routes & rides: the one armed for deleting, being renamed, being
    // saved as a route, or being saved as a file.
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<LibraryItem?>(null) }
    var savingAsRoute by remember { mutableStateOf<Track?>(null) }
    var exporting by remember { mutableStateOf<LibraryItem?>(null) }

    var routes by remember { mutableStateOf<List<SavedRoute>?>(null) }

    suspend fun reload() {
        tracks = withContext(Dispatchers.IO) { runCatching { store.listTracks() }.getOrDefault(emptyList()) }
        routes = withContext(Dispatchers.IO) { runCatching { store.listRoutes() }.getOrDefault(emptyList()) }
    }
    LaunchedEffect(store) { reload() }

    /** The GPX of a route or ride, named as listed. Call off the main thread. */
    fun gpxOf(item: LibraryItem): String = when (item) {
        is LibraryItem.Ride ->
            store.exportTrackGpx(item.track.id, libraryTitle(item, zone))
                ?: error(resources.getString(R.string.rides_gone))
        is LibraryItem.Route -> {
            val e = engine ?: error(resources.getString(R.string.region_missing))
            val line = store.routeGeometry(item.route.id) ?: error(resources.getString(R.string.rides_gone))
            e.routeGpx(line, item.route.name, routeOptions(defaultRouteOptions(), ROUTE_EXTRA_PERCENT, gravel))
        }
    }

    fun fileNameOf(item: LibraryItem): String = when (item) {
        is LibraryItem.Ride -> rideFileName(item.track.startedAt, zone)
        is LibraryItem.Route -> routeFileName(item.route.createdAt, zone)
    }

    val saveAs = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml"),
    ) { uri: Uri? ->
        val item = exporting ?: return@rememberLauncherForActivityResult
        exporting = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val gpx = gpxOf(item)
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(gpx.toByteArray()) }
                        ?: error(resources.getString(R.string.rides_cannot_write))
                }
            }
            onMessage(
                result.fold(
                    onSuccess = { resources.getString(R.string.rides_exported) },
                    onFailure = { resources.getString(R.string.rides_export_failed, it.message ?: it.toString()) },
                ),
            )
        }
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
                    onFailure = { onMessage(resources.getString(R.string.route_share_failed, it.message ?: it.toString())) },
                )
            }
        }

        override fun export(item: LibraryItem) {
            exporting = item
            saveAs.launch(fileNameOf(item))
        }

        override fun saveAsRoute(item: LibraryItem.Ride) {
            savingAsRoute = item.track
        }

        override fun armDelete(item: LibraryItem) {
            confirmDelete = item.key
        }

        override fun delete(item: LibraryItem) {
            confirmDelete = null
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        when (item) {
                            is LibraryItem.Route -> store.deleteRoute(item.route.id)
                            is LibraryItem.Ride -> store.deleteTrack(item.track.id)
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
    var busy by remember { mutableStateOf(false) }
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
                    val bytes = store.exportSections(format)
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: error(resources.getString(R.string.rides_cannot_write))
                }
            }
            busy = false
            onMessage(
                result.fold(
                    onSuccess = { resources.getString(R.string.sections_exported) },
                    onFailure = { resources.getString(R.string.rides_export_failed, it.message ?: it.toString()) },
                ),
            )
        }
    }
    // Rides: import a GPX file from another app or an earlier export.
    val openRide = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error(resources.getString(R.string.sections_cannot_read))
                    val bytes = input.use { readCapped(it, MAX_GPX_FILE_BYTES) }
                        ?: error(resources.getString(R.string.sections_file_too_large, MAX_GPX_FILE_BYTES shr 20))
                    store.importTrackGpx(bytes)
                }
            }
            busy = false
            reload()
            onMessage(
                result.fold(
                    onSuccess = { t -> resources.getString(R.string.rides_imported, sectionKm(t.distanceM)) },
                    onFailure = { resources.getString(R.string.rides_import_failed, it.message ?: it.toString()) },
                ),
            )
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
                    store.importSections(bytes, engine)
                }
            }
            busy = false
            result.onSuccess { onSectionsChanged() }
            onMessage(
                result.fold(
                    onSuccess = { r -> importSummary(resources, r) },
                    onFailure = { resources.getString(R.string.sections_import_failed, it.message ?: it.toString()) },
                ),
            )
        }
    }

    var showAbout by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        // One list that scrolls as a whole, so every part (the rides at the
        // bottom too) can be reached however long the others get.
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            item(key = "routing") {
                Column {
                    Text(stringResource(R.string.routing_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.routing_gravel), Modifier.padding(top = 8.dp))
                    GravelChips(gravel, onGravel)
                    Text(
                        stringResource(R.string.routing_gravel_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "library-title") {
                Column {
                    Spacer(Modifier.height(24.dp))
                    Text(stringResource(R.string.library_title), style = MaterialTheme.typography.titleLarge)
                    OutlinedButton(onClick = { openRide.launch(arrayOf("*/*")) }, enabled = !busy) {
                        OneLine(stringResource(R.string.rides_import))
                    }
                }
            }
            val library = libraryItems(routes, tracks)
            when {
                library == null -> item(key = "library-loading") {
                    Text(stringResource(R.string.rides_loading), Modifier.padding(vertical = 16.dp))
                }
                library.isEmpty() -> item(key = "library-none") {
                    Text(stringResource(R.string.library_none), Modifier.padding(vertical = 16.dp))
                }
                else -> items(library, key = { it.key }) { item ->
                    LibraryRow(item, zone, confirmDelete == item.key, actions)
                }
            }
            item(key = "sections") {
                Column {
                    Spacer(Modifier.height(24.dp))
                    Text(stringResource(R.string.sections_title), style = MaterialTheme.typography.titleLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box {
                            OutlinedButton(onClick = { formatMenu = true }, enabled = !busy) {
                                OneLine(stringResource(R.string.sections_export))
                            }
                            DropdownMenu(expanded = formatMenu, onDismissRequest = { formatMenu = false }) {
                                EXPORT_FORMATS.forEach { (format, label) ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(label)) },
                                        onClick = {
                                            formatMenu = false
                                            exportFormat = format
                                            saveSections.launch(sectionsFileName(System.currentTimeMillis() / 1000, zone, exportExtension(format)))
                                        },
                                    )
                                }
                            }
                        }
                        OutlinedButton(onClick = { openSections.launch(arrayOf("*/*")) }, enabled = !busy) {
                            OneLine(stringResource(R.string.sections_import))
                        }
                    }
                }
            }
            item(key = "region") {
                Column {
                    Spacer(Modifier.height(24.dp))
                    RegionSection()
                }
            }
            item(key = "about") {
                TextButton(
                    onClick = { showAbout = true },
                    modifier = Modifier.padding(top = 16.dp),
                ) { OneLine(stringResource(R.string.about_open)) }
            }
        }
    }
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
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
    savingAsRoute?.let { t ->
        RouteNameDialog(
            title = stringResource(R.string.library_save_as_route_title),
            initial = rideName(t.name, t.startedAt, zone),
            onDismiss = { savingAsRoute = null },
            onSave = { name ->
                savingAsRoute = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { store.saveTrackAsRoute(t.id, name) } }
                    onMessage(
                        result.fold(
                            onSuccess = { resources.getString(R.string.route_saved, it?.name ?: name) },
                            onFailure = { resources.getString(R.string.route_save_failed, it.message ?: it.toString()) },
                        ),
                    )
                    reload()
                }
            },
        )
    }
}

internal fun rideSummary(res: android.content.res.Resources, t: Track): String {
    val count = t.pointCount.coerceAtMost(Int.MAX_VALUE.toULong()).toInt()
    val fixes = res.getQuantityString(R.plurals.gps_fixes, count, count)
    val ended = t.endedAt ?: return res.getString(R.string.rides_recording, fixes)
    return res.getString(
        R.string.rides_summary,
        sectionKm(t.distanceM),
        formatDuration((ended - t.startedAt) * 1000),
        fixes,
    )
}

/** Export formats offered, with their labels. */
private val EXPORT_FORMATS = listOf(
    ExportFormat.GEO_JSON to R.string.format_geojson,
    ExportFormat.ZIP to R.string.format_zip,
    ExportFormat.GZIP to R.string.format_gzip,
)

private fun importSummary(res: android.content.res.Resources, r: ImportReport): String =
    res.getString(
        R.string.sections_imported,
        r.added.toLong(),
        r.skipped.toLong(),
        r.replaced.toLong(),
        r.unmatched.toLong(),
    )
