package eu.decentnewsroom.bookshelf.data.bookshelf

import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.BookSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookshelfDirectoryRulesTest {
    @Test
    fun toggleBookAddsAndRemovesPublicationReference() {
        val book = bookSummary()

        val added = BookshelfDirectoryRules.toggleBook(BookshelfDirectoryRules.emptyTags(), book)

        assertEquals(2, added.size)
        assertEquals("a", added[1][0])
        assertEquals(book.coordinate, added[1][1])
        assertTrue(BookshelfDirectoryRules.extractBookReferences(added).any { it.coordinate == book.coordinate })

        val removed = BookshelfDirectoryRules.toggleBook(added, book)

        assertEquals(BookshelfDirectoryRules.emptyTags(), removed)
        assertFalse(BookshelfDirectoryRules.extractBookReferences(removed).any { it.coordinate == book.coordinate })
    }

    @Test
    fun validDirectoryRequiresStableIdentifierAndEmptyContent() {
        val book = bookSummary()
        val tags = BookshelfDirectoryRules.toggleBook(BookshelfDirectoryRules.emptyTags(), book)

        BookshelfDirectoryRules.assertValidDirectory(tags, "")
    }

    private fun bookSummary(): BookSummary {
        val pubkey = "1".repeat(64)
        val eventId = "a".repeat(64)

        return BookSummary(
            id = eventId,
            coordinate = "${BookKinds.PUBLICATION_INDEX}:$pubkey:a-book",
            pubkey = pubkey,
            identifier = "a-book",
            title = "A Book",
            summary = null,
            authors = listOf("An Author"),
            coverImageUrl = null,
            sourceUrl = null,
            language = null,
            releaseDate = null,
            version = null,
            type = "book",
            topics = emptyList(),
            relay = "wss://relay.example",
            createdAt = 1,
            chapterCount = 1,
            chapterRefs = emptyList(),
        )
    }
}
