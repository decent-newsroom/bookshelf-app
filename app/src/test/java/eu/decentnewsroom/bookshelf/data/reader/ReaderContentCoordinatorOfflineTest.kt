package eu.decentnewsroom.bookshelf.data.reader

import eu.decentnewsroom.bookshelf.data.mercury.MercuryApiClient
import eu.decentnewsroom.bookshelf.data.mercury.MercuryBookRepository
import eu.decentnewsroom.bookshelf.data.rendering.ChapterHtmlCache
import eu.decentnewsroom.bookshelf.data.rendering.PlainTextChapterRenderer
import eu.decentnewsroom.bookshelf.domain.BookChapter
import eu.decentnewsroom.bookshelf.domain.BookDetail
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.domain.ChapterReference
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class ReaderContentCoordinatorOfflineTest {
    @Test
    fun offlineSavedBookUsesSnapshotWithoutCallingMercury() = runBlocking {
        val root = Files.createTempDirectory("coordinator-offline").toFile()
        try {
            val detail = bookDetail()
            val cache = OfflineBookCache(root)
            cache.save(detail)
            val client = OkHttpClient.Builder()
                .protocols(listOf(Protocol.HTTP_1_1))
                .addInterceptor { error("Mercury must not be called while offline") }
                .build()
            val repository = MercuryBookRepository(MercuryApiClient(client, "http://127.0.0.1:1"))
            val coordinator = ReaderContentCoordinator(
                repository = repository,
                offlineBookCache = cache,
                chapterHtmlCache = ChapterHtmlCache(root, PlainTextChapterRenderer()),
                isInternetAvailable = { false },
            )

            assertEquals(detail, coordinator.open(detail.summary, isSaved = true))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun bookDetail(): BookDetail {
        val pubkey = "1".repeat(64)
        val coordinate = "${BookKinds.PUBLICATION_INDEX}:$pubkey:offline"
        val ref = ChapterReference(coordinate, pubkey, "chapter", null, null)
        val summary = BookSummary(
            id = "a".repeat(64), coordinate = coordinate, pubkey = pubkey, identifier = "offline",
            title = "Offline", summary = null, authors = emptyList(), coverImageUrl = null,
            sourceUrl = null, language = null, releaseDate = null, version = null, type = "book",
            topics = emptyList(), relay = null, createdAt = 1, chapterCount = 1, chapterRefs = listOf(ref),
        )
        return BookDetail(summary, listOf(BookChapter(ref, 0, true, "Chapter", null, "cached", null, 1, "cached")), 1, 0, false)
    }
}
