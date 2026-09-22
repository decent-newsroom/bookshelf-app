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
