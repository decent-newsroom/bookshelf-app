package eu.decentnewsroom.bookshelf.data.nostr

import org.junit.Assert.assertEquals
import org.junit.Test

class RatingEventDraftTest {
    @Test
    fun targetUsesDeclaredEntityTypeAndDefaultsToBook() {
        val coordinate = "30040:${"a".repeat(64)}:example"

        assertEquals("novel:$coordinate", RatingEventDraft("b".repeat(64), coordinate, 0.8, createdAt = 1, entityType = "novel").targetId)
        assertEquals("book:$coordinate", RatingEventDraft("b".repeat(64), coordinate, 0.8, createdAt = 1, entityType = " ").targetId)
    }
}