package eu.decentnewsroom.bookshelf.data.reader

import eu.decentnewsroom.bookshelf.domain.BookChapter
import eu.decentnewsroom.bookshelf.domain.BookDetail
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.domain.ChapterReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OfflineBookCacheTest {
    @Test
    fun roundTripOmitsSourceEventAndPreservesRenderedReaderContent() = runBlocking {
        val root = Files.createTempDirectory("reader-cache").toFile()
        try {
            val book = bookDetail()
            val cache = OfflineBookCache(root)

            cache.store(book)

            val loaded = cache.load(book.summary.coordinate)
            assertEquals(book.summary, loaded?.summary)
            assertEquals(book.chapters.size, loaded?.chapters?.size)
            assertEquals("<p>cached</p>", loaded?.chapters?.single()?.renderedHtml)
            assertNull(loaded?.chapters?.single()?.sourceEvent)
            assertEquals(1, cache.stats().entryCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptDataIsAnUnreadableCacheMiss() = runBlocking {
        val root = Files.createTempDirectory("reader-cache-corrupt").toFile()
        try {
            val cache = OfflineBookCache(root)
            val coordinate = bookDetail().summary.coordinate
            cache.store(bookDetail())
            val entry = root.resolve("bookshelf/reader-cache-v1").listFiles()!!.single()
            entry.writeText("not json")
            assertNull(cache.load(coordinate))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun removeAndClearAreIndependentAndAtomicTemporaryFilesDoNotCount() = runBlocking {
        val root = Files.createTempDirectory("reader-cache-clear").toFile()
        try {
            val cache = OfflineBookCache(root)
            val book = bookDetail()
            cache.store(book)
            val directory = root.resolve("bookshelf/reader-cache-v1")
            File(directory, "unrelated.tmp").writeText("temporary")
            assertEquals(1, cache.stats().entryCount)
            cache.remove(book.summary.coordinate)
            assertNull(cache.load(book.summary.coordinate))
            cache.store(book)
            cache.clear()
            assertEquals(0, cache.stats().entryCount)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun bookDetail(): BookDetail {
        val pubkey = "1".repeat(64)
        val coordinate = "${BookKinds.PUBLICATION_INDEX}:$pubkey:offline"
        val reference = ChapterReference(coordinate, pubkey, "chapter-1", null, "event")
        val summary = BookSummary(
            id = "a".repeat(64), coordinate = coordinate, pubkey = pubkey, identifier = "offline",
            title = "Offline", summary = "Summary", authors = listOf("Author"), coverImageUrl = null,
            sourceUrl = null, language = "en", releaseDate = null, version = null, type = "book",
            topics = emptyList(), relay = null, createdAt = 1, chapterCount = 1, chapterRefs = listOf(reference),
        )
        return BookDetail(
            summary = summary,
            chapters = listOf(BookChapter(reference, 0, true, "Chapter", null, "source", "id", 1, "<p>cached</p>")),
            availableChapterCount = 1,
            missingChapterCount = 0,
            truncated = false,
        )
    }
}

