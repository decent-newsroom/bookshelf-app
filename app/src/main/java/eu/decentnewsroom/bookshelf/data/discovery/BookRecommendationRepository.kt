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
    private val persistentCache: BookRecommendationCache? = null,
    private val resilience: MercurySearchResilience = MercurySearchResilience(
        MercurySearchRetryConfig(maxConcurrentRequests = 1),
    ),
) {
    constructor(
        api: MercuryApiClient,
        books: MercuryBookRepository,
        endpoint: String,
        isInternetAvailable: () -> Boolean,
        cache: BookRecommendationCache,
    ) : this(endpoint, { seed, exclusions, limit ->
        api.recommendPublications(seed, exclusions, limit).mapNotNull(books::mapIndexEvent)
    }, isInternetAvailable, persistentCache = cache)

    private data class Key(val endpoint: String, val seedId: String, val limit: Int, val exclusions: List<String>) {
        val storageKey: String get() = listOf(endpoint, seedId, limit.toString(), exclusions.joinToString(",")).joinToString("\n")
    }
    private data class Cached(val result: BookRecommendationResult, val expiresAt: Long)
    private data class Flight(val deferred: Deferred<BookRecommendationResult>, var users: Int = 0)
    private val mutex = Mutex()
    private val cache = LinkedHashMap<Key, Cached>()
    private val flights = mutableMapOf<Key, Flight>()
    private var generation = 0L
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
            val cached = cached(key)
            if (!isInternetAvailable()) return (cached ?: BookRecommendationResult()).copy(status = BookRecommendationStatus.OFFLINE)
            if (cached != null && !cached.isStale) return cached
            flights.getOrPut(key) {
                val requestGeneration = generation
                Flight(scope.async(start = CoroutineStart.LAZY) { fetch(key, requestGeneration) })
            }.also { it.users++ }
        }
        try {
            return flight.deferred.await()
        } finally {
            // The final subscriber owns cancellation; another subscriber may still need this request.
            withContext(NonCancellable) {
                mutex.withLock {
                    flight.users--
                    if (flight.users == 0) {
                        if (flights[key] === flight) flights.remove(key)
                        flight.deferred.cancel()
                    }
                }
            }
        }
    }

    suspend fun cacheStats(): BookRecommendationCacheStats = mutex.withLock {
        persistentCache?.stats() ?: BookRecommendationCacheStats(entryCount = cache.size)
    }

    suspend fun clearCache(): BookRecommendationCacheStats = mutex.withLock {
        generation++
        cache.clear()
        // Detach old work so a subsequent request cannot subscribe to a pre-clear refresh.
        flights.clear()
        persistentCache?.clear() ?: BookRecommendationCacheStats()
    }

    private suspend fun fetch(key: Key, requestGeneration: Long): BookRecommendationResult {
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
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (requestGeneration != generation) return@withLock BookRecommendationResult(status = BookRecommendationStatus.UNAVAILABLE)
            if (result.status == BookRecommendationStatus.COMPLETE) {
                val fetchedAt = nowMillis()
                cache.remove(key)
                cache[key] = Cached(result, fetchedAt + CACHE_TTL_MILLIS)
                while (cache.size > MAX_CACHE_ENTRIES) cache.remove(cache.keys.first())
                try {
                    persistentCache?.write(key.storageKey, RecommendationCacheEntry(result.books, fetchedAt))
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    // Disk failure must not hide a successful network response.
                }
                result
            } else {
                cached(key)?.copy(status = result.status) ?: result
            }
        }
    }

    private suspend fun cached(key: Key): BookRecommendationResult? {
        val memory = cache[key]
        val stored = if (memory == null) persistentCache?.read(key.storageKey) else null
        val entry = memory ?: stored?.let {
            Cached(BookRecommendationResult(books = it.books), it.fetchedAtMillis + CACHE_TTL_MILLIS)
        } ?: return null
        // Stale disk entries remain useful offline; freshness only controls remote refresh.
        cache[key] = entry
        while (cache.size > MAX_CACHE_ENTRIES) cache.remove(cache.keys.first())
        val age = nowMillis() - (entry.expiresAt - CACHE_TTL_MILLIS)
        return entry.result.copy(servedFromCache = true, isStale = age !in 0 until CACHE_TTL_MILLIS)
    }

    private fun key(seed: BookSummary, limit: Int, exclusions: List<String>): Key? {
        if (!EVENT_ID.matches(seed.id) || limit !in 1..50 || exclusions.size > 100 ||
            exclusions.any { !EVENT_ID.matches(it) }) return null
        return Key(endpoint.trimEnd('/'), seed.id.lowercase(), limit, exclusions.map { it.lowercase() }.distinct().sorted())
    }

    private class OfflineRecommendations : RuntimeException()
    private companion object {
        val EVENT_ID = Regex("^[A-Fa-f0-9]{64}$")
        const val CACHE_TTL_MILLIS = 24 * 60 * 60 * 1_000L
        const val MAX_CACHE_ENTRIES = 20
    }
}

enum class BookRecommendationStatus { COMPLETE, OFFLINE, SEED_UNAVAILABLE, INVALID_REQUEST, UNAVAILABLE }

data class BookRecommendationResult(
    val books: List<BookSummary> = emptyList(),
    val status: BookRecommendationStatus = BookRecommendationStatus.COMPLETE,
    val servedFromCache: Boolean = false,
    val isStale: Boolean = false,
) {
    fun visibleBooks(seedCoordinate: String, savedCoordinates: Set<String>): List<BookSummary> =
        books.filter { it.coordinate != seedCoordinate && it.coordinate !in savedCoordinates }

    val message: String? get() = when (status) {
        BookRecommendationStatus.COMPLETE -> if (isStale) "Showing saved recommendations while they refresh." else null
        BookRecommendationStatus.OFFLINE -> "Recommendations need internet. Available results are saved on this device."
        BookRecommendationStatus.SEED_UNAVAILABLE -> "This edition is not indexed for recommendations yet."
        BookRecommendationStatus.INVALID_REQUEST -> "This book cannot be used for recommendations."
        BookRecommendationStatus.UNAVAILABLE -> "Recommendations are temporarily unavailable. Try again."
    }
}
