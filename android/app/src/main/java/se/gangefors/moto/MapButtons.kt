// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.BoxScope
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.roundToInt
import org.maplibre.android.camera.CameraUpdateFactory

/** The round buttons at the top of the map. */
internal val TOP_BUTTON_SIZE = 48.dp
private val TOP_BUTTON_MARGIN = 16.dp

/** A round button at the top of the map (menu, ride settings). */
@Composable
internal fun TopMapButton(icon: Int, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .safeDrawingPadding()
            .padding(top = 8.dp, start = TOP_BUTTON_MARGIN, end = TOP_BUTTON_MARGIN)
            .size(TOP_BUTTON_SIZE)
            .semantics { contentDescription = description },
        shape = CircleShape,
        // The accent colour, as the other map buttons (Material's floating
        // action buttons): lilac on the light theme, deep purple on the dark.
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null)
        }
    }
}

/** Frames the whole route or loop: the size of the top buttons, on the plain surface. */
@Composable
internal fun FitRouteButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.fit_route)
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(TOP_BUTTON_SIZE)
            .semantics { contentDescription = description },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_fit_route), contentDescription = null)
        }
    }
}

/**
 * Shows or hides the ridden roads (ADR-0010) while planning: the size of
 * the top buttons, in the accent colour when on (as the other map
 * buttons), the plain surface when off. A screen reader says "Show ridden
 * roads" with the switch's state.
 */
@Composable
internal fun RiddenButton(on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.settings_show_ridden)
    Surface(
        checked = on,
        onCheckedChange = onChange,
        modifier = modifier
            .size(TOP_BUTTON_SIZE)
            .semantics { contentDescription = description },
        shape = CircleShape,
        color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_ridden), contentDescription = null)
        }
    }
}

/** Quick-tag button: large enough to hit with gloves on. */
internal val TAG_BUTTON_SIZE: Dp = 96.dp

/** The add-favourite icon on the tag button, big enough to read at a glance. */
internal val TAG_ICON_SIZE: Dp = 48.dp

internal val TAG_COLOR = Color(0xFFE8710A)

/** The record symbol's red. */
internal val RECORD_RED = Color(0xFFD93025)

/** The bottom-right buttons' distance from the safe edges. */
internal val FAB_PADDING: Dp = 16.dp

@Composable
internal fun BoxScope.TopButtons(screen: MapScreenScope, topButtons: Boolean) {
    with(screen) {
        with(state) {
            if (topButtons) {
                if (store is StoreState.Ready) {
                    TopMapButton(
                        icon = R.drawable.ic_menu,
                        description = stringResource(R.string.menu_open),
                        onClick = { menuOpen = true },
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                }
                TopMapButton(
                    icon = R.drawable.ic_settings,
                    description = stringResource(R.string.ride_settings_open),
                    onClick = { showSettings = true },
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
    }
}

@Composable
internal fun BoxScope.PlanButtons(screen: MapScreenScope) {
    with(screen) {
        with(state) {
            // The ridden roads button, bottom right: switches the layer, and
            // Ride settings' switch with it. Never hidden by a card: it sits
            // right above the sheet and the cards (upright), or above the info
            // cards at the right (landscape), and drops back when they close.
            // Both buttons stay over a pulled-up sheet, right above its top.
            val fitButton = planning
            if (fitButton || riddenButton) {
                val bottomPx = controlsBottomPx(landscape, insets.bottom, mapSize.height, sheetTop, cardsTop, infoRightTop)
                DisposableEffect(Unit) { onDispose { planButtonsTop = Int.MAX_VALUE } }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .padding(end = FAB_PADDING, bottom = with(density) { bottomPx.toDp() } + FAB_PADDING)
                        .onGloballyPositioned { planButtonsTop = it.boundsInRoot().top.roundToInt() },
                ) {
                    // Frames all the routes or loops of the plan, as when they
                    // were found.
                    if (fitButton) {
                        FitRouteButton(onClick = {
                            val ends = listOfNotNull(
                                loopStart?.toLatLon(),
                                routeEnds?.first?.toLatLon(),
                                routeEnds?.second?.toLatLon(),
                            ).map { listOf(it) }
                            val lines = if (loopStart != null) loops.map { it.geometry } else routeChoices.map { it.geometry }
                            showOnMap(lines + ends, always = true)
                        })
                    }
                    if (riddenButton) RiddenButton(on = riddenOn, onChange = { riddenWhilePlanning = it })
                }
            }
        }
    }
}

@Composable
internal fun BoxScope.MapButtonColumn(screen: MapScreenScope, cardsShown: Boolean) {
    with(screen) {
        with(state) {
            val reviewShown = tagsToReview(pendingTags, recording is Recording.State.Active, region is RegionState.Ready)
            if (!marking && !planning && !cardsShown) {
                DisposableEffect(Unit) { onDispose { buttonsLeft = Int.MAX_VALUE } }
                val landscape = mapSize.width > mapSize.height
                // What shows, for the measured fit (heights in dp).
                val (showAddLoop, showAdd, showLocate, showZoom, showFlag) = buttonsShown(
                    region is RegionState.Ready, store is StoreState.Ready, hasLocation, rideMode, ridePanned,
                    recording is Recording.State.Active, reviewShown,
                )
                val rightColumn = rightColumnDp(showFlag, showAdd, showAddLoop, showZoom)
                // The flag (the spots tagged on rides) with how many wait,
                // the count on the button's corner, clear of the flag; on
                // top, above Add favourite (2026-10-04).
                val flagButton: @Composable () -> Unit = {
                    if (store is StoreState.Ready && reviewShown) {
                        BadgedBox(badge = { Badge { Text(badgeCount(pendingTags)) } }) {
                            FloatingActionButton(onClick = { startReview() }) {
                                Icon(
                                    painterResource(R.drawable.ic_flag),
                                    contentDescription = pluralStringResource(R.plurals.tags_review, pendingTags, pendingTags),
                                )
                            }
                        }
                    }
                }
                // Not in ride mode (riding a route or recording). Add
                // favourite on top, Loop below it, right above Record
                // (2026-10-03).
                val addButton: @Composable () -> Unit = {
                    if (showAdd) {
                        FloatingActionButton(onClick = {
                            marker.begin()
                            markSession++
                            marking = true
                            draft = null
                            showDraft()
                            message = resources.getString(R.string.section_pick_start)
                        }) {
                            Icon(painterResource(R.drawable.ic_add_road), contentDescription = stringResource(R.string.section_mark))
                        }
                    }
                }
                // Loops from where the rider is, in one tap: a new set
                // each time.
                val loopButton: @Composable () -> Unit = {
                    if (showAddLoop) {
                        FloatingActionButton(onClick = {
                            val start = riderStart() ?: return@FloatingActionButton
                            picker.reset()
                            startLoop(start, shuffleSeed())
                        }) {
                            Icon(painterResource(R.drawable.ic_loop), contentDescription = stringResource(R.string.loop_from_me))
                        }
                    }
                }
                // While riding or recording: + and −, as a pinch is hard
                // with gloves. Followed on a route they change its zoom for
                // the rest of the ride; otherwise they zoom the map.
                val zoomButtons: @Composable () -> Unit = {
                    if (recording is Recording.State.Active) {
                        ZoomButtons(
                            onZoom = { by ->
                                if (rideMode && !ridePanned) {
                                    // One step from the zoom the map shows now,
                                    // whatever the speed zoom was doing.
                                    val m = map
                                    val base = rideZoom(
                                        (recording as? Recording.State.Active)?.lastFix?.speedMps,
                                        rideZoomOffset(rideZoomStep),
                                    )
                                    rideZoomTappedAt = SystemClock.elapsedRealtime()
                                    rideZoomNudge = if (m != null) {
                                        nudgeFromShown(m.cameraPosition.zoom, base, by)
                                    } else {
                                        nudgeRideZoom(rideZoomNudge, by)
                                    }
                                } else {
                                    riderMovedMap()
                                    map?.animateCamera(CameraUpdateFactory.zoomBy(by))
                                }
                            },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
                // The position button, left of Record (or Stop), so neither
                // moves when the position button hides (2026-10-03). It is the
                // location button, or while riding Recentre after a pan or
                // pinch (hidden while the map follows the rider).
                val locateButton: @Composable () -> Unit = {
                    if (hasLocation && !rideMode) {
                        FloatingActionButton(onClick = { onLocateTap() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_my_location),
                                contentDescription = stringResource(R.string.my_location),
                            )
                        }
                    } else if (hasLocation && rideMode && ridePanned) {
                        FloatingActionButton(onClick = { ridePanned = false }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_my_location),
                                contentDescription = stringResource(R.string.ride_recentre),
                            )
                        }
                    }
                }
                // Record: a red dot. While recording: a stop square with
                // a red arc running round the button, the same size as
                // the others; the distance is in the notification.
                // Riding to a route's start records nothing yet: Record
                // is still there, to record the way there too.
                val recordButton: @Composable (Modifier) -> Unit = { modifier ->
                    val active = (recording as? Recording.State.Active)?.takeIf { it.trackId != null }
                    Box(modifier) {
                        if (active != null) {
                            RecordingButton(
                                onStop = { RecordingService.stop(context) },
                                description = stringResource(R.string.record_stop_description, sectionKm(active.distanceM)),
                            )
                        } else {
                            FloatingActionButton(onClick = { recordPermissions.launch(recordingPermissions()) }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_record_dot),
                                    contentDescription = stringResource(R.string.record_start),
                                    tint = RECORD_RED,
                                )
                            }
                        }
                    }
                }
                // The buttons stay below the top row (the compass sits beside
                // Ride settings' button) and clear of the system bars. Right
                // column: the flag when it fits (landscape; always upright),
                // Add favourite, Loop, Record, which never move. Left column:
                // the position button beside Record, with the flag right above
                // it where the right column has no room for it. Maps fit left
                // of the right column (the position button sits low, where
                // little is fitted).
                BoxWithConstraints(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .safeDrawingPadding()
                        .padding(
                            start = MAP_MARGIN_DP.dp,
                            end = MAP_MARGIN_DP.dp,
                            bottom = MAP_MARGIN_DP.dp,
                            top = buttonsTopLimitDp(settingsButtonShown = !rideMode).dp,
                        ),
                ) {
                    val flagLeft = showFlag && !flagInRightColumn(landscape, maxHeight.value, rightColumn)
                    val leftColumn = flagLeft || showLocate
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
                        if (leftColumn) {
                            Column(
                                modifier = if (flagLeft) {
                                    Modifier.onGloballyPositioned { buttonsLeft = it.boundsInRoot().left.roundToInt() }
                                } else {
                                    Modifier
                                },
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                if (flagLeft) flagButton()
                                locateButton()
                            }
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (!flagLeft) flagButton()
                            addButton()
                            loopButton()
                            zoomButtons()
                            recordButton(
                                if (flagLeft) {
                                    Modifier
                                } else {
                                    Modifier.onGloballyPositioned { buttonsLeft = it.boundsInRoot().left.roundToInt() }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BoxScope.QuickTagButton(screen: MapScreenScope, cardsShown: Boolean) {
    with(screen) {
        with(state) {
            // Quick-tag (PRD R3): one big button, usable with gloves, in ride
            // mode only (2026-10-03). Bottom left, above the map's logo
            // and attribution. The flag for the tags waiting for review is in
            // the right-hand column, above Add favourite (2026-10-04).
            if (store is StoreState.Ready && !marking && !planning && !cardsShown && rideMode) {
                DisposableEffect(Unit) { onDispose { tagTop = Int.MAX_VALUE } }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .safeDrawingPadding()
                        .padding(start = 16.dp, bottom = LOGO_BAND_DP.dp)
                        .onGloballyPositioned { tagTop = it.boundsInRoot().top.roundToInt() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (rideMode) {
                        LargeFloatingActionButton(
                            onClick = { quickTag() },
                            shape = CircleShape,
                            containerColor = TAG_COLOR,
                            contentColor = Color.White,
                            modifier = Modifier
                                .size(TAG_BUTTON_SIZE)
                                .semantics { contentDescription = resources.getString(R.string.tag_button_description) },
                        ) {
                            Icon(painterResource(R.drawable.ic_add_road), contentDescription = null, modifier = Modifier.size(TAG_ICON_SIZE))
                        }
                    }
                }
            }
        }
    }
}
