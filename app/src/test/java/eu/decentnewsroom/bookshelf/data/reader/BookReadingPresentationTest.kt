package eu.decentnewsroom.bookshelf.data.reader

import eu.decentnewsroom.bookshelf.data.reading.FinishedBook
import eu.decentnewsroom.bookshelf.data.reading.TrackedBook
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.domain.BookChapter
import eu.decentnewsroom.bookshelf.domain.BookDetail
import eu.decentnewsroom.bookshelf.domain.ChapterReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookReadingPresentationTest {
    @Test
    fun openedOnlyRecordDoesNotShowStartedBadge() {
        val result = resolveBookReadingPresentation(progress(), tracked = null, finished = null, book = summary())
        assertFalse(result.hasStarted)
        assertNull(result.fraction)
        assertEquals("", result.accessibleLabel)
    }

    @Test
    fun positiveLegacyLocationStartsAtApproximatePosition() {
        val result = resolveBookReadingPresentation(
            progress(index = 1, offset = 0, hasStarted = false), null, null, summary(),
        )
        assertTrue(result.hasStarted)
        assertEquals(0.25f, result.fraction!!, 0.0001f)
        assertEquals(25, result.percentage)
    }

    @Test
    fun positiveOffsetWithinFirstChapterIsStartedButHasNoInventedPercentage() {
        val result = resolveBookReadingPresentation(progress(index = 0, offset = 36, hasStarted = true), null, null, summary())
        assertTrue(result.hasStarted)
        assertEquals(0f, result.fraction!!, 0.0001f)
    }

    @Test
    fun endpointNeedsCompleteMatchingPublication() {
        val valid = progress(
            index = 3,
            started = true,
            end = true,
            complete = true,
            fingerprint = readingPublicationFingerprint(summary()),
            contentFingerprint = "verified-content",
        )
        val result = resolveBookReadingPresentation(valid, null, null, summary())
        assertTrue(result.isComplete)
        assertEquals(100, result.percentage)
        assertEquals("100% read; end of book reached", result.accessibleLabel)

        val stale = resolveBookReadingPresentation(valid.copy(publicationFingerprint = "stale"), null, null, summary())
        assertFalse(stale.isComplete)
        assertEquals(75, stale.percentage)
    }

    @Test
    fun truncatedOrUnknownOrderCanShowStartedWithoutWholeBookFraction() {
        val unknown = summary().copy(sectionStreamKnown = false)
        val result = resolveBookReadingPresentation(progress(index = 2, started = true), null, null, unknown)
        assertTrue(result.hasStarted)
        assertNull(result.fraction)
        assertEquals("Reading started; overall progress unavailable", result.accessibleLabel)
    }

    @Test
    fun trackedProgressIsApproximateAndNeverCompletesByRounding() {
        val result = resolveBookReadingPresentation(null, tracked(position = 3, total = 4), null, summary())
        assertEquals(0.75f, result.fraction!!, 0.0001f)
        assertEquals(75, result.percentage)
        assertEquals(BookReadingProgressSource.Tracked, result.source)
        assertEquals(3, result.sectionPosition)
        assertEquals(4, result.sectionCount)
        assertEquals("Furthest section: 4 of 4 · About 75% by section position", result.progressLabel)
        assertEquals("Approximately 75% by furthest tracked section", result.accessibleLabel)

        val nearEnd = resolveBookReadingPresentation(null, tracked(position = 999, total = 1000), null)
        assertEquals(99, nearEnd.percentage)
        assertFalse(nearEnd.isComplete)
    }

    @Test
    fun finishedWinsUnlessAnActiveTrackingCycleOrExplicitRereadExists() {
        val history = finished(finishedAtSeconds = 100)
        val complete = resolveBookReadingPresentation(progress(index = 1, started = true), null, history, summary())
        assertTrue(complete.isMarkedFinished)
        assertEquals(100, complete.percentage)

        val trackedRead = resolveBookReadingPresentation(progress(index = 1, started = true), tracked(), history, summary())
        assertFalse(trackedRead.isMarkedFinished)
        assertEquals(25, trackedRead.percentage)

        val reread = resolveBookReadingPresentation(
            progress(index = 1, started = true, cycle = 101_000), null, history, summary(),
        )
        assertFalse(reread.isMarkedFinished)
        assertEquals(25, reread.percentage)
    }

    @Test
    fun lastOpenedTimestampCannotEstablishRereading() {
        val record = progress(index = 1, started = true).copy(updatedAtMillis = 999_000)
        val result = resolveBookReadingPresentation(record, null, finished(finishedAtSeconds = 100), summary())
        assertTrue(result.isMarkedFinished)
    }

    @Test
    fun genuineReadingActivityAfterFinishCanEstablishRereading() {
        val result = resolveBookReadingPresentation(
            progress(index = 1, started = true).copy(lastReadingActivityMillis = 101_000),
            null,
            finished(finishedAtSeconds = 100),
            summary(),
        )
        assertFalse(result.isMarkedFinished)
        assertEquals(25, result.percentage)
    }

    @Test
    fun millisecondActivityWithinFinishedTimestampSecondDoesNotCountAsLater() {
        val result = resolveBookReadingPresentation(
            progress(index = 1, started = true).copy(lastReadingActivityMillis = 100_999),
            null,
            finished(finishedAtSeconds = 100),
            summary(),
        )
        assertTrue(result.isMarkedFinished)
    }

    @Test
    fun unchangedCompletedEndpointDoesNotBecomeRereadingFromDuplicateActivityTime() {
        val completed = progress(
            index = 3,
            started = true,
            end = true,
            complete = true,
            fingerprint = readingPublicationFingerprint(summary()),
            contentFingerprint = "verified-content",
        ).copy(lastReadingActivityMillis = 101_000)
        val result = resolveBookReadingPresentation(completed, null, finished(finishedAtSeconds = 100), summary())
        assertTrue(result.isMarkedFinished)
    }

    @Test
    fun incompleteLocalContentKeepsUnknownLocalPositionDespiteSyncedProgress() {
        val incomplete = progress(index = 1, started = true, complete = false).copy(contentFingerprint = "partial")
        val result = resolveBookReadingPresentation(incomplete, tracked(position = 2, total = 4), null, summary())
        assertNull(result.fraction)

        val localOnly = resolveBookReadingPresentation(incomplete, null, null, summary())
        assertNull(localOnly.fraction)
    }

    @Test
    fun trackedPositionRequiresKnownOrderWhenSummaryIsProvided() {
        val unknown = summary().copy(sectionStreamKnown = false)
        val result = resolveBookReadingPresentation(null, tracked(position = 2, total = 4), null, unknown)
        assertNull(result.fraction)
        assertTrue(result.hasStarted)
        assertEquals("Overall progress unavailable", result.progressLabel)
        assertEquals("Reading started; overall progress unavailable", result.accessibleLabel)
    }

    @Test
    fun trackedPositionFromAnotherEditionMustResolveItsEventId() {
        val oldEdition = summary().copy(id = "old-edition")
        val currentEdition = summary().copy(id = "current-edition")
        val resolved = resolveBookReadingPresentation(
            null,
            tracked(position = 0, total = 4, sectionId = "event-2", book = oldEdition),
            null,
            currentEdition,
        )
        assertEquals(0.5f, resolved.fraction!!, 0.0001f)
        assertEquals(2, resolved.sectionPosition)
        assertEquals("Furthest section: 3 of 4 · About 50% by section position", resolved.progressLabel)

        val unresolved = resolveBookReadingPresentation(
            null,
            tracked(position = 3, total = 4, sectionId = "missing", book = oldEdition),
            null,
            currentEdition,
        )
        assertNull(unresolved.fraction)
    }

    @Test
    fun legacyUnknownDenominatorRemainsStartedWithUnknownFraction() {
        val result = resolveBookReadingPresentation(
            progress(index = 1, started = false, fullCount = 0), null, null,
        )
        assertTrue(result.hasStarted)
        assertNull(result.percentage)
    }

    private fun progress(
        index: Int = 0,
        offset: Int = 0,
        started: Boolean = false,
        hasStarted: Boolean = started,
        end: Boolean = false,
        complete: Boolean = true,
        fullCount: Int = 4,
        fingerprint: String? = null,
        contentFingerprint: String? = null,
        cycle: Long = 0,
    ) = ReadingProgress(
        bookCoordinate = "book",
        currentChapterIndex = index,
        chapterCount = 4,
        updatedAtMillis = 500_000,
        chapterScrollOffsetPx = offset,
        hasStarted = hasStarted,
        reachedEnd = end,
        fullChapterCount = fullCount,
        completeContent = complete,
        chapterCoordinate = "chapter-$index",
        contentFingerprint = contentFingerprint,
        publicationFingerprint = fingerprint,
        readingCycleStartedAtMillis = cycle,
    )

    private fun tracked(
        position: Int = 0,
        total: Int = 4,
        sectionId: String? = null,
        book: BookSummary? = null,
    ) = TrackedBook(
        bookCoordinate = "book", book = book, position = position, total = total,
        sectionId = sectionId, updatedAt = 1,
    )

    private fun finished(finishedAtSeconds: Long) = FinishedBook(
        bookCoordinate = "book", finishedAt = finishedAtSeconds,
    )

    private fun summary(): BookSummary {
        val refs = (0 until 4).map { index ->
            ChapterReference("chapter-$index", "author", "chapter-$index", null, "event-$index")
        }
        return BookSummary(
            id = "publication", coordinate = "book", pubkey = "author", identifier = "id",
            title = "Book", summary = null, authors = emptyList(), coverImageUrl = null,
            sourceUrl = null, language = null, releaseDate = null, version = null, type = "book",
            topics = emptyList(), relay = null, createdAt = 0, chapterCount = 4, chapterRefs = refs,
        )
    }

    @Test
    fun repeatedActivityObservationDoesNotChangeTimestampsOrCycle() {
        val previous = progress(index = 1, started = true, cycle = 10_000).copy(
            publicationFingerprint = "edition",
            contentFingerprint = "content",
            lastReadingActivityMillis = 10_000,
        )
        val result = nextObservedReadingProgress(
            existing = previous,
            bookCoordinate = "book",
            chapterIndex = 1,
            chapterCount = 4,
            chapterScrollOffsetPx = 0,
            fullChapterCount = 4,
            completeContent = true,
            chapterCoordinate = "chapter-1",
            publicationFingerprint = "edition",
            contentFingerprint = "content",
            reachedEnd = false,
            readingActivity = true,
            nowMillis = 99_000,
        )
        assertEquals(previous, result)
    }

    @Test
    fun incomingResolvedIndexWinsWhenEditionFingerprintChanges() {
        val previous = progress(index = 1, started = true).copy(
            publicationFingerprint = "old-edition",
            contentFingerprint = "old-content",
        )
        val result = nextObservedReadingProgress(
            existing = previous,
            bookCoordinate = "book",
            chapterIndex = 2,
            chapterCount = 4,
            chapterScrollOffsetPx = 8,
            fullChapterCount = 4,
            completeContent = true,
            chapterCoordinate = "chapter-2",
            publicationFingerprint = "new-edition",
            contentFingerprint = "new-content",
            reachedEnd = false,
            readingActivity = true,
            nowMillis = 20_000,
        )
        assertEquals(2, result.currentChapterIndex)
        assertEquals("chapter-2", result.chapterCoordinate)
    }

    @Test
    fun normalizationUsesCoordinatesAndAvoidsStaleIndexOnReorderedEdition() {
        val current = detail(summary(), idPrefix = "current")
        val resolved = resolveReaderChapterIndex(
            progress(index = 1, started = true).copy(chapterCoordinate = "chapter-2", publicationFingerprint = "old"),
            current,
            4,
            readingPublicationFingerprint(current.summary),
        )
        assertEquals(2, resolved)

        val missingCoordinate = resolveReaderChapterIndex(
            progress(index = 3, started = true).copy(chapterCoordinate = "removed", publicationFingerprint = "old"),
            current,
            4,
            readingPublicationFingerprint(current.summary),
        )
        assertEquals(0, missingCoordinate)
    }

    @Test
    fun normalizationPreservesPixelsOnlyForTheSameAvailableChapter() {
        val reordered = detail(summary(), idPrefix = "new-order")
        val saved = progress(index = 1, offset = 47, started = true)
            .copy(chapterCoordinate = "chapter-2")
        val index = resolveReaderChapterIndex(
            saved,
            reordered,
            4,
            readingPublicationFingerprint(reordered.summary),
        )
        assertEquals(47, normalizedReaderScrollOffsetForBook(saved, reordered, index))

        val removed = saved.copy(chapterCoordinate = "removed")
        assertEquals(0, normalizedReaderScrollOffsetForBook(removed, reordered, 0))

        val unavailable = reordered.copy(
            chapters = reordered.chapters.map { chapter ->
                if (chapter.reference.coordinate == "chapter-2") chapter.copy(available = false) else chapter
            },
        )
        assertEquals(0, normalizedReaderScrollOffsetForBook(saved, unavailable, index))
    }

    @Test
    fun explicitCyclePreservesVerifiedCurrentEndpoint() {
        val completed = progress(index = 3, started = true, end = true, complete = true)
        val next = readingCycleProgress(completed, 12_000, verifiedEndpoint = true)
        assertTrue(next.reachedEnd)
        assertEquals(completed.currentChapterIndex, next.currentChapterIndex)
        assertEquals(completed.chapterScrollOffsetPx, next.chapterScrollOffsetPx)
        assertEquals(12_000L, next.readingCycleStartedAtMillis)
    }

    @Test
    fun successfulFinishClearsOldRereadEvidenceAndRestoresFinishedPrecedence() {
        val completedCycle = progress(
            index = 3,
            started = true,
            end = true,
            complete = true,
            fingerprint = readingPublicationFingerprint(summary()),
            contentFingerprint = "verified-content",
            cycle = 101_000,
        ).copy(lastReadingActivityMillis = 101_000)
        val finishHistory = finished(finishedAtSeconds = 100)
        assertFalse(resolveBookReadingPresentation(completedCycle, null, finishHistory, summary()).isMarkedFinished)

        val afterFinish = readingCycleCompletedProgress(completedCycle)
        assertEquals(0L, afterFinish.readingCycleStartedAtMillis)
        assertEquals(0L, afterFinish.lastReadingActivityMillis)
        assertEquals(completedCycle.currentChapterIndex, afterFinish.currentChapterIndex)
        assertEquals(completedCycle.chapterScrollOffsetPx, afterFinish.chapterScrollOffsetPx)
        assertEquals(completedCycle.reachedEnd, afterFinish.reachedEnd)
        assertEquals(completedCycle.updatedAtMillis, afterFinish.updatedAtMillis)
        assertTrue(resolveBookReadingPresentation(afterFinish, null, finishHistory, summary()).isMarkedFinished)
    }

    @Test
    fun loadedChapterIdsFingerprintContentWhenSummaryRefsOmitEventIds() {
        val noEventIds = summary().copy(chapterRefs = summary().chapterRefs.map { it.copy(eventId = null) })
        val first = detail(noEventIds, idPrefix = "first")
        val revised = detail(noEventIds, idPrefix = "revised")
        assertTrue(readingPublicationFingerprint(first.summary) == readingPublicationFingerprint(revised.summary))
        assertFalse(readingContentFingerprint(first) == readingContentFingerprint(revised))
    }

    private fun detail(summary: BookSummary, idPrefix: String): BookDetail {
        val chapters = summary.chapterRefs.mapIndexed { index, reference ->
            BookChapter(
                reference = reference,
                position = index,
                available = true,
                title = "Chapter $index",
                summary = null,
                content = "Content $index",
                id = "$idPrefix-$index",
                createdAt = 1,
            )
        }
        return BookDetail(summary, chapters, chapters.size, missingChapterCount = 0, truncated = false)
    }
}
