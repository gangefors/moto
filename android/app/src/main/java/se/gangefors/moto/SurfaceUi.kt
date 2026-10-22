// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import se.gangefors.moto.core.Surface

/**
 * Paved | Gravel for a surface correction (ADR-0018), neither selected
 * until the rider picks one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurfacePicker(selected: Surface?, onSelect: (Surface) -> Unit, enabled: Boolean = true) {
    val description = stringResource(R.string.surface_choice)
    SingleChoiceSegmentedButtonRow(Modifier.semantics { contentDescription = description }) {
        SURFACE_CHOICES.forEachIndexed { i, s ->
            SegmentedButton(
                selected = s == selected,
                onClick = { onSelect(s) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(i, SURFACE_CHOICES.size),
                icon = {},
                label = { OneLine(stringResource(surfaceChoiceName(s))) },
            )
        }
    }
}

/**
 * The road card's last row when the rider has set its surface: "Your
 * correction: Gravel", with the pencil and the bin right beside it, both
 * acting on what is to their left.
 */
@Composable
fun SurfaceCorrectedRow(row: SurfaceRow.Corrected, onEdit: () -> Unit, onDelete: () -> Unit) {
    var confirming by remember(row) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.surface_corrected, stringResource(surfaceChoiceName(row.surface))),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f, fill = false),
        )
        IconButton(onClick = onEdit) {
            Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.surface_edit))
        }
        DeleteButton(confirming = confirming, onArm = { confirming = true }, onDelete = onDelete)
    }
}
