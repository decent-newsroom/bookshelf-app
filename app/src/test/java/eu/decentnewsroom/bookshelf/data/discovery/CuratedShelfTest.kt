package eu.decentnewsroom.bookshelf.data.discovery

import eu.decentnewsroom.bookshelf.data.mercury.MercuryApiClient
import eu.decentnewsroom.bookshelf.data.mercury.MercuryBookRepository
import eu.decentnewsroom.bookshelf.domain.BookSummary
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class CuratedShelfTest {
    @Test
    fun catalogNaddrsDecodeToPublicationCoordinatesInOrder() {
        assertEquals(
            listOf(9, 10, 10, 10, 7, 8, 8, 10),
            CuratedShelfCatalog.shelves.map { it.publicationNaddrs.size },
        )

        val all = CuratedShelfCatalog.shelves.flatMap { it.publicationNaddrs }
        assertEquals(72, all.size)
        all.forEach { naddr ->
            val reference = NaddrPublicationReferenceDecoder.decode(naddr)
            assertNotNull(naddr, reference)
            assertTrue(reference!!.coordinate!!.startsWith("30040:"))
        }

        assertEquals(
            "30040:3e1ad0f3a5d3c12245db7788546c43ade3d97c6e046c594f6017cd6cd4164690:" +
                "pg27780-treasure-island",
            NaddrPublicationReferenceDecoder.decode(all.first())?.coordinate,
        )
        assertNull(NaddrPublicationReferenceDecoder.decode("not-an-naddr"))
    }

    @Test
    fun cacheRoundTripsFreshAndStaleBookSummary() = runBlocking {
        val root = Files.createTempDirectory("shelf-cache").toFile()
        try {
            var now = 1_000L
            val cache = ShelfMetadataCache(root, clock = { now })
            val book = testBook()

            cache.merge(listOf(book))

            val entry = cache.read().getValue(book.coordinate)
            assertEquals(book, entry.book)
            assertTrue(cache.isFresh(entry))
            assertFalse(File(root, "shelf-metadata/v1.json.tmp").exists())

            now += 25 * 60 * 60 * 1_000L
            assertFalse(cache.isFresh(entry))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptCacheIsTreatedAsEmpty() = runBlocking {
        val root = Files.createTempDirectory("shelf-cache-corrupt").toFile()
        try {
            val cacheFile = File(root, "shelf-metadata/v1.json")
            requireNotNull(cacheFile.parentFile).mkdirs()
            cacheFile.writeText("not json")

            assertTrue(ShelfMetadataCache(root).read().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun staleCacheIsAvailableBeforeRefreshAndSurvivesNetworkFailure() = runBlocking {
        val root = Files.createTempDirectory("shelf-cache-stale").toFile()
        try {
            var now = 1_000L
            val cache = ShelfMetadataCache(root, clock = { now })
            val book = testBook()
            cache.merge(listOf(book))
            now += 25 * 60 * 60 * 1_000L

            val catalog = listOf(
                CuratedShelfSpec(
                    id = "adventure",
                    title = "Adventure",
                    publicationNaddrs = listOf(CuratedShelfCatalog.shelves.first().publicationNaddrs.first()),
                ),
            )
            val httpClient =
                OkHttpClient.Builder()
                    .connectTimeout(100, TimeUnit.MILLISECONDS)
                    .readTimeout(100, TimeUnit.MILLISECONDS)
                    .build()
            val repository =
                CuratedShelfRepository(
                    bookRepository = MercuryBookRepository(MercuryApiClient(httpClient, "http://127.0.0.1:1")),
                    cache = cache,
                    catalog = catalog,
                )

            val cached = repository.loadCached()
            assertEquals(listOf(book), cached.shelves.single().books)
            assertTrue(cached.needsRefresh)

            val failedRefresh = repository.refresh()
            assertEquals(listOf(book), failedRefresh.shelves.single().books)
            assertNotNull(failedRefresh.error)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun testBook(): BookSummary {
        val pubkey = "3e1ad0f3a5d3c12245db7788546c43ade3d97c6e046c594f6017cd6cd4164690"
        return BookSummary(
            id = "a".repeat(64),
            coordinate = "30040:$pubkey:pg27780-treasure-island",
            pubkey = pubkey,
            identifier = "pg27780-treasure-island",
            title = "Treasure Island",
            summary = null,
            authors = listOf("Robert Louis Stevenson"),
            coverImageUrl = null,
            sourceUrl = null,
            language = "en",
            releaseDate = null,
            version = null,
            type = "book",
            topics = emptyList(),
            relay = null,
            createdAt = 1L,
            chapterCount = 1,
            chapterRefs = emptyList(),
        )
    }
}