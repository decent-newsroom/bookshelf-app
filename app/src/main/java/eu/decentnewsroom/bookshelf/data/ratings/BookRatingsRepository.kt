package eu.decentnewsroom.bookshelf.data.ratings

import eu.decentnewsroom.bookshelf.data.nostr.NostrRelayClient
import eu.decentnewsroom.bookshelf.domain.BookSummary
import kotlinx.coroutines.CancellationException

/** Reads verified R1 ratings through the shared Quartz relay boundary. */
public class BookRatingsRepository(
    private val relayClient: NostrRelayClient,
    private val cache: BookRatingCache? = null,
) {
    suspend fun ratingsFor(book: BookSummary): List<BookRating> {
        val target = "books:${book.coordinate}"
        val cached = cache?.ratingsFor(book.coordinate).orEmpty()
        val fetched = try {
            relayClient.fetchRatings(listOf(target))
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            return cached
        }
        cache?.merge(fetched)
        return (cached + fetched.mapNotNull { event ->
            (BookRatingEventParser.parse(event) as? BookRatingParseResult.Accepted)?.rating
        }).associateBy(BookRating::eventId).values.toList()
    }

    suspend fun aggregateFor(book: BookSummary): BookRatingAggregate? =
        BookRatingAggregator.aggregateForBook(book.coordinate, ratingsFor(book))

    suspend fun cacheStats(): BookRatingCacheStats = cache?.stats() ?: BookRatingCacheStats()

    suspend fun clearCache(): BookRatingCacheStats = cache?.clear() ?: BookRatingCacheStats()
}
