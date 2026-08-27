package eu.decentnewsroom.bookshelf.data.rendering

import android.content.Context
import eu.decentnewsroom.bookshelf.domain.BookChapter
import eu.decentnewsroom.bookshelf.domain.BookDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class ChapterHtmlCacheStats(
    val entryCount: Int = 0,
    val sizeBytes: Long = 0,
)

class ChapterHtmlCache private constructor(
    private val cacheDirectory: File,
    private val renderer: ChapterRenderer,
) {
    constructor(
        context: Context,
        renderer: ChapterRenderer = AsciidoctorChapterRenderer(),
    ) : this(File(context.applicationContext.cacheDir, CACHE_DIRECTORY), renderer)

    internal constructor(
        cacheRoot: File,
        renderer: ChapterRenderer,
        cacheDirectoryName: String = CACHE_DIRECTORY,
    ) : this(File(cacheRoot, cacheDirectoryName), renderer)

    suspend fun renderBook(book: BookDetail): BookDetail =
        withContext(Dispatchers.IO) {
            book.copy(chapters = book.chapters.map { chapter -> renderChapter(chapter) })
        }

    suspend fun stats(): ChapterHtmlCacheStats =
        withContext(Dispatchers.IO) {
            readStats()
        }

    suspend fun clear(): ChapterHtmlCacheStats =
        withContext(Dispatchers.IO) {
            cacheDirectory.listFiles()?.forEach { entry -> entry.deleteRecursively() }
            readStats()
        }

    private suspend fun renderChapter(chapter: BookChapter): BookChapter {
        val source = chapter.content?.takeIf(String::isNotBlank) ?: return chapter
        val htmlFile = File(cacheDirectory, "${cacheKey(chapter, source)}.html")
        val cachedHtml = runCatching { htmlFile.takeIf(File::isFile)?.readText(Charsets.UTF_8) }.getOrNull()
        if (cachedHtml != null) {
            return chapter.copy(
                renderedHtml = cachedHtml,
                renderedHtmlCachePath = htmlFile.absolutePath,
            )
        }

        return runCatching {
            when (val rendered = renderer.render(source)) {
                is RenderedChapter.Html -> {
                    cacheDirectory.mkdirs()
                    htmlFile.writeText(rendered.html, Charsets.UTF_8)
                    chapter.copy(
                        renderedHtml = rendered.html,
                        renderedHtmlCachePath = htmlFile.absolutePath,
                    )
                }

                is RenderedChapter.PlainText -> chapter.copy(content = rendered.text)
            }
        }.getOrDefault(chapter)
    }

    private fun readStats(): ChapterHtmlCacheStats {
        val files =
            cacheDirectory
                .listFiles { file -> file.isFile && file.extension.equals("html", ignoreCase = true) }
                ?.toList()
                .orEmpty()

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
        const val CACHE_VERSION = "asciidoc-kmp-html-v2"
    }
}
