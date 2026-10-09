package eu.decentnewsroom.bookshelf.ui

import eu.decentnewsroom.bookshelf.data.reader.BookReadingPresentation
import eu.decentnewsroom.bookshelf.data.reader.BookReadingProgressSource
import eu.decentnewsroom.bookshelf.data.reader.ReadingProgress
import eu.decentnewsroom.bookshelf.ui.books.chapterReadingProgressLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class BookProgressLabelTest {
    private val progress = ReadingProgress("book", 1, 4, 1_000)

    @Test
    fun trackedLabelIsPreservedWithoutLocalChapterPosition() {
        val presentation = BookReadingPresentation(
            hasStarted = true,
            fraction = 0.75f,
            isComplete = false,
            source = BookReadingProgressSource.Tracked,
            sectionPosition = 3,
            sectionCount = 4,
        )

        assertEquals(presentation.progressLabel, chapterReadingProgressLabel(progress, presentation))
    }

    @Test
    fun localLabelIncludesChapterPositionAndExplicitCompletionPercentage() {
        val inProgress = BookReadingPresentation(hasStarted = true, fraction = 0.25f, isComplete = false)
        val complete = BookReadingPresentation(hasStarted = true, fraction = 1f, isComplete = true)

        assertEquals("Chapter 2 of 4 | About 25% by chapter position", chapterReadingProgressLabel(progress, inProgress))
        assertEquals("Chapter 2 of 4 | 100% read", chapterReadingProgressLabel(progress, complete))
    }
}
