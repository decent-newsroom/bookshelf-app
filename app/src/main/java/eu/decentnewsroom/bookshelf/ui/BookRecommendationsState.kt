package eu.decentnewsroom.bookshelf.ui

import eu.decentnewsroom.bookshelf.data.discovery.BookRecommendationResult
import eu.decentnewsroom.bookshelf.data.discovery.BookRecommendationStatus
import eu.decentnewsroom.bookshelf.domain.BookSummary

/** Holds only in-session discovery metadata; saved books never contain this state. */
data class BookRecommendationsState(
    val seed: BookSummary,
    val result: BookRecommendationResult = BookRecommendationResult(),
    val isLoading: Boolean = false,
) {
    val message: String? get() = result.message?.let {
        if (result.books.isNotEmpty() && result.status != BookRecommendationStatus.COMPLETE) "$it Previously loaded results may be out of date." else it
    }
}

internal fun BookRecommendationResult.retainingVisibleBooks(previous: BookRecommendationResult): BookRecommendationResult =
    if (status != BookRecommendationStatus.COMPLETE && books.isEmpty()) copy(books = previous.books) else this
