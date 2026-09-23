package eu.decentnewsroom.bookshelf.data.ratings

import eu.decentnewsroom.bookshelf.domain.BookKinds

/** A verified, R1-compatible rating of a kind-30040 publication. */
public data class BookRating(
    val eventId: String,
    val bookCoordinate: String,
    val reviewerPubkey: String,
    val createdAt: Long,
    val producerRating: String,
    val normalizedRating: Double,
    val review: String,
    val declaredEntityType: String?,
    val legacyStarTag: String?,
    val dTag: String? = null,
) {
    val displayStars: Double get() = normalizedRating * STARS_PER_RATING

    /** Events without a d tag cannot be treated as revisions of one another. */
    internal val revisionKey: Triple<Int, String, String>
        get() = Triple(BookKinds.RATING, reviewerPubkey.lowercase(), dTag?.let { "d:$it" } ?: "event:$eventId")

    private companion object { const val STARS_PER_RATING = 5.0 }
}

/** The newest effective ratings and their aggregate for one publication. */
public data class BookRatingAggregate(
    val bookCoordinate: String,
    val ratings: List<BookRating>,
    val averageNormalizedRating: Double,
    val averageStars: Double,
    val latestRatingAt: Long,
) {
    val ratingCount: Int get() = ratings.size
}
