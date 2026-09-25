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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.gangefors.moto.core.Engine
import se.gangefors.moto.core.ExportFormat
import se.gangefors.moto.core.ImportReport
import se.gangefors.moto.core.SectionStore
import se.gangefors.moto.core.Track
import se.gangefors.moto.core.Gravel
import se.gangefors.moto.core.exportExtension

/**
 * The rider's settings and data: what routes do with gravel roads
 * ([gravel], the same setting as on the route and loop cards), all saved
 * sections, exported as GeoJSON (plain or
 * compressed) or imported from such a file, and the recorded rides, newest
 * first, each exported as GPX or deleted (tapped twice), plus rides imported
 * from a GPX file (another app's track, or an earlier export). Files are written
 * and read only where the rider picks with the system file picker: no
 * storage permission, and nothing leaves the phone unless the rider sends
 * it. [engine] fits imported sections to the map; [onSectionsChanged]
 * reloads them after an import; [onShow] draws a ride on the map (to mark
 * sections along it); [onMessage] reports what happened. At the
 * bottom, About and licences opens [AboutDialog].
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
    gravel: Gravel,
    onGravel: (Gravel) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    var tracks by remember { mutableStateOf<List<Track>?>(null) }
    var confirmDelete by remember { mutableLongStateOf(0L) }
    var exporting by remember { mutableStateOf<Track?>(null) }

    suspend fun reload() {
        tracks = withContext(Dispatchers.IO) { runCatching { store.listTracks() }.getOrDefault(emptyList()) }
    }
    LaunchedEffect(store) { reload() }

    val saveAs = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml"),
    ) { uri: Uri? ->
        val track = exporting ?: return@rememberLauncherForActivityResult
        exporting = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val gpx = store.exportTrackGpx(track.id, rideTitle(track.startedAt, zone))
                        ?: error(resources.getString(R.string.rides_gone))
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
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.routing_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.routing_gravel), Modifier.padding(top = 8.dp))
            GravelChips(gravel, onGravel)
            Text(
                stringResource(R.string.routing_gravel_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.rides_title), style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = { openRide.launch(arrayOf("*/*")) }, enabled = !busy) {
                OneLine(stringResource(R.string.rides_import))
            }
            val list = tracks
            when {
                list == null -> Text(stringResource(R.string.rides_loading), Modifier.padding(vertical = 16.dp))
                list.isEmpty() -> Text(stringResource(R.string.rides_none), Modifier.padding(vertical = 16.dp))
                else -> LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    items(list, key = { it.id }) { t ->
                        // The buttons wrap under the ride's text when there
                        // is no room beside it (narrow screens, large fonts).
                        FlowRow(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.padding(end = 8.dp)) {
                                Text(rideTitle(t.startedAt, zone))
                                Text(
                                    rideSummary(resources, t),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(
                                    onClick = { onShow(t) },
                                    enabled = t.endedAt != null,
                                ) { OneLine(stringResource(R.string.rides_show)) }
                                TextButton(
                                    onClick = {
                                        exporting = t
                                        saveAs.launch(rideFileName(t.startedAt, zone))
                                    },
                                    enabled = t.endedAt != null,
                                ) { OneLine(stringResource(R.string.rides_export)) }
                                DeleteButton(
                                    confirming = confirmDelete == t.id,
                                    onArm = { confirmDelete = t.id },
                                    onDelete = {
                                        confirmDelete = 0L
                                        scope.launch {
                                            withContext(Dispatchers.IO) { runCatching { store.deleteTrack(t.id) } }
                                            reload()
                                        }
                                    },
                                    enabled = t.endedAt != null,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { showAbout = true }) { OneLine(stringResource(R.string.about_open)) }
        }
    }
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
}

private fun rideSummary(res: android.content.res.Resources, t: Track): String {
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
