package eu.decentnewsroom.bookshelf.data.reader

import eu.decentnewsroom.bookshelf.data.reading.FinishedBook
import eu.decentnewsroom.bookshelf.data.reading.TrackedBook
import eu.decentnewsroom.bookshelf.domain.BookSummary
import kotlin.math.roundToInt

/** Shared, source-independent reading status for reader controls and book thumbnails. */
enum class BookReadingProgressSource {
    Local,
    Tracked,
}

data class BookReadingPresentation(
    val hasStarted: Boolean,
    val fraction: Float?,
    val isComplete: Boolean,
    val isMarkedFinished: Boolean = false,
    val source: BookReadingProgressSource = BookReadingProgressSource.Local,
    val sectionPosition: Int? = null,
    val sectionCount: Int? = null,
) {
    val percentage: Int?
        get() = fraction?.let { value ->
            if (isComplete) 100 else value.coerceIn(0f, 0.99f).times(100f).roundToInt().coerceAtMost(99)
        }

    val progressLabel: String
        get() = when {
            isMarkedFinished -> "Marked as finished"
            isComplete -> "100% read"
            source == BookReadingProgressSource.Tracked && percentage != null && sectionPosition != null && sectionCount != null ->
                "Furthest section: ${sectionPosition + 1} of $sectionCount · About ${percentage}% by section position"
            source == BookReadingProgressSource.Tracked && hasStarted -> "Overall progress unavailable"
            percentage != null -> "About ${percentage}% by chapter position"
            hasStarted -> "Overall progress unavailable"
            else -> "Not started"
        }

    val accessibleLabel: String
        get() = when {
            isMarkedFinished -> "Marked as finished"
            isComplete -> "100% read; end of book reached"
            source == BookReadingProgressSource.Tracked && percentage != null ->
                "Approximately ${percentage}% by furthest tracked section"
            source == BookReadingProgressSource.Tracked && hasStarted -> "Reading started; overall progress unavailable"
            percentage != null -> "Approximately ${percentage}% by chapter position"
            hasStarted -> "Reading started; overall progress unavailable"
            else -> ""
        }
}

/**
 * Resolves device-local chapter progress together with the active guest/account reading state.
 * Nostr timestamps are seconds; local reading-cycle timestamps are milliseconds.
 */
fun resolveBookReadingPresentation(
    progress: ReadingProgress?,
    tracked: TrackedBook?,
    finished: FinishedBook?,
    book: BookSummary? = null,
): BookReadingPresentation {
    val legacyLocalStart = progress != null &&
        (progress.currentChapterIndex > 0 || progress.chapterScrollOffsetPx > 0)
    val localStarted = progress?.hasStarted == true || legacyLocalStart
    val finishedAtSeconds = finished?.finishedAt ?: 0L
    val explicitLocalReread = progress?.readingCycleStartedAtMillis?.let {
        isLocalTimeAfterNostrSeconds(it, finishedAtSeconds)
    } == true
    val postFinishReadingActivity = progress?.lastReadingActivityMillis?.let {
        isLocalTimeAfterNostrSeconds(it, finishedAtSeconds)
    } == true
    val currentFingerprint = book?.let(::readingPublicationFingerprint)
    val fingerprintMatches = book == null || progress?.publicationFingerprint == currentFingerprint
    val validLocalEndpoint = progress?.reachedEnd == true && progress.completeContent &&
        progress.contentFingerprint != null && progress.fullChapterCount > 0 && fingerprintMatches &&
        (book == null || isSummaryCompleteOrder(book))
    val activeRead = tracked != null || explicitLocalReread || (postFinishReadingActivity && !validLocalEndpoint)

    if (finished != null && !activeRead) {
        return BookReadingPresentation(hasStarted = true, fraction = 1f, isComplete = true, isMarkedFinished = true)
    }

    if (localStarted) {
        if (validLocalEndpoint) {
            return BookReadingPresentation(hasStarted = true, fraction = 1f, isComplete = true)
        }

        val denominator = when {
            book != null && isSummaryCompleteOrder(book) -> book.chapterCount
            book == null && progress.fullChapterCount > 0 -> progress.fullChapterCount
            else -> 0
        }
        val localIndex = if (book != null && fingerprintMatches) {
            progress.currentChapterIndex
        } else if (book != null) {
            progress.chapterCoordinate?.let { coordinate ->
                book.chapterRefs.indexOfFirst { it.coordinate == coordinate }.takeIf { it >= 0 }
            } ?: progress.currentChapterIndex
        } else {
            progress.currentChapterIndex
        }
        val detailCompatibleForFraction = progress.contentFingerprint == null || progress.completeContent
        val coordinateResolves = book == null || progress.chapterCoordinate == null ||
            book.chapterRefs.any { it.coordinate == progress.chapterCoordinate }
        val localFraction = if (denominator > 0 && detailCompatibleForFraction && coordinateResolves) {
            localIndex.coerceIn(0, denominator - 1).toFloat() / denominator.toFloat()
        } else null
        return BookReadingPresentation(hasStarted = true, fraction = localFraction, isComplete = false)
    }

    if (tracked != null) {
        return trackedPresentation(tracked, book)
    }

    return BookReadingPresentation(hasStarted = localStarted, fraction = null, isComplete = false)
}

internal fun readingPublicationFingerprint(summary: BookSummary): String {
    val identity = buildString {
        append(summary.id).append('\u0000')
        summary.chapterRefs.forEach { ref ->
            append(ref.coordinate).append('\u0000').append(ref.eventId.orEmpty()).append('\u0001')
        }
    }
    return java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

private fun isSummaryCompleteOrder(summary: BookSummary): Boolean =
    summary.sectionStreamKnown && summary.chapterCount > 0 && summary.chapterRefs.size == summary.chapterCount

private fun trackedPresentation(tracked: TrackedBook, book: BookSummary?): BookReadingPresentation {
    val knownOrder = if (book != null) isSummaryCompleteOrder(book) else tracked.total > 0
    val total = if (book != null) book.chapterCount else tracked.total
    val editionChanged = book != null && tracked.book != null && tracked.book.id != book.id
    val resolvedPosition = when {
        book == null -> tracked.position
        !knownOrder -> null
        editionChanged -> tracked.sectionId?.let { sectionId ->
            book.chapterRefs.indexOfFirst { it.eventId == sectionId }.takeIf { it >= 0 }
        }
        else -> tracked.position
    }
    val fraction = if (knownOrder && total > 0 && resolvedPosition != null) {
        resolvedPosition.coerceIn(0, total - 1).toFloat() / total.toFloat()
    } else null
    val normalizedPosition = if (knownOrder && total > 0 && resolvedPosition != null) {
        resolvedPosition.coerceIn(0, total - 1)
    } else null
    return BookReadingPresentation(
        hasStarted = true,
        fraction = fraction,
        isComplete = false,
        source = BookReadingProgressSource.Tracked,
        sectionPosition = normalizedPosition,
        sectionCount = total.takeIf { knownOrder && it > 0 },
    )
}

private fun isLocalTimeAfterNostrSeconds(localMillis: Long, remoteSeconds: Long): Boolean =
    localMillis > 0 && remoteSeconds >= 0 && localMillis / 1_000L > remoteSeconds
