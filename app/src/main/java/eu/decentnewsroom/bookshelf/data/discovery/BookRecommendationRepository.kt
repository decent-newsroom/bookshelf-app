package eu.decentnewsroom.bookshelf.data.discovery

import eu.decentnewsroom.bookshelf.data.mercury.MercuryApiClient
import eu.decentnewsroom.bookshelf.data.mercury.MercuryApiException
import eu.decentnewsroom.bookshelf.data.mercury.MercuryBookRepository
import eu.decentnewsroom.bookshelf.data.mercury.MercurySearchResilience
import eu.decentnewsroom.bookshelf.data.mercury.MercurySearchRetryConfig
import eu.decentnewsroom.bookshelf.domain.BookSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Similarity discovery owns its concurrency/cooldown and never participates in search retries. */
class BookRecommendationRepository internal constructor(
    private val endpoint: String,
    private val fetchBooks: suspend (String, List<String>, Int) -> List<BookSummary>,
    private val isInternetAvailable: () -> Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val resilience: MercurySearchResilience = MercurySearchResilience(
        MercurySearchRetryConfig(maxConcurrentRequests = 1),
    ),
) {
    constructor(
        api: MercuryApiClient,
        books: MercuryBookRepository,
        endpoint: String,
        isInternetAvailable: () -> Boolean,
    ) : this(endpoint, { seed, exclusions, limit ->
        api.recommendPublications(seed, exclusions, limit).mapNotNull(books::mapIndexEvent)
    }, isInternetAvailable)

    private data class Key(val endpoint: String, val seedId: String, val limit: Int, val exclusions: List<String>)
    private data class Cached(val result: BookRecommendationResult, val expiresAt: Long)
    private data class Flight(val deferred: Deferred<BookRecommendationResult>, var users: Int = 0)
    private val mutex = Mutex()
    private val cache = LinkedHashMap<Key, Cached>()
    private val flights = mutableMapOf<Key, Flight>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun cachedRecommendations(
        seed: BookSummary,
        limit: Int = 10,
        excludeIds: List<String> = emptyList(),
    ): BookRecommendationResult? {
        val key = key(seed, limit, excludeIds) ?: return null
        return mutex.withLock { cached(key) }
    }

    suspend fun recommendations(
        seed: BookSummary,
        limit: Int = 10,
        excludeIds: List<String> = emptyList(),
    ): BookRecommendationResult {
        val key = key(seed, limit, excludeIds)
            ?: return BookRecommendationResult(status = BookRecommendationStatus.INVALID_REQUEST)
        val flight = mutex.withLock {
            cached(key)?.let { result ->
                return if (isInternetAvailable()) result else result.copy(status = BookRecommendationStatus.OFFLINE)
            }
            if (!isInternetAvailable()) return BookRecommendationResult(status = BookRecommendationStatus.OFFLINE)
            flights.getOrPut(key) {
                Flight(scope.async(start = CoroutineStart.LAZY) { fetch(key) })
            }.also { it.users++ }
        }
        try {
            return flight.deferred.await()
        } finally {
            // The final subscriber owns cancellation; another subscriber may still need this request.
            withContext(NonCancellable) {
                mutex.withLock {
                    flight.users--
                    if (flight.users == 0 && flights[key] === flight) {
                        flights.remove(key)
                        flight.deferred.cancel()
                    }
                }
            }
        }
    }

    private suspend fun fetch(key: Key): BookRecommendationResult {
        val result = try {
            val books = resilience.execute {
                if (!isInternetAvailable()) throw OfflineRecommendations()
                fetchBooks(key.seedId, key.exclusions, key.limit)
            }
            currentCoroutineContext().ensureActive()
            // Keep the first coordinate's server rank, but choose its newest verified revision.
            val ranked = books.groupBy(BookSummary::coordinate).values.map { revisions ->
                revisions.maxWith(compareBy<BookSummary> { it.createdAt }.thenBy { it.id })
            }.take(key.limit)
            BookRecommendationResult(books = ranked)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: OfflineRecommendations) {
            BookRecommendationResult(status = BookRecommendationStatus.OFFLINE)
        } catch (failure: MercuryApiException) {
            BookRecommendationResult(status = when (failure.statusCode) {
                400 -> BookRecommendationStatus.INVALID_REQUEST
                404 -> BookRecommendationStatus.SEED_UNAVAILABLE
                else -> BookRecommendationStatus.UNAVAILABLE
            })
        } catch (_: Exception) {
            BookRecommendationResult(status = BookRecommendationStatus.UNAVAILABLE)
        }
        if (result.status == BookRecommendationStatus.COMPLETE) {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                cache.entries.removeAll { it.value.expiresAt <= nowMillis() }
                cache[key] = Cached(result, nowMillis() + CACHE_TTL_MILLIS)
                while (cache.size > MAX_CACHE_ENTRIES) cache.remove(cache.keys.first())
            }
        }
        return result
    }

    private fun cached(key: Key): BookRecommendationResult? {
        cache.entries.removeAll { it.value.expiresAt <= nowMillis() }
        return cache[key]?.result?.copy(servedFromCache = true)
    }

    private fun key(seed: BookSummary, limit: Int, exclusions: List<String>): Key? {
        if (!EVENT_ID.matches(seed.id) || limit !in 1..50 || exclusions.size > 100 ||
            exclusions.any { !EVENT_ID.matches(it) }) return null
        return Key(endpoint.trimEnd('/'), seed.id.lowercase(), limit, exclusions.map { it.lowercase() }.distinct().sorted())
    }

    private class OfflineRecommendations : RuntimeException()
    private companion object {
        val EVENT_ID = Regex("^[A-Fa-f0-9]{64}$")
        const val CACHE_TTL_MILLIS = 5 * 60 * 1_000L
        const val MAX_CACHE_ENTRIES = 20
    }
}

enum class BookRecommendationStatus { COMPLETE, OFFLINE, SEED_UNAVAILABLE, INVALID_REQUEST, UNAVAILABLE }

data class BookRecommendationResult(
    val books: List<BookSummary> = emptyList(),
    val status: BookRecommendationStatus = BookRecommendationStatus.COMPLETE,
    val servedFromCache: Boolean = false,
) {
    fun visibleBooks(seedCoordinate: String, savedCoordinates: Set<String>): List<BookSummary> =
        books.filter { it.coordinate != seedCoordinate && it.coordinate !in savedCoordinates }

    val message: String? get() = when (status) {
        BookRecommendationStatus.COMPLETE -> null
        BookRecommendationStatus.OFFLINE -> "Recommendations need internet. Available results are from this session."
        BookRecommendationStatus.SEED_UNAVAILABLE -> "This edition is not indexed for recommendations yet."
        BookRecommendationStatus.INVALID_REQUEST -> "This book cannot be used for recommendations."
        BookRecommendationStatus.UNAVAILABLE -> "Recommendations are temporarily unavailable. Try again."
    }
}
