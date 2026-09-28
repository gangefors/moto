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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * Menu → Map region (ADR-0008), in two groups as Android's settings are:
 * the region on this phone (with a bin to remove a downloaded one), and
 * the regions to download, looked up as soon as the page opens. Each
 * offer is a row with Download or Update, or "Installed"; a download shows
 * its progress in its row, with an X to stop it.
 */
@Composable
fun RegionSection() {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val active by Regions.active.collectAsState()
    val download by Regions.download.collectAsState()
    var confirmRemove by remember { mutableStateOf(false) }
    val installed = active.downloaded
    val working = download is DownloadState.Downloading || download is DownloadState.Installing
    // The offers from the last lookup, kept while one of them downloads.
    var offers by remember { mutableStateOf<List<RegionOffer>?>(null) }
    LaunchedEffect(download) {
        when (val d = download) {
            is DownloadState.Offers -> offers = d.offers
            is DownloadState.Failed -> if (d.offers.isNotEmpty()) offers = d.offers
            else -> {}
        }
    }
    LaunchedEffect(Unit) { if (download is DownloadState.Idle) Regions.check(context) }

    SettingsGroup(stringResource(R.string.region_group_phone), first = true)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                when {
                    installed != null -> installed.name
                    active.state is RegionState.Ready -> stringResource(R.string.region_bundled)
                    active.state is RegionState.Loading -> stringResource(R.string.region_loading)
                    else -> stringResource(R.string.region_none)
                },
            )
            if (installed != null) {
                Supporting(stringResource(R.string.region_data_from, osmDate(installed.osmTimestamp, zone)))
            }
        }
        if (installed != null) {
            DeleteButton(
                confirming = confirmRemove,
                onArm = { confirmRemove = true },
                onDelete = {
                    confirmRemove = false
                    Regions.remove(context)
                },
                enabled = !working,
            )
        }
    }

    SettingsGroup(stringResource(R.string.region_group_available))
    val d = download
    val shown = (d as? DownloadState.Offers)?.offers ?: offers ?: when (d) {
        is DownloadState.Downloading -> listOf(d.offer)
        is DownloadState.Installing -> listOf(d.offer)
        else -> null
    }
    when {
        shown != null && shown.isEmpty() -> Text(stringResource(R.string.region_no_offers), Modifier.padding(vertical = 8.dp))
        shown != null -> shown.forEach { OfferRow(it, installed, zone, d, busy = working) }
        d is DownloadState.Checking || d is DownloadState.Idle ->
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.region_checking), Modifier.padding(start = 12.dp))
            }
    }
    if (d is DownloadState.Failed) {
        Text(
            stringResource(R.string.region_failed_download, d.message),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        if (d.offers.isEmpty() && offers == null) {
            OutlinedButton(onClick = { Regions.check(context) }) { OneLine(stringResource(R.string.region_retry)) }
        }
    }
    Text(
        stringResource(R.string.region_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp),
    )
}

/** Secondary text under a row's name. */
@Composable
private fun Supporting(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * An offered region: its name, size and date, and Download, Update or
 * "Installed"; while it downloads ([download] is about it), its progress
 * with an X to stop. Other offers can't start while [busy].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OfferRow(offer: RegionOffer, installed: DownloadedRegion?, zone: ZoneId, download: DownloadState, busy: Boolean) {
    val context = LocalContext.current
    val total = offer.gzBytes.toLong()
    val mine = when (download) {
        is DownloadState.Downloading -> download.offer.id == offer.id
        is DownloadState.Installing -> download.offer.id == offer.id
        else -> false
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // The action wraps under the text when there is no room.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.padding(end = 8.dp, top = 4.dp, bottom = 4.dp)) {
                Text(offer.name)
                Supporting(
                    when (download) {
                        is DownloadState.Downloading if mine ->
                            stringResource(R.string.region_progress, mb(download.done), mb(total))
                        is DownloadState.Installing if mine -> stringResource(R.string.region_installing_short)
                        else -> stringResource(R.string.region_offer, mb(total), osmDate(offer.osmTimestamp, zone))
                    },
                )
            }
            when {
                download is DownloadState.Downloading && mine ->
                    IconButton(onClick = { Regions.cancel() }) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.region_stop))
                    }
                download is DownloadState.Installing && mine -> {}
                else -> when (offerAction(installed?.id, installed?.osmTimestamp, offer.id, offer.osmTimestamp)) {
                    OfferAction.INSTALLED -> Text(
                        stringResource(R.string.region_installed_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                    OfferAction.UPDATE -> Button(onClick = { Regions.start(context, offer) }, enabled = !busy) {
                        OneLine(stringResource(R.string.region_update))
                    }
                    OfferAction.DOWNLOAD -> OutlinedButton(onClick = { Regions.start(context, offer) }, enabled = !busy) {
                        OneLine(stringResource(R.string.region_download))
                    }
                }
            }
        }
        if (mine) {
            when (download) {
                is DownloadState.Downloading -> LinearProgressIndicator(
                    progress = { downloadShare(download.done, total) },
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}
