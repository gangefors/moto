// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow

/**
 * A button label on one line. Buttons never squeeze their label into a
 * narrow column of letters: rows of buttons wrap (FlowRow) instead, and
 * a label that still can't fit ends in an ellipsis.
 */
@Composable
fun OneLine(text: String) {
    Text(text, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
}
