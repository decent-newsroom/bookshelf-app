package eu.decentnewsroom.bookshelf.ui

import eu.decentnewsroom.bookshelf.ui.reader.ReaderResumePositionTracker
import eu.decentnewsroom.bookshelf.ui.reader.ReaderObservation
import eu.decentnewsroom.bookshelf.ui.reader.readerHasCompleteContent
import eu.decentnewsroom.bookshelf.ui.reader.readerIsAtVerifiedEnd
import eu.decentnewsroom.bookshelf.ui.reader.readerObservationHasActivity
import eu.decentnewsroom.bookshelf.ui.reader.readerTrackingChapterIndexForListItem
import eu.decentnewsroom.bookshelf.ui.reader.readerHasTerminalActions
import eu.decentnewsroom.bookshelf.ui.reader.readerResumePositionForListItem
import eu.decentnewsroom.bookshelf.ui.reader.readerShouldPublishPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTerminalProgressTest {
    @Test
    fun pixelOnlyFramesWaitWhileChapterStartEndpointAndNewActivityPublishImmediately() {
        val current = ReaderObservation(1, 80, false, false, 1)
        assertFalse(readerShouldPublishPosition(current, current.copy(scrollOffsetPx = 81), false))
        assertTrue(readerShouldPublishPosition(current, current.copy(chapterIndex = 2), false))
        assertTrue(readerShouldPublishPosition(current, current.copy(reachedEnd = true), false))
        assertTrue(readerShouldPublishPosition(current.copy(scrollOffsetPx = 0), current, false))
        assertTrue(readerShouldPublishPosition(current, current.copy(scrollOffsetPx = 81), true))
    }

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

    @Test
    fun endpointRequiresCompleteAvailableContentAndLaidOutBottom() {
        assertTrue(readerHasCompleteContent(true, 2, 2, true, false))
        assertFalse(readerHasCompleteContent(false, 2, 2, true, false))
        assertFalse(readerHasCompleteContent(true, 2, 1, true, false))
        assertFalse(readerHasCompleteContent(true, 2, 2, false, false))
        assertFalse(readerHasCompleteContent(true, 2, 2, true, true))

        assertTrue(readerIsAtVerifiedEnd(true, false, true, 800))
        assertFalse(readerIsAtVerifiedEnd(true, false, false, 800))
        assertFalse(readerIsAtVerifiedEnd(true, false, true, 0))
        assertFalse(readerIsAtVerifiedEnd(true, true, true, 800))
        assertFalse(readerIsAtVerifiedEnd(false, false, true, 800))
    }

    @Test
    fun shortBookCanReachBottomAndStillUsesLastRealChapterIndex() {
        val chapterCount = 1
        val endpoint = readerIsAtVerifiedEnd(
            completeContent = readerHasCompleteContent(true, 1, 1, true, false),
            canScrollForward = false,
            hasVisibleItems = true,
            viewportSizePx = 800,
        )

        assertTrue(endpoint)
        assertEquals(0, chapterCount - 1)
        assertEquals(0 to 0, readerResumePositionForListItem(1, 0, chapterCount))
    }

    @Test
    fun footerOnlyStartFallsBackToSavedChapterAndNeverCreatesSentinelIndex() {
        val tracker = ReaderResumePositionTracker(4, initialPosition = 2 to 37)

        assertNull(tracker.observe(5, 290))
        assertEquals(2 to 37, tracker.positionOnExit(5, 290))
        assertEquals(2, tracker.positionOnExit(5, 290)?.first)
    }

    @Test
    fun trackedSectionAttributionRequiresARealChapterOrVerifiedEndpoint() {
        assertNull(readerTrackingChapterIndexForListItem(0, 4, reachedEnd = false))
        assertEquals(0, readerTrackingChapterIndexForListItem(1, 4, reachedEnd = false))
        assertEquals(3, readerTrackingChapterIndexForListItem(4, 4, reachedEnd = false))
        assertNull(readerTrackingChapterIndexForListItem(5, 4, reachedEnd = false))
        assertEquals(3, readerTrackingChapterIndexForListItem(5, 4, reachedEnd = true))
    }

    @Test
    fun activityIgnoresSavedEndpointAndNonScrollLayoutReflow() {
        val savedEnd = ReaderObservation(3, 41, reachedEnd = true, readingActivity = false)
        assertFalse(readerObservationHasActivity(savedEnd, 3, 41, true, false, false))
        assertFalse(readerObservationHasActivity(savedEnd, 2, 12, false, false, false))
        assertTrue(readerObservationHasActivity(savedEnd, 2, 12, false, true, false))
        assertFalse(readerObservationHasActivity(savedEnd, 3, 41, false, false, true))
        assertTrue(readerObservationHasActivity(savedEnd, 2, 12, false, false, true))
    }
}
