package eu.decentnewsroom.bookshelf.data.ratings

/** A verified, R1-compatible rating of a kind-30040 publication. */
data class BookRating(
    val eventId: String,
    val bookCoordinate: String,
    val reviewerPubkey: String,
    val createdAt: Long,
    val producerRating: String,
    val normalizedRating: Double,
    val review: String,
    val declaredEntityType: String?,
    val legacyStarTag: String?,
) {
    val displayStars: Double get() = normalizedRating * STARS_PER_RATING

    private companion object { const val STARS_PER_RATING = 5.0 }
}

/** The newest effective ratings and their aggregate for one publication. */
data class BookRatingAggregate(
    val bookCoordinate: String,
    val ratings: List<BookRating>,
    val averageNormalizedRating: Double,
    val averageStars: Double,
    val latestRatingAt: Long,
) {
    val ratingCount: Int get() = ratings.size
}
