// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import se.gangefors.moto.core.Rating

/** What the rider chose in the section sheet. */
data class SectionChoice(val name: String, val rating: Rating, val oneWay: Boolean)

/**
 * A bottom sheet to name, rate and set the direction of a section: for a
 * new one before it is saved, or a saved one (then [onDelete] is set).
 * [fallbackName] is used when the name is left blank.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionSheet(
    title: String,
    initial: SectionChoice,
    fallbackName: String,
    saveLabel: String,
    onSave: (SectionChoice) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initial.name) }
    var rating by remember { mutableStateOf(initial.rating) }
    var oneWay by remember { mutableStateOf(initial.oneWay) }
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name,
                onValueChange = { name = takeCodePoints(it, MAX_SECTION_NAME_CHARS) },
                label = { Text(stringResource(R.string.section_name)) },
                placeholder = { Text(fallbackName) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                RATINGS.forEachIndexed { i, r ->
                    SegmentedButton(
                        selected = rating == r,
                        onClick = { rating = r },
                        shape = SegmentedButtonDefaults.itemShape(i, RATINGS.size),
                    ) { Text(stringResource(ratingLabel(r))) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.section_one_way))
                    Text(
                        stringResource(R.string.section_one_way_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = oneWay, onCheckedChange = { oneWay = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    TextButton(
                        onClick = { if (confirmDelete) onDelete() else confirmDelete = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = DELETE_COLOR),
                    ) {
                        Text(stringResource(if (confirmDelete) R.string.section_delete_confirm else R.string.section_delete))
                    }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                }
                Button(onClick = { onSave(SectionChoice(cleanSectionName(name, fallbackName), rating, oneWay)) }) {
                    Text(saveLabel)
                }
            }
        }
    }
}

fun ratingLabel(rating: Rating): Int = when (rating) {
    Rating.GOOD -> R.string.rating_good
    Rating.GREAT -> R.string.rating_great
    Rating.EPIC -> R.string.rating_epic
}

private val DELETE_COLOR = Color(0xFFC5221F)
