package eu.decentnewsroom.bookshelf.ui

import eu.decentnewsroom.bookshelf.data.bookshelf.BookshelfDirectoryRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingDirectoryTagsTest {
    @Test
    fun acceptsRequiredPublishOnlyClientTag() {
        val editable = listOf(
            listOf("d", BookshelfDirectoryRules.IDENTIFIER),
            listOf("a", coordinate("a"), "wss://relay.example", "1".repeat(64)),
        )
        val published = BookshelfDirectoryRules.tagsForPublishing(editable)

        assertTrue(hasExpectedEditableDirectoryTags(published, published))
    }

    @Test
    fun rejectsAChangedCollectionReference() {
        val expected = BookshelfDirectoryRules.tagsForPublishing(
            listOf(
                listOf("d", BookshelfDirectoryRules.IDENTIFIER),
                listOf("a", coordinate("a"), "wss://relay.example", "1".repeat(64)),
            ),
        )
        val signed = BookshelfDirectoryRules.tagsForPublishing(
            listOf(
                listOf("d", BookshelfDirectoryRules.IDENTIFIER),
                listOf("a", coordinate("b"), "wss://relay.example", "2".repeat(64)),
            ),
        )

        assertFalse(hasExpectedEditableDirectoryTags(signed, expected))
    }

    private fun coordinate(fill: String): String = "30040:${fill.repeat(64)}:book"
}