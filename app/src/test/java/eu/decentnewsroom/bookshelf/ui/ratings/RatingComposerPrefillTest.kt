package eu.decentnewsroom.bookshelf.ui.ratings

import eu.decentnewsroom.bookshelf.data.ratings.BookRating
import eu.decentnewsroom.bookshelf.domain.BookSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingComposerPrefillTest {
    private val publisher = "a".repeat(64)
    private val reviewer = "b".repeat(64)
    private val coordinate = "30040:$publisher:book"
    private val book = BookSummary(
        id = "c".repeat(64), coordinate = coordinate, pubkey = publisher,
        identifier = "book", title = "A book", summary = null, authors = emptyList(),
        coverImageUrl = null, sourceUrl = null, language = null, releaseDate = null,
        version = null, type = "book", topics = emptyList(), relay = null,
        createdAt = 1, chapterCount = 0, chapterRefs = emptyList(),
    )

    @Test
    fun ownReviewPreservesFractionalRatingAndOriginalRevisionTarget() {
        val review = rating(dTag = "academic:$coordinate", value = 0.73)

        val composer = ratingComposerForBook(book, reviewer.uppercase(), listOf(review))

        assertEquals(book, composer.book)
        assertEquals(4, composer.selectedStars)
        assertEquals(0.73, requireNotNull(composer.originalNormalizedRating), 0.0)
        assertEquals(0.73 * 5, requireNotNull(composer.originalDisplayStars), 0.0)
        assertEquals(review.review, composer.opinion)
        assertEquals(review.eventId, composer.editingEventId)
        assertEquals("academic:$coordinate", composer.editingDTag)
        assertEquals(review.createdAt, composer.previousCreatedAt)
        assertFalse(composer.hasChangedStars)
        assertFalse(composer.requiresSignIn)
    }

    @Test
    fun newestRevisionPrefillsEvenWhenAnOlderRevisionOccursLast() {
        val old = rating(id = "old", createdAt = 10, value = 0.2)
        val newest = rating(id = "new", createdAt = 20, value = 0.8)

        val composer = ratingComposerForBook(book, reviewer, listOf(newest, old))

        assertEquals("new", composer.editingEventId)
        assertEquals("Review new", composer.opinion)
        assertEquals(20L, composer.previousCreatedAt)
        assertEquals(0.8, requireNotNull(composer.originalNormalizedRating), 0.0)
    }

    @Test
    fun reviewsForAnotherBookOrReviewerCannotReplaceOwnReview() {
        val own = rating(id = "own", createdAt = 10)
        val otherBook = rating(id = "other-book", createdAt = 30,
            bookCoordinate = "30040:$publisher:another-book", dTag = "books:30040:$publisher:another-book")
        val otherReviewer = rating(id = "other-reviewer", createdAt = 40, author = "d".repeat(64))

        val composer = ratingComposerForBook(book, reviewer, listOf(otherBook, otherReviewer, own))

        assertEquals("own", composer.editingEventId)
        assertEquals("Review own", composer.opinion)
    }

    @Test
    fun missingMalformedOrMismatchedRevisionTargetsStartANewReview() {
        listOf(null, "not-a-book-target", "books:30040:$publisher:another-book").forEach { dTag ->
            val composer = ratingComposerForBook(book, reviewer, listOf(rating(dTag = dTag)))

            assertNull(composer.editingEventId)
            assertNull(composer.editingDTag)
            assertNull(composer.previousCreatedAt)
            assertNull(composer.selectedStars)
            assertNull(composer.originalNormalizedRating)
            assertNull(composer.originalDisplayStars)
            assertEquals("", composer.opinion)
            assertFalse(composer.requiresSignIn)
        }
    }

    @Test
    fun unrelatedReviewsLeaveANewComposerEmpty() {
        val composer = ratingComposerForBook(book, reviewer,
            listOf(rating(author = "d".repeat(64))))

        assertNull(composer.editingEventId)
        assertNull(composer.selectedStars)
        assertEquals("", composer.opinion)
    }

    @Test
    fun signedOutComposerRequiresSignInAndDoesNotPrefillAnotherUsersReview() {
        val composer = ratingComposerForBook(book, null, listOf(rating()))

        assertTrue(composer.requiresSignIn)
        assertNull(composer.editingEventId)
        assertNull(composer.selectedStars)
        assertEquals("", composer.opinion)
    }

    private fun rating(
        id: String = "event",
        author: String = reviewer,
        bookCoordinate: String = coordinate,
        dTag: String? = "books:$coordinate",
        createdAt: Long = 1,
        value: Double = 0.8,
    ) = BookRating(
        eventId = id, bookCoordinate = bookCoordinate, reviewerPubkey = author,
        createdAt = createdAt, producerRating = value.toString(), normalizedRating = value,
        review = "Review $id", declaredEntityType = "books", legacyStarTag = null, dTag = dTag,
    )
}
