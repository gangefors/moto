// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** One line of an import report: a file or item's [name], what became of
 * it ([detail]), and an optional [extra] line (the names rides got). */
data class ReportRow(val name: String, val detail: String, val extra: String? = null)

/** A group of an import report: its [title] ("Not imported · 2"), in the
 * error colour when [error], and its rows. */
data class ReportGroup(val title: String, val error: Boolean, val rows: List<ReportRow>)

/** What an import report shows: its [title], a [summary] line and the
 * [groups] (empty ones are left out). */
data class ImportReportContent(val title: String, val summary: String, val groups: List<ReportGroup>)

/**
 * The report after an import with something to say (mockup Import GPX
 * report): a summary, then each group of files or items with what became
 * of each. The list scrolls (with fades at the edge that has more), long
 * names wrap, and it stays until OK, unlike a message that goes away by
 * itself.
 */
@Composable
fun ImportReportDialog(content: ImportReportContent, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(content.title) },
        text = {
            val scroll = rememberScrollState()
            Column(Modifier.scrollHints(scroll).verticalScroll(scroll)) {
                Text(content.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                content.groups.filter { it.rows.isNotEmpty() }.forEach { group ->
                    val accent = if (group.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    Text(
                        group.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = accent,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    group.rows.forEach { row ->
                        Column(Modifier.padding(top = 4.dp, bottom = 6.dp)) {
                            Text(row.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                row.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (group.error) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            row.extra?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { OneLine(stringResource(R.string.ok)) } },
    )
}
