package eu.decentnewsroom.bookshelf.data.reader

import android.content.Context
import eu.decentnewsroom.bookshelf.domain.BookChapter
import eu.decentnewsroom.bookshelf.domain.BookDetail
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.domain.ChapterReference
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

/**
 * Durable snapshots of reader content for books saved on this device.
 *
 * This cache deliberately excludes [eu.decentnewsroom.bookshelf.domain.NostrEvent]
 * (the signed source event is not needed to render a cached book). Entries are
 * bounded so a malformed or unexpectedly large remote book cannot consume all
 * private storage. A later coordinator decides which saved books to retain.
 */
class OfflineBookCache private constructor(
    private val directory: File,
    private val maxEntries: Int,
    private val maxSnapshotBytes: Long,
    private val maxChapters: Int,
) {
    constructor(context: Context) : this(
        directory = File(File(context.applicationContext.filesDir, STORAGE_DIRECTORY), CACHE_DIRECTORY),
        maxEntries = MAX_ENTRIES,
        maxSnapshotBytes = MAX_SNAPSHOT_BYTES,
        maxChapters = MAX_CHAPTERS,
    )

    /** Test and platform-neutral constructor; [cacheRoot] is the app files directory. */
    internal constructor(
        cacheRoot: File,
        cacheDirectoryName: String = CACHE_DIRECTORY,
        maxEntries: Int = MAX_ENTRIES,
        maxSnapshotBytes: Long = MAX_SNAPSHOT_BYTES,
        maxChapters: Int = MAX_CHAPTERS,
    ) : this(
        directory = File(File(cacheRoot, STORAGE_DIRECTORY), cacheDirectoryName),
        maxEntries = maxEntries,
        maxSnapshotBytes = maxSnapshotBytes,
        maxChapters = maxChapters,
    )

    private val mutex = Mutex()

    init {
        require(maxEntries > 0) { "maxEntries must be positive." }
        require(maxSnapshotBytes > 0) { "maxSnapshotBytes must be positive." }
        require(maxChapters > 0) { "maxChapters must be positive." }
    }

    /** Saves a complete reader snapshot. Existing snapshots are replaced atomically. */
    suspend fun save(book: BookDetail) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(book.summary.coordinate.isNotBlank()) { "Book coordinate must not be blank." }
            require(book.chapters.size <= maxChapters) { "Book has too many chapters to cache." }
            val snapshot = Snapshot.from(book)
            val encoded = json.encodeToString(Snapshot.serializer(), snapshot)
            val bytes = encoded.toByteArray(Charsets.UTF_8)
            require(bytes.size.toLong() <= maxSnapshotBytes) { "Book snapshot is too large to cache." }
            directory.mkdirs()
            val destination = fileFor(book.summary.coordinate)
            val temporary = File(directory, destination.name + ".tmp")
            try {
                temporary.writeBytes(bytes)
                runCatching {
                    Files.move(temporary.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                }.recoverCatching {
                    Files.move(temporary.toPath(), destination.toPath(), REPLACE_EXISTING)
                }.getOrElse { throw IllegalStateException("Could not update offline book cache.", it) }
            } finally {
                temporary.delete()
            }
            prune()
        }
    }

    /** Returns a valid cached snapshot, or null for a miss/corrupt entry. */
    /** Alias retained for callers that describe this operation as storing a snapshot. */
    suspend fun store(book: BookDetail) = save(book)

    suspend fun load(bookCoordinate: String): BookDetail? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (bookCoordinate.isBlank()) return@withLock null
            val file = fileFor(bookCoordinate)
            if (!file.isFile || file.length() > maxSnapshotBytes) return@withLock null
            runCatching {
                val snapshot = json.decodeFromString<Snapshot>(file.readText(Charsets.UTF_8))
                if (!snapshot.isSafeFor(bookCoordinate, maxChapters)) return@runCatching null
                file.setLastModified(System.currentTimeMillis())
                snapshot.toBookDetail()
            }.getOrNull()
        }
    }

    suspend fun remove(bookCoordinate: String) = withContext(Dispatchers.IO) {
        mutex.withLock { if (bookCoordinate.isNotBlank()) fileFor(bookCoordinate).delete() }
    }

    suspend fun clear(): OfflineBookCacheStats = withContext(Dispatchers.IO) {
        mutex.withLock {
            directory.listFiles()?.forEach { it.deleteRecursively() }
            readStats()
        }
    }

    suspend fun stats(): OfflineBookCacheStats = withContext(Dispatchers.IO) {
        mutex.withLock { readStats() }
    }

    private fun prune() {
        val files = snapshotFiles().sortedBy(File::lastModified).toMutableList()
        while (files.size > maxEntries) files.removeAt(0).delete()
    }

    private fun readStats(): OfflineBookCacheStats {
        val files = snapshotFiles()
        return OfflineBookCacheStats(files.size, files.sumOf(File::length))
    }

    private fun snapshotFiles(): List<File> = directory.listFiles { file ->
        file.isFile && file.extension == "json"
    }?.toList().orEmpty()

    private fun fileFor(coordinate: String): File = File(directory, "${sha256(coordinate)}.json")

    @Serializable
    private data class Snapshot(
        val version: Int,
        val coordinate: String,
        val summary: BookSummary,
        val chapters: List<CachedChapter>,
        val availableChapterCount: Int,
        val missingChapterCount: Int,
        val truncated: Boolean,
    ) {
        fun toBookDetail() = BookDetail(
            summary = summary,
            chapters = chapters.map(CachedChapter::toBookChapter),
            availableChapterCount = availableChapterCount,
            missingChapterCount = missingChapterCount,
            truncated = truncated,
        )

        fun isSafeFor(expectedCoordinate: String, chapterLimit: Int): Boolean =
            version == CACHE_VERSION && coordinate == expectedCoordinate &&
                summary.coordinate == expectedCoordinate && chapters.size <= chapterLimit &&
                availableChapterCount >= 0 && missingChapterCount >= 0 &&
                chapters.all(CachedChapter::isSafe)

        companion object {
            fun from(book: BookDetail) = Snapshot(
                version = CACHE_VERSION,
                coordinate = book.summary.coordinate,
                summary = book.summary,
                chapters = book.chapters.map(CachedChapter::from),
                availableChapterCount = book.availableChapterCount,
                missingChapterCount = book.missingChapterCount,
                truncated = book.truncated,
            )
        }
    }

    @Serializable
    private data class CachedChapter(
        val reference: ChapterReference,
        val position: Int,
        val available: Boolean,
        val title: String,
        val summary: String?,
        val content: String?,
        val id: String?,
        val createdAt: Long?,
        val renderedHtml: String?,
    ) {
        fun toBookChapter() = BookChapter(
            reference = reference,
            position = position,
            available = available,
            title = title,
            summary = summary,
            content = content,
            id = id,
            createdAt = createdAt,
            renderedHtml = renderedHtml,
        )

        fun isSafe(): Boolean = position >= 0 && title.length <= MAX_TEXT_CHARS &&
            (summary == null || summary.length <= MAX_TEXT_CHARS) &&
            (content == null || content.length <= MAX_CONTENT_CHARS) &&
            (renderedHtml == null || renderedHtml.length <= MAX_CONTENT_CHARS)

        companion object {
            fun from(chapter: BookChapter) = CachedChapter(
                reference = chapter.reference,
                position = chapter.position,
                available = chapter.available,
                title = chapter.title,
                summary = chapter.summary,
                content = chapter.content,
                id = chapter.id,
                createdAt = chapter.createdAt,
                renderedHtml = chapter.renderedHtml,
            )
        }
    }

    private companion object {
        const val STORAGE_DIRECTORY = "bookshelf"
        const val CACHE_DIRECTORY = "reader-cache-v1"
        const val CACHE_VERSION = 1
        const val MAX_ENTRIES = 32 // 32 * 8 MiB keeps the bounded cache under 256 MiB
        const val MAX_CHAPTERS = 500
        const val MAX_SNAPSHOT_BYTES = 8L * 1024 * 1024
        const val MAX_TEXT_CHARS = 1_000_000
        const val MAX_CONTENT_CHARS = 8_000_000
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}

data class OfflineBookCacheStats(
    val entryCount: Int = 0,
    val sizeBytes: Long = 0,
)




