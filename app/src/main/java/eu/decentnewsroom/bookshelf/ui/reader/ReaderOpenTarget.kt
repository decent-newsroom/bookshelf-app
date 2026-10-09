package eu.decentnewsroom.bookshelf.ui.reader

import eu.decentnewsroom.bookshelf.domain.BookChapter
import eu.decentnewsroom.bookshelf.data.reader.ReadingProgress
import eu.decentnewsroom.bookshelf.data.reading.TrackedBook
import eu.decentnewsroom.bookshelf.domain.BookDetail

/** Transient navigation intent; never stored with saved books or reading history. */
data class ReaderOpenTarget(val chapterCoordinate: String)

/** Resolve after loading/rendering, since chapter order and availability can change. */
internal fun ReaderOpenTarget.resolveChapterIndex(chapters: List<BookChapter>): Int? =
    chapters.indexOfFirst { it.reference.coordinate == chapterCoordinate && it.available }
        .takeIf { it >= 0 }

/** Opened-only zero-position records remain ambiguous and may use tracked progress as fallback. */
internal fun readerNeedsTrackedFallback(
    resolvedTargetIndex: Int?,
    progress: ReadingProgress?,
): Boolean = resolvedTargetIndex == null && progress?.let {
    it.hasStarted || it.currentChapterIndex > 0 || it.chapterScrollOffsetPx > 0
} != true

/** Resolve a tracked bookmark against the loaded edition without clamping into another section. */
internal fun resolveTrackedReaderChapterIndex(book: BookDetail, tracked: TrackedBook?): Int? {
    if (tracked == null || tracked.bookCoordinate != book.summary.coordinate) return null

    val sectionId = tracked.sectionId
    if (sectionId != null) {
        val byIdentity = book.chapters.indexOfFirst {
            it.id == sectionId || it.reference.eventId == sectionId
        }
        if (byIdentity >= 0) return byIdentity
    }

    val editionChanged = tracked.book?.id != null && tracked.book.id != book.summary.id
    if (editionChanged) return null

    val summary = book.summary
    val hasFullKnownOrder = summary.sectionStreamKnown && summary.chapterCount > 0 &&
        summary.chapterRefs.size == summary.chapterCount
    if (!hasFullKnownOrder || tracked.position !in book.chapters.indices) return null
    return tracked.position
}

internal data class ReaderStartPosition(val chapterIndex: Int, val scrollOffsetPx: Int)

internal fun readerStartPosition(progress: ReadingProgress, resolvedChapterIndex: Int?): ReaderStartPosition =
    if (resolvedChapterIndex != null) ReaderStartPosition(resolvedChapterIndex, 0)
    else ReaderStartPosition(progress.currentChapterIndex, progress.chapterScrollOffsetPx.coerceAtLeast(0))
