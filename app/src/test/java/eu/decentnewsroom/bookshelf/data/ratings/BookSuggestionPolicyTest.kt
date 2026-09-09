package eu.decentnewsroom.bookshelf.data.ratings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSuggestionPolicyTest {
    private val now = 1_800_000_000L

    @Test
    fun includesOnlyRecentBooksAtFourStarsWithThreeEffectiveRaters() {
        val qualifying = ratings("30040:publisher:qualifying", listOf(.8, .9, 1.0 - .001))
        val tooFew = ratings("30040:publisher:few", listOf(.9, .9))
        val belowThreshold = ratings("30040:publisher:low", listOf(.7, .8, .89))
        val stale = ratings(
            "30040:publisher:stale",
            listOf(.9, .9, .9),
            createdAt = now - BookSuggestionPolicy.RECENT_WINDOW_SECONDS - 1,
        )

        val results = BookSuggestionPolicy.recentlyHighlyRated(
            qualifying + tooFew + belowThreshold + stale,
            now,
        )

        assertEquals(listOf("30040:publisher:qualifying"), results.map(BookSuggestion::bookCoordinate))
    }

    @Test
    fun keepsOnlyNewestRatingForEachReviewerBeforeApplyingThresholds() {
        val coordinate = "30040:publisher:replacement"
        val ratings = listOf(
            rating(coordinate, "one", .9, now - 10),
            rating(coordinate, "one", .1, now),
            rating(coordinate, "two", .9, now),
            rating(coordinate, "three", .9, now),
        )

        assertTrue(BookSuggestionPolicy.recentlyHighlyRated(ratings, now).isEmpty())
    }

    @Test
    fun ranksByAverageThenCountThenRecencyThenCoordinate() {
        val top = ratings("30040:publisher:top", listOf(.9, .9, .9, .9))
        val newer = ratings("30040:publisher:newer", listOf(.8, .8, .8), createdAt = now)
        val older = ratings("30040:publisher:older", listOf(.8, .8, .8), createdAt = now - 1)

        val results = BookSuggestionPolicy.recentlyHighlyRated(top + older + newer, now)

        assertEquals(
            listOf("30040:publisher:top", "30040:publisher:newer", "30040:publisher:older"),
            results.map(BookSuggestion::bookCoordinate),
        )
    }

    private fun ratings(coordinate: String, values: List<Double>, createdAt: Long = now): List<BookRating> =
        values.mapIndexed { index, value -> rating(coordinate, "reviewer-$index", value, createdAt) }

    private fun rating(coordinate: String, reviewer: String, value: Double, createdAt: Long) =
        BookRating(
            eventId = "$coordinate-$reviewer-$createdAt-$value",
            bookCoordinate = coordinate,
            reviewerPubkey = reviewer,
            createdAt = createdAt,
            producerRating = value.toString(),
            normalizedRating = value,
            review = "",
            declaredEntityType = "books",
            legacyStarTag = null,
        )
}
