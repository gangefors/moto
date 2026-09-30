// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * How to use the app ([HELP_TOPICS]): a full page from the menu, like the
 * others, with the back arrow. Each topic has its text, then a key of its
 * buttons, each with its own icon; The map lists every button on the map
 * in the order they sit there, and "see …" scrolls to the topic that
 * tells more. Ride settings describes each setting in the groups the
 * settings page uses. Headings have three levels: topics, groups,
 * settings.
 */
@Composable
fun HelpPage(onDismiss: () -> Unit) {
    FullPage(stringResource(R.string.help_title), onBack = onDismiss) {
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        // Where each topic's heading sits in the page, for "see …".
        val headings = remember { mutableStateMapOf<HelpTopicId, Int>() }
        val titles = HELP_TOPICS.associate { it.id to stringResource(it.title) }
        val goTo = { id: HelpTopicId ->
            headings[id]?.let { y -> scope.launch { scroll.animateScrollTo(y) } }
            Unit
        }
        Column(
            Modifier
                .fillMaxSize()
                .scrollHints(scroll)
                .verticalScroll(scroll)
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            for (topic in HELP_TOPICS) {
                TopicHeading(
                    titles.getValue(topic.id),
                    topic.titleIcon,
                    Modifier.onGloballyPositioned { headings[topic.id] = it.positionInParent().y.roundToInt() },
                )
                Text(stringResource(topic.text), Modifier.padding(top = 4.dp))
                if (topic.keys.isNotEmpty()) {
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (key in topic.keys) KeyRow(key, key.see?.let(titles::getValue)) { key.see?.let(goTo) }
                    }
                }
                for (group in topic.groups) SettingsGroup(group)
                if (topic.figures.isNotEmpty()) {
                    topic.figuresText?.let { Text(stringResource(it), Modifier.padding(top = 12.dp)) }
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (key in topic.figures) KeyRow(key, null) {}
                    }
                }
            }
        }
    }
}

/** A topic's heading (level 1), in the accent colour, with its icon after it. */
@Composable
private fun TopicHeading(title: String, icon: String?, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(top = 24.dp).semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        val drawable = icon?.let { BUTTON_ICONS[it]?.drawable }
        if (drawable != null) {
            Icon(painterResource(drawable), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * One button in a key: its icon as on the map (the record dot red, TAG
 * the orange circle), its name, what it does, and "see …" to the topic
 * that tells more.
 */
@Composable
private fun KeyRow(key: HelpKey, seeTitle: String?, onSee: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { KeyIcon(key.icon) }
        val see = seeTitle?.let { stringResource(R.string.help_see, it) }
        val link = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
        val name = stringResource(key.name)
        val what = stringResource(key.what)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(name) }
                append(" – ")
                append(what)
                if (see != null) {
                    append(" (")
                    withLink(LinkAnnotation.Clickable("see", link) { onSee() }) { append(see) }
                    append(")")
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A button's icon for a key, drawn as the button shows it. */
@Composable
private fun KeyIcon(icon: String) {
    when (icon) {
        "tag" -> Box(Modifier.size(24.dp).background(TAG_COLOR, CircleShape), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.tag_button), color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold)
        }
        else -> BUTTON_ICONS[icon]?.drawable?.let {
            Icon(
                painterResource(it),
                contentDescription = null,
                tint = if (icon == "record") RECORD_RED else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * A group of settings: its name (level 2) over a thin line, then each
 * setting's name (level 3) and what it does.
 */
@Composable
private fun SettingsGroup(group: HelpGroup) {
    Text(
        stringResource(group.title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp).semantics { heading() },
    )
    HorizontalDivider(Modifier.padding(top = 4.dp))
    for (setting in group.settings) {
        IconText(
            stringResource(setting.name),
            Modifier.padding(top = 10.dp).semantics { heading() },
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        )
        IconText(stringResource(setting.text), Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
