// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.roundToInt
import se.gangefors.moto.core.FollowPhase
import se.gangefors.moto.core.TagStatus
import se.gangefors.moto.core.defaultRouteOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun BoxScope.RideCards(screen: MapScreenScope) {
    with(screen) {
        with(state) {
            // Riding a route: the card at the top, the map's compass below it
            // on the right (ADR-0011).
            if (following != null) {
                RideCard(
                    following = following,
                    darkMap = darkMap,
                    // At the end the X just keeps recording; on the way it asks.
                    onStopFollowing = {
                        if (following.state.phase == FollowPhase.FINISHED) RecordingService.unfollow(context) else leavingRoute = true
                    },
                    onStopNow = { RecordingService.stop(context) },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .safeDrawingPadding()
                        .padding(top = 8.dp, start = 8.dp, end = 8.dp)
                        .widthIn(max = TOP_BOX_MAX_WIDTH)
                        .fillMaxWidth()
                        .onGloballyPositioned { rideCardBottom = it.boundsInRoot().bottom.roundToInt() },
                )
            } else if (freeRiding) {
                val active = recording as? Recording.State.Active
                if (active != null) {
                    RecordingCard(
                        distanceM = active.distanceM,
                        startedAtMs = active.startedAtMs,
                        pausedMs = active.pausedMs,
                        near = nearby,
                        mapBearing = mapBearing,
                        darkMap = darkMap,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .safeDrawingPadding()
                            .padding(top = 8.dp, start = 8.dp, end = 8.dp)
                            .widthIn(max = TOP_BOX_MAX_WIDTH)
                            .fillMaxWidth()
                            .onGloballyPositioned { rideCardBottom = it.boundsInRoot().bottom.roundToInt() },
                    )
                }
            }
        }
    }
}

@Composable
internal fun MapScreenScope.InfoCards() {
    with(state) {
        if (!rideMode) favouriteInfo?.let { f ->
            FavouriteInfoCard(
                f,
                engine = (region as? RegionState.Ready)?.engine,
                darkMap = darkMap,
                onClose = { favouriteInfoId = null },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        shownSection?.let { s ->
            ShownSectionCard(
                s,
                engine = (region as? RegionState.Ready)?.engine,
                onLoop = if (hasLocation) ({ rideSection(s, loop = true) }) else null,
                onRide = if (hasLocation) ({ rideSection(s, loop = false) }) else null,
                onEdit = { editing = s },
                onClose = { hideSection() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        roadInfo?.let {
            RoadInfoCard(
                it,
                onClose = {
                    roadInfo = null
                    overlays?.snap?.clear()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        shownRide?.let {
            ShownRideCard(
                it,
                onClose = { shownRide = null },
                onRename = { renamingRide = it.track },
                onShare = { shareRide(it.track) },
                onDelete = { deleteShownRide(it.track) },
                modifier = Modifier.fillMaxWidth(),
                onRide = if (it.track.endedAt != null) ({ rideAgain(it) }) else null,
            )
        }
        shownSaved?.let { s ->
            SavedRouteCard(
                s,
                onShare = {
                    shareLine(s.line, s.route.name, routeOptions(defaultRouteOptions(), ROUTE_EXTRA_PERCENT, gravel, avoid))
                },
                onRename = { renamingSaved = s.route },
                onDelete = { deleteShownSaved(s.route) },
                onClose = {
                    shownSaved = null
                    overlays?.route?.show(null, null, null)
                },
                modifier = Modifier.fillMaxWidth(),
                onRide = {
                    beginRide(
                        RideRoute(s.route.name, s.route.isLoop, s.route.durationS, s.line, s.favouriteParts, s.favouriteRatings),
                    )
                },
            )
        }
    }
}

@Composable
internal fun MapScreenScope.TaskCard(taskCard: Boolean, offerLoop: Boolean, via: Int?) {
    with(state) {
        if (taskCard) Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 3.dp,
            shadowElevation = 3.dp,
        ) {
            Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
                // What to do next, with the X at the top right (like
                // the route and loop cards): it leaves the task.
                Row(verticalAlignment = Alignment.Top) {
                    IconText(
                        message ?: via?.let { stringResource(R.string.route_via_selected, it + 1) } ?: "",
                        modifier = Modifier.weight(1f).padding(top = 12.dp, end = 4.dp),
                    )
                    if (via != null && message == null) {
                        // The bin removes the waypoint; X (or a tap
                        // elsewhere on the map) lets it go.
                        IconButton(
                            onClick = {
                                selectedVia = null
                                vias = removeVia(vias, via)
                            },
                            colors = IconButtonDefaults.iconButtonColors(contentColor = DELETE_COLOR),
                        ) {
                            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.route_via_remove))
                        }
                    }
                    IconButton(onClick = { cancelTask() }) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.task_close))
                    }
                }
                if (offerLoop) {
                    FlowRow(
                        Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // Loop first (the rider), then route from the
                        // rider's position.
                        IconTextButton(R.drawable.ic_loop, stringResource(R.string.loop_from_here)) {
                            val start = picker.takeStart() ?: return@IconTextButton
                            message = null
                            startLoop(start)
                        }
                        if (hasLocation) {
                            // The long-pressed point becomes the end; the
                            // rider's position the start. A later
                            // long-press moves the end, as usual.
                            IconTextButton(R.drawable.ic_directions, stringResource(R.string.route_from_me)) {
                                val from = riderStart() ?: return@IconTextButton
                                val to = picker.takeStart() ?: return@IconTextButton
                                picker.startAt(from)
                                startPicked = null
                                message = null
                                overlays?.route?.show(from, to, null)
                                vias = emptyList()
                                arriveBy = null
                                val planWasOpen = routeEnds != null || loopStart != null
                                if (routeEnds == null) clearRoutes()
                                routeEnds = from to to
                                closeInfoCards(infoCardsToCloseOnPlanStart(planWasOpen))
                            }
                        }
                    }
                }
                if (marking) {
                    val tag = reviewTag
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (tag != null) {
                            // Discards this tag (second tap) and moves on;
                            // X or Back ends the review, leaving the tags
                            // not yet handled pending.
                            var confirming by remember(tag.id) { mutableStateOf(false) }
                            DeleteButton(
                                confirming = confirming,
                                onArm = { confirming = true },
                                onDelete = { finishTag(TagStatus.DISCARDED) },
                                enabled = !proposing,
                            )
                        }
                        // The buttons wrap onto a second line when there is
                        // no room, instead of squeezing each other.
                        FlowRow(
                            Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (tag != null) {
                                // Leaves this one pending and moves on.
                                OutlinedButton(onClick = { skipTag() }) {
                                    OneLine(stringResource(R.string.tag_review_skip))
                                }
                            }
                            Button(
                                onClick = { savingDraft = true },
                                enabled = draft != null && !proposing,
                            ) { OneLine(stringResource(R.string.section_save_ellipsis)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BoxScope.CardColumns(screen: MapScreenScope, leftCardsShown: Boolean, infoAtRight: Boolean, infoCardOpen: Boolean, taskCard: Boolean, offerLoop: Boolean, via: Int?) {
    with(screen) {
        with(state) {
            if (leftCardsShown) {
                DisposableEffect(Unit) {
                    onDispose {
                        cardsTop = Int.MAX_VALUE
                        cardsRight = 0
                    }
                }
                val aboveSheet = with(density) {
                    if (planning && sheetTop < mapSize.height) (mapSize.height - sheetTop).toDp() else 0.dp
                }
                // Two cards stacked (what was tapped above what is shown) may be
                // taller than the room: the info cards then scroll.
                val stackMaxPx = cardStackMaxHeightPx(
                    mapSize.height,
                    maxOf(topPanelBottom, insets.top),
                    if (planning && sheetTop < mapSize.height) mapSize.height - sheetTop else insets.bottom,
                    with(density) { 8.dp.roundToPx() },
                )
                Column(
                    modifier = Modifier
                        .align(if (landscape) Alignment.BottomStart else Alignment.BottomCenter)
                        .then(
                            when {
                                !planning -> Modifier.safeDrawingPadding()
                                // The sheet covers the bar below; the cutout at the left is still clear.
                                landscape -> Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Left))
                                else -> Modifier
                            },
                        )
                        .padding(start = 8.dp, end = 8.dp, bottom = aboveSheet + 8.dp)
                        .widthIn(max = if (landscape) columnWidthDp.dp - 16.dp else TOP_BOX_MAX_WIDTH)
                        .heightIn(max = with(density) { stackMaxPx.toDp() })
                        .fillMaxWidth()
                        .onGloballyPositioned {
                            cardsTop = it.boundsInRoot().top.roundToInt()
                            cardsRight = it.boundsInRoot().right.roundToInt()
                        },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!infoAtRight && infoCardOpen) {
                        Column(
                            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) { InfoCards() }
                    }
                    TaskCard(taskCard, offerLoop, via)
                }
            }
            // Landscape with a plan open: the info cards at the bottom right,
            // clear of the bar and cutout, and of the sheet's column.
            if (infoAtRight && infoCardOpen) {
                DisposableEffect(Unit) { onDispose { infoRightTop = Int.MAX_VALUE } }
                val columnDp = with(density) {
                    infoColumnWidthDp(windowConfig.screenWidthDp, insets.left.toDp().value, insets.right.toDp().value)
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Right + WindowInsetsSides.Bottom))
                        .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                        .widthIn(max = (columnDp - 16f).coerceAtLeast(0f).dp)
                        .heightIn(
                            max = with(density) {
                                cardStackMaxHeightPx(mapSize.height, maxOf(topPanelBottom, insets.top), insets.bottom, 8.dp.roundToPx()).toDp()
                            },
                        )
                        .fillMaxWidth()
                        .onGloballyPositioned { infoRightTop = it.boundsInRoot().top.roundToInt() },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfoCards()
                    }
                }
            }
        }
    }
}
