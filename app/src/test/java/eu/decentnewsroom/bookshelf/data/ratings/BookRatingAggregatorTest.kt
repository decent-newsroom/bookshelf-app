package eu.decentnewsroom.bookshelf.data.ratings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookRatingAggregatorTest {
    @Test
    fun newestRatingPerRevisionAddressIsUsedAndTiesUseEventId() {
        val aggregate = requireNotNull(BookRatingAggregator.aggregateForBook("30040:publisher:book", listOf(
            rating(id = "a", reviewer = "one", dTag = "one", createdAt = 10, value = 0.2),
            rating(id = "b", reviewer = "one", dTag = "one", createdAt = 11, value = 0.8),
            rating(id = "a", reviewer = "two", dTag = "two", createdAt = 11, value = 0.4),
            rating(id = "c", reviewer = "two", dTag = "two", createdAt = 11, value = 0.6),
        )))
        assertEquals(2, aggregate.ratingCount)
        assertEquals(listOf("c", "b"), aggregate.ratings.map(BookRating::eventId))
        assertEquals(0.7, aggregate.averageNormalizedRating, 0.0)
        assertEquals(3.5, aggregate.averageStars, 0.0)
        assertEquals(11, aggregate.latestRatingAt)
    }

    @Test
    fun aggregateDoesNotMixBooksAndReturnsNullWithoutRatings() {
        assertNull(BookRatingAggregator.aggregateForBook("missing", listOf(rating())))
    }

    @Test
    fun distinctDTagsFromOneReviewerRemainDistinctRatings() {
        val effective = BookRatingAggregator.effectiveRatings(listOf(
            rating(id = "first", reviewer = "one", dTag = "book-one"),
            rating(id = "second", reviewer = "one", dTag = "book-two"),
        ))

        assertEquals(setOf("first", "second"), effective.map(BookRating::eventId).toSet())
    }

    @Test
    fun aggregateStillCountsOneRatingPerReviewerAcrossDistinctDTags() {
        val ratings = listOf(
            rating(id = "first", reviewer = "one", dTag = "book-one", createdAt = 10, value = 0.2),
            rating(id = "second", reviewer = "one", dTag = "book-two", createdAt = 11, value = 0.8),
        )

        assertEquals(2, BookRatingAggregator.effectiveRatings(ratings).size)
        val aggregate = requireNotNull(BookRatingAggregator.aggregateForBook("30040:publisher:book", ratings))
        assertEquals(1, aggregate.ratingCount)
        assertEquals("second", aggregate.ratings.single().eventId)
    }

    @Test
    fun missingDTagFallsBackToEventIdentity() {
        val effective = BookRatingAggregator.effectiveRatings(listOf(
            rating(id = "first", reviewer = "one", createdAt = 10),
            rating(id = "second", reviewer = "one", createdAt = 11),
            rating(id = "first", reviewer = "one", createdAt = 10),
        ))

        assertEquals(setOf("first", "second"), effective.map(BookRating::eventId).toSet())
    }

    private fun rating(id: String = "event", reviewer: String = "reviewer", dTag: String? = null, createdAt: Long = 1, value: Double = 0.8) = BookRating(
        eventId = id, bookCoordinate = "30040:publisher:book", reviewerPubkey = reviewer,
        createdAt = createdAt, producerRating = value.toString(), normalizedRating = value,
        review = "", declaredEntityType = "books", legacyStarTag = null, dTag = dTag,
    )
}
