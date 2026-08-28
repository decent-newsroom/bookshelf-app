package eu.decentnewsroom.bookshelf.ui

import eu.decentnewsroom.bookshelf.data.reader.normalizedReaderChapterIndex
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
    fun chapterIndexesClampToFirstAndLastChapter() {
        assertEquals(0, normalizedReaderChapterIndex(-1, 4))
        assertEquals(0, normalizedReaderChapterIndex(0, 4))
        assertEquals(1, normalizedReaderChapterIndex(1, 4))
        assertEquals(3, normalizedReaderChapterIndex(99, 4))
        assertEquals(0, normalizedReaderChapterIndex(0, 0))
    }
}