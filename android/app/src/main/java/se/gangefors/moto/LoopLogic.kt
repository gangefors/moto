// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import se.gangefors.moto.core.RoundTripTarget

/**
 * A round-trip length the rider picks (PRD R7): a riding time or a
 * distance. The core returns loops within ±15 % of it.
 */
sealed interface LoopChoice {
    /** Stored in preferences as "min:120" or "km:100". */
    val key: String
    val target: RoundTripTarget

    data class Minutes(val minutes: Int) : LoopChoice {
        override val key get() = "min:$minutes"
        override val target get() = RoundTripTarget.DurationS(minutes * 60.0)
    }

    data class Km(val km: Int) : LoopChoice {
        override val key get() = "km:$km"
        override val target get() = RoundTripTarget.DistanceM(km * 1000.0)
    }
}

/** The lengths on the slider: 30 min to 7 h in half hours, or 20 to
 * 400 km in tens (the core's limit; a time counts at 54 km/h, so 7 h is
 * about 380 km). */
val LOOP_MINUTES: IntProgression = 30..420 step 30
val LOOP_KM: IntProgression = 20..400 step 10

val DEFAULT_LOOP: LoopChoice = LoopChoice.Minutes(120)

/** Speed a riding time is turned into a distance at when switching unit,
 * km/h (the core's 15 m/s). */
private const val LOOP_KMH = 54.0

/** A stored choice, or the default when it isn't one on the slider
 * (preferences are read back as untrusted input). */
fun loopChoiceOf(stored: String?): LoopChoice {
    val (unit, number) = stored?.split(':')?.takeIf { it.size == 2 } ?: return DEFAULT_LOOP
    val n = number.toIntOrNull() ?: return DEFAULT_LOOP
    return when {
        unit == "min" && n in LOOP_MINUTES -> LoopChoice.Minutes(n)
        unit == "km" && n in LOOP_KM -> LoopChoice.Km(n)
        else -> DEFAULT_LOOP
    }
}

/** The steps of the slider [choice] is on. */
fun loopSteps(choice: LoopChoice): List<LoopChoice> = when (choice) {
    is LoopChoice.Minutes -> LOOP_MINUTES.map { LoopChoice.Minutes(it) }
    is LoopChoice.Km -> LOOP_KM.map { LoopChoice.Km(it) }
}

/** About the same loop in the other unit, on its nearest step. */
fun switchUnit(choice: LoopChoice): LoopChoice = when (choice) {
    is LoopChoice.Minutes -> LoopChoice.Km(nearestStep(LOOP_KM, choice.minutes / 60.0 * LOOP_KMH))
    is LoopChoice.Km -> LoopChoice.Minutes(nearestStep(LOOP_MINUTES, choice.km / LOOP_KMH * 60.0))
}

private fun nearestStep(steps: IntProgression, v: Double): Int = steps.minBy { kotlin.math.abs(it - v) }

/** Which way loops should head: any, or roughly north, east, south or
 * west ([bearing] in degrees clockwise from north). */
enum class LoopDirection(val bearing: Double?) {
    ANY(null),
    NORTH(0.0),
    EAST(90.0),
    SOUTH(180.0),
    WEST(270.0),
}

/** A seed for another set of loops: never 0 (the standard loops). */
fun shuffleSeed(random: kotlin.random.Random = kotlin.random.Random.Default): UInt =
    random.nextInt(1, Int.MAX_VALUE).toUInt()

/** The loop shown after [index] of [count]: the next one, back to the
 * first after the last. */
fun nextLoop(index: Int, count: Int): Int = if (count <= 0) 0 else (index + 1).mod(count)
