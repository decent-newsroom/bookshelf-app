package eu.decentnewsroom.bookshelf.ui

import eu.decentnewsroom.bookshelf.data.reader.ReadingProgress
import eu.decentnewsroom.bookshelf.domain.BookChapter
import eu.decentnewsroom.bookshelf.domain.ChapterReference
import eu.decentnewsroom.bookshelf.ui.reader.ReaderOpenTarget
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

    private fun chapter(name: String, available: Boolean = true) = BookChapter(
        ChapterReference("30041:pubkey:$name", "pubkey", name, null, null),
        0, available, name, null, "text", null, null,
    )
}
