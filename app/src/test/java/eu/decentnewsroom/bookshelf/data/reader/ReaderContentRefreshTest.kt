package eu.decentnewsroom.bookshelf.data.reader

import eu.decentnewsroom.bookshelf.data.mercury.ChapterEventSource
import eu.decentnewsroom.bookshelf.data.mercury.MercuryApiClient
import eu.decentnewsroom.bookshelf.data.mercury.MercuryBookRepository
import eu.decentnewsroom.bookshelf.data.mercury.PublicationIndexRelaySource
import eu.decentnewsroom.bookshelf.data.rendering.ChapterHtmlCache
import eu.decentnewsroom.bookshelf.data.rendering.ChapterRenderer
import eu.decentnewsroom.bookshelf.data.rendering.RenderedChapter
import eu.decentnewsroom.bookshelf.domain.ChapterReference
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ReaderContentRefreshTest {
    private val author = "a".repeat(64)
    private fun coordinate(kind: Int, name: String) = "$kind:$author:$name"
    private fun index(name: String, revision: Long, children: List<List<String>>) = NostrEvent(
        id = revision.toString(16).padStart(64, '0'), pubkey = author, kind = 30040,
        createdAt = revision, tags = listOf(listOf("d", name), listOf("title", "$name $revision")) + children,
    )
    private fun chapter(revision: Long) = NostrEvent(
        id = (revision + 100).toString(16).padStart(64, '0'), pubkey = author, kind = 30041,
        createdAt = revision, tags = listOf(listOf("d", "leaf")), content = "Text $revision",
    )

    @Test
    fun refreshReplacesRootNestedIndexAndPinnedChapterAndPersistsRenderedCopy() = runBlocking {
        val root = Files.createTempDirectory("refresh-book").toFile()
        try {
            val leafCoordinate = coordinate(30041, "leaf")
            val oldRoot = index("root", 1, listOf(listOf("a", leafCoordinate)))
            val newRoot = index("root", 3, listOf(listOf("a", coordinate(30040, "part"), "", "f".repeat(64))))
            val newPart = index("part", 4, listOf(listOf("a", leafCoordinate, "", chapter(1).id)))
            var events = listOf(oldRoot)
            var body: NostrEvent? = chapter(1)
            var cancelChapters = false
            var chapterRequests = emptyList<ChapterReference>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("[]".toResponseBody()).build()
            }.build()
            val repository = MercuryBookRepository(MercuryApiClient(client, "https://example.test"),
                chapterEventSource = object : ChapterEventSource {
                    override suspend fun fetchChapters(references: List<ChapterReference>): List<NostrEvent> {
                        if (cancelChapters) throw CancellationException("Dismissed")
                        chapterRequests = references
                        return listOfNotNull(body)
                    }
                },
                publicationIndexRelaySource = PublicationIndexRelaySource { coordinates ->
                    events.filter { coordinate(30040, it.tags.first()[1]) in coordinates }
                })
            val cache = OfflineBookCache(root)
            val renderer = object : ChapterRenderer {
                override suspend fun render(source: String) = RenderedChapter.Html("<p>$source</p>")
            }
            val coordinator = ReaderContentCoordinator(repository, cache, ChapterHtmlCache(root, renderer)) { true }
            val saved = requireNotNull(repository.mapIndexEvent(oldRoot))
            coordinator.open(saved, isSaved = true)
            events = listOf(oldRoot, newRoot, newPart)
            body = chapter(5)

            val refreshed = coordinator.refresh(saved)

            assertEquals(newRoot.id, refreshed.summary.id)
            assertEquals(newPart.id, refreshed.outline.first().eventId)
            assertTrue(chapterRequests.all { it.eventId == null })
            assertEquals(chapter(5).id, refreshed.chapters.single().id)
            assertEquals("<p>Text 5</p>", cache.load(saved.coordinate)?.chapters?.single()?.renderedHtml)

            // An incomplete current structure must not replace the complete download.
            events = listOf(index("root", 6, listOf(listOf("a", coordinate(30040, "missing")))))
            assertTrue(runCatching { coordinator.refresh(saved) }.isFailure)
            assertEquals(newRoot.id, cache.load(saved.coordinate)?.summary?.id)

            // A complete but older chapter revision must also preserve the download.
            events = listOf(newRoot, newPart)
            body = chapter(2)
            assertTrue(runCatching { coordinator.refresh(saved) }.isFailure)
            assertEquals(chapter(5).id, cache.load(saved.coordinate)?.chapters?.single()?.id)

            // A missing chapter cannot silently borrow its old body and report success.
            body = null
            assertTrue(runCatching { coordinator.refresh(saved) }.isFailure)
            assertEquals(chapter(5).id, cache.load(saved.coordinate)?.chapters?.single()?.id)

            body = chapter(7)
            val tooSmall = ReaderContentCoordinator(repository, OfflineBookCache(root, maxSnapshotBytes = 1),
                ChapterHtmlCache(root, renderer)) { true }
            assertTrue(runCatching { tooSmall.refresh(saved) }.isFailure)
            assertEquals(chapter(5).id, cache.load(saved.coordinate)?.chapters?.single()?.id)

            cancelChapters = true
            assertTrue(runCatching { coordinator.refresh(saved) }.exceptionOrNull() is CancellationException)
            assertEquals(chapter(5).id, cache.load(saved.coordinate)?.chapters?.single()?.id)

            val offline = ReaderContentCoordinator(repository, cache, ChapterHtmlCache(root, renderer)) { false }
            assertTrue(runCatching { offline.refresh(saved) }.isFailure)
            assertEquals(newRoot.id, offline.open(saved, isSaved = true).summary.id)
        } finally {
            root.deleteRecursively()
        }
    }
}
