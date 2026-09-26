package eu.decentnewsroom.bookshelf.ui.reader

import eu.decentnewsroom.bookshelf.domain.BookChapter
import eu.decentnewsroom.bookshelf.data.reader.ReadingProgress

/** Transient navigation intent; never stored with saved books or reading history. */
data class ReaderOpenTarget(val chapterCoordinate: String)

/** Resolve after loading/rendering, since chapter order and availability can change. */
internal fun ReaderOpenTarget.resolveChapterIndex(chapters: List<BookChapter>): Int? =
    chapters.indexOfFirst { it.reference.coordinate == chapterCoordinate && it.available }
        .takeIf { it >= 0 }

internal data class ReaderStartPosition(val chapterIndex: Int, val scrollOffsetPx: Int)

internal fun readerStartPosition(progress: ReadingProgress, resolvedChapterIndex: Int?): ReaderStartPosition =
    if (resolvedChapterIndex != null) ReaderStartPosition(resolvedChapterIndex, 0)
    else ReaderStartPosition(progress.currentChapterIndex, progress.chapterScrollOffsetPx.coerceAtLeast(0))
