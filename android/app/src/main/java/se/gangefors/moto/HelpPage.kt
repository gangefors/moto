// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** The topics of the help page: a heading and its text. */
private val TOPICS = listOf(
    R.string.help_map_title to R.string.help_map,
    R.string.help_plan_title to R.string.help_plan,
    R.string.help_sections_title to R.string.help_sections,
    R.string.help_ride_title to R.string.help_ride,
    R.string.help_settings_title to R.string.help_settings,
)

/**
 * The buttons the help text shows, by the key it names them with
 * ("[mark]"): the button's own icon, its colour when it has one of its
 * own, and what a screen reader says for it. TAG is drawn as the button
 * itself, a label on orange.
 */
private class HelpIcon(val drawable: Int?, val description: Int, val tint: Color? = null)

private val HELP_ICONS = mapOf(
    "menu" to HelpIcon(R.drawable.ic_menu, R.string.menu_open),
    "location" to HelpIcon(R.drawable.ic_my_location, R.string.my_location),
    "loop" to HelpIcon(R.drawable.ic_loop, R.string.loop_from_me),
    "directions" to HelpIcon(R.drawable.ic_directions, R.string.route_from_me),
    "share" to HelpIcon(R.drawable.ic_share, R.string.route_share),
    "save" to HelpIcon(R.drawable.ic_bookmark, R.string.route_save),
    "mark" to HelpIcon(R.drawable.ic_add_road, R.string.section_mark),
    "sections" to HelpIcon(R.drawable.ic_star, R.string.sections_title),
    "record" to HelpIcon(R.drawable.ic_record_dot, R.string.record_start, RECORD_RED),
    "tag" to HelpIcon(null, R.string.tag_button_description),
    "flag" to HelpIcon(R.drawable.ic_flag, R.string.help_icon_review),
    "settings" to HelpIcon(R.drawable.ic_settings, R.string.ride_settings_open),
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
                HelpText(stringResource(text), Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** [text] with its "[key]"s drawn as the buttons' icons, at text size. */
@Composable
private fun HelpText(text: String, modifier: Modifier = Modifier) {
    val parts = helpParts(text, HELP_ICONS.keys)
    val descriptions = HELP_ICONS.mapValues { stringResource(it.value.description) }
    val annotated = buildAnnotatedString {
        for (part in parts) {
            when (part) {
                is HelpPart.Words -> append(part.text)
                is HelpPart.Icon -> appendInlineContent(part.key, descriptions.getValue(part.key))
            }
        }
    }
    val inline = HELP_ICONS.mapValues { (key, icon) ->
        val width = if (icon.drawable == null) 2.6.em else 1.3.em
        InlineTextContent(Placeholder(width, 1.3.em, PlaceholderVerticalAlign.TextCenter)) {
            if (icon.drawable != null) {
                Icon(
                    painterResource(icon.drawable),
                    contentDescription = null,
                    tint = icon.tint ?: LocalContentColor.current,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (key == "tag") {
                Box(
                    Modifier.fillMaxSize().background(TAG_COLOR, RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.tag_button), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    Text(annotated, modifier, inlineContent = inline)
}
