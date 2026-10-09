package eu.decentnewsroom.bookshelf.ui

import eu.decentnewsroom.bookshelf.data.reader.ReadingProgress
import eu.decentnewsroom.bookshelf.data.reading.FinishedBook
import eu.decentnewsroom.bookshelf.data.reading.ReadingState
import eu.decentnewsroom.bookshelf.data.reading.TrackedBook
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.domain.ChapterReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookReadingPresentationsTest {
    @Test
    fun backwardLocalPositionWinsOverFurthestTrackedSectionAndRemainsUnsavedEligible() {
        val book = book()
        val progress = ReadingProgress("book", 1, 4, 500, chapterScrollOffsetPx = 20,
            hasStarted = true, fullChapterCount = 4, completeContent = true, chapterCoordinate = "chapter-1")
        val reading = ReadingState(tracked = listOf(TrackedBook("book", book, 3, 4, updatedAt = 2)))
        val result = bookReadingPresentations(mapOf("book" to progress), reading, listOf(book))
        assertEquals(25, result.getValue("book").percentage)
        assertTrue(result.getValue("book").hasStarted)
        assertFalse(result.getValue("book").isComplete)
    }

    @Test
    fun finishedWithoutActivityHasOneCompletedPresentationAcrossSurfaces() {
        val reading = ReadingState(finished = listOf(FinishedBook("book", finishedAt = 12)))
        val result = bookReadingPresentations(mapOf("book" to ReadingProgress("book", 1, 4, 90_000)), reading, listOf(book()))
        assertEquals(100, result.getValue("book").percentage)
        assertTrue(result.getValue("book").isMarkedFinished)
    }

    @Test
    fun accountHandoffHidesOldListsButPreservesDeviceLocalProgress() {
        val old = ReadingState(finished = listOf(FinishedBook("old-account-book", finishedAt = 1)))
        val filtered = activeReadingState(old, "old", "new")
        val progress = ReadingProgress("book", 1, 4, 100, hasStarted = true, fullChapterCount = 4,
            completeContent = true, chapterCoordinate = "chapter-1")
        val result = bookReadingPresentations(mapOf("book" to progress), filtered, listOf(book()))
        assertFalse("old-account-book" in result)
        assertEquals(25, result.getValue("book").percentage)
        assertEquals(old, activeReadingState(old, "old", "old"))
    }

    private fun book(): BookSummary = BookSummary(
        id = "edition", coordinate = "book", pubkey = "author", identifier = "book", title = "Book",
        summary = null, authors = emptyList(), coverImageUrl = null, sourceUrl = null, language = null,
        releaseDate = null, version = null, type = "book", topics = emptyList(), relay = null,
        createdAt = 0, chapterCount = 4,
        chapterRefs = (0 until 4).map { ChapterReference("chapter-$it", "author", "chapter-$it", null, "event-$it") },
    )
}
