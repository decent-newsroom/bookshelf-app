package eu.decentnewsroom.bookshelf.ui

import eu.decentnewsroom.bookshelf.data.reader.ReadingProgress
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.BookSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueReadingBookTest {
    @Test
    fun selectsTheMostRecentlyOpenedBookThatIsStillSaved() {
        val older = bookSummary("older", "1")
        val newer = bookSummary("newer", "2")
        val removed = bookSummary("removed", "3")

        val result = mostRecentlyOpenedSavedBook(
            savedBooks = listOf(older, newer),
            readingProgress = mapOf(
                older.coordinate to ReadingProgress(older.coordinate, 1, 4, 20),
                newer.coordinate to ReadingProgress(newer.coordinate, 2, 5, 30),
                removed.coordinate to ReadingProgress(removed.coordinate, 3, 4, 40),
            ),
        )

        assertEquals(newer, result?.book)
        assertEquals(2, result?.progress?.currentChapterIndex)
    }

    @Test
    fun doesNotShowAContinueReadingEntryWithoutSavedProgress() {
        assertNull(mostRecentlyOpenedSavedBook(listOf(bookSummary("saved", "1")), emptyMap()))
    }

    private fun bookSummary(identifier: String, keyDigit: String): BookSummary {
        val pubkey = keyDigit.repeat(64)
        return BookSummary(
            id = keyDigit.repeat(64),
            coordinate = "${BookKinds.PUBLICATION_INDEX}:$pubkey:$identifier",
            pubkey = pubkey,
            identifier = identifier,
            title = identifier,
            summary = null,
            authors = emptyList(),
            coverImageUrl = null,
            sourceUrl = null,
            language = null,
            releaseDate = null,
            version = null,
            type = "book",
            topics = emptyList(),
            relay = null,
            createdAt = 0,
            chapterCount = 5,
            chapterRefs = emptyList(),
        )
    }
}
