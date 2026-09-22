package eu.decentnewsroom.bookshelf.ui

import eu.decentnewsroom.bookshelf.data.reader.normalizedReaderChapterIndex
import eu.decentnewsroom.bookshelf.ui.reader.chapterIndexForReaderListItem
import eu.decentnewsroom.bookshelf.ui.reader.readerScrollOffsetForListItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderProgressIndexTest {
    @Test
    fun listItemsMapToChapterIndexesWithoutHeaderDoubleSubtraction() {
        assertEquals(0, chapterIndexForReaderListItem(0, 4))
        assertEquals(0, chapterIndexForReaderListItem(1, 4))
        assertEquals(1, chapterIndexForReaderListItem(2, 4))
        assertEquals(3, chapterIndexForReaderListItem(4, 4))
    }

    @Test
    fun headerOffsetIsNotStoredAsChapterOffset() {
        assertEquals(0, readerScrollOffsetForListItem(0, 240))
        assertEquals(0, readerScrollOffsetForListItem(1, -2))
        assertEquals(18, readerScrollOffsetForListItem(2, 18))
    }

    @Test
    fun chapterIndexesClampToFirstAndLastChapter() {
        assertEquals(0, normalizedReaderChapterIndex(-1, 4))
        assertEquals(0, normalizedReaderChapterIndex(0, 4))
        assertEquals(1, normalizedReaderChapterIndex(1, 4))
        assertEquals(3, normalizedReaderChapterIndex(99, 4))
        assertEquals(0, normalizedReaderChapterIndex(0, 0))
    }
}
