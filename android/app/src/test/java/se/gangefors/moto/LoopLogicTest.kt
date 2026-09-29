// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.gangefors.moto.core.RoundTripTarget

class LoopLogicTest {
    @Test
    fun choicesBecomeCoreTargets() {
        assertEquals(RoundTripTarget.DurationS(7200.0), LoopChoice.Minutes(120).target)
        assertEquals(RoundTripTarget.DistanceM(100_000.0), LoopChoice.Km(100).target)
    }

    @Test
    fun choicesAreStoredByKey() {
        val all = loopSteps(LoopChoice.Minutes(30)) + loopSteps(LoopChoice.Km(20))
        all.forEach { assertEquals(it, loopChoiceOf(it.key)) }
        assertEquals(all.size, all.map { it.key }.toSet().size)
        // What the old chips stored still reads back.
        assertEquals(LoopChoice.Minutes(240), loopChoiceOf("min:240"))
        assertEquals(LoopChoice.Km(150), loopChoiceOf("km:150"))
        assertEquals("min:120", LoopChoice.Minutes(120).key)
        assertEquals("km:50", LoopChoice.Km(50).key)
    }

    @Test
    fun unknownStoredChoicesFallBackToTheDefault() {
        assertEquals(DEFAULT_LOOP, loopChoiceOf(null))
        assertEquals(DEFAULT_LOOP, loopChoiceOf(""))
        assertEquals(DEFAULT_LOOP, loopChoiceOf("km:5000"))
        assertEquals(DEFAULT_LOOP, loopChoiceOf("min:-60"))
        assertEquals(DEFAULT_LOOP, loopChoiceOf("KM:100"))
        assertEquals(DEFAULT_LOOP, loopChoiceOf("min:45"))
        assertEquals(DEFAULT_LOOP, loopChoiceOf("km:410"))
        assertEquals(LoopChoice.Km(400), loopChoiceOf("km:400"))
        assertEquals(DEFAULT_LOOP, loopChoiceOf("min:450"))
        assertEquals(DEFAULT_LOOP, loopChoiceOf("km:15"))
        assertEquals(DEFAULT_LOOP, loopChoiceOf("min:120:1"))
        assertEquals(DEFAULT_LOOP, loopChoiceOf("min:"))
        assertEquals(true, DEFAULT_LOOP in loopSteps(DEFAULT_LOOP))
    }

    @Test
    fun choicesFitTheCoresLimits() {
        // The core takes 5–400 km (a time counts at 15 m/s, 54 km/h).
        (loopSteps(LoopChoice.Minutes(30)) + loopSteps(LoopChoice.Km(20))).forEach {
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
    fun directionsAreCompassBearings() {
        assertEquals(listOf(null, 0.0, 90.0, 180.0, 270.0), LoopDirection.entries.map { it.bearing })
    }

    @Test
    fun previousLoopWrapsAround() {
        assertEquals(2, previousLoop(0, 3))
        assertEquals(0, previousLoop(1, 3))
        assertEquals(1, previousLoop(2, 3))
        assertEquals(0, previousLoop(0, 1))
        assertEquals(0, previousLoop(0, 0))
        assertEquals(1, previousLoop(5, 3))
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

    @Test
    fun switchingUnitKeepsAboutTheSameLoop() {
        assertEquals(LoopChoice.Km(110), switchUnit(LoopChoice.Minutes(120)))
        assertEquals(LoopChoice.Minutes(120), switchUnit(LoopChoice.Km(110)))
        assertEquals(LoopChoice.Km(30), switchUnit(LoopChoice.Minutes(30)))
        assertEquals(LoopChoice.Km(320), switchUnit(LoopChoice.Minutes(360)))
        assertEquals(LoopChoice.Km(380), switchUnit(LoopChoice.Minutes(420)))
        assertEquals(LoopChoice.Minutes(420), switchUnit(LoopChoice.Km(400)))
        assertEquals(LoopChoice.Minutes(30), switchUnit(LoopChoice.Km(20)))
        assertEquals(LoopChoice.Minutes(330), switchUnit(LoopChoice.Km(300)))
        // Every step switches to a step on the other slider.
        (loopSteps(LoopChoice.Minutes(30)) + loopSteps(LoopChoice.Km(20))).forEach {
            val other = switchUnit(it)
            assertEquals(true, other in loopSteps(other))
        }
    }

    @Test
    fun aNewLoopStartsAtTheLengthLastPicked() {
        val saved = mutableListOf<LoopChoice>()
        val length = LoopLength(LoopChoice.Minutes(120)) { saved += it }
        // Picked on the loop sheet: kept for the next loop, and stored.
        length.pick(LoopChoice.Km(150))
        assertEquals(LoopChoice.Km(150), length.choice)
        // Picked in Ride settings: the same length.
        length.pick(LoopChoice.Minutes(180))
        assertEquals(LoopChoice.Minutes(180), length.choice)
        // The same length again stores nothing.
        length.pick(LoopChoice.Minutes(180))
        assertEquals(listOf(LoopChoice.Km(150), LoopChoice.Minutes(180)), saved)
    }
}

class OneAheadTest {
    private val dropped = mutableListOf<String>()
    private val ahead = OneAhead<Int, String> { dropped += it }

    @Test
    fun theValueForTheSameKeyIsHandedOverOnce() {
        ahead.hold(1, "set 1")
        assertEquals("set 1", ahead.take(1))
        assertNull(ahead.take(1))
        assertEquals(emptyList<String>(), dropped)
    }

    @Test
    fun anotherKeyGetsNothingAndDropsTheHeldValue() {
        ahead.hold(1, "set 1")
        assertNull(ahead.take(2))
        assertEquals(listOf("set 1"), dropped)
        assertNull(ahead.take(1))
    }

    @Test
    fun holdingAnotherOrClearingDropsTheOldOne() {
        ahead.hold(1, "set 1")
        ahead.hold(2, "set 2")
        assertEquals(listOf("set 1"), dropped)
        ahead.clear()
        assertEquals(listOf("set 1", "set 2"), dropped)
        ahead.clear()
        assertEquals(2, dropped.size)
        assertNull(ahead.take(2))
    }

    @Test
    fun theScaleShowsWholeHoursAndHundredsOfKm() {
        val hours = loopSteps(LoopChoice.Minutes(120)).filter(::isLoopMark).map { (it as LoopChoice.Minutes).minutes / 60 }
        assertEquals((1..7).toList(), hours)
        val km = loopSteps(LoopChoice.Km(120)).filter(::isLoopMark).map { (it as LoopChoice.Km).km }
        assertEquals(listOf(100, 200, 300, 400), km)
    }
}
