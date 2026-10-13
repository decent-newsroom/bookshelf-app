package eu.decentnewsroom.bookshelf.ui.reader

import eu.decentnewsroom.bookshelf.domain.*
import org.junit.Assert.*
import org.junit.Test

class ReaderNavigationTest {
    @Test fun firstOpenShowsLeadingDivisionWhileExplicitTargetSkipsToChapter() {
        val navigation = ReaderNavigation(detail())
        val initial = eu.decentnewsroom.bookshelf.data.reader.ReadingProgress.initial("book", 2)
        assertEquals(1, navigation.initialListItem(initial, null))
        assertEquals(2, navigation.initialListItem(initial, 0))
        assertEquals(3, navigation.initialListItem(initial.copy(readerEntryKey = "act"), null))
        assertEquals(4, navigation.initialListItem(initial.copy(hasStarted = true, currentChapterIndex = 1), null))
    }
    @Test fun mixedEntriesHaveExplicitLeafTargetsAndAncestorSelection() {
        val navigation = ReaderNavigation(detail())
        assertEquals(2, navigation.listItemForChapter(0))
        assertEquals(4, navigation.listItemForChapter(1))
        assertEquals(3, navigation.listItemForEntry("act"))
        assertNull(navigation.chapterAt(1))
        assertNull(navigation.trackingChapterAt(3, false))
        assertEquals(1, navigation.trackingChapterAt(5, true))
        assertEquals(setOf("second", "act", "part"), navigation.activeKeys("second"))
    }

    @Test fun repeatedCoordinatesKeepDistinctOccurrenceTargetsAndOffsets() {
        val navigation = ReaderNavigation(detail())
        assertNotEquals(navigation.listItemForEntry("first"), navigation.listItemForEntry("second"))
        val tracker = ReaderResumePositionTracker(2, 0 to 0, navigation)
        assertEquals(1 to 73, tracker.observe(4, 73))
        assertNull(tracker.observe(3, 600))
        assertEquals(1 to 73, tracker.positionOnExit(5, 100))
    }

    @Test fun divisionOnlyOutlineNeverInventsAChapterOrEndpoint() {
        val divisions = detail().copy(chapters = emptyList(), outline = detail().outline.filter { it.isDivision })
        val navigation = ReaderNavigation(divisions)
        assertEquals(2, navigation.entries.size)
        assertEquals(2, navigation.listItemForEntry("act"))
        assertNull(navigation.trackingChapterAt(2, true))
        assertFalse(readerHasTerminalActions(true, 0, 0, false))
    }

    @Test fun legacyFlatDetailsKeepTheirChapterTargets() {
        val navigation = ReaderNavigation(detail().copy(outline = emptyList()))
        assertEquals(1, navigation.listItemForChapter(0))
        assertEquals(2, navigation.listItemForChapter(1))
        assertNull(navigation.entryAt(0))
        assertNull(navigation.chapterAt(3))
    }

    @Test fun headingChangesPublishEvenWhenRetainedChapterPositionIsUnchanged() {
        val previous = ReaderObservation(0, 42, false, false, readerEntryKey = "first")
        assertTrue(readerShouldPublishPosition(previous, previous.copy(readerEntryKey = "act"), false))
    }

    private fun detail(): BookDetail {
        val reference = ChapterReference("30041:author:shared", "author", "shared", null, null)
        val chapters = listOf("first", "second").mapIndexed { i, key ->
            BookChapter(reference, i, true, "Chapter", null, "body", null, null, occurrenceKey = key)
        }
        val summary = BookSummary("id", "30040:author:root", "author", "root", "Root", null,
            emptyList(), null, null, null, null, null, "book", emptyList(), null, 1, 2, chapters.map { it.reference })
        fun division(key: String, parent: String? = null, depth: Int = 0) = PublicationOutlineNode(
            key, parent, PublicationChildReference(30040, "30040:author:$key", "author", key), depth, key,
        )
        return BookDetail(summary, chapters, 2, 0, false, listOf(
            division("part"),
            PublicationOutlineNode("first", "part", PublicationChildReference.fromChapter(reference), 1, "Chapter", chapterIndex = 0),
            division("act", "part", 1),
            PublicationOutlineNode("second", "act", PublicationChildReference.fromChapter(reference), 2, "Chapter", chapterIndex = 1),
        ))
    }
}
