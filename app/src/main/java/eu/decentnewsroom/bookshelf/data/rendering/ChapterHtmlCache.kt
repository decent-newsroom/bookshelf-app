package eu.decentnewsroom.bookshelf.data.rendering

import android.content.Context
import eu.decentnewsroom.bookshelf.domain.BookChapter
import eu.decentnewsroom.bookshelf.domain.BookDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

data class ChapterHtmlCacheStats(
    val entryCount: Int = 0,
    val sizeBytes: Long = 0,
)

class ChapterHtmlCache private constructor(
    private val cacheDirectory: File,
    private val renderer: ChapterRenderer,
    private val limits: Limits,
) {
    constructor(
        context: Context,
        renderer: ChapterRenderer = AsciidoctorChapterRenderer(),
    ) : this(File(context.applicationContext.cacheDir, CACHE_DIRECTORY), renderer, Limits())

    internal constructor(
        cacheRoot: File,
        renderer: ChapterRenderer,
        cacheDirectoryName: String = CACHE_DIRECTORY,
        maxSourceBytes: Int = MAX_SOURCE_BYTES,
        maxEntryBytes: Int = MAX_ENTRY_BYTES,
        maxSizeBytes: Long = MAX_CACHE_SIZE_BYTES,
        maxEntries: Int = MAX_CACHE_ENTRIES,
    ) : this(
        File(cacheRoot, cacheDirectoryName),
        renderer,
        Limits(maxSourceBytes, maxEntryBytes, maxSizeBytes, maxEntries),
    )

    private val mutex = Mutex()

    suspend fun renderBook(book: BookDetail): BookDetail =
        withContext(Dispatchers.IO) {
            book.copy(chapters = book.chapters.map { chapter -> renderChapter(chapter) })
        }

    suspend fun stats(): ChapterHtmlCacheStats =
        withContext(Dispatchers.IO) {
            mutex.withLock { readStats() }
        }

    suspend fun clear(): ChapterHtmlCacheStats =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                cacheDirectory.listFiles()?.forEach { entry -> entry.deleteRecursively() }
                readStats()
            }
        }

    private suspend fun renderChapter(chapter: BookChapter): BookChapter {
        val source = chapter.content?.takeIf(String::isNotBlank) ?: return chapter
        if (source.toByteArray(Charsets.UTF_8).size > limits.maxSourceBytes) return chapter

        val htmlFile = File(cacheDirectory, "${cacheKey(chapter, source)}.html")
        val cachedHtml = mutex.withLock { readCachedHtml(htmlFile) }
        if (cachedHtml != null) {
            return chapter.copy(
                renderedHtml = cachedHtml,
                renderedHtmlCachePath = htmlFile.absolutePath,
            )
        }

        return runCatching {
            when (val rendered = renderer.render(source)) {
                is RenderedChapter.Html -> {
                    val renderedBytes = rendered.html.toByteArray(Charsets.UTF_8)
                    if (
                        renderedBytes.size > limits.maxEntryBytes ||
                        renderedBytes.size.toLong() > limits.maxSizeBytes
                    ) {
                        chapter
                    } else {
                        mutex.withLock { writeAtomicallyAndPrune(htmlFile, renderedBytes) }
                        chapter.copy(
                            renderedHtml = rendered.html,
                            renderedHtmlCachePath = htmlFile.absolutePath,
                        )
                    }
                }

                is RenderedChapter.PlainText -> chapter.copy(content = rendered.text)
            }
        }.getOrDefault(chapter)
    }

    private fun readCachedHtml(htmlFile: File): String? {
        if (!htmlFile.isFile || htmlFile.length() > limits.maxEntryBytes) return null
        return runCatching {
            htmlFile.setLastModified(System.currentTimeMillis())
            htmlFile.readText(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun writeAtomicallyAndPrune(
        htmlFile: File,
        renderedBytes: ByteArray,
    ) {
        cacheDirectory.mkdirs()
        val temporaryFile = File(cacheDirectory, "${htmlFile.name}.tmp")
        try {
            temporaryFile.writeBytes(renderedBytes)
            runCatching {
                Files.move(temporaryFile.toPath(), htmlFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            }.recoverCatching {
                Files.move(temporaryFile.toPath(), htmlFile.toPath(), REPLACE_EXISTING)
            }.getOrThrow()
            htmlFile.setLastModified(System.currentTimeMillis())
            prune()
        } finally {
            temporaryFile.delete()
        }
    }

    private fun prune() {
        val files = htmlFiles().sortedBy(File::lastModified).toMutableList()
        var sizeBytes = files.sumOf(File::length)
        while (files.size > limits.maxEntries || sizeBytes > limits.maxSizeBytes) {
            if (files.isEmpty()) break
            val oldest = files.removeAt(0)
            val oldestSize = oldest.length()
            if (oldest.delete()) sizeBytes -= oldestSize
        }
    }

    private fun htmlFiles(): List<File> =
        cacheDirectory
            .listFiles { file -> file.isFile && file.extension.equals("html", ignoreCase = true) }
            ?.toList()
            .orEmpty()

    private fun readStats(): ChapterHtmlCacheStats {
        val files = htmlFiles()

        return ChapterHtmlCacheStats(
            entryCount = files.size,
            sizeBytes = files.sumOf(File::length),
        )
    }

    private fun cacheKey(chapter: BookChapter, source: String): String =
        sha256(
            listOf(
                CACHE_VERSION,
                chapter.reference.coordinate,
                chapter.id.orEmpty(),
                chapter.createdAt?.toString().orEmpty(),
                source,
            ).joinToString(separator = "\n"),
        )

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val CACHE_DIRECTORY = "chapter-html"
        const val CACHE_VERSION = "asciidoc-kmp-fragment-v3"
        const val MAX_SOURCE_BYTES = 2 * 1024 * 1024
        const val MAX_ENTRY_BYTES = 4 * 1024 * 1024
        const val MAX_CACHE_SIZE_BYTES = 64L * 1024 * 1024
        const val MAX_CACHE_ENTRIES = 1_000
    }

    private data class Limits(
        val maxSourceBytes: Int = MAX_SOURCE_BYTES,
        val maxEntryBytes: Int = MAX_ENTRY_BYTES,
        val maxSizeBytes: Long = MAX_CACHE_SIZE_BYTES,
        val maxEntries: Int = MAX_CACHE_ENTRIES,
    ) {
        init {
            require(maxSourceBytes > 0)
            require(maxEntryBytes > 0)
            require(maxSizeBytes > 0)
            require(maxEntries > 0)
        }
    }
}
