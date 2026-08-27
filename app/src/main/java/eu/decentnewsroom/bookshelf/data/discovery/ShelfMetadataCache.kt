package eu.decentnewsroom.bookshelf.data.discovery

import android.content.Context
import eu.decentnewsroom.bookshelf.domain.BookSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

@Serializable
data class CachedShelfBook(val book: BookSummary, val fetchedAtMillis: Long)

class ShelfMetadataCache private constructor(
    private val file: File,
    private val clock: () -> Long,
) {
    constructor(context: Context) : this(
        file = cacheFile(context.applicationContext.cacheDir),
        clock = System::currentTimeMillis,
    )

    internal constructor(
        cacheRoot: File,
        clock: () -> Long = System::currentTimeMillis,
        cacheDirectoryName: String = CACHE_DIRECTORY,
    ) : this(
        file = File(File(cacheRoot, cacheDirectoryName), CACHE_FILE_NAME),
        clock = clock,
    )

    suspend fun read(): Map<String, CachedShelfBook> = withContext(Dispatchers.IO) { readBlocking() }

    suspend fun merge(books: List<BookSummary>) =
        withContext(Dispatchers.IO) {
            val now = clock()
            val next = readBlocking().toMutableMap()
            books.forEach { next[it.coordinate] = CachedShelfBook(it, now) }
            writeBlocking(next)
        }

    fun isFresh(entry: CachedShelfBook, ttlMillis: Long = TTL_MILLIS): Boolean =
        clock() - entry.fetchedAtMillis in 0..ttlMillis

    private fun readBlocking(): Map<String, CachedShelfBook> =
        runCatching {
            if (!file.isFile) {
                return emptyMap()
            }
            json.decodeFromString<CacheFile>(file.readText(Charsets.UTF_8)).books
                .associateBy { it.book.coordinate }
        }.getOrDefault(emptyMap())

    private fun writeBlocking(books: Map<String, CachedShelfBook>) {
        requireNotNull(file.parentFile).mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(json.encodeToString(CacheFile(books.values.toList())), Charsets.UTF_8)
        runCatching {
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }.recoverCatching {
            Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING)
        }.getOrElse {
            temporary.delete()
            throw IllegalStateException("Could not update shelf metadata cache.", it)
        }
    }

    @Serializable
    private data class CacheFile(val books: List<CachedShelfBook>)

    private companion object {
        const val CACHE_DIRECTORY = "shelf-metadata"
        const val CACHE_FILE_NAME = "v1.json"
        const val TTL_MILLIS = 24 * 60 * 60 * 1000L
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun cacheFile(cacheRoot: File): File = File(File(cacheRoot, CACHE_DIRECTORY), CACHE_FILE_NAME)
    }
}