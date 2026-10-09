package eu.decentnewsroom.bookshelf.ui.reader

private const val ReaderHeaderItemCount = 1

internal fun readerListItemIndexForChapter(chapterIndex: Int, chapterCount: Int): Int =
    if (chapterCount <= 0) {
        0
    } else {
        coerceReaderChapterIndex(chapterIndex, chapterCount) + ReaderHeaderItemCount
    }

internal fun chapterIndexForReaderListItem(listItemIndex: Int, chapterCount: Int): Int =
    if (chapterCount <= 0) {
        0
    } else {
        (listItemIndex - ReaderHeaderItemCount).coerceIn(0, chapterCount - 1)
    }

internal fun coerceReaderChapterIndex(chapterIndex: Int, chapterCount: Int): Int =
    if (chapterCount <= 0) {
        0
    } else {
        chapterIndex.coerceIn(0, chapterCount - 1)
    }

internal fun readerScrollOffsetForListItem(listItemIndex: Int, scrollOffsetPx: Int): Int =
    if (listItemIndex < ReaderHeaderItemCount) 0 else scrollOffsetPx.coerceAtLeast(0)

/** Header has no chapter offset; terminal cards must never overwrite the last chapter's offset. */
internal fun readerResumePositionForListItem(listItemIndex: Int, scrollOffsetPx: Int, chapterCount: Int): Pair<Int, Int>? =
    if (chapterCount <= 0 || listItemIndex > chapterCount || listItemIndex < 0) null
    else chapterIndexForReaderListItem(listItemIndex, chapterCount) to readerScrollOffsetForListItem(listItemIndex, scrollOffsetPx)

internal fun readerHasTerminalActions(sectionStreamKnown: Boolean, total: Int, loadedSectionCount: Int, truncated: Boolean): Boolean =
    sectionStreamKnown && total > 0 && loadedSectionCount == total && !truncated

/** Retains the most recent chapter position while end cards are in view. */
internal class ReaderResumePositionTracker(private val chapterCount: Int) {
    private var lastChapterPosition: Pair<Int, Int>? = null

    fun observe(listItemIndex: Int, scrollOffsetPx: Int): Pair<Int, Int>? =
        readerResumePositionForListItem(listItemIndex, scrollOffsetPx, chapterCount)
            ?.also { lastChapterPosition = it }

    fun positionOnExit(listItemIndex: Int, scrollOffsetPx: Int): Pair<Int, Int>? =
        observe(listItemIndex, scrollOffsetPx) ?: lastChapterPosition
}
