package eu.decentnewsroom.bookshelf.ui.ratings

import eu.decentnewsroom.bookshelf.data.ratings.BookRating
import eu.decentnewsroom.bookshelf.data.ratings.BookRatingAggregator
import eu.decentnewsroom.bookshelf.data.ratings.BookRatingEventParser
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.ui.RatingComposerState
import kotlin.math.roundToInt

/** Uses the same effective-review and editable-target rules as community ratings. */
internal fun ratingComposerForBook(book: BookSummary, pubkey: String?, ratings: List<BookRating>): RatingComposerState {
    val ownReview = BookRatingAggregator.effectiveRatings(ratings)
        .filter { it.bookCoordinate == book.coordinate && it.reviewerPubkey.equals(pubkey, ignoreCase = true) }
        .maxWithOrNull(compareBy<BookRating> { it.createdAt }.thenBy { it.eventId })
        ?.takeIf { rating -> rating.dTag?.let { BookRatingEventParser.parseBookTarget(it)?.second == book.coordinate } == true }
    return RatingComposerState(
        book = book,
        selectedStars = ownReview?.displayStars?.roundToInt()?.coerceIn(0, 5),
        originalDisplayStars = ownReview?.displayStars,
        originalNormalizedRating = ownReview?.normalizedRating,
        opinion = ownReview?.review.orEmpty(),
        editingEventId = ownReview?.eventId,
        editingDTag = ownReview?.dTag,
        previousCreatedAt = ownReview?.createdAt,
        requiresSignIn = pubkey == null,
    )
}
