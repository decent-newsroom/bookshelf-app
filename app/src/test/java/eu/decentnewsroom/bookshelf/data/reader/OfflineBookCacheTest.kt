package eu.decentnewsroom.bookshelf.data.reader

import eu.decentnewsroom.bookshelf.domain.BookChapter
import eu.decentnewsroom.bookshelf.domain.BookDetail
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.domain.ChapterReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OfflineBookCacheTest {
    @Test
    fun legacyFiveHundredSectionPrefixRemainsReadableWithoutClaimingBookEnd() = runBlocking {
        val root = Files.createTempDirectory("reader-cache-legacy-cap").toFile()
        try {
            val cache = OfflineBookCache(root)
            val book = bookWithSections(500)
            cache.store(book)
            removeKnownStreamFlag(root)

            val loaded = requireNotNull(cache.load(book.summary.coordinate))
            assertEquals(500, loaded.chapters.size)
            assertEquals("<p>cached</p>", loaded.chapters.first().renderedHtml)
            assertTrue(loaded.truncated)
            assertTrue(loaded.summary.sectionStreamKnown.not())

            cache.store(book)
            val refreshed = requireNotNull(cache.load(book.summary.coordinate))
            assertTrue(refreshed.summary.sectionStreamKnown)
            assertTrue(refreshed.truncated.not())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun legacyShortBookKeepsKnownStreamCompatibility() = runBlocking {
        val root = Files.createTempDirectory("reader-cache-legacy-short").toFile()
        try {
            val cache = OfflineBookCache(root)
            val book = bookWithSections(499)
            cache.store(book)
            removeKnownStreamFlag(root)

            val loaded = requireNotNull(cache.load(book.summary.coordinate))
            assertTrue(loaded.summary.sectionStreamKnown)
            assertTrue(loaded.truncated.not())
            assertEquals(499, loaded.chapters.size)
        } finally {
            root.deleteRecursively()
        }
    }

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

    private fun bookWithSections(count: Int): BookDetail {
        val original = bookDetail()
        val reference = original.summary.chapterRefs.single()
        val references = (0 until count).map { index -> reference.copy(
            coordinate = "30041:${reference.pubkey}:chapter-$index", identifier = "chapter-$index",
        ) }
        return original.copy(
            summary = original.summary.copy(chapterCount = count, chapterRefs = references),
            chapters = references.mapIndexed { index, ref -> original.chapters.single().copy(reference = ref, position = index) },
            availableChapterCount = count,
        )
    }

    /** Recreate the pre-tracking v1 wire shape, including its absent default field. */
    private fun removeKnownStreamFlag(root: File) {
        val entry = root.resolve("bookshelf/reader-cache-v1").listFiles()!!.single()
        val encoded = Json.parseToJsonElement(entry.readText()).jsonObject
        val legacySummary = JsonObject(encoded.getValue("summary").jsonObject - "sectionStreamKnown")
        entry.writeText(JsonObject(encoded + ("summary" to legacySummary)).toString())
    }
}

