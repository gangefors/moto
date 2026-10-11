// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
 * Menu → Map region (ADR-0008, ADR-0009, ADR-0017), in two groups as
 * Android's settings are: the regions on this phone, and the regions to
 * download, looked up as soon as the page opens. Each region on the phone
 * has a switch to use it (a region switched off stays on the phone but
 * isn't used for routing) and a bin to remove it; the regions in use are
 * one map, joined at their borders. Each offer is a row with Download or
 * Update, or "Installed". Downloads queue and run in the background, one
 * at a time: the queue's first region shows its progress, waiting or
 * failure in its row, with an X that stops the whole queue; the others
 * wait their turn. The group starts with the mobile-data setting.
 */
@Composable
fun RegionSection() {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val active by Regions.active.collectAsState()
    val download by Regions.download.collectAsState()
    val queue by Regions.queue.collectAsState()
    val running by Regions.running.collectAsState()
    val policy by Regions.policy.collectAsState()
    val network by Regions.network.collectAsState()
    val mobileData by Regions.mobileData.collectAsState()
    val ask by Regions.ask.collectAsState()
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    val installed = active.installed
    // Switches and bins wait only while a region is downloading or installing.
    val working = running == RunPhase.DOWNLOADING || running == RunPhase.INSTALLING
    val changing = active.state is RegionState.Loading
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

    // Notifications: asked for once, on the first download (Android 13+);
    // downloads run whatever the answer. Looked at again on each return.
    var notificationsOn by remember { mutableStateOf(RegionNotices.allowed(context)) }
    var asking by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asking = false
        notificationsOn = RegionNotices.allowed(context)
    }
    LaunchedEffect(queue.active) {
        if (queue.active && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !Regions.notificationsAsked(context)
        ) {
            Regions.markNotificationsAsked(context)
            asking = true
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notificationsOn = RegionNotices.allowed(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    SettingsGroup(stringResource(R.string.region_group_phone), first = true)
    when {
        installed.isEmpty() -> Text(
            if (changing) stringResource(R.string.region_loading) else stringResource(R.string.region_none),
            Modifier.padding(vertical = 8.dp),
        )
        else -> {
            Supporting(stringResource(R.string.region_total, installed.size, mb(installedBytes(installed))))
            installed.forEach { r ->
                InstalledRow(
                    r,
                    zone,
                    confirming = confirmRemove == r.id,
                    onArm = { confirmRemove = r.id },
                    onRemove = {
                        confirmRemove = null
                        Regions.remove(context, r.id)
                    },
                    onEnable = { Regions.setEnabled(context, r.id, it) },
                    enabled = !working && !changing,
                )
            }
            val older = olderNeighbours(installed)
            if (older.isNotEmpty()) {
                Text(
                    stringResource(R.string.region_older, older.joinToString(", ") { it.name }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            if (enabledRegions(installed).isEmpty()) {
                Text(
                    stringResource(R.string.region_all_off),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
    (active.state as? RegionState.Failed)?.let {
        Text(
            stringResource(R.string.region_failed, it.message),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }

    SettingsGroup(stringResource(R.string.region_group_available))
    MobileDataRow(mobileData) { Regions.setMobileData(context, it) }
    if (queue.total >= 2) {
        Text(
            stringResource(R.string.region_queue_count, queue.index, queue.total),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
        )
    }
    val d = download
    val shown = (d as? DownloadState.Offers)?.offers ?: offers ?: when (d) {
        is DownloadState.Downloading -> listOf(d.offer)
        is DownloadState.Installing -> listOf(d.offer)
        else -> null
    }
    when {
        shown != null && shown.isEmpty() -> Text(stringResource(R.string.region_no_offers), Modifier.padding(vertical = 8.dp))
        shown != null -> {
            val updates = offersToUpdate(installed, shown).filter { it.id !in queue.ids }
            if (updates.size > 1 && !changing) {
                Button(onClick = { Regions.enqueue(context, updates) }, modifier = Modifier.padding(vertical = 4.dp)) {
                    OneLine(stringResource(R.string.region_update_all, updates.size))
                }
            }
            shown.forEach { offer ->
                OfferRow(
                    offer,
                    installed,
                    zone,
                    phase = rowPhase(offer.id, queue, running, policy, network),
                    download = d,
                    // "Download now" only on a network the setting doesn't allow.
                    canAsk = mobileDataAsk(policy, network) != null,
                    busy = changing,
                )
            }
        }
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
    queue.failed?.let {
        Text(
            stringResource(R.string.region_failed_download, it),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        OutlinedButton(onClick = { Regions.retry(context) }) { OneLine(stringResource(R.string.region_retry)) }
    }
    if (!notificationsOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Text(
            stringResource(R.string.region_notifications_off),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        TextButton(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            },
        ) { OneLine(stringResource(R.string.region_notifications_on)) }
    }
    val carryOn = stringResource(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) R.string.region_hint_background else R.string.region_hint_open,
    )
    Text(
        carryOn + " " + stringResource(R.string.region_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp),
    )

    // The mobile-data question waits for the offers (its size) and for the
    // notification question.
    val question = ask
    val known = offers
    if (question != null && known != null && !asking) {
        val size = queue.ids.sumOf { id ->
            val offer = known.firstOrNull { it.id == id } ?: return@sumOf 0L
            (offer.gzBytes.toLong() - Regions.partBytes(context, id)).coerceAtLeast(0)
        }
        AlertDialog(
            onDismissRequest = { Regions.dismissAsk() },
            title = { Text(stringResource(R.string.region_mobile_title)) },
            text = {
                Text(stringResource(if (question.roaming) R.string.region_mobile_text_roaming else R.string.region_mobile_text, mb(size)))
            },
            confirmButton = {
                TextButton(onClick = { Regions.allowOnce(context, question.grant) }) { OneLine(stringResource(R.string.region_mobile_now)) }
            },
            dismissButton = {
                TextButton(onClick = { Regions.dismissAsk() }) { OneLine(stringResource(R.string.region_mobile_wait)) }
            },
        )
    }
}

/** "Download over mobile data": the whole row toggles. */
@Composable
private fun MobileDataRow(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(R.string.region_mobile_data))
            Supporting(stringResource(R.string.region_mobile_data_hint))
        }
        Switch(checked = on, onCheckedChange = null)
    }
}

/**
 * A region on the phone: its name, the day its map data is from and its
 * size (or that it is switched off), a switch to use it, and a bin.
 */
@Composable
private fun InstalledRow(
    region: InstalledRegion,
    zone: ZoneId,
    confirming: Boolean,
    onArm: () -> Unit,
    onRemove: () -> Unit,
    onEnable: (Boolean) -> Unit,
    enabled: Boolean,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .toggleable(
                    value = region.enabled,
                    role = Role.Switch,
                    enabled = enabled,
                    onValueChange = onEnable,
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(region.name)
                Supporting(
                    when {
                        !region.enabled -> stringResource(R.string.region_off, mb(region.bytes))
                        region.osmTimestamp > 0 ->
                            stringResource(R.string.region_data_size, osmDate(region.osmTimestamp, zone), mb(region.bytes))
                        else -> stringResource(R.string.region_size, mb(region.bytes))
                    },
                )
            }
            Switch(checked = region.enabled, onCheckedChange = null, enabled = enabled)
        }
        DeleteButton(confirming = confirming, onArm = onArm, onDelete = onRemove, enabled = enabled)
    }
}

/** Secondary text under a row's name. */
@Composable
private fun Supporting(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * An offered region in [phase] of the queue: not queued, its name, size
 * and date with Download, Update or "Installed" (tapping one queues it);
 * queued, waiting its turn; the queue's first, starting, downloading,
 * installing, waiting for a network, retrying or failed, with an X that
 * stops the queue (none while installing) and, while it waits on a
 * network the setting doesn't allow ([canAsk]), "Download now".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OfferRow(
    offer: RegionOffer,
    installed: List<InstalledRegion>,
    zone: ZoneId,
    phase: RowPhase,
    download: DownloadState,
    canAsk: Boolean,
    busy: Boolean,
) {
    val context = LocalContext.current
    val total = offer.gzBytes.toLong()
    val action = offerAction(installed, offer.id, offer.osmTimestamp, offer.gzSha256)
    val offerText = stringResource(
        if (action == OfferAction.UPDATE && isRebuild(installed, offer.id, offer.osmTimestamp)) {
            R.string.region_offer_rebuilt
        } else {
            R.string.region_offer
        },
        mb(total),
        osmDate(offer.osmTimestamp, zone),
    )
    if (phase == RowPhase.NONE || phase == RowPhase.QUEUED || phase == RowPhase.INSTALLING) {
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
                        when (phase) {
                            RowPhase.QUEUED -> stringResource(
                                if (action == OfferAction.DOWNLOAD) R.string.region_waiting_download else R.string.region_waiting,
                            )
                            RowPhase.INSTALLING -> stringResource(R.string.region_installing_short)
                            else -> offerText
                        },
                    )
                }
                if (phase == RowPhase.NONE) {
                    when (action) {
                        OfferAction.INSTALLED -> Text(
                            stringResource(R.string.region_installed_label),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp),
                        )
                        OfferAction.UPDATE -> Button(onClick = { Regions.enqueue(context, listOf(offer)) }, enabled = !busy) {
                            OneLine(stringResource(R.string.region_update))
                        }
                        OfferAction.DOWNLOAD -> OutlinedButton(onClick = { Regions.enqueue(context, listOf(offer)) }, enabled = !busy) {
                            OneLine(stringResource(R.string.region_download))
                        }
                    }
                }
            }
            if (phase == RowPhase.INSTALLING) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        return
    }
    // The queue's first region: the X stays at the right, however long the text.
    val downloading = (download as? DownloadState.Downloading)?.takeIf { it.offer.id == offer.id }
    val stored = remember(phase, download, offer.id) { Regions.partBytes(context, offer.id) }
    val have = downloading?.done ?: stored
    val progress = stringResource(R.string.region_progress, mb(have), mb(total))
    val resources = LocalResources.current
    fun withBytes(what: String) = if (have > 0) resources.getString(R.string.region_with_progress, what, mb(have), mb(total)) else what
    val text = when (phase) {
        RowPhase.STARTING -> stringResource(R.string.region_starting)
        RowPhase.DOWNLOADING -> progress
        RowPhase.WAITING_WIFI -> withBytes(stringResource(R.string.region_wait_wifi))
        RowPhase.WAITING_NETWORK -> withBytes(stringResource(R.string.region_wait_network))
        RowPhase.RETRYING -> withBytes(stringResource(R.string.region_retrying))
        else -> offerText
    }
    val waiting = phase == RowPhase.WAITING_WIFI || phase == RowPhase.WAITING_NETWORK
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Only "Download now" wraps under the text when there is no room.
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.SpaceBetween,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.padding(end = 8.dp, top = 4.dp, bottom = 4.dp)) {
                    Text(offer.name)
                    Supporting(text)
                }
                if (waiting && canAsk) {
                    OutlinedButton(onClick = { Regions.askAgain() }) { OneLine(stringResource(R.string.region_download_now)) }
                }
            }
            IconButton(onClick = { Regions.stop(context) }) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.region_stop))
            }
        }
        when {
            phase == RowPhase.STARTING -> LinearProgressIndicator(Modifier.fillMaxWidth())
            phase == RowPhase.DOWNLOADING || (have > 0 && phase != RowPhase.FAILED) -> LinearProgressIndicator(
                progress = { downloadShare(have, total) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
