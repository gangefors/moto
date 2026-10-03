// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The licence notices bundled at build time (see app/build.gradle.kts). */
private const val NOTICES_ASSET = "licenses/third_party.txt"

/**
 * About the app (ADR-0004): its version and licence with where to get the
 * source, the map data and tile attribution, and the licence notices of
 * everything built into the app, read from the APK's assets. Nothing is
 * fetched from the network. A full page with the back arrow, like the
 * menu's other topics.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var blocks by remember { mutableStateOf<List<LicenceBlock>?>(null) }
    LaunchedEffect(Unit) {
        blocks = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(NOTICES_ASSET).bufferedReader().use { it.readText() } }
                .map(::licenceBlocks)
                .getOrElse { emptyList() }
        }
    }
    // A page like the menu's others: the back arrow and the title.
    FullPage(stringResource(R.string.about_title), onBack = onDismiss) {
        val list = rememberLazyListState()
                SelectionContainer {
                    LazyColumn(Modifier.fillMaxSize().scrollHints(list).padding(horizontal = 24.dp), state = list) {
                        item {
                            Text(stringResource(R.string.about_version, appVersion(context)), Modifier.padding(top = 8.dp))
                            Text(stringResource(R.string.about_licence), Modifier.padding(top = 12.dp))
                            Text(stringResource(R.string.about_source), Modifier.padding(top = 12.dp))
                            Text(stringResource(R.string.about_privacy), Modifier.padding(top = 12.dp))
                            Text(
                                stringResource(R.string.about_map_attribution),
                                Modifier.padding(top = 12.dp),
                            )
                            HorizontalDivider(Modifier.padding(vertical = 16.dp))
                            Text(stringResource(R.string.about_notices_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.about_notices_intro),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        when (val list = blocks) {
                            null -> item { Text(stringResource(R.string.about_notices_loading)) }
                            else -> {
                                if (list.isEmpty()) item { Text(stringResource(R.string.about_notices_missing)) }
                                items(list) { block ->
                                    when (block) {
                                        is LicenceBlock.Heading -> Text(
                                            block.text,
                                            style = if (block.level == 1) {
                                                MaterialTheme.typography.titleMedium
                                            } else {
                                                MaterialTheme.typography.titleSmall
                                            },
                                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                                        )
                                        is LicenceBlock.Paragraph -> Text(
                                            block.text,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(bottom = 8.dp),
                                        )
                                    }
                                }
                            }
                        }
                        item { Text("", Modifier.padding(bottom = 24.dp)) }
                    }
                }
    }
}

/** The installed version name, e.g. "0.1.0". */
internal fun appVersion(context: Context): String = runCatching {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, 0)
    }
    info.versionName
}.getOrNull() ?: "?"
