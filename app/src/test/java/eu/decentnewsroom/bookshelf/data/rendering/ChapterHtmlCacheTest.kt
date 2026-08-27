package eu.decentnewsroom.bookshelf.data.rendering

import eu.decentnewsroom.bookshelf.domain.BookChapter
import eu.decentnewsroom.bookshelf.domain.BookDetail
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.domain.ChapterReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChapterHtmlCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun renderBookWritesRenderedHtmlToCacheAndReusesIt() = runBlocking {
        val renderer = RecordingRenderer("<p><strong>Hello</strong></p>")
        val cache = ChapterHtmlCache(temporaryFolder.root, renderer)

        val first = cache.renderBook(bookDetail(content = "*Hello*"))
        val firstChapter = first.chapters.single()
        val cachePath = requireNotNull(firstChapter.renderedHtmlCachePath)

        assertEquals("<p><strong>Hello</strong></p>", firstChapter.renderedHtml)
        assertTrue(File(cachePath).isFile)
        assertEquals(1, renderer.renderCount)
        assertEquals(ChapterHtmlCacheStats(entryCount = 1, sizeBytes = File(cachePath).length()), cache.stats())

        val second = cache.renderBook(bookDetail(content = "*Hello*"))

        assertEquals("<p><strong>Hello</strong></p>", second.chapters.single().renderedHtml)
        assertEquals(cachePath, second.chapters.single().renderedHtmlCachePath)
        assertEquals(1, renderer.renderCount)
    }

    @Test
    fun clearDeletesCachedHtmlFiles() = runBlocking {
        val renderer = RecordingRenderer("<p>Hello</p>")
        val cache = ChapterHtmlCache(temporaryFolder.root, renderer)
        val rendered = cache.renderBook(bookDetail(content = "Hello"))
        val cachePath = requireNotNull(rendered.chapters.single().renderedHtmlCachePath)

        val stats = cache.clear()

        assertEquals(ChapterHtmlCacheStats(), stats)
        assertFalse(File(cachePath).exists())
    }

    @Test
    fun oversizedSourceIsNotRenderedOrCached() = runBlocking {
        val renderer = RecordingRenderer("<p>Hello</p>")
        val cache = ChapterHtmlCache(temporaryFolder.root, renderer, maxSourceBytes = 4)

        val result = cache.renderBook(bookDetail(content = "Hello"))

        assertEquals("Hello", result.chapters.single().content)
        assertNull(result.chapters.single().renderedHtml)
        assertEquals(0, renderer.renderCount)
        assertEquals(ChapterHtmlCacheStats(), cache.stats())
    }

    @Test
    fun oversizedRenderedHtmlIsNotExposedOrCached() = runBlocking {
        val renderer = RecordingRenderer("123456789")
        val cache = ChapterHtmlCache(temporaryFolder.root, renderer, maxEntryBytes = 8)

        val result = cache.renderBook(bookDetail(content = "Hello"))

        assertNull(result.chapters.single().renderedHtml)
        assertNull(result.chapters.single().renderedHtmlCachePath)
        assertEquals(1, renderer.renderCount)
        assertEquals(ChapterHtmlCacheStats(), cache.stats())
    }

    @Test
    fun cachePrunesLeastRecentlyUsedEntries() = runBlocking {
        val renderer = RecordingRenderer("<p>Hello</p>")
        val cache = ChapterHtmlCache(temporaryFolder.root, renderer, maxEntries = 1)
        val first = cache.renderBook(bookDetail(content = "First"))
        val firstFile = File(requireNotNull(first.chapters.single().renderedHtmlCachePath))
        firstFile.setLastModified(1L)

        val second = cache.renderBook(bookDetail(content = "Second"))
        val secondFile = File(requireNotNull(second.chapters.single().renderedHtmlCachePath))

        assertFalse(firstFile.exists())
        assertTrue(secondFile.isFile)
        assertEquals(1, cache.stats().entryCount)
        assertTrue(
            secondFile.parentFile?.listFiles()?.none { it.extension.equals("tmp", ignoreCase = true) } == true,
        )
    }

    private class RecordingRenderer(private val html: String) : ChapterRenderer {
        var renderCount = 0
            private set

        override suspend fun render(source: String): RenderedChapter {
            renderCount += 1
            return RenderedChapter.Html(html)
        }
    }

    private fun bookDetail(content: String): BookDetail =
        BookDetail(
            summary = bookSummary(),
            chapters = listOf(bookChapter(content)),
            availableChapterCount = 1,
            missingChapterCount = 0,
            truncated = false,
        )

    private fun bookSummary(): BookSummary {
        val pubkey = "1".repeat(64)
        return BookSummary(
            id = "a".repeat(64),
            coordinate = "${BookKinds.PUBLICATION_INDEX}:$pubkey:a-book",
            pubkey = pubkey,
            identifier = "a-book",
            title = "A Book",
            summary = null,
            authors = listOf("An Author"),
            coverImageUrl = null,
            sourceUrl = null,
            language = null,
            releaseDate = null,
            version = null,
            type = "book",
            topics = emptyList(),
            relay = null,
            createdAt = 1,
            chapterCount = 1,
            chapterRefs = emptyList(),
        )
    }

    private fun bookChapter(content: String): BookChapter {
        val pubkey = "2".repeat(64)
        return BookChapter(
            reference = ChapterReference(
                coordinate = "${BookKinds.PUBLICATION_CONTENT}:$pubkey:chapter-one",
                pubkey = pubkey,
                identifier = "chapter-one",
                relay = null,
                eventId = "b".repeat(64),
            ),
            position = 1,
            available = true,
            title = "Chapter One",
            summary = null,
            content = content,
            id = "b".repeat(64),
            createdAt = 1,
        )
    }
}
