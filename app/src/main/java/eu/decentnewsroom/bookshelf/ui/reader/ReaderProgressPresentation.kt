package eu.decentnewsroom.bookshelf.ui.reader

import eu.decentnewsroom.bookshelf.data.reader.BookReadingPresentation
import eu.decentnewsroom.bookshelf.data.reader.BookReadingProgressSource
import eu.decentnewsroom.bookshelf.data.reader.ReadingProgress
import eu.decentnewsroom.bookshelf.data.reader.resolveBookReadingPresentation
import eu.decentnewsroom.bookshelf.data.reading.TrackedBook
import eu.decentnewsroom.bookshelf.domain.BookSummary

/** Keeps the reader's primary position tied to this device when synced progress is further ahead. */
internal fun readerPositionPresentation(
    progress: ReadingProgress,
    presentation: BookReadingPresentation,
    book: BookSummary,
): BookReadingPresentation = when (presentation.source) {
    BookReadingProgressSource.Local -> presentation
    BookReadingProgressSource.Tracked -> resolveBookReadingPresentation(
        progress = progress,
        tracked = null,
        finished = null,
        book = book,
    )
}

/** Describes synced furthest progress separately from the reader's current local position. */
internal fun readerFurthestTrackedLabel(
    progress: ReadingProgress,
    tracked: TrackedBook?,
    book: BookSummary,
): String? {
    if (tracked == null) return null

    val presentation = resolveBookReadingPresentation(
        progress = null,
        tracked = tracked,
        finished = null,
        book = book,
    )
    val position = presentation.sectionPosition
    val count = presentation.sectionCount
    if (position == null || count == null) return "Furthest tracked section: unavailable"
    if (position == progress.currentChapterIndex && count == progress.chapterCount) return null
    return "Furthest tracked section: ${position + 1} of $count"
}
