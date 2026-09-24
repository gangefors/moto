// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import se.gangefors.moto.core.RoundTripTarget

/**
 * A round-trip length the rider can pick (PRD R7): a riding time or a
 * distance. The core returns loops within ±15 % of it.
 */
sealed interface LoopChoice {
    /** Stored in preferences as "min:120" or "km:100". */
    val key: String
    val target: RoundTripTarget

    data class Hours(val hours: Int) : LoopChoice {
        override val key get() = "min:${hours * 60}"
        override val target get() = RoundTripTarget.DurationS(hours * 3600.0)
    }

    data class Km(val km: Int) : LoopChoice {
        override val key get() = "km:$km"
        override val target get() = RoundTripTarget.DistanceM(km * 1000.0)
    }
}

/** The lengths on offer: times first (how a rider plans a free
 * afternoon), then distances. */
val LOOP_CHOICES: List<LoopChoice> = listOf(
    LoopChoice.Hours(1),
    LoopChoice.Hours(2),
    LoopChoice.Hours(3),
    LoopChoice.Hours(4),
    LoopChoice.Km(50),
    LoopChoice.Km(100),
    LoopChoice.Km(150),
    LoopChoice.Km(200),
)

val DEFAULT_LOOP: LoopChoice = LoopChoice.Hours(2)

/** A stored choice, or the default when it isn't one on offer
 * (preferences are read back as untrusted input). */
fun loopChoiceOf(stored: String?): LoopChoice = LOOP_CHOICES.firstOrNull { it.key == stored } ?: DEFAULT_LOOP

/** The loop shown after [index] of [count]: the next one, back to the
 * first after the last. */
fun nextLoop(index: Int, count: Int): Int = if (count <= 0) 0 else (index + 1).mod(count)
