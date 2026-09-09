package eu.decentnewsroom.bookshelf.data.ratings

/** Aggregates ratings without allowing repeated reviews by one author to outweigh others. */
object BookRatingAggregator {
    /** Equal timestamps are resolved by ID so relay arrival order cannot change results. */
    fun effectiveRatings(ratings: Iterable<BookRating>): List<BookRating> =
        ratings.groupBy { it.bookCoordinate to it.reviewerPubkey.lowercase() }.values.map { candidates ->
            candidates.maxWith(compareBy<BookRating> { it.createdAt }.thenBy { it.eventId })
        }

    fun aggregateForBook(bookCoordinate: String, ratings: Iterable<BookRating>): BookRatingAggregate? {
        val effective = effectiveRatings(ratings.filter { it.bookCoordinate == bookCoordinate })
            .sortedWith(compareByDescending<BookRating> { it.createdAt }.thenByDescending { it.eventId })
        if (effective.isEmpty()) return null
        val average = effective.map(BookRating::normalizedRating).average()
        return BookRatingAggregate(
            bookCoordinate = bookCoordinate,
            ratings = effective,
            averageNormalizedRating = average,
            averageStars = average * STARS_PER_RATING,
            latestRatingAt = effective.maxOf(BookRating::createdAt),
        )
    }

    private const val STARS_PER_RATING = 5.0
}
