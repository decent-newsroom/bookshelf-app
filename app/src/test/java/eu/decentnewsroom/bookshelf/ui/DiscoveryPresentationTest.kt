package eu.decentnewsroom.bookshelf.ui

import eu.decentnewsroom.bookshelf.data.discovery.BookRecommendationResult
import eu.decentnewsroom.bookshelf.data.discovery.BookRecommendationStatus
import eu.decentnewsroom.bookshelf.data.mercury.BookSearchOutcome
import eu.decentnewsroom.bookshelf.data.mercury.BookSearchResult
import eu.decentnewsroom.bookshelf.data.mercury.BookSearchStatus
import eu.decentnewsroom.bookshelf.data.mercury.MatchProvenance
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.ui.search.isContentMatch
import eu.decentnewsroom.bookshelf.ui.search.searchMatchLabel
import eu.decentnewsroom.bookshelf.ui.search.searchOutcomeMessage
import org.junit.Assert.*
import org.junit.Test

class DiscoveryPresentationTest {
    @Test fun partialSearchRemainsVisibleAlongsideUsefulResults() {
        val result = BookSearchResult(book(), setOf(MatchProvenance.CHAPTER_TEXT))
        assertNotNull(searchOutcomeMessage(BookSearchOutcome(listOf(result), BookSearchStatus.PARTIAL)))
        assertNull(searchOutcomeMessage(BookSearchOutcome(listOf(result), BookSearchStatus.COMPLETE)))
        assertEquals("Text match", result.provenance.searchMatchLabel())
    }

    @Test fun resultProvenanceSelectsContentPresentationIndependentlyOfSearchControls() {
        listOf(MatchProvenance.CHAPTER_TITLE, MatchProvenance.CHAPTER_BODY, MatchProvenance.CHAPTER_TEXT).forEach {
            assertTrue(setOf(it).isContentMatch())
            assertTrue(setOf(it, MatchProvenance.METADATA).isContentMatch())
        }
        assertFalse(setOf(MatchProvenance.METADATA).isContentMatch())
        assertFalse(setOf(MatchProvenance.EXACT_EVENT, MatchProvenance.EXACT_COORDINATE).isContentMatch())
    }

    @Test fun failedRecommendationRefreshRetainsPreviousBooksButEmptySuccessClearsThem() {
        val previous = BookRecommendationResult(listOf(book()))
        val failed = BookRecommendationResult(status = BookRecommendationStatus.UNAVAILABLE).retainingVisibleBooks(previous)
        assertEquals(previous.books, failed.books)
        assertEquals(BookRecommendationStatus.UNAVAILABLE, failed.status)
        assertTrue(BookRecommendationsState(book(), failed).message!!.contains("out of date"))
        assertTrue(BookRecommendationResult().retainingVisibleBooks(previous).books.isEmpty())
    }

    private fun book() = BookSummary(
        "1".repeat(64), "30040:pubkey:book", "pubkey", "book", "Book", null,
        emptyList(), null, null, null, null, null, "book", emptyList(), null, 0, 0, emptyList(),
    )
}
