package eu.decentnewsroom.bookshelf.data.ratings

import eu.decentnewsroom.bookshelf.data.nostr.NostrRelayClient
import eu.decentnewsroom.bookshelf.data.ratings.BookSuggestion
import eu.decentnewsroom.bookshelf.data.ratings.BookSuggestionPolicy
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.domain.BookRatingSummary
import kotlinx.coroutines.CancellationException

/** Reads verified R1 ratings through the shared Quartz relay boundary. */
public class BookRatingsRepository(
    private val relayClient: NostrRelayClient,
    private val cache: BookRatingCache? = null,
    /** Blocks relay reads while Android has no validated internet connection. */
    private val isInternetAvailable: () -> Boolean = { true },
) {
    suspend fun cachedRatingsFor(book: BookSummary): List<BookRating> =
        BookRatingAggregator.effectiveRatings(cache?.ratingsFor(book.coordinate).orEmpty())

    suspend fun ratingsFor(book: BookSummary): List<BookRating> {
        val cached = cachedRatingsFor(book)
        if (!isInternetAvailable()) return cached
        val fetched = try {
            relayClient.fetchRatings(publicationCoordinates = listOf(book.coordinate))
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            return cached
        }
        cache?.merge(fetched)
        return BookRatingAggregator.effectiveRatings(cached + fetched.mapNotNull { event ->
            (BookRatingEventParser.parse(event) as? BookRatingParseResult.Accepted)?.rating
        })
    }

    suspend fun recentlyHighlyRated(nowSeconds: Long = System.currentTimeMillis() / 1_000L): List<BookSuggestion> {
        if (!isInternetAvailable()) {
            return BookSuggestionPolicy.recentlyHighlyRated(cache?.allRatings().orEmpty(), nowSeconds)
        }
        val ratings = relayClient.fetchRecentRatings().mapNotNull { event ->
            (BookRatingEventParser.parse(event) as? BookRatingParseResult.Accepted)?.rating
        }
        return BookSuggestionPolicy.recentlyHighlyRated(ratings, nowSeconds)
    }
    suspend fun enrich(book: BookSummary): BookSummary =
        book.copy(
            ratingSummary = aggregateFor(book)?.let { aggregate ->
                BookRatingSummary(
                    averageNormalizedRating = aggregate.averageNormalizedRating,
                    averageStars = aggregate.averageStars,
                    ratingCount = aggregate.ratingCount,
                    latestRatingAt = aggregate.latestRatingAt,
                )
            },
        )
    suspend fun aggregateFor(book: BookSummary): BookRatingAggregate? =
        BookRatingAggregator.aggregateForBook(book.coordinate, ratingsFor(book))

    suspend fun cacheStats(): BookRatingCacheStats = cache?.stats() ?: BookRatingCacheStats()

    suspend fun clearCache(): BookRatingCacheStats = cache?.clear() ?: BookRatingCacheStats()
}
