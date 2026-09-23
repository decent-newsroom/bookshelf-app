package eu.decentnewsroom.bookshelf.data.ratings

/** Aggregates the newest event for each replaceable rating address. */
public object BookRatingAggregator {
    /** Equal timestamps are resolved by ID so relay arrival order cannot change results. */
    fun effectiveRatings(ratings: Iterable<BookRating>): List<BookRating> =
        ratings.groupBy(BookRating::revisionKey).values.map { candidates ->
            candidates.maxWith(compareBy<BookRating> { it.createdAt }.thenBy { it.eventId })
        }

    fun aggregateForBook(bookCoordinate: String, ratings: Iterable<BookRating>): BookRatingAggregate? {
        val effective = effectiveRatings(ratings.filter { it.bookCoordinate == bookCoordinate })
            .groupBy { it.reviewerPubkey.lowercase() }
            .values.map { revisions -> revisions.maxWith(compareBy<BookRating> { it.createdAt }.thenBy { it.eventId }) }
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
