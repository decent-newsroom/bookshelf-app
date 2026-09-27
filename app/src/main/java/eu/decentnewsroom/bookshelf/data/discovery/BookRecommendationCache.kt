package eu.decentnewsroom.bookshelf.data.discovery

import android.content.Context
import eu.decentnewsroom.bookshelf.domain.BookSummary
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
import java.security.MessageDigest

data class BookRecommendationCacheStats(val entryCount: Int = 0, val sizeBytes: Long = 0)

@Serializable
internal data class RecommendationCacheEntry(val books: List<BookSummary>, val fetchedAtMillis: Long)

/** A bounded summary-only cache, independent of reader content, ratings, and signed outboxes. */
class BookRecommendationCache internal constructor(
    cacheRoot: File,
    private val maxEntries: Int = 20,
    private val maxSizeBytes: Long = 8L * 1024 * 1024,
) {
    constructor(context: Context) : this(context.applicationContext.cacheDir)

    private val directory = File(cacheRoot, "book-recommendations/v1")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    internal suspend fun read(key: String): RecommendationCacheEntry? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = file(key)
            if (!file.isFile || file.length() > MAX_ENTRY_BYTES) return@withLock null
            runCatching { json.decodeFromString<RecommendationCacheEntry>(file.readText(Charsets.UTF_8)) }
                .getOrNull()?.takeIf { it.books.size <= 50 }
        }
    }

    internal suspend fun write(key: String, entry: RecommendationCacheEntry) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val bytes = json.encodeToString(entry).toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_ENTRY_BYTES || bytes.size > maxSizeBytes) return@withLock
            check(directory.isDirectory || directory.mkdirs()) { "Could not create recommendation cache." }
            val destination = file(key)
            val temporary = File(directory, "${destination.name}.tmp")
            try {
                temporary.writeBytes(bytes)
                runCatching { Files.move(temporary.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
                    .recoverCatching { Files.move(temporary.toPath(), destination.toPath(), REPLACE_EXISTING) }.getOrThrow()
                destination.setLastModified(entry.fetchedAtMillis)
                val files = entries().sortedBy(File::lastModified).toMutableList()
                var size = files.sumOf(File::length)
                while (files.size > maxEntries || size > maxSizeBytes) {
                    val oldest = files.removeAt(0)
                    val length = oldest.length()
                    check(oldest.delete()) { "Could not prune recommendation cache." }
                    size -= length
                }
            } finally {
                temporary.delete()
            }
        }
    }

    suspend fun stats(): BookRecommendationCacheStats = withContext(Dispatchers.IO) {
        mutex.withLock { statsBlocking() }
    }

    internal suspend fun clear(): BookRecommendationCacheStats = withContext(Dispatchers.IO) {
        mutex.withLock {
            directory.listFiles()?.filter(File::isFile)?.forEach {
                check(it.delete()) { "Could not clear recommendation cache." }
            }
            statsBlocking()
        }
    }

    private fun entries() = directory.listFiles { file -> file.isFile && file.extension == "json" }?.toList().orEmpty()
    private fun statsBlocking() = entries().let { BookRecommendationCacheStats(it.size, it.sumOf(File::length)) }
    private fun file(key: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.json")
    }

    private companion object { const val MAX_ENTRY_BYTES = 1024 * 1024 }
}
