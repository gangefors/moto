// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** The topics of the help page: a heading and its text. */
private val TOPICS = listOf(
    R.string.help_map_title to R.string.help_map,
    R.string.help_plan_title to R.string.help_plan,
    R.string.help_sections_title to R.string.help_sections,
    R.string.help_ride_title to R.string.help_ride,
    R.string.help_settings_title to R.string.help_settings,
)

/**
 * How to use the app, by topic: a full page from the menu, like the
 * others, with the back arrow. Where the text names a button, it shows
 * the button's icon, so the rider knows which one it means.
 */
@Composable
fun HelpPage(onDismiss: () -> Unit) {
    FullPage(stringResource(R.string.help_title), onBack = onDismiss) {
        val scroll = rememberScrollState()
        Column(
            Modifier
                .fillMaxSize()
                .scrollHints(scroll)
                .verticalScroll(scroll)
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            for ((title, text) in TOPICS) {
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp),
                )
                IconText(stringResource(text), Modifier.padding(top = 4.dp))
            }
        }
    }
}

