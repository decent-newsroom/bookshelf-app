package eu.decentnewsroom.bookshelf.ui.reader

import eu.decentnewsroom.bookshelf.data.reader.BookReadingPresentation
import eu.decentnewsroom.bookshelf.data.reader.BookReadingProgressSource
import eu.decentnewsroom.bookshelf.data.reader.ReadingProgress
import eu.decentnewsroom.bookshelf.data.reader.readingPublicationFingerprint
import eu.decentnewsroom.bookshelf.data.reader.resolveBookReadingPresentation
import eu.decentnewsroom.bookshelf.data.reading.TrackedBook
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.domain.ChapterReference
import eu.decentnewsroom.bookshelf.ui.books.chapterReadingProgressLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderProgressPresentationTest {
    @Test
    fun trackedMainPositionFallsBackToActualLocalLocation() {
        val book = summary()
        val progress = progress(index = 1)
        val trackedPresentation = resolveBookReadingPresentation(
            progress = null,
            tracked = tracked(position = 3),
            finished = null,
            book = book,
        )

        val result = readerPositionPresentation(progress, trackedPresentation, book)

        assertEquals(BookReadingProgressSource.Tracked, trackedPresentation.source)
        assertEquals(BookReadingProgressSource.Local, result.source)
        assertEquals(0.25f, result.fraction!!, 0.0001f)
        assertEquals("About 25% by chapter position", result.progressLabel)
    }

    @Test
    fun furthestTrackedPositionStaysVisibleWhileReadingEarlierSection() {
        val label = readerFurthestTrackedLabel(progress(index = 1), tracked(position = 3), summary())

        assertEquals("Furthest tracked section: 4 of 4", label)
    }

    @Test
    fun lastTrackedSectionDoesNotMarkTheBookComplete() {
        val presentation = resolveBookReadingPresentation(
            progress = null,
            tracked = tracked(position = 3),
            finished = null,
            book = summary(),
        )

        assertEquals(75, presentation.percentage)
        assertFalse(presentation.isComplete)
        assertEquals("Furthest tracked section: 4 of 4", readerFurthestTrackedLabel(progress(), tracked(position = 3), summary()))
    }

    @Test
    fun freshReaderKeepsChapterOnePositionDespiteHigherTrackedProgress() {
        val book = summary()
        val progress = ReadingProgress.initial("book", 4)
        val trackedPresentation = resolveBookReadingPresentation(
            progress = null,
            tracked = tracked(position = 3),
            finished = null,
            book = book,
        )

        val localPresentation = readerPositionPresentation(progress, trackedPresentation, book)

        assertNull(localPresentation.fraction)
        assertFalse(localPresentation.hasStarted)
        assertEquals(
            "Chapter 1 of 4 | Not started",
            chapterReadingProgressLabel(progress, localPresentation),
        )
    }

    @Test
    fun furthestTrackedLabelIsOmittedWhenItMatchesLocalPosition() {
        assertNull(readerFurthestTrackedLabel(progress(index = 2), tracked(position = 2), summary()))
    }

    @Test
    fun furthestTrackedLabelHandlesUnknownOrderAndChangedEdition() {
        val progress = progress(index = 0)
        val unknownOrder = summary().copy(sectionStreamKnown = false)
        assertEquals(
            "Furthest tracked section: unavailable",
            readerFurthestTrackedLabel(progress, tracked(position = 3), unknownOrder),
        )

        val previousEdition = summary().copy(id = "old-edition")
        val currentEdition = summary().copy(id = "current-edition")
        val staleTracked = tracked(position = 3, book = previousEdition, sectionId = "missing-event")
        assertEquals(
            "Furthest tracked section: unavailable",
            readerFurthestTrackedLabel(progress, staleTracked, currentEdition),
        )
        assertNull(readerFurthestTrackedLabel(progress, null, currentEdition))
    }

    @Test
    fun completedLocalPresentationIsPreservedAndLocalProgressCanReachEnd() {
        val book = summary()
        val completedProgress = progress(index = 3).copy(
            reachedEnd = true,
            contentFingerprint = "verified-content",
            publicationFingerprint = readingPublicationFingerprint(book),
        )
        val completed = resolveBookReadingPresentation(completedProgress, null, null, book)
        val tracked = resolveBookReadingPresentation(null, tracked(position = 1), null, book)

        val local = readerPositionPresentation(completedProgress, completed, book)
        val fallback = readerPositionPresentation(completedProgress, tracked, book)

        assertSame(completed, local)
        assertTrue(local.isComplete)
        assertEquals(100, local.percentage)
        assertTrue(fallback.isComplete)
        assertFalse(fallback.isMarkedFinished)
    }

    @Test
    fun changedEditionTrackedEventIdResolvesInsteadOfUsingStalePosition() {
        val progress = progress(index = 0)
        val previousEdition = summary().copy(id = "old-edition")
        val currentEdition = summary().copy(id = "current-edition")
        val tracked = tracked(position = 3, book = previousEdition, sectionId = "event-1")

        assertEquals(
            "Furthest tracked section: 2 of 4",
            readerFurthestTrackedLabel(progress, tracked, currentEdition),
        )
    }

    @Test
    fun markedFinishedLocalPresentationIsPreserved() {
        val markedFinished = BookReadingPresentation(
            hasStarted = true,
            fraction = 1f,
            isComplete = true,
            isMarkedFinished = true,
        )

        assertSame(markedFinished, readerPositionPresentation(progress(), markedFinished, summary()))
    }

    private fun progress(index: Int = 0) = ReadingProgress(
        bookCoordinate = "book",
        currentChapterIndex = index,
        chapterCount = 4,
        updatedAtMillis = 500_000,
        hasStarted = true,
        reachedEnd = false,
        fullChapterCount = 4,
        completeContent = true,
        chapterCoordinate = "chapter-$index",
    )

    private fun tracked(
        position: Int,
        book: BookSummary? = null,
        sectionId: String? = null,
    ) = TrackedBook(
        bookCoordinate = "book",
        book = book,
        position = position,
        total = 4,
        sectionId = sectionId,
        updatedAt = 1_000L,
    )

    private fun summary() = BookSummary(
        id = "edition",
        coordinate = "book",
        pubkey = "author",
        identifier = "book",
        title = "Book",
        summary = null,
        authors = emptyList(),
        coverImageUrl = null,
        sourceUrl = null,
        language = null,
        releaseDate = null,
        version = null,
        type = "book",
        topics = emptyList(),
        relay = null,
        createdAt = 1L,
        chapterCount = 4,
        chapterRefs = (0..3).map { index ->
            ChapterReference(
                coordinate = "chapter-$index",
                pubkey = "author",
                identifier = "chapter-$index",
                relay = null,
                eventId = "event-$index",
            )
        },
    )
}
