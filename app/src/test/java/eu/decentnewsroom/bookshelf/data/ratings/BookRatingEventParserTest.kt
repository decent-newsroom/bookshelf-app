package eu.decentnewsroom.bookshelf.data.ratings

import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookRatingEventParserTest {
    private val publisher = "a".repeat(64)
    private val reviewer = "b".repeat(64)
    private val coordinate = "30040:$publisher:am-fluss-der-zeiten"

    @Test
    fun parsesStrictR1BooksRatingAndPreservesOptionalMetadata() {
        val result = BookRatingEventParser.parse(event(listOf(
            listOf("d", "books:$coordinate"), listOf("a", coordinate), listOf("m", "books"),
            listOf("rating", "0.800"), listOf("s", "4"),
        )))

        val rating = (result as BookRatingParseResult.Accepted).rating
        assertEquals(coordinate, rating.bookCoordinate)
        assertEquals(0.8, rating.normalizedRating, 0.0)
        assertEquals(4.0, rating.displayStars, 0.0)
        assertEquals("0.800", rating.producerRating)
        assertEquals("books", rating.declaredEntityType)
        assertEquals("4", rating.legacyStarTag)
    }

    @Test
    fun parsesRatingForTheIndexDeclaredEntityType() {
        val result = BookRatingEventParser.parse(event(listOf(
            listOf("d", "novel:$coordinate"), listOf("a", coordinate), listOf("m", "novel"), listOf("rating", "0.800"),
        )))

        val rating = (result as BookRatingParseResult.Accepted).rating
        assertEquals(coordinate, rating.bookCoordinate)
        assertEquals("novel", rating.declaredEntityType)
    }
    @Test
    fun parsesRatingThatReferencesALibraryCardThroughBothAddressTagCases() {
        val result = BookRatingEventParser.parse(event(listOf(
            listOf("d", "rating-event-id"), listOf("a", coordinate), listOf("A", coordinate),
            listOf("rating", "0.800"),
        )))

        val rating = (result as BookRatingParseResult.Accepted).rating
        assertEquals(coordinate, rating.bookCoordinate)
        assertEquals(null, rating.declaredEntityType)
    }
    @Test
    fun inclusiveCompatibilityPolicyAcceptsBothEndpoints() {
        listOf("0", "0.000", "1", "1.000").forEach { value ->
            val result = BookRatingEventParser.parse(event(listOf(
                listOf("d", "books:$coordinate"), listOf("a", coordinate), listOf("m", "books"), listOf("rating", value),
            )))
            assertTrue("$value", result is BookRatingParseResult.Accepted)
        }
    }

    @Test
    fun rejectsMalformedTargetsConflictsAndNonDecimalScores() {
        val cases = listOf(
            listOf(listOf("d", coordinate), listOf("rating", "0.5")),
            listOf(listOf("d", "books:$coordinate"), listOf("rating", "0.5")),
            listOf(listOf("d", "books:30041:$publisher:chapter"), listOf("rating", "0.5")),
            listOf(listOf("a", coordinate), listOf("d", "books:$coordinate"), listOf("d", "books:30040:${"c".repeat(64)}:other"), listOf("rating", "0.5")),
            listOf(listOf("a", coordinate), listOf("d", "books:$coordinate"), listOf("m", "movie"), listOf("rating", "0.5")),
            listOf(listOf("a", coordinate), listOf("rating", "-0.1")),
            listOf(listOf("a", coordinate), listOf("rating", "1.001")),
            listOf(listOf("a", coordinate), listOf("rating", "0.5e0")),
        )
        cases.forEach { assertTrue("$it", BookRatingEventParser.parse(event(it)) is BookRatingParseResult.Rejected) }
    }

    private fun event(tags: List<List<String>>) = NostrEvent(
        id = "1".repeat(64), pubkey = reviewer, createdAt = 1_700_000_000L,
        kind = BookKinds.RATING, tags = tags, content = "A public review", sig = "2".repeat(128),
    )
}
