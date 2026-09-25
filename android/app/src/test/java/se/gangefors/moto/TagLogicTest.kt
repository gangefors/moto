// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.Tag
import se.gangefors.moto.core.TagStatus
import se.gangefors.moto.core.TrackPoint

class TagLogicTest {
    private val now = 1_790_000_000_000L

    private fun fix(ageMs: Long, lat: Double = 55.7) =
        TrackPoint(now - ageMs, LatLon(lat, 13.2), 5.0, 20.0, 90.0)

    @Test
    fun usesTheFreshestFix() {
        val recording = fix(2_000, lat = 55.1)
        val map = fix(500, lat = 55.2)
        assertEquals(55.2, chooseTagFix(recording, map, now)!!.position.lat, 0.0)
        assertEquals(55.1, chooseTagFix(recording, null, now)!!.position.lat, 0.0)
        assertEquals(55.2, chooseTagFix(null, map, now)!!.position.lat, 0.0)
    }

    @Test
    fun staleOrMissingFixesTagNothing() {
        assertNull(chooseTagFix(null, null, now))
        assertNull(chooseTagFix(fix(MAX_TAG_FIX_AGE_MS + 1), fix(60_000), now))
        // A fix far in the future (wrong clock) doesn't count either.
        assertNull(chooseTagFix(fix(-60_000), null, now))
        // A little ahead of the phone clock is fine.
        assertEquals(now + 2_000, chooseTagFix(fix(-2_000), null, now)!!.timeMs)
        assertEquals(now - MAX_TAG_FIX_AGE_MS, chooseTagFix(fix(MAX_TAG_FIX_AGE_MS), null, now)!!.timeMs)
    }

    @Test
    fun aTagCarriesTheFixAndTheRide() {
        val t = newTag(fix(0), trackId = 7L)
        assertEquals(now, t.timeMs)
        assertEquals(90.0, t.headingDeg!!, 0.0)
        assertEquals(20.0, t.speedMps!!, 0.0)
        assertEquals(7L, t.trackId)
        assertNull(newTag(fix(0), trackId = null).trackId)
    }

    private fun tag(id: Long) = Tag(id, "local", now + id, LatLon(55.7, 13.2), null, null, null, TagStatus.PENDING)

    @Test
    fun reviewsTagsOneByOne() {
        val review = TagReview(listOf(tag(1), tag(2), tag(3)))
        assertEquals(3, review.size)
        assertEquals(1L, review.current!!.id)
        assertEquals(1, review.position)
        assertEquals(2L, review.next()!!.id)
        assertEquals(2, review.position)
        assertEquals(3L, review.next()!!.id)
        assertNull(review.next())
        assertNull(review.next())
        assertNull(review.current)
    }

    @Test
    fun skippedTagsAreCountedAndTheReviewMovesOn() {
        val review = TagReview(listOf(tag(1), tag(2), tag(3)))
        assertEquals(2L, review.skip()!!.id)
        assertEquals(1, review.skipped)
        assertEquals(3L, review.next()!!.id)
        assertNull(review.skip())
        assertEquals(2, review.skipped)
        // Nothing left to skip.
        assertNull(review.skip())
        assertEquals(2, review.skipped)
    }

    @Test
    fun anEmptyReviewHasNothingToShow() {
        val review = TagReview(emptyList())
        assertNull(review.current)
        assertNull(review.next())
        assertNull(review.skip())
        assertEquals(0, review.skipped)
    }

    @Test
    fun aSuggestionStartsAsAProposedSection() {
        val m = SectionMarker<Double> { a, b -> kotlin.math.abs(a - b) }
        m.propose(1.0, 9.0)
        assertEquals(SectionMarker.State.Proposed(1.0, 9.0), m.state)
        // Trimming moves the nearer end, as when marking by hand.
        assertEquals(SectionMarker.State.Proposed(1.0, 7.0), m.onTap(7.0))
        m.rejectLast()
        assertEquals(SectionMarker.State.Proposed(1.0, 9.0), m.state)
    }
}
