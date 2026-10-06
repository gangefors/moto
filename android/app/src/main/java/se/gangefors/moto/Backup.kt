// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.gangefors.moto.core.BackupSummary
import se.gangefors.moto.core.Engine
import se.gangefors.moto.core.SectionStore
import se.gangefors.moto.core.backupSummary
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * When the last backup was made, and the regions a restore found missing
 * (kept here, not in a screen, so they outlive the activity being made
 * again to apply restored settings).
 */
object BackupState {
    private const val PREFS = "backup"
    private const val LAST = "last_backup_ms"

    private val _missingRegions = MutableStateFlow<List<String>>(emptyList())
    val missingRegions: StateFlow<List<String>> = _missingRegions.asStateFlow()

    fun offerRegions(ids: List<String>) {
        _missingRegions.value = ids
    }

    fun lastBackupMs(context: Context): Long? =
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(LAST, 0L) }
            .getOrDefault(0L).takeIf { it > 0 }

    fun setLastBackup(context: Context, ms: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong(LAST, ms) }
    }
}

/** A list in words: "a", "a and b", "a, b and c". */
private fun inWords(resources: Resources, parts: List<String>): String = when (parts.size) {
    0 -> ""
    1 -> parts[0]
    else -> resources.getString(R.string.backup_and, parts.dropLast(1).joinToString(", "), parts.last())
}

/** "23 favourites, 112 rides, 9 routes and 4 tags", leaving out what there is none of. */
private fun countsInWords(resources: Resources, favourites: Int, rides: Int, routes: Int, tags: Int): String {
    val parts = listOfNotNull(
        favourites.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.backup_count_favourites, it, it) },
        rides.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.backup_count_rides, it, it) },
        routes.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.backup_count_routes, it, it) },
        tags.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.backup_count_tags, it, it) },
    )
    return if (parts.isEmpty()) resources.getString(R.string.backup_nothing) else inWords(resources, parts)
}

private fun restoredText(resources: Resources, c: RestoreCounts): String {
    if (c.added == 0) return resources.getString(R.string.restored_nothing)
    val added = resources.getString(R.string.restored, countsInWords(resources, c.favourites, c.rides, c.routes, c.tags))
    return if (c.alreadyHere == 0) {
        added
    } else {
        added + " " + resources.getQuantityString(R.plurals.restored_already, c.alreadyHere, c.alreadyHere)
    }
}

/** Why a restore was refused, in the rider's words. */
private fun restoreError(resources: Resources, e: Throwable): String = when {
    e is FileTooLarge -> resources.getString(R.string.restore_too_large)
    e.message?.contains("newer version of moto") == true -> resources.getString(R.string.restore_newer)
    else -> resources.getString(R.string.restore_failed)
}

private class FileTooLarge : Exception()

/** Copies [uri] to [target], at most [MAX_BACKUP_FILE_BYTES]. */
private fun copyIn(context: Context, uri: Uri, target: File) {
    val input = context.contentResolver.openInputStream(uri) ?: error("can’t read the chosen file")
    input.use { from ->
        target.outputStream().use { to ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BACKUP_FILE_BYTES) throw FileTooLarge()
                to.write(buf, 0, n)
            }
        }
    }
}

/** The app's backup files in its own cache: written here, then copied out; picked files copied in. */
private fun outFile(context: Context) = File(context.cacheDir, "backup-out.zip")
private fun inFile(context: Context) = File(context.cacheDir, "backup-in.zip")

/**
 * Backup and restore (ADR-0012), from the menu: the Backup dialog when
 * [open], Android's file pickers, the restore question, and what happened.
 * After a restore with settings, the activity is made again so every
 * screen reads them.
 */
@Composable
fun BackupFlow(
    open: Boolean,
    onClose: () -> Unit,
    store: SectionStore?,
    engine: Engine?,
    busy: BusyTasks,
    onRestored: () -> Unit,
    onOpenRegions: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf<BackupSummary?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val missing by BackupState.missingRegions.collectAsState()

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri == null || store == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = busy.run(R.string.busy_backup) {
                withContext(Dispatchers.IO) {
                    val out = outFile(context)
                    try {
                        runCatching {
                            val summary = store.writeBackup(
                                out.path,
                                "moto ${appVersion(context)}",
                                Regions.installedRegionIds(context).sorted(),
                                RoutePrefs.forBackup(context),
                            )
                            context.contentResolver.openOutputStream(uri, "wt")?.use { to ->
                                out.inputStream().use { it.copyTo(to) }
                            } ?: error(resources.getString(R.string.rides_cannot_write))
                            summary
                        }
                    } finally {
                        out.delete()
                    }
                }
            }
            result.fold(
                onSuccess = { s ->
                    BackupState.setLastBackup(context, s.createdAtMs)
                    Toasts.show(
                        resources.getString(
                            R.string.backup_saved,
                            countsInWords(resources, s.favourites.count(), s.rides.count(), s.routes.count(), s.tags.count()),
                        ),
                    )
                },
                onFailure = { Toasts.show(resources.getString(R.string.backup_failed, it.message ?: it.toString())) },
            )
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = busy.run(R.string.busy_backup_read) {
                withContext(Dispatchers.IO) {
                    val file = inFile(context)
                    runCatching {
                        copyIn(context, uri, file)
                        backupSummary(file.path)
                    }.onFailure { file.delete() }
                }
            }
            result.fold(
                onSuccess = { asking = it },
                onFailure = { failure = restoreError(resources, it) },
            )
        }
    }

    if (open) {
        BackupDialog(
            lastBackupMs = BackupState.lastBackupMs(context),
            onBackup = {
                onClose()
                save.launch(resources.getString(R.string.backup_file_name, backupFileDate(LocalDate.now())))
            },
            onRestore = {
                onClose()
                pick.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed"))
            },
            onDismiss = onClose,
        )
    }
    asking?.let { summary ->
        RestoreDialog(
            summary = summary,
            onRestore = {
                asking = null
                if (store == null) return@RestoreDialog
                scope.launch {
                    val result = busy.run(R.string.busy_restore) {
                        withContext(Dispatchers.IO) {
                            val file = inFile(context)
                            try {
                                runCatching { store.restoreBackup(file.path, engine) }
                            } finally {
                                file.delete()
                            }
                        }
                    }
                    result.fold(
                        onSuccess = { r ->
                            Toasts.show(restoredText(resources, restoreCounts(r)))
                            BackupState.offerRegions(missingRegions(r.regions, Regions.installedRegionIds(context)))
                            onRestored()
                            val settings = r.settings.orEmpty()
                            if (settingsFromBackup(settings).isNotEmpty()) {
                                RoutePrefs.restore(context, settings)
                                // Every screen reads its settings again.
                                activity?.recreate()
                            }
                        },
                        onFailure = { failure = restoreError(resources, it) },
                    )
                }
            },
            onDismiss = {
                asking = null
                inFile(context).delete()
            },
        )
    }
    failure?.let { text ->
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text(stringResource(R.string.restore_failed_title)) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { failure = null }) { OneLine(stringResource(R.string.ok)) } },
        )
    }
    if (missing.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { BackupState.offerRegions(emptyList()) },
            title = { Text(stringResource(R.string.restore_regions_title)) },
            text = { Text(stringResource(R.string.restore_regions_text, inWords(resources, missing.map(::regionIdName)))) },
            confirmButton = {
                TextButton(onClick = {
                    Regions.downloadMissing(context, missing)
                    BackupState.offerRegions(emptyList())
                    onOpenRegions()
                }) { OneLine(stringResource(R.string.restore_regions_download)) }
            },
            dismissButton = {
                TextButton(onClick = { BackupState.offerRegions(emptyList()) }) { OneLine(stringResource(R.string.restore_regions_later)) }
            },
        )
    }
}

/**
 * The Backup dialog: what a backup holds and that it is private, when
 * the last one was made, then Cancel, Restore and Backup (wrapping onto
 * lines of their own when they don't fit).
 */
@Composable
private fun BackupDialog(lastBackupMs: Long?, onBackup: () -> Unit, onRestore: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.backup_text))
                Text(stringResource(R.string.backup_private), Modifier.padding(top = 12.dp))
                lastBackupMs?.let {
                    Text(
                        stringResource(R.string.backup_last, backupWhen(it, ZoneId.systemDefault())),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onBackup) { OneLine(stringResource(R.string.backup_do)) } },
        // Two buttons in the dialog's own flow row, so all three wrap.
        dismissButton = {
            TextButton(onClick = onDismiss) { OneLine(stringResource(R.string.cancel)) }
            TextButton(onClick = onRestore) { OneLine(stringResource(R.string.backup_restore)) }
        },
    )
}

/** The restore question: when the backup was made, what is in it, and what restoring does. */
@Composable
private fun RestoreDialog(summary: BackupSummary, onRestore: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.restore_made, backupWhen(summary.createdAtMs, ZoneId.systemDefault()), summary.app))
                Spacer(Modifier.padding(top = 12.dp))
                HorizontalDivider()
                CountRow(R.drawable.ic_star, R.string.restore_favourites, summary.favourites.count())
                CountRow(R.drawable.ic_ride, R.string.restore_rides, summary.rides.count())
                CountRow(R.drawable.ic_bookmark, R.string.restore_routes, summary.routes.count())
                CountRow(R.drawable.ic_flag, R.string.restore_tags, summary.tags.count())
                if (summary.hasSettings) CountRow(R.drawable.ic_settings, R.string.restore_settings, null)
                Text(stringResource(R.string.restore_text), Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = { TextButton(onClick = onRestore) { OneLine(stringResource(R.string.backup_restore)) } },
        dismissButton = { TextButton(onClick = onDismiss) { OneLine(stringResource(R.string.cancel)) } },
    )
}

/** One kind of thing in a backup, with how many (none shown for settings). */
@Composable
private fun CountRow(icon: Int, label: Int, count: Int?) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 10.dp).size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(stringResource(label), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
            count?.let { Text("$it", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge) }
        }
        HorizontalDivider()
    }
}
