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
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The buttons text may show, by the key it names them with ("[mark]"):
 * the button's own icon, its colour when it has one of its own, and what
 * a screen reader says for it. TAG is drawn as the button itself, a label
 * on orange.
 */
internal class ButtonIcon(val drawable: Int?, val description: Int, val tint: Color? = null)

internal val BUTTON_ICONS = mapOf(
    "menu" to ButtonIcon(R.drawable.ic_menu, R.string.menu_open),
    "location" to ButtonIcon(R.drawable.ic_my_location, R.string.my_location),
    "loop" to ButtonIcon(R.drawable.ic_loop, R.string.loop_from_me),
    "directions" to ButtonIcon(R.drawable.ic_directions, R.string.route_from_me),
    "share" to ButtonIcon(R.drawable.ic_share, R.string.route_share),
    "save" to ButtonIcon(R.drawable.ic_bookmark, R.string.route_save),
    "mark" to ButtonIcon(R.drawable.ic_add_road, R.string.section_mark),
    "sections" to ButtonIcon(R.drawable.ic_star, R.string.sections_title),
    "record" to ButtonIcon(R.drawable.ic_record_dot, R.string.record_start, RECORD_RED),
    "tag" to ButtonIcon(null, R.string.tag_button_description),
    "flag" to ButtonIcon(R.drawable.ic_flag, R.string.help_icon_review),
    "settings" to ButtonIcon(R.drawable.ic_settings, R.string.ride_settings_open),
    "bin" to ButtonIcon(R.drawable.ic_delete, R.string.delete, DELETE_COLOR),
)

/**
 * [text] with its "[key]"s drawn as the buttons' icons ([BUTTON_ICONS]),
 * at text size, so text that names a button shows the rider which one.
 */
@Composable
fun IconText(text: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    val parts = helpParts(text, BUTTON_ICONS.keys)
    val descriptions = BUTTON_ICONS.mapValues { stringResource(it.value.description) }
    val annotated = buildAnnotatedString {
        for (part in parts) {
            when (part) {
                is HelpPart.Words -> append(part.text)
                is HelpPart.Icon -> appendInlineContent(part.key, descriptions.getValue(part.key))
            }
        }
    }
    val inline = BUTTON_ICONS.mapValues { (key, icon) ->
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
    Text(annotated, modifier, style = style, inlineContent = inline)
}

/** [text] as words only, each "[key]" read as its button's name. */
@Composable
fun iconTextWords(text: String): String {
    val names = BUTTON_ICONS.mapValues { stringResource(it.value.description) }
    return helpWords(helpParts(text, BUTTON_ICONS.keys), names)
}
