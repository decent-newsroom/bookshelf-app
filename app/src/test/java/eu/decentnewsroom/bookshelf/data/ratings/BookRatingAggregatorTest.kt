package eu.decentnewsroom.bookshelf.data.ratings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookRatingAggregatorTest {
    @Test
    fun newestRatingPerReviewerIsUsedAndTiesUseEventId() {
        val aggregate = requireNotNull(BookRatingAggregator.aggregateForBook("30040:publisher:book", listOf(
            rating(id = "a", reviewer = "one", createdAt = 10, value = 0.2),
            rating(id = "b", reviewer = "one", createdAt = 11, value = 0.8),
            rating(id = "a", reviewer = "two", createdAt = 11, value = 0.4),
            rating(id = "c", reviewer = "two", createdAt = 11, value = 0.6),
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

    private fun rating(id: String = "event", reviewer: String = "reviewer", createdAt: Long = 1, value: Double = 0.8) = BookRating(
        eventId = id, bookCoordinate = "30040:publisher:book", reviewerPubkey = reviewer,
        createdAt = createdAt, producerRating = value.toString(), normalizedRating = value,
        review = "", declaredEntityType = "books", legacyStarTag = null,
    )
}
