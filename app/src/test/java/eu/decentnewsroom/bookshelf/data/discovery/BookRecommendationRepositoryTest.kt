package eu.decentnewsroom.bookshelf.data.discovery

import eu.decentnewsroom.bookshelf.data.mercury.MercuryApiException
import eu.decentnewsroom.bookshelf.data.mercury.MercurySearchResilience
import eu.decentnewsroom.bookshelf.data.mercury.MercurySearchRetryConfig
import eu.decentnewsroom.bookshelf.domain.BookSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BookRecommendationRepositoryTest {
    @Test fun rankAndNewestRevisionSurviveLiveSavedFiltering() = runBlocking {
        val seed = book(1)
        val old = book(2)
        val newer = old.copy(id = id(8), createdAt = 2)
        val third = book(3)
        val repository = repository { _, _, _ -> listOf(old, third, newer, seed) }
        val result = repository.recommendations(seed)
        assertEquals(listOf(newer, third, seed), result.books)
        assertEquals(listOf(newer, third), result.visibleBooks(seed.coordinate, emptySet()))
        assertEquals(listOf(third), result.visibleBooks(seed.coordinate, setOf(newer.coordinate)))
        assertEquals(listOf(newer, third), result.visibleBooks(seed.coordinate, emptySet()))
    }

    @Test fun successfulEmptyResultsCacheButErrorsDoNot() = runBlocking {
        var calls = 0
        val repository = repository { _, _, _ ->
            calls++
            if (calls == 1) throw MercuryApiException("missing", statusCode = 404)
            emptyList()
        }
        assertEquals(BookRecommendationStatus.SEED_UNAVAILABLE, repository.recommendations(book(1)).status)
        assertFalse(repository.recommendations(book(1)).servedFromCache)
        assertTrue(repository.recommendations(book(1)).servedFromCache)
        assertEquals(2, calls)
    }

    @Test fun offlineUsesOnlyFreshSessionCacheAndExpiryPreventsReuse() = runBlocking {
        var online = true
        var now = 0L
        var calls = 0
        val repository = BookRecommendationRepository("primary", { _, _, _ -> calls++; listOf(book(2)) }, { online }, { now })
        repository.recommendations(book(1))
        online = false
        assertEquals(listOf(book(2)), repository.recommendations(book(1)).books)
        assertEquals(BookRecommendationStatus.OFFLINE, repository.recommendations(book(1)).status)
        now = 300_000L
        assertNull(repository.cachedRecommendations(book(1)))
        assertTrue(repository.recommendations(book(1)).books.isEmpty())
        assertEquals(1, calls)
    }

    @Test fun cacheKeysCanonicalizeExclusionsAndBoundCapacity() = runBlocking {
        var calls = 0
        val repository = repository { _, _, _ -> calls++; emptyList() }
        repository.recommendations(book(1), excludeIds = listOf(id(15).uppercase(), id(14), id(15)))
        assertTrue(repository.recommendations(book(1), excludeIds = listOf(id(14), id(15))).servedFromCache)
        assertFalse(repository.recommendations(book(1), limit = 11, excludeIds = listOf(id(14), id(15))).servedFromCache)
        (2..21).forEach { repository.recommendations(book(it)) }
        assertNull(repository.cachedRecommendations(book(1), excludeIds = listOf(id(14), id(15))))
        assertEquals(22, calls)
    }

    @Test fun invalidIdsLimitsAndTooManyDuplicateExclusionsNeverFetch() = runBlocking {
        val repository = repository { _, _, _ -> error("Must not fetch") }
        assertEquals(BookRecommendationStatus.INVALID_REQUEST, repository.recommendations(book(1).copy(id = "bad")).status)
        assertEquals(BookRecommendationStatus.INVALID_REQUEST, repository.recommendations(book(1), limit = 51).status)
        assertEquals(BookRecommendationStatus.INVALID_REQUEST, repository.recommendations(book(1), excludeIds = List(101) { id(2) }).status)
    }

    @Test fun identicalRequestsCoalesceAndOneCancelledSubscriberDoesNotCancelAnother() = runBlocking {
        withTimeout(5_000) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val repository = repository { _, _, _ ->
                calls.incrementAndGet(); started.complete(Unit); release.await(); listOf(book(2))
            }
            val first = async(start = CoroutineStart.UNDISPATCHED) { repository.recommendations(book(1)) }
            started.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { repository.recommendations(book(1)) }
            first.cancelAndJoin()
            release.complete(Unit)
            assertEquals(listOf(book(2)), second.await().books)
            assertEquals(1, calls.get())
        }
    }

    @Test fun finalSubscriberCancellationCancelsFetchAndDoesNotCache() = runBlocking {
        withTimeout(5_000) {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val repository = repository { _, _, _ ->
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            val request = async { repository.recommendations(book(1)) }
            started.await()
            request.cancelAndJoin()
            cancelled.await()
            assertNull(repository.cachedRecommendations(book(1)))
        }
    }

    @Test fun retryRechecksConnectivityAnd400IsNotRetried() = runBlocking {
        var online = true
        var calls = 0
        val resilience = MercurySearchResilience(
            MercurySearchRetryConfig(maxConcurrentRequests = 1),
            sleep = { online = false }, jitterMillis = { 0 },
        )
        val repository = BookRecommendationRepository("primary", { _, _, _ ->
            calls++; throw MercuryApiException("busy", statusCode = 503)
        }, { online }, resilience = resilience)
        assertEquals(BookRecommendationStatus.OFFLINE, repository.recommendations(book(1)).status)
        assertEquals(1, calls)
        val invalid = repository { _, _, _ -> calls++; throw MercuryApiException("invalid", statusCode = 400) }
        assertEquals(BookRecommendationStatus.INVALID_REQUEST, invalid.recommendations(book(1)).status)
        assertEquals(2, calls)
    }

    @Test fun busyResponseRetriesOnlyOnceAndThenEntersIndependentCooldown() = runBlocking {
        var calls = 0
        val resilience = MercurySearchResilience(
            MercurySearchRetryConfig(maxConcurrentRequests = 1),
            sleep = {}, jitterMillis = { 0 },
        )
        val repository = BookRecommendationRepository("primary", { _, _, _ ->
            calls++; throw MercuryApiException("busy", statusCode = 503)
        }, { true }, resilience = resilience)
        assertEquals(BookRecommendationStatus.UNAVAILABLE, repository.recommendations(book(1)).status)
        assertEquals(2, calls)
        repository.recommendations(book(1))
        assertEquals(3, calls)
        repository.recommendations(book(2))
        assertEquals(3, calls)
    }
    private fun repository(fetch: suspend (String, List<String>, Int) -> List<BookSummary>) =
        BookRecommendationRepository("primary", fetch, { true })

    private fun id(number: Int) = number.toString(16).padStart(64, '0')
    private fun book(number: Int) = BookSummary(
        id = id(number), coordinate = "30040:${id(99)}:book-$number", pubkey = id(99),
        identifier = "book-$number", title = "Book $number", summary = null, authors = emptyList(),
        coverImageUrl = null, sourceUrl = null, language = null, releaseDate = null, version = null,
        type = "book", topics = emptyList(), relay = null, createdAt = 1L, chapterCount = 0, chapterRefs = emptyList(),
    )
}
