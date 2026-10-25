// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gangefors.moto.core.Direction
import se.gangefors.moto.core.FavouriteSurface
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.Rating
import se.gangefors.moto.core.Section
import se.gangefors.moto.core.SectionDraft
import se.gangefors.moto.core.SectionSource
import se.gangefors.moto.core.SectionStatus
import se.gangefors.moto.core.Surface
import se.gangefors.moto.core.Tag
import se.gangefors.moto.core.WaySpan

class SectionSheetLogicTest {
    private val line = listOf(LatLon(55.7, 13.2), LatLon(55.71, 13.2), LatLon(55.72, 13.21))
    private val ways = listOf(WaySpan(42, 0u, 3u))
    private val draft = SectionDraft(ways, line, 2300.0)
    private val moved = SectionDraft(listOf(WaySpan(43, 0u, 2u)), line.take(2), 1100.0)
    private val tag = Tag(5, "local", 0, LatLon(55.7, 13.2), null, null, null)

    private fun section(
        name: String = "",
        direction: Direction = Direction.BOTH,
        status: SectionStatus = SectionStatus.OK,
        surface: Surface? = null,
        rating: Rating = Rating.GREAT,
    ) = Section(3, "local", name, rating, direction, SectionSource.MAP, status, 0, 0, ways, line, surface)

    private val new = SheetSubject.New(draft, null)
    private val saved = SheetSubject.Saved(section(), null)

    @Test
    fun theSubjectComesFromWhatIsOpen() {
        val s = section()
        assertNull(sheetSubject(false, draft, null, null, null))
        assertNull(sheetSubject(true, null, null, null, null))
        assertEquals(SheetSubject.New(draft, null), sheetSubject(true, draft, null, null, null))
        assertEquals(SheetSubject.New(draft, tag), sheetSubject(true, draft, tag, null, null))
        assertEquals(SheetSubject.Saved(s, null), sheetSubject(false, null, null, s, null))
        val pending = PendingEdit(3, SectionChoice(Rating.EPIC, true), moved)
        assertEquals(SheetSubject.Saved(s, moved), sheetSubject(false, null, null, s, pending))
        // A pending edit of another favourite is not this one's.
        assertEquals(SheetSubject.Saved(s, null), sheetSubject(false, null, null, s, pending.copy(sectionId = 9)))
    }

    @Test
    fun theTitleAndItsLength() {
        assertEquals(R.string.section_new_title, sheetTitleRes(new))
        assertEquals(R.string.section_edit_title, sheetTitleRes(saved))
        assertEquals(2300.0, sheetLengthM(new), 0.0)
        assertEquals(1100.0, sheetLengthM(SheetSubject.Saved(section(), moved)), 0.0)
        assertEquals(lengthM(line), sheetLengthM(saved), 0.0)
    }

    @Test
    fun onlyASavedFavouriteHasTheBin() {
        assertFalse(canDelete(new))
        assertFalse(canDelete(SheetSubject.New(draft, tag)))
        assertTrue(canDelete(saved))
    }

    @Test
    fun aNewFavouriteStartsOnTheDefaults() {
        val c = sheetFirstChoice(new, null)
        assertEquals(Rating.GOOD, c.rating)
        assertFalse(c.oneWay)
        assertEquals("", c.name)
        assertFalse(c.reverse)
        assertEquals(FavouriteSurface.MAP, c.surface)
    }

    @Test
    fun keptChoicesWinForBothSubjects() {
        val kept = SectionChoice(Rating.EPIC, true, "Ridge", reverse = true, surface = FavouriteSurface.GRAVEL)
        assertEquals(kept, sheetFirstChoice(new, kept))
        assertEquals(kept, sheetFirstChoice(SheetSubject.Saved(section(rating = Rating.GOOD), moved), kept))
    }

    @Test
    fun aSavedFavouriteStartsOnItsSavedValues() {
        val s = SheetSubject.Saved(
            section(name = "Ridge road", direction = Direction.FORWARD, surface = Surface.PAVED, rating = Rating.EPIC),
            null,
        )
        assertEquals(
            SectionChoice(Rating.EPIC, true, "Ridge road", surface = FavouriteSurface.PAVED),
            sheetFirstChoice(s, null),
        )
        // The app's own name is not the rider's.
        assertEquals("", sheetFirstChoice(SheetSubject.Saved(section(name = "Map 2026-10-25 10:00, 2.3 km"), null), null).name)
        assertEquals(FavouriteSurface.GRAVEL, sheetFirstChoice(SheetSubject.Saved(section(surface = Surface.GRAVEL), null), null).surface)
    }

    @Test
    fun theWaysAskedAboutAreThoseSaveWouldStore() {
        assertEquals(ways, sheetMapWays(new))
        assertEquals(moved.ways, sheetMapWays(SheetSubject.Saved(section(), moved)))
        assertEquals(ways, sheetMapWays(saved))
        assertNull(sheetMapWays(SheetSubject.Saved(section(status = SectionStatus.UNMATCHED), null)))
        // A moved line wins even when the saved one no longer fits.
        assertEquals(moved.ways, sheetMapWays(SheetSubject.Saved(section(status = SectionStatus.UNMATCHED), moved)))
    }

    @Test
    fun theLineFittedIsTheOneSaveWouldStore() {
        assertEquals(line, sheetLine(new))
        assertEquals(moved.geometry, sheetLine(SheetSubject.Saved(section(), moved)))
        assertEquals(line, sheetLine(saved))
    }

    @Test
    fun savingANewFavouriteAdds() {
        val c = SectionChoice(Rating.GREAT, true, "  Ridge road ", reverse = true, surface = FavouriteSurface.GRAVEL)
        val w = sheetWrite(new, c, "Map 2026-10-25 10:00, 2.3 km") as SheetWrite.Add
        assertEquals("Ridge road", w.section.name)
        assertEquals(Rating.GREAT, w.section.rating)
        assertEquals(Direction.FORWARD, w.section.direction)
        assertEquals(SectionSource.MAP, w.section.source)
        assertEquals(ways, w.section.ways)
        assertEquals(line, w.section.geometry)
        assertEquals(Surface.GRAVEL, w.section.surface)
        assertTrue(w.section.reverse)
    }

    @Test
    fun aNewFavouriteWithoutANameGetsTheAutoNameAndTheTagSource() {
        val c = SectionChoice(Rating.GOOD, false, "   ")
        val w = sheetWrite(SheetSubject.New(draft, tag), c, "Tag 2026-10-25 10:00, 2.3 km") as SheetWrite.Add
        assertEquals("Tag 2026-10-25 10:00, 2.3 km", w.section.name)
        assertEquals(SectionSource.TAG, w.section.source)
        assertEquals(Direction.BOTH, w.section.direction)
        assertNull(w.section.surface)
    }

    @Test
    fun theTurnRoundIsMappedOnlyWhenOneWay() {
        val off = sheetWrite(new, SectionChoice(Rating.GOOD, false, reverse = true), "x") as SheetWrite.Add
        assertFalse(off.section.reverse)
        val on = sheetWrite(new, SectionChoice(Rating.GOOD, true, reverse = true), "x") as SheetWrite.Add
        assertTrue(on.section.reverse)
        val notTurned = sheetWrite(new, SectionChoice(Rating.GOOD, true), "x") as SheetWrite.Add
        assertFalse(notTurned.section.reverse)
    }

    @Test
    fun theSurfaceIsMappedForANewFavourite() {
        fun surface(s: FavouriteSurface) =
            (sheetWrite(new, SectionChoice(Rating.GOOD, false, surface = s), "x") as SheetWrite.Add).section.surface
        assertNull(surface(FavouriteSurface.MAP))
        assertEquals(Surface.PAVED, surface(FavouriteSurface.PAVED))
        assertEquals(Surface.GRAVEL, surface(FavouriteSurface.GRAVEL))
    }

    @Test
    fun savingASavedFavouriteUpdates() {
        val s = SheetSubject.Saved(section(name = "Ridge road"), moved)
        val c = SectionChoice(Rating.EPIC, true, "Ridge", reverse = true, surface = FavouriteSurface.PAVED)
        val w = sheetWrite(s, c, "") as SheetWrite.Update
        assertEquals(3L, w.id)
        assertEquals("Ridge", w.update.name)
        assertEquals(Rating.EPIC, w.update.rating)
        assertEquals(Direction.FORWARD, w.update.direction)
        assertTrue(w.update.reverse)
        assertEquals(moved, w.update.line)
        assertEquals(FavouriteSurface.PAVED, w.update.surface)
    }

    @Test
    fun anUnchangedNameIsNoNameUpdate() {
        val s = SheetSubject.Saved(section(name = "Ridge road"), null)
        val w = sheetWrite(s, SectionChoice(Rating.GREAT, false, "Ridge road"), "") as SheetWrite.Update
        assertNull(w.update.name)
        assertFalse(w.update.reverse)
        assertNull(w.update.line)
    }

    @Test
    fun theDraftPreviewShowsArrowsAndTurnsOnlyWhileTheSheetIsOpen() {
        assertEquals(line to false, draftPreviewLine(line, false, true to true))
        assertEquals(line to false, draftPreviewLine(line, true, null))
        assertEquals(line to false, draftPreviewLine(line, true, false to false))
        assertEquals(line to true, draftPreviewLine(line, true, true to false))
        assertEquals(line.reversed() to true, draftPreviewLine(line, true, true to true))
        // A turn without one-way is not drawn (the sheet never sends it).
        assertEquals(line to false, draftPreviewLine(line, true, false to true))
    }
}
