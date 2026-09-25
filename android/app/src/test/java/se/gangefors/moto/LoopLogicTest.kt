// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Test
import se.gangefors.moto.core.RoundTripTarget

class LoopLogicTest {
    @Test
    fun choicesBecomeCoreTargets() {
        assertEquals(RoundTripTarget.DurationS(7200.0), LoopChoice.Hours(2).target)
        assertEquals(RoundTripTarget.DistanceM(100_000.0), LoopChoice.Km(100).target)
    }

    @Test
    fun choicesAreStoredByKey() {
        LOOP_CHOICES.forEach { assertEquals(it, loopChoiceOf(it.key)) }
        assertEquals(LOOP_CHOICES.size, LOOP_CHOICES.map { it.key }.toSet().size)
        assertEquals("min:120", LoopChoice.Hours(2).key)
        assertEquals("km:50", LoopChoice.Km(50).key)
    }

    @Test
    fun unknownStoredChoicesFallBackToTheDefault() {
        assertEquals(DEFAULT_LOOP, loopChoiceOf(null))
        assertEquals(DEFAULT_LOOP, loopChoiceOf(""))
        assertEquals(DEFAULT_LOOP, loopChoiceOf("km:5000"))
        assertEquals(DEFAULT_LOOP, loopChoiceOf("min:-60"))
        assertEquals(DEFAULT_LOOP, loopChoiceOf("KM:100"))
        assertEquals(true, DEFAULT_LOOP in LOOP_CHOICES)
    }

    @Test
    fun choicesFitTheCoresLimits() {
        // The core takes 5–400 km (a time counts at 15 m/s, 54 km/h).
        LOOP_CHOICES.forEach {
            val km = when (val t = it.target) {
                is RoundTripTarget.DistanceM -> t.meters / 1000.0
                is RoundTripTarget.DurationS -> t.seconds * 15.0 / 1000.0
            }
            assert(km in 5.0..400.0) { "$it is $km km" }
        }
    }

    @Test
    fun shuffleSeedsAreNeverTheStandardSet() {
        val random = kotlin.random.Random(1)
        repeat(1000) { assert(shuffleSeed(random) != 0u) }
        assertEquals(shuffleSeed(kotlin.random.Random(5)), shuffleSeed(kotlin.random.Random(5)))
    }

    @Test
    fun nextLoopWrapsAround() {
        assertEquals(1, nextLoop(0, 3))
        assertEquals(2, nextLoop(1, 3))
        assertEquals(0, nextLoop(2, 3))
        assertEquals(0, nextLoop(0, 1))
        assertEquals(0, nextLoop(0, 0))
        assertEquals(0, nextLoop(5, 3))
    }
}
