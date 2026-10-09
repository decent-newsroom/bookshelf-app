package eu.decentnewsroom.bookshelf.ui

import eu.decentnewsroom.bookshelf.data.reader.ReadingProgress
import eu.decentnewsroom.bookshelf.data.reading.TrackedBook
import eu.decentnewsroom.bookshelf.domain.BookChapter
import eu.decentnewsroom.bookshelf.domain.BookDetail
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.domain.ChapterReference
import eu.decentnewsroom.bookshelf.ui.reader.ReaderOpenTarget
import eu.decentnewsroom.bookshelf.ui.reader.readerNeedsTrackedFallback
import eu.decentnewsroom.bookshelf.ui.reader.resolveTrackedReaderChapterIndex
import eu.decentnewsroom.bookshelf.ui.reader.readerStartPosition
import eu.decentnewsroom.bookshelf.ui.reader.resolveChapterIndex
import org.junit.Assert.*
import org.junit.Test

class ReaderOpenTargetTest {
    @Test fun targetFollowsCoordinateAfterChapterReordering() {
        val first = chapter("first")
        val target = chapter("target")
        val intent = ReaderOpenTarget(target.reference.coordinate)
        assertEquals(1, intent.resolveChapterIndex(listOf(first, target)))
        assertEquals(0, intent.resolveChapterIndex(listOf(target, first)))
    }

    @Test fun missingAndUnavailableTargetsFallBackToResume() {
        val target = ReaderOpenTarget(chapter("target").reference.coordinate)
        assertNull(target.resolveChapterIndex(listOf(chapter("other"))))
        assertNull(target.resolveChapterIndex(listOf(chapter("target", available = false))))
        val resume = ReadingProgress("book", 2, 4, 99, 123)
        val position = readerStartPosition(resume, target.resolveChapterIndex(emptyList()))
        assertEquals(2, position.chapterIndex)
        assertEquals(123, position.scrollOffsetPx)
    }

    @Test fun explicitTargetOverridesResumeWithoutChangingPersistedProgress() {
        val resume = ReadingProgress("book", 2, 4, 99, 123)
        val position = readerStartPosition(resume, 1)
        assertEquals(1, position.chapterIndex)
        assertEquals(0, position.scrollOffsetPx)
        assertEquals(2, resume.currentChapterIndex)
        assertEquals(123, resume.chapterScrollOffsetPx)
    }

    @Test fun trackedFallbackOnlyAppliesToOpenedOnlyLocalProgress() {
        val openedOnly = ReadingProgress.initial("book-coordinate", 4)
        assertTrue(readerNeedsTrackedFallback(resolvedTargetIndex = null, progress = openedOnly))
        assertFalse(readerNeedsTrackedFallback(resolvedTargetIndex = null, progress = openedOnly.copy(hasStarted = true)))
        assertFalse(readerNeedsTrackedFallback(resolvedTargetIndex = null, progress = openedOnly.copy(currentChapterIndex = 1)))
        assertFalse(readerNeedsTrackedFallback(resolvedTargetIndex = null, progress = openedOnly.copy(chapterScrollOffsetPx = 1)))
        assertFalse(readerNeedsTrackedFallback(resolvedTargetIndex = 2, progress = openedOnly))
        assertTrue(readerNeedsTrackedFallback(resolvedTargetIndex = null, progress = null))
    }

    @Test fun trackedSectionIdentityResolvesAcrossEditionsButUnmatchedEditionDoesNotUsePosition() {
        val current = bookDetail("new-edition", listOf(chapter("first", eventId = "event-a"), chapter("second", eventId = "event-b")))
        val previousEdition = summary("old-edition", current.summary.chapterRefs)
        val tracked = tracked(current.summary.coordinate, position = 0, sectionId = "event-b", book = previousEdition)

        assertEquals(1, resolveTrackedReaderChapterIndex(current, tracked))
        assertNull(resolveTrackedReaderChapterIndex(current, tracked.copy(sectionId = "missing-event")))
    }

    @Test fun numericTrackedPositionNeedsFullOrderAndMustExistInLoadedPrefix() {
        val fullRefs = listOf("first", "second", "third").map { ref(it).copy(eventId = "event-$it") }
        val partial = bookDetail("edition", fullRefs.take(2).mapIndexed { index, reference ->
            chapter(reference.identifier, eventId = reference.eventId).copy(position = index)
        }, fullRefs)
        val sameEdition = summary("edition", fullRefs)

        assertNull(resolveTrackedReaderChapterIndex(partial, tracked(partial.summary.coordinate, 2, null, sameEdition)))
        assertNull(resolveTrackedReaderChapterIndex(partial, tracked(partial.summary.coordinate, 2, "event-third", sameEdition)))
        assertEquals(1, resolveTrackedReaderChapterIndex(partial, tracked(partial.summary.coordinate, 1, null, sameEdition)))
        val unknownOrder = partial.copy(summary = partial.summary.copy(sectionStreamKnown = false))
        assertNull(resolveTrackedReaderChapterIndex(unknownOrder, tracked(partial.summary.coordinate, 1, null, sameEdition)))
    }

    private fun chapter(name: String, available: Boolean = true, eventId: String? = null) = BookChapter(
        ChapterReference("30041:pubkey:$name", "pubkey", name, null, eventId),
        0, available, name, null, "text", null, null,
    )

    private fun ref(name: String) = ChapterReference("30041:pubkey:$name", "pubkey", name, null, null)

    private fun summary(id: String, refs: List<ChapterReference>) = BookSummary(
        id = id,
        coordinate = "30040:pubkey:book",
        pubkey = "pubkey",
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
        createdAt = 1,
        chapterCount = refs.size,
        chapterRefs = refs,
        sectionStreamKnown = true,
    )

    private fun bookDetail(id: String, chapters: List<BookChapter>, fullRefs: List<ChapterReference> = chapters.map { it.reference }) =
        BookDetail(
            summary = summary(id, fullRefs),
            chapters = chapters,
            availableChapterCount = chapters.count { it.available },
            missingChapterCount = chapters.count { !it.available },
            truncated = chapters.size < fullRefs.size,
        )

    private fun tracked(coordinate: String, position: Int, sectionId: String?, book: BookSummary?) = TrackedBook(
        bookCoordinate = coordinate,
        book = book,
        position = position,
        total = book?.chapterCount ?: 0,
        sectionId = sectionId,
        updatedAt = 1,
    )
}
