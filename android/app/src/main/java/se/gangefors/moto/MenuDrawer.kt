// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import se.gangefors.moto.debug.DebugTools

/** What the menu opens. */
enum class MenuTopic { LIBRARY, SECTIONS, REGION, HELP, ABOUT, DEBUG }

/**
 * The menu (the button at the top left): a drawer from the left over a
 * dimmed map, listing the rider's data (Routes & rides, Sections, Map
 * region), then How to use and About (and Debug tools in debug builds);
 * ride settings have their own button (the gear at the top right). A tap
 * on a topic closes the menu and opens its page ([onPick]); a tap on the
 * map or Back just closes it. Beside the app's name, a sun or moon flips
 * the app between light and dark ([dark], [onToggleTheme]); it shows the
 * theme a tap switches to, and the menu stays open.
 */
@Composable
fun MenuDrawer(
    open: Boolean,
    onClose: () -> Unit,
    onPick: (MenuTopic) -> Unit,
    dark: Boolean = false,
    onToggleTheme: () -> Unit = {},
) {
    BackHandler(enabled = open) { onClose() }
    AnimatedVisibility(visible = open, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() },
        )
    }
    AnimatedVisibility(
        visible = open,
        enter = slideInHorizontally { -it },
        exit = slideOutHorizontally { -it },
    ) {
        ModalDrawerSheet(Modifier.fillMaxHeight().widthIn(max = 320.dp)) {
            val pick = { t: MenuTopic ->
                onClose()
                onPick(t)
            }
            Column(
                Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 28.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onToggleTheme) {
                        Icon(
                            painterResource(if (dark) R.drawable.ic_light_mode else R.drawable.ic_dark_mode),
                            contentDescription = stringResource(if (dark) R.string.theme_to_light else R.string.theme_to_dark),
                        )
                    }
                }
                Item(R.drawable.ic_bookmark, R.string.library_title) { pick(MenuTopic.LIBRARY) }
                Item(R.drawable.ic_star, R.string.sections_title) { pick(MenuTopic.SECTIONS) }
                Item(R.drawable.ic_map, R.string.region_title) { pick(MenuTopic.REGION) }
                HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                Item(R.drawable.ic_help, R.string.help_open) { pick(MenuTopic.HELP) }
                Item(R.drawable.ic_info, R.string.about_open) { pick(MenuTopic.ABOUT) }
                DebugTools.MenuEntry { pick(MenuTopic.DEBUG) }
            }
        }
    }
}

@Composable
private fun Item(icon: Int, label: Int, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { OneLine(stringResource(label)) },
        icon = { Icon(painterResource(icon), contentDescription = null) },
        selected = false,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}
