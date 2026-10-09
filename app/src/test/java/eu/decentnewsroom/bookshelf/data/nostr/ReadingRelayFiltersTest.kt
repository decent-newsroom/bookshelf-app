package eu.decentnewsroom.bookshelf.data.nostr

import eu.decentnewsroom.bookshelf.domain.BookKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingRelayFiltersTest {
    @Test
    fun finishedHistoryPagesUseInclusiveTimestampBoundariesAndAuthor() {
        val pubkey = "A".repeat(64)
        val first = finishedLabelsFilter(pubkey)
        assertEquals(listOf(BookKinds.FINISHED_LABEL), first.kinds)
        assertEquals(listOf(pubkey.lowercase()), first.authors)
        assertEquals(mapOf("l" to listOf("read")), first.tags)
        assertEquals(500, first.limit)
        assertNull(first.until)
        val boundary = finishedLabelsFilter(pubkey, until = 100L, since = 100L, limit = 501)
        assertEquals(100L, boundary.since)
        assertEquals(100L, boundary.until)
        assertEquals(501, boundary.limit)
        assertEquals(99L, finishedLabelsFilter(pubkey, until = 99L).until)
    }
}
