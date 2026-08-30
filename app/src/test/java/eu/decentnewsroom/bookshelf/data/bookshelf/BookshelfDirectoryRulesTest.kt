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
        val tags =
            BookshelfDirectoryRules.tagsForPublishing(
                BookshelfDirectoryRules.toggleBook(BookshelfDirectoryRules.emptyTags(), book),
            )

        BookshelfDirectoryRules.assertValidDirectory(tags, "")
    }

    @Test
    fun publishingTagsOverrideExistingClientTag() {
        val tags =
            BookshelfDirectoryRules.tagsForPublishing(
                BookshelfDirectoryRules.emptyTags() + listOf(listOf("client", "Another client")),
            )

        assertEquals(listOf("client", BookshelfDirectoryRules.CLIENT_NAME), tags.last())
        assertEquals(1, tags.count { it.firstOrNull() == "client" })
        BookshelfDirectoryRules.assertValidDirectory(tags, "")
    }

    @Test
    fun validDirectoryAllowsOneTitleTag() {
        val tags = BookshelfDirectoryRules.tagsForPublishing(BookshelfDirectoryRules.emptyTags()) + listOf(listOf("title", "My Books"))

        BookshelfDirectoryRules.assertValidDirectory(tags, "")
    }

    @Test
    fun mergeKeepsLocalOrderAndDeduplicatesRemoteItems() {
        val book = bookSummary()
        val local = BookshelfDirectoryRules.toggleBook(BookshelfDirectoryRules.emptyTags(), book)
        val merged = BookshelfDirectoryRules.mergeEditableTags(local, local)

        assertEquals(local, merged)
    }

    @Test
    fun mergeDeduplicatesBeforeApplyingItemLimit() {
        val localBook = bookSummary()
        val remoteBook = localBook.copy(
            id = "b".repeat(64),
            coordinate = "${BookKinds.PUBLICATION_INDEX}:${"2".repeat(64)}:another-book",
            pubkey = "2".repeat(64),
            identifier = "another-book",
            title = "Another Book",
        )
        val local = BookshelfDirectoryRules.toggleBook(BookshelfDirectoryRules.emptyTags(), localBook)
        val remoteBookTag =
            BookshelfDirectoryRules
                .toggleBook(BookshelfDirectoryRules.emptyTags(), remoteBook)
                .last()
        val remote =
            BookshelfDirectoryRules.emptyTags() +
                List(499) { local.last() } +
                listOf(remoteBookTag)

        val merged = BookshelfDirectoryRules.mergeEditableTags(local, remote)

        assertEquals(
            listOf(localBook.coordinate, remoteBook.coordinate),
            BookshelfDirectoryRules.extractBookReferences(merged).mapNotNull { it.coordinate },
        )
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
