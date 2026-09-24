// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import se.gangefors.moto.core.SectionStore
import se.gangefors.moto.core.Track

/**
 * The rider's recorded rides, newest first, each with Export (a GPX file
 * saved wherever the rider picks with the system file picker: no storage
 * permission, and nothing leaves the phone unless the rider sends it) and
 * Delete (tapped twice). [onMessage] reports what happened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RidesSheet(store: SectionStore, onMessage: (String) -> Unit, onDismiss: () -> Unit) {
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

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.rides_title), style = MaterialTheme.typography.titleLarge)
            val list = tracks
            when {
                list == null -> Text(stringResource(R.string.rides_loading), Modifier.padding(vertical = 16.dp))
                list.isEmpty() -> Text(stringResource(R.string.rides_none), Modifier.padding(vertical = 16.dp))
                else -> LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    items(list, key = { it.id }) { t ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(rideTitle(t.startedAt, zone))
                                Text(
                                    rideSummary(resources, t),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(
                                    onClick = {
                                        exporting = t
                                        saveAs.launch(rideFileName(t.startedAt, zone))
                                    },
                                    enabled = t.endedAt != null,
                                ) { Text(stringResource(R.string.rides_export)) }
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
        }
    }
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

