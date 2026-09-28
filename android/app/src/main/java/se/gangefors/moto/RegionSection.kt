// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import se.gangefors.moto.core.RegionOffer

/** "2026-09-27", the day the map data is from, in local time. */
fun osmDate(timestamp: Long, zone: ZoneId): String =
    Instant.ofEpochSecond(timestamp).atZone(zone)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT))

/**
 * My data → Map region (ADR-0008): which region the app routes on, the
 * regions there are to download, and the download itself (progress,
 * stop), plus a bin to remove a downloaded region again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RegionSection() {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val active by Regions.active.collectAsState()
    val download by Regions.download.collectAsState()
    var confirmRemove by remember { mutableStateOf(false) }
    val installed = active.downloaded

    Text(stringResource(R.string.region_title), style = MaterialTheme.typography.titleLarge)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                installed != null -> stringResource(
                    R.string.region_installed,
                    installed.name,
                    osmDate(installed.osmTimestamp, zone),
                )
                active.state is RegionState.Ready -> stringResource(R.string.region_bundled)
                active.state is RegionState.Loading -> stringResource(R.string.region_loading)
                else -> stringResource(R.string.region_none)
            },
            Modifier.weight(1f).padding(vertical = 8.dp),
        )
        if (installed != null) {
            DeleteButton(
                confirming = confirmRemove,
                onArm = { confirmRemove = true },
                onDelete = {
                    confirmRemove = false
                    Regions.remove(context)
                },
                enabled = download !is DownloadState.Downloading && download !is DownloadState.Installing,
            )
        }
    }

    when (val d = download) {
        DownloadState.Idle -> OutlinedButton(onClick = { Regions.check(context) }) {
            OneLine(stringResource(R.string.region_check))
        }
        DownloadState.Checking -> Text(stringResource(R.string.region_checking), Modifier.padding(vertical = 8.dp))
        is DownloadState.Offers -> Offers(d.offers, installed, zone)
        is DownloadState.Downloading -> Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        R.string.region_downloading,
                        d.offer.name,
                        mb(d.done),
                        mb(d.offer.gzBytes.toLong()),
                    ),
                    Modifier.weight(1f),
                )
                IconButton(onClick = { Regions.cancel() }) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.region_stop))
                }
            }
            LinearProgressIndicator(
                progress = { (d.done.toFloat() / d.offer.gzBytes.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is DownloadState.Installing -> Column(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.region_installing, d.offer.name), Modifier.padding(vertical = 8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        is DownloadState.Failed -> Column {
            Text(
                stringResource(R.string.region_failed_download, d.message),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            if (d.offers.isEmpty()) {
                OutlinedButton(onClick = { Regions.check(context) }) { OneLine(stringResource(R.string.region_check)) }
            } else {
                Offers(d.offers, installed, zone)
            }
        }
    }
    // Debug builds: how long the steps of this start took.
    val debuggable = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    val times by StartupTimes.steps.collectAsState()
    if (debuggable && times.isNotEmpty()) {
        Text(
            times.joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    // Debug builds: time the region check, twice, step by step.
    if (debuggable && installed != null) {
        val scope = rememberCoroutineScope()
        var profile by remember { mutableStateOf<List<String>?>(null) }
        var profiling by remember { mutableStateOf(false) }
        TextButton(
            onClick = {
                profiling = true
                scope.launch {
                    profile = withContext(Dispatchers.IO) {
                        runCatching { Regions.profileOpen(context) }.getOrElse { listOf(it.message ?: it.toString()) }
                    }
                    profiling = false
                }
            },
            enabled = !profiling,
        ) { OneLine(if (profiling) "Timing the region check…" else "Time the region check") }
        profile?.let {
            Text(
                it.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Text(
        stringResource(R.string.region_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** The regions to download, each with its size and Download, Update or "Installed". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Offers(offers: List<RegionOffer>, installed: DownloadedRegion?, zone: ZoneId) {
    val context = LocalContext.current
    if (offers.isEmpty()) {
        Text(stringResource(R.string.region_no_offers), Modifier.padding(vertical = 8.dp))
        return
    }
    offers.forEach { o ->
        FlowRow(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.padding(end = 8.dp)) {
                Text(o.name)
                Text(
                    stringResource(
                        R.string.region_offer,
                        mb(o.gzBytes.toLong()),
                        osmDate(o.osmTimestamp, zone),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val mine = installed?.id == o.id
            when {
                mine && !isUpdate(installed?.osmTimestamp, o.osmTimestamp) ->
                    Text(stringResource(R.string.region_up_to_date), Modifier.padding(8.dp))
                else -> OutlinedButton(onClick = { Regions.start(context, o) }) {
                    OneLine(stringResource(if (mine) R.string.region_update else R.string.region_download))
                }
            }
        }
    }
}
