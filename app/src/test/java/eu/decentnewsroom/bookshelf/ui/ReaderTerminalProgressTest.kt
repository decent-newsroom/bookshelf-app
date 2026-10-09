package eu.decentnewsroom.bookshelf.ui

import eu.decentnewsroom.bookshelf.ui.reader.ReaderResumePositionTracker
import eu.decentnewsroom.bookshelf.ui.reader.readerHasTerminalActions
import eu.decentnewsroom.bookshelf.ui.reader.readerResumePositionForListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTerminalProgressTest {
    @Test
    fun terminalCardsDoNotSaveTheirOffsetAgainstTheLastChapter() {
        assertEquals(3 to 81, readerResumePositionForListItem(4, 81, 4))
        assertNull(readerResumePositionForListItem(5, 250, 4))
        assertNull(readerResumePositionForListItem(6, 250, 4))
    }

    @Test
    fun leavingImmediatelyFromTerminalCardsPreservesLastObservedChapterPixels() {
        val tracker = ReaderResumePositionTracker(4)
        assertEquals(3 to 81, tracker.observe(4, 81))
        assertNull(tracker.observe(5, 250))
        assertEquals(3 to 81, tracker.positionOnExit(6, 430))
        assertEquals(2 to 32, tracker.positionOnExit(3, 32))
    }

    @Test
    fun metadataAndMissingSectionSlotsKeepExistingListIndexSemantics() {
        assertEquals(0 to 0, readerResumePositionForListItem(0, 260, 4))
        assertEquals(1 to 24, readerResumePositionForListItem(2, 24, 4))
        assertNull(readerResumePositionForListItem(0, 24, 0))
    }

    @Test
    fun unknownPartialAndCappedStreamsCannotAppearFinished() {
        assertFalse(readerHasTerminalActions(false, 500, 500, false))
        assertFalse(readerHasTerminalActions(true, 501, 500, true))
        assertFalse(readerHasTerminalActions(true, 501, 500, false))
        assertFalse(readerHasTerminalActions(true, 0, 0, false))
        assertTrue(readerHasTerminalActions(true, 500, 500, false))
        assertTrue(readerHasTerminalActions(true, 4, 4, false))
    }
}
