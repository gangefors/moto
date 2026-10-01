// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.gangefors.moto.OneLine
import se.gangefors.moto.R
import se.gangefors.moto.RegionState
import se.gangefors.moto.Regions
import se.gangefors.moto.SavedSections
import se.gangefors.moto.StoreState
import androidx.compose.material3.LinearProgressIndicator

/**
 * Debug tools: phone and build, settings in effect, the rider's data in
 * numbers, memory, battery use of recent rides,
 * start steps, the region check, a
 * fixed benchmark and every route, loop and snap since the app started.
 * Copy report puts it all on the clipboard as text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DebugDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val startup by DebugTools.startup.collectAsState()
    val queries by DebugTools.queries.collectAsState()
    val active by Regions.active.collectAsState()
    var device by remember { mutableStateOf(emptyList<String>()) }
    var memory by remember { mutableStateOf(emptyList<String>()) }
    var check by remember { mutableStateOf(emptyList<String>()) }
    var settings by remember { mutableStateOf(emptyList<String>()) }
    var data by remember { mutableStateOf(emptyList<String>()) }
    var bench by remember { mutableStateOf(emptyList<BenchResult>()) }
    // The case the benchmark runs now, and how far it is (0–1).
    var benchAt by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var working by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        device = runCatching { deviceLines(context) }.getOrElse { listOf(it.toString()) }
        memory = runCatching { memoryLines() }.getOrElse { listOf(it.toString()) }
        settings = runCatching { settingsLines(settingsInEffect(context)) }.getOrElse { listOf(it.toString()) }
        data = runCatching { riderData(context)?.let(::riderDataLines) ?: listOf("store not open") }
            .getOrElse { listOf(it.toString()) }
    }
    LaunchedEffect(Unit) { refresh() }

    val rides = remember { DebugTools.rideLines(context).asReversed() }
    fun sections() = listOf(
        ReportSection("Settings", settings),
        ReportSection("Rider data", data),
        ReportSection("Memory", memory),
        ReportSection("Rides: battery use (latest first)", rides),
        ReportSection("Start", startup),
        ReportSection("Region check", check),
        ReportSection("Benchmark (cold / warm; \"your data\" uses your favourites and rides)", bench.map(::benchLine)),
        ReportSection("Queries: summary", queryStats(queries).map(::statsLine)),
        ReportSection("Queries: latest first", queries.asReversed().take(REPORT_QUERIES).map(::queryLine)),
    )

    fun busyWith(label: String, block: suspend () -> Unit) {
        if (working != null) return
        working = label
        scope.launch {
            try {
                block()
            } finally {
                working = null
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 4.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Debug tools", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "Close")
                    }
                }
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val text = reportText("moto debug report", listOf(ReportSection("Phone and build", device)) + sections())
                                Log.i("moto", text)
                                context.getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(ClipData.newPlainText("moto debug report", text))
                                Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
                            }) { OneLine("Copy report") }
                            OutlinedButton(onClick = { busyWith("Measuring memory…") { refresh() } }, enabled = working == null) {
                                OneLine("Measure memory")
                            }
                            OutlinedButton(
                                onClick = {
                                    val engine = (active.state as? RegionState.Ready)?.engine ?: return@OutlinedButton
                                    busyWith("Running the benchmark…") {
                                        bench = emptyList()
                                        try {
                                            withContext(Dispatchers.Default) {
                                                val store = (SavedSections.open(context) as? StoreState.Ready)?.store
                                                runBenchmark(
                                                    engine,
                                                    store,
                                                    starting = { i, case ->
                                                        benchAt = benchProgress(i, BENCH_CASES.size, case.label) to i.toFloat() / BENCH_CASES.size
                                                    },
                                                    progress = { r -> bench = bench + r },
                                                )
                                            }
                                        } finally {
                                            benchAt = null
                                        }
                                        refresh()
                                    }
                                },
                                enabled = working == null && active.state is RegionState.Ready,
                            ) { OneLine("Run benchmark") }
                            OutlinedButton(
                                onClick = {
                                    busyWith("Timing the region check…") {
                                        check = withContext(Dispatchers.IO) {
                                            runCatching { regionCheckLines(context) }.getOrElse { listOf(it.message ?: it.toString()) }
                                        }
                                    }
                                },
                                enabled = working == null,
                            ) { OneLine("Time the region check") }
                            OutlinedButton(onClick = { DebugTools.clearQueries() }, enabled = queries.isNotEmpty()) {
                                OneLine("Clear queries")
                            }
                        }
                        working?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary) }
                        benchAt?.let { (text, done) ->
                            LinearProgressIndicator(progress = { done }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                            Text(text, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item {
                        SelectionContainer {
                            Column {
                                Block("Phone and build", device)
                                sections().forEach { Block(it.title, it.lines) }
                                Text("", Modifier.padding(bottom = 24.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Block(title: String, lines: List<String>) {
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    Text(title, style = MaterialTheme.typography.titleSmall)
    Text(
        lines.ifEmpty { listOf("—") }.joinToString("\n"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** How many of the latest queries the screen and report list one by one. */
private const val REPORT_QUERIES = 50
