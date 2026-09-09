package eu.decentnewsroom.bookshelf.data.ratings

/**
 * Product policy for the initial "Recently rated highly" discovery feed.
 *
 * Ranking deliberately stays stable and explainable until there is sufficient
 * real-world data to tune a more sophisticated score.
 */
object BookSuggestionPolicy {
    const val MINIMUM_AVERAGE_NORMALIZED_RATING = 0.800
    const val MINIMUM_RATING_COUNT = 3
    const val RECENT_WINDOW_SECONDS = 90L * 24L * 60L * 60L

    fun recentlyHighlyRated(
        ratings: Iterable<BookRating>,
        nowSeconds: Long,
    ): List<BookSuggestion> {
        require(nowSeconds >= 0) { "nowSeconds must not be negative." }
        val cutoff = (nowSeconds - RECENT_WINDOW_SECONDS).coerceAtLeast(0)

        return BookRatingAggregator
            .effectiveRatings(ratings)
            .groupBy(BookRating::bookCoordinate)
            .mapNotNull { (coordinate, effectiveRatings) ->
                BookRatingAggregator.aggregateForBook(coordinate, effectiveRatings)
                    ?.takeIf { aggregate ->
                        aggregate.averageNormalizedRating >= MINIMUM_AVERAGE_NORMALIZED_RATING &&
                            aggregate.ratingCount >= MINIMUM_RATING_COUNT &&
                            aggregate.latestRatingAt >= cutoff
                    }
                    ?.let(::BookSuggestion)
            }
            .sortedWith(
                compareByDescending<BookSuggestion> { it.aggregate.averageNormalizedRating }
                    .thenByDescending { it.aggregate.ratingCount }
                    .thenByDescending { it.aggregate.latestRatingAt }
                    .thenBy { it.bookCoordinate },
            )
    }
}

/** A qualifying discovery candidate; book metadata is resolved by the caller. */
data class BookSuggestion(
    val aggregate: BookRatingAggregate,
) {
    val bookCoordinate: String get() = aggregate.bookCoordinate
}
