// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource

/**
 * The app's delete action: a bin. The first tap arms it ([confirming]
 * turns it filled red), the second deletes; the caller keeps the state
 * and calls [onArm] or [onDelete].
 */
@Composable
fun DeleteButton(
    confirming: Boolean,
    onArm: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = { if (confirming) onDelete() else onArm() },
        enabled = enabled,
        modifier = modifier,
        colors = if (confirming) {
            IconButtonDefaults.filledIconButtonColors(containerColor = DELETE_COLOR, contentColor = Color.White)
        } else {
            IconButtonDefaults.iconButtonColors(contentColor = DELETE_COLOR)
        },
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_delete),
            contentDescription = stringResource(if (confirming) R.string.delete_confirm else R.string.delete),
        )
    }
}

/** Colour of destructive actions. */
val DELETE_COLOR = Color(0xFFC5221F)
