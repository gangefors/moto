// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import se.gangefors.moto.core.RoadClass
import se.gangefors.moto.core.RoadInfo
import kotlin.math.roundToInt

/** How curvy a road reads to the rider, from the core's 0–1 score. */
enum class Curviness { STRAIGHT, GENTLE, CURVY, VERY_CURVY }

/** Things worth pointing out about a road. */
enum class RoadNote { ONE_WAY, UNPAVED, TOLL, FERRY, DESTINATION_ONLY }

/** Below this far from the road, the tap counts as on it. */
const val ON_ROAD_M = 25

/** The figures the road info card shows, rounded for reading. */
data class RoadFacts(
    val curviness: Curviness,
    val curvyPercent: Int,
    val speedKmh: Int,
    val lengthKm: Double,
    val notes: List<RoadNote>,
    /** How far the tap was from the road, or null when it was on it. */
    val tapOffM: Int?,
)

fun curvinessOf(score: Double): Curviness = when {
    !(score >= 0.1) -> Curviness.STRAIGHT // NaN too
    score < 0.35 -> Curviness.GENTLE
    score < 0.7 -> Curviness.CURVY
    else -> Curviness.VERY_CURVY
}

fun roadFacts(info: RoadInfo): RoadFacts {
    val curvy = if (info.curviness.isFinite()) info.curviness.coerceIn(0.0, 1.0) else 0.0
    val off = info.point.distanceM.takeIf { it.isFinite() }?.roundToInt()?.coerceAtLeast(0) ?: 0
    return RoadFacts(
        curviness = curvinessOf(curvy),
        curvyPercent = (curvy * 100).roundToInt(),
        speedKmh = info.speedKmh.toInt(),
        lengthKm = if (info.lengthM.isFinite()) info.lengthM.coerceAtLeast(0.0) / 1000.0 else 0.0,
        notes = buildList {
            if (info.oneWay) add(RoadNote.ONE_WAY)
            if (!info.paved) add(RoadNote.UNPAVED)
            if (info.toll) add(RoadNote.TOLL)
            if (info.ferry && info.`class` != RoadClass.FERRY) add(RoadNote.FERRY)
            if (info.destinationOnly) add(RoadNote.DESTINATION_ONLY)
        },
        tapOffM = off.takeIf { it > ON_ROAD_M },
    )
}
