package eu.decentnewsroom.bookshelf.data.ratings

import android.content.Context
import eu.decentnewsroom.bookshelf.data.nostr.NostrEventContext
import eu.decentnewsroom.bookshelf.data.nostr.NostrEventVerifier
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Private, durable storage for verified rating events, independent from chapter HTML cache. */
class BookRatingCache private constructor(
    private val file: File,
    private val clock: () -> Long,
    private val maxEntries: Int,
) {
    constructor(context: Context) : this(
        file = File(File(context.applicationContext.cacheDir, CACHE_DIRECTORY), CACHE_FILE_NAME),
        clock = System::currentTimeMillis,
        maxEntries = MAX_ENTRIES,
    )

    internal constructor(
        cacheRoot: File,
        clock: () -> Long = System::currentTimeMillis,
        cacheDirectoryName: String = CACHE_DIRECTORY,
        maxEntries: Int = MAX_ENTRIES,
    ) : this(File(File(cacheRoot, cacheDirectoryName), CACHE_FILE_NAME), clock, maxEntries)

    private val mutex = Mutex()

    init { require(maxEntries > 0) { "maxEntries must be positive." } }

    suspend fun ratingsFor(bookCoordinate: String): List<BookRating> = withContext(Dispatchers.IO) {
        mutex.withLock {
            readFile().entries.asSequence()
                .filter { it.bookCoordinate == bookCoordinate }
                .mapNotNull { it.event.toAcceptedRatingOrNull() }
                .toList()
        }
    }

    suspend fun allRatings(): List<BookRating> = withContext(Dispatchers.IO) {
        mutex.withLock { readFile().entries.mapNotNull { it.event.toAcceptedRatingOrNull() } }
    }

    /** Writes only events that remain signature-verified and R1-valid at the persistence boundary. */
    suspend fun merge(events: Iterable<NostrEvent>): BookRatingCacheStats = withContext(Dispatchers.IO) {
        mutex.withLock {
            val accepted = events.mapNotNull { event ->
                event.toAcceptedRatingOrNull()?.let { CachedRatingEvent(event, it.bookCoordinate, clock()) }
            }
            if (accepted.isEmpty()) return@withLock readStats(readFile())
            val next = readFile().entries.associateBy { it.event.id }.toMutableMap()
            accepted.forEach { next[it.event.id] = it }
            val cacheFile = CacheFile(
                lastSuccessfulSyncAtMillis = clock(),
                entries = next.values
                    .sortedWith(compareByDescending<CachedRatingEvent> { it.cachedAtMillis }.thenByDescending { it.event.id })
                    .take(maxEntries),
            )
            writeFile(cacheFile)
            readStats(cacheFile)
        }
    }

    suspend fun stats(): BookRatingCacheStats = withContext(Dispatchers.IO) { mutex.withLock { readStats(readFile()) } }

    suspend fun clear(): BookRatingCacheStats = withContext(Dispatchers.IO) {
        mutex.withLock {
            file.delete()
            file.parentFile?.takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
            BookRatingCacheStats()
        }
    }

    private fun NostrEvent.toAcceptedRatingOrNull(): BookRating? {
        if (NostrEventVerifier.verify(this, context = NostrEventContext(expectedKind = BookKinds.RATING)) == null) return null
        return (BookRatingEventParser.parse(this) as? BookRatingParseResult.Accepted)?.rating
    }

    private fun readFile(): CacheFile = runCatching {
        if (!file.isFile) return@runCatching CacheFile()
        json.decodeFromString<CacheFile>(file.readText(Charsets.UTF_8))
    }.getOrDefault(CacheFile())

    private fun writeFile(cacheFile: CacheFile) {
        val parent = requireNotNull(file.parentFile)
        parent.mkdirs()
        val temporary = File(parent, "${file.name}.tmp")
        temporary.writeText(json.encodeToString(CacheFile.serializer(), cacheFile), Charsets.UTF_8)
        runCatching { Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
            .recoverCatching { Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING) }
            .getOrElse {
                temporary.delete()
                throw IllegalStateException("Could not update book rating cache.", it)
            }
    }

    private fun readStats(cacheFile: CacheFile) = BookRatingCacheStats(
        entryCount = cacheFile.entries.size,
        sizeBytes = file.takeIf(File::isFile)?.length() ?: 0L,
        lastSuccessfulSyncAtMillis = cacheFile.lastSuccessfulSyncAtMillis,
    )

    @Serializable private data class CacheFile(
        val lastSuccessfulSyncAtMillis: Long? = null,
        val entries: List<CachedRatingEvent> = emptyList(),
    )
    @Serializable private data class CachedRatingEvent(
        val event: NostrEvent,
        val bookCoordinate: String,
        val cachedAtMillis: Long,
    )

    private companion object {
        const val CACHE_DIRECTORY = "book-ratings/v1"
        const val CACHE_FILE_NAME = "events.json"
        const val MAX_ENTRIES = 5_000
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}

public data class BookRatingCacheStats(
    val entryCount: Int = 0,
    val sizeBytes: Long = 0,
    val lastSuccessfulSyncAtMillis: Long? = null,
)
