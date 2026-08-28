package eu.decentnewsroom.bookshelf.data.mercury

import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.utils.Secp256k1InstanceKotlin
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.BookReference
import eu.decentnewsroom.bookshelf.domain.ChapterReference
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MercuryBookRepositorySearchTest {
    private val signingKeys = (1..6).associate { marker -> testPubkey(marker) to ByteArray(32) { marker.toByte() } }

    @Test
    fun searchUsesPublicationAndSectionEndpoints() = runBlocking {
        val query = "hidden needle"
        val metadataPubkey = testPubkey(1)
        val sectionPubkey = testPubkey(2)
        val sectionBookPubkey = testPubkey(3)
        val sectionCoordinate = "${BookKinds.PUBLICATION_CONTENT}:$sectionPubkey:chapter-one"
        val metadataBook = publicationEvent(
            pubkey = metadataPubkey,
            identifier = "metadata-book",
            title = "Metadata Book",
            author = "Author Name",
            chapterCoordinates = listOf("${BookKinds.PUBLICATION_CONTENT}:$metadataPubkey:chapter-one"),
        )
        val sectionEvent = eventJson(
            pubkey = sectionPubkey,
            kind = BookKinds.PUBLICATION_CONTENT,
            tags = listOf(listOf("d", "chapter-one"), listOf("title", "Hidden Chapter")),
            content = "This body contains the hidden needle text.",
        )
        val sectionBook = publicationEvent(
            pubkey = sectionBookPubkey,
            identifier = "section-book",
            title = "Section Book",
            author = "Other Author",
            chapterCoordinates = listOf(sectionCoordinate),
        )
        val server = RecordingHttpServer { request ->
            when (request.path) {
                "/api/publications/search" -> {
                    if (request.body.contains("\"q\":\"$query\"")) {
                        eventListJson(metadataBook)
                    } else {
                        "[]"
                    }
                }
                "/api/publications/sections/search" -> eventListJson(sectionEvent)
                "/api/events/filter" -> eventListJson(sectionBook)
                else -> "[]"
            }
        }

        server.use {
            val repository = MercuryBookRepository(MercuryApiClient(OkHttpClient(), server.baseUrl))

            val results = repository.search(query)
            val requests = server.requests.toList()

            assertEquals(listOf("Metadata Book", "Section Book"), results.map { it.title })
            assertTrue(
                requests.any {
                    it.path == "/api/publications/search" && it.body.contains("\"q\":\"$query\"")
                },
            )
            assertTrue(
                requests.any {
                    it.path == "/api/publications/sections/search" && it.body.contains("\"q\":\"$query\"")
                },
            )
            assertTrue(
                requests.any {
                    it.path == "/api/events/filter" && it.body.contains("\"#a\":[\"$sectionCoordinate\"]")
                },
            )
        }
    }

    @Test
    fun typedFreeTextUsesOneMetadataAndOneEligibleSectionRequest() = runBlocking {
        val metadataBook = publicationEvent(
            pubkey = testPubkey(1),
            identifier = "metadata-book",
            title = "Metadata Book",
            author = "Author Name",
            chapterCoordinates = listOf("${BookKinds.PUBLICATION_CONTENT}:${testPubkey(1)}:chapter-one"),
        )
        val sectionPubkey = testPubkey(2)
        val sectionBook = publicationEvent(
            pubkey = testPubkey(3),
            identifier = "section-book",
            title = "Section Book",
            author = "Other Author",
            chapterCoordinates = listOf("${BookKinds.PUBLICATION_CONTENT}:$sectionPubkey:chapter-one"),
        )
        val sectionEvent = eventJson(
            pubkey = sectionPubkey,
            kind = BookKinds.PUBLICATION_CONTENT,
            tags = listOf(listOf("d", "chapter-one"), listOf("title", "Hidden Chapter")),
            content = "Prefix hidden needle suffix.",
        )
        val server = RecordingHttpServer { request ->
            when (request.path) {
                "/api/publications/search" -> eventListJson(metadataBook)
                "/api/publications/sections/search" -> eventListJson(sectionEvent)
                "/api/events/filter" -> eventListJson(sectionBook)
                else -> "[]"
            }
        }
        var chapterSourceCalled = false
        server.use {
            val repository = MercuryBookRepository(
                apiClient = MercuryApiClient(OkHttpClient(), server.baseUrl),
                chapterEventSource = object : ChapterEventSource {
                    override suspend fun fetchChapters(references: List<ChapterReference>): List<NostrEvent> {
                        chapterSourceCalled = true
                        return emptyList()
                    }
                },
            )
            val results = repository.search(BookSearchQuery("hidden needle"))
            val requests = server.requests.toList()
            assertEquals(3, requests.size)
            assertEquals(1, requests.count { it.path == "/api/publications/search" })
            assertEquals(1, requests.count { it.path == "/api/publications/sections/search" })
            assertEquals(1, requests.count { it.path == "/api/events/filter" })
            assertTrue(requests.single { it.path == "/api/publications/search" }.body.contains("hidden needle"))
            assertFalse(requests.single { it.path == "/api/publications/search" }.body.contains("title"))
            assertEquals("Prefix hidden needle suffix.", results.last().excerpt)
            assertTrue(results.last().provenance.contains(MatchProvenance.CHAPTER_BODY))
            assertFalse(chapterSourceCalled)
        }
    }

    @Test
    fun structuredDSearchUsesMercuryDFieldOnly() = runBlocking {
        val server = RecordingHttpServer { "[]" }
        server.use {
            val repository = MercuryBookRepository(MercuryApiClient(OkHttpClient(), server.baseUrl))
            repository.search(BookSearchQuery.from("d:book-slug"))
            val request = server.requests.single()
            assertEquals("/api/publications/search", request.path)
            assertTrue(request.body.contains("book-slug"))
            assertFalse(request.body.contains("identifier"))
            assertFalse(server.requests.any { it.path == "/api/publications/sections/search" })
        }
    }

    @Test
    fun wrongKindsFromSearchEndpointsAreRejectedAtDecodeBoundary() = runBlocking {
        val pubkey = testPubkey(4)
        val wrongIndex = eventJson(pubkey, BookKinds.PUBLICATION_CONTENT, listOf(listOf("d", "chapter")))
        val wrongSection = eventJson(pubkey, BookKinds.PUBLICATION_INDEX, listOf(listOf("d", "book"), listOf("title", "Book"), listOf("a", "${BookKinds.PUBLICATION_CONTENT}:$pubkey:chapter")))
        val server = RecordingHttpServer { request ->
            if (request.path == "/api/publications/search") eventListJson(wrongIndex) else eventListJson(wrongSection)
        }
        server.use {
            val repository = MercuryBookRepository(MercuryApiClient(OkHttpClient(), server.baseUrl))
            assertTrue(repository.search(BookSearchQuery("hidden needle")).isEmpty())
        }
    }

    @Test
    fun exactChapterCoordinatePreservesColonInIdentifier() = runBlocking {
        val pubkey = testPubkey(5)
        val identifier = "chapter:part:one"
        val coordinate = "${BookKinds.PUBLICATION_CONTENT}:$pubkey:$identifier"
        val chapter = eventJson(pubkey, BookKinds.PUBLICATION_CONTENT, listOf(listOf("d", identifier), listOf("title", "Exact Chapter")), "Exact body")
        val book = publicationEvent(
            pubkey = testPubkey(6),
            identifier = "book",
            title = "Exact Book",
            author = "Author",
            chapterCoordinates = listOf(coordinate),
        )
        val server = RecordingHttpServer { request ->
            if (request.path == "/api/events/filter" && request.body.contains("#d") && request.body.contains("chapter:part:one")) {
                eventListJson(chapter)
            } else if (request.path == "/api/events/filter") {
                eventListJson(book)
            } else {
                "[]"
            }
        }
        server.use {
            val repository = MercuryBookRepository(MercuryApiClient(OkHttpClient(), server.baseUrl))
            val results = repository.search(BookSearchQuery(coordinate = coordinate))
            val chapterRequest = server.requests.first { it.body.contains("#d") && it.body.contains("chapter:part:one") }
            assertTrue(chapterRequest.body.contains("chapter:part:one"))
            assertEquals("Exact Chapter", results.single().matchedChapterTitle)
        }
    }

    @Test
    fun exactPublicationEventIdReturnsOnlyTheRequestedIndex() = runBlocking {
        val book = publicationEvent(testPubkey(1), "exact-book", "Exact Book", "Author", listOf(BookKinds.PUBLICATION_CONTENT.toString() + ":" + testPubkey(1) + ":chapter"))
        val id = book.substringAfter("\"id\":\"").substringBefore("\"")
        val server = RecordingHttpServer { request -> if (request.path == "/api/events/" + id) book else "[]" }
        server.use {
            val repository = MercuryBookRepository(MercuryApiClient(OkHttpClient(), server.baseUrl))
            val results = repository.search(BookSearchQuery(eventId = id))
            assertEquals("Exact Book", results.single().book.title)
            assertTrue(server.requests.single().path == "/api/events/" + id)
        }
    }

    @Test
    fun exactChapterEventIdResolvesBackToItsPublication() = runBlocking {
        val pubkey = testPubkey(2)
        val chapter = eventJson(pubkey, BookKinds.PUBLICATION_CONTENT, listOf(listOf("d", "chapter"), listOf("title", "Exact Chapter")), "Exact body")
        val chapterId = chapter.substringAfter("\"id\":\"").substringBefore("\"")
        val coordinate = BookKinds.PUBLICATION_CONTENT.toString() + ":" + pubkey + ":chapter"
        val book = publicationEvent(testPubkey(3), "book", "Resolved Book", "Author", listOf(coordinate))
        val server = RecordingHttpServer { request ->
            when {
                request.path == "/api/events/" + chapterId -> chapter
                request.path == "/api/events/filter" -> eventListJson(book)
                else -> "[]"
            }
        }
        server.use {
            val repository = MercuryBookRepository(MercuryApiClient(OkHttpClient(), server.baseUrl))
            val results = repository.search(BookSearchQuery(eventId = chapterId))
            assertEquals("Resolved Book", results.single().book.title)
            assertEquals("Exact Chapter", results.single().matchedChapterTitle)
        }
    }

    @Test
    fun quotedPhrasesRequireContiguousTextWhileUnquotedTermsMayBeSeparated() = runBlocking {
        val chapterPubkey = testPubkey(4)
        val chapterCoordinate = BookKinds.PUBLICATION_CONTENT.toString() + ":" + chapterPubkey + ":chapter"
        val chapter = eventJson(chapterPubkey, BookKinds.PUBLICATION_CONTENT, listOf(listOf("d", "chapter"), listOf("title", "Chapter")), "hidden words between needle")
        val book = publicationEvent(testPubkey(5), "book", "Book", "Author", listOf(chapterCoordinate))
        val server = RecordingHttpServer { request ->
            if (request.path == "/api/publications/sections/search") eventListJson(chapter)
            else if (request.path == "/api/events/filter") eventListJson(book)
            else "[]"
        }
        server.use {
            val repository = MercuryBookRepository(MercuryApiClient(OkHttpClient(), server.baseUrl))
            val separated = repository.search(BookSearchQuery("hidden needle"))
            val phrase = repository.search(BookSearchQuery("\"hidden needle\""))
            assertEquals("hidden words between needle", separated.single().excerpt)
            assertEquals(null, phrase.singleOrNull()?.excerpt)
        }
    }

    @Test
    fun reciprocalRankFusionDedupesOverlapAndRewardsBothChannels() = runBlocking {
        val overlapPubkey = testPubkey(1)
        val overlapCoordinate = BookKinds.PUBLICATION_CONTENT.toString() + ":" + overlapPubkey + ":overlap-chapter"
        val overlap = publicationEvent(overlapPubkey, "overlap", "Needle Overlap", "Author", listOf(overlapCoordinate))
        val metadataPubkey = testPubkey(2)
        val metadataCoordinate = BookKinds.PUBLICATION_CONTENT.toString() + ":" + metadataPubkey + ":metadata-chapter"
        val metadataOnly = publicationEvent(metadataPubkey, "metadata", "Needle Metadata", "Author", listOf(metadataCoordinate))
        val sectionPubkey = testPubkey(3)
        val sectionCoordinate = BookKinds.PUBLICATION_CONTENT.toString() + ":" + sectionPubkey + ":section-chapter"
        val sectionOnly = publicationEvent(sectionPubkey, "section", "Section Only", "Author", listOf(sectionCoordinate))
        val section = eventJson(sectionPubkey, BookKinds.PUBLICATION_CONTENT, listOf(listOf("d", "section-chapter"), listOf("title", "Section")), "needle appears, then overlap appears")
        val overlapSection = eventJson(overlapPubkey, BookKinds.PUBLICATION_CONTENT, listOf(listOf("d", "overlap-chapter"), listOf("title", "Overlap")), "needle appears, then overlap appears")
        val server = RecordingHttpServer { request ->
            when (request.path) {
                "/api/publications/search" -> eventListJson(overlap, metadataOnly)
                "/api/publications/sections/search" -> eventListJson(overlapSection, section)
                "/api/events/filter" -> eventListJson(overlap, sectionOnly)
                else -> "[]"
            }
        }
        server.use {
            val repository = MercuryBookRepository(MercuryApiClient(OkHttpClient(), server.baseUrl))
            val results = repository.search(BookSearchQuery("needle overlap"))
            assertEquals(3, results.size)
            assertEquals(overlapPubkey, results.first().book.pubkey)
            assertEquals(0, results.first().rank)
            assertEquals(1, results.count { it.book.coordinate == results.first().book.coordinate })
            assertTrue(results.first().provenance.contains(MatchProvenance.TITLE))
            assertTrue(results.first().provenance.contains(MatchProvenance.CHAPTER_BODY))
        }
    }

    @Test
    fun fieldScopedAuthorSearchDoesNotSearchSectionBodies() = runBlocking {
        val server = RecordingHttpServer { "[]" }

        server.use {
            val repository = MercuryBookRepository(MercuryApiClient(OkHttpClient(), server.baseUrl))

            repository.search("author: Austen")
            val requests = server.requests.toList()

            assertTrue(
                requests.any {
                    it.path == "/api/publications/search" && it.body.contains("\"author\":\"Austen\"")
                },
            )
            assertFalse(requests.any { it.path == "/api/publications/sections/search" })
        }
    }

    @Test
    fun overlongSearchIsRejectedBeforeNetworkAccess() = runBlocking {
        val server = RecordingHttpServer { "[]" }

        server.use {
            val repository = MercuryBookRepository(MercuryApiClient(OkHttpClient(), server.baseUrl))

            assertTrue(repository.search("x".repeat(257)).isEmpty())
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun publicationReferenceLookupUsesExactDTagAndDoesNotFetchChapters() = runBlocking {
        val pubkey = testPubkey(6)
        val wantedIdentifier = "pg1-wanted"
        val oldBook = publicationEvent(
            pubkey = pubkey,
            identifier = wantedIdentifier,
            title = "Old title",
            author = "Author",
            chapterCoordinates = listOf("${BookKinds.PUBLICATION_CONTENT}:$pubkey:chapter-one"),
            createdAt = 1,
        )
        val newBook = publicationEvent(
            pubkey = pubkey,
            identifier = wantedIdentifier,
            title = "New title",
            author = "Author",
            chapterCoordinates = listOf("${BookKinds.PUBLICATION_CONTENT}:$pubkey:chapter-one"),
            createdAt = 2,
        )
        val unrelated = publicationEvent(
            pubkey = pubkey,
            identifier = "unrelated",
            title = "Unrelated",
            author = "Author",
            chapterCoordinates = listOf("${BookKinds.PUBLICATION_CONTENT}:$pubkey:unrelated-chapter"),
        )
        val server = RecordingHttpServer { request ->
            if (request.path == "/api/events/filter") eventListJson(unrelated, oldBook, newBook) else "[]"
        }
        var chapterSourceCalled = false

        server.use {
            val repository =
                MercuryBookRepository(
                    apiClient = MercuryApiClient(OkHttpClient(), server.baseUrl),
                    chapterEventSource =
                        object : ChapterEventSource {
                            override suspend fun fetchChapters(references: List<ChapterReference>): List<NostrEvent> {
                                chapterSourceCalled = true
                                return emptyList()
                            }
                        },
                )
            val coordinate = "${BookKinds.PUBLICATION_INDEX}:$pubkey:$wantedIdentifier"

            val results =
                repository.getBooksForReferences(
                    listOf(
                        BookReference(
                            type = "a",
                            coordinate = coordinate,
                            relay = null,
                            eventId = null,
                            pubkey = pubkey,
                        ),
                    ),
                )

            assertEquals(listOf("New title"), results.map { it.title })
            assertFalse(chapterSourceCalled)
            val filterRequests = server.requests.filter { it.path == "/api/events/filter" }
            assertEquals(1, filterRequests.size)
            assertTrue(filterRequests.single().body.contains("\"authors\":[\"$pubkey\"]"))
            assertTrue(filterRequests.single().body.contains("\"#d\":[\"$wantedIdentifier\"]"))
            assertTrue(filterRequests.single().body.contains("\"kinds\":[${BookKinds.PUBLICATION_INDEX}]"))
        }
    }
    @Test
    fun searchInfersGutenbergCoverFromSourceMetadata() = runBlocking {
        val pubkey = testPubkey(4)
        val book = publicationEvent(
            pubkey = pubkey,
            identifier = "pg74359-domestic-medicine",
            title = "Domestic medicine",
            author = "William Buchan",
            chapterCoordinates = listOf("${BookKinds.PUBLICATION_CONTENT}:$pubkey:pg74359-chapter-1-domestic-medicine"),
            extraTags = listOf(listOf("s", "https://www.gutenberg.org/ebooks/74359")),
        )
        val server = RecordingHttpServer { request ->
            if (request.path == "/api/publications/search") eventListJson(book) else "[]"
        }

        server.use {
            val repository = MercuryBookRepository(MercuryApiClient(OkHttpClient(), server.baseUrl))

            val results = repository.search("Domestic medicine")

            assertEquals("https://www.gutenberg.org/cache/epub/74359/pg74359.cover.medium.jpg", results.single().coverImageUrl)
        }
    }

    @Test
    fun searchInfersGutenbergCoverFromPublicationIdentifier() = runBlocking {
        val pubkey = testPubkey(5)
        val book = publicationEvent(
            pubkey = pubkey,
            identifier = "pg65238-an-example-book",
            title = "An Example Book",
            author = "Writer Name",
            chapterCoordinates = listOf("${BookKinds.PUBLICATION_CONTENT}:$pubkey:pg65238-chapter-1-an-example-book"),
        )
        val server = RecordingHttpServer { request ->
            if (request.path == "/api/publications/search") eventListJson(book) else "[]"
        }

        server.use {
            val repository = MercuryBookRepository(MercuryApiClient(OkHttpClient(), server.baseUrl))

            val results = repository.search("An Example Book")

            assertEquals("https://www.gutenberg.org/cache/epub/65238/pg65238.cover.medium.jpg", results.single().coverImageUrl)
        }
    }

    private class RecordingHttpServer(
        private val responder: (RecordedHttpRequest) -> String,
    ) : Closeable {
        private val closed = AtomicBoolean(false)
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val executor = Executors.newSingleThreadExecutor()
        val requests = Collections.synchronizedList(mutableListOf<RecordedHttpRequest>())
        val baseUrl: String = "http://${socket.inetAddress.hostAddress}:${socket.localPort}"

        init {
            executor.execute(::acceptRequests)
        }

        override fun close() {
            closed.set(true)
            socket.close()
            executor.shutdownNow()
            executor.awaitTermination(1, TimeUnit.SECONDS)
        }

        private fun acceptRequests() {
            while (!closed.get()) {
                val connection =
                    try {
                        socket.accept()
                    } catch (exception: SocketException) {
                        if (closed.get()) {
                            return
                        }
                        throw exception
                    }

                handle(connection)
            }
        }

        private fun handle(connection: Socket) {
            connection.use { client ->
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                var contentLength = 0
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) {
                        break
                    }
                    if (header.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = header.substringAfter(":").trim().toInt()
                    }
                }

                val bodyBuffer = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val count = reader.read(bodyBuffer, read, contentLength - read)
                    if (count == -1) {
                        break
                    }
                    read += count
                }

                val request = RecordedHttpRequest(
                    path = requestLine.split(" ").getOrNull(1)?.substringBefore("?").orEmpty(),
                    body = String(bodyBuffer, 0, read),
                )
                requests += request

                val body = responder(request).toByteArray(Charsets.UTF_8)
                val headers =
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n" +
                        "\r\n"
                val output = client.getOutputStream()
                output.write(headers.toByteArray(Charsets.US_ASCII))
                output.write(body)
                output.flush()
            }
        }
    }

    private data class RecordedHttpRequest(
        val path: String,
        val body: String,
    )

    private fun publicationEvent(
        pubkey: String,
        identifier: String,
        title: String,
        author: String,
        chapterCoordinates: List<String>,
        extraTags: List<List<String>> = emptyList(),
        createdAt: Long = 1,
    ): String {
        val tags =
            listOf(
                listOf("d", identifier),
                listOf("title", title),
                listOf("author", author),
            ) + extraTags + chapterCoordinates.map { listOf("a", it) }

        return eventJson(
            pubkey = pubkey,
            kind = BookKinds.PUBLICATION_INDEX,
            tags = tags,
            createdAt = createdAt,
        )
    }

    private fun eventJson(
        pubkey: String,
        kind: Int,
        tags: List<List<String>>,
        content: String = "",
        createdAt: Long = 1,
    ): String {
        val quartzTags = tags.map { it.toTypedArray() }.toTypedArray()
        val id = EventHasher.hashId(pubkey, createdAt, kind, quartzTags, content)
        val privateKey = requireNotNull(signingKeys[pubkey])
        val signature = Secp256k1InstanceKotlin.signSchnorr(id.hexBytes(), privateKey, ByteArray(32)).toHex()
        return """
        {
          "id":"$id",
          "pubkey":"$pubkey",
          "created_at":$createdAt,
          "kind":$kind,
          "tags":${tagsJson(tags)},
          "content":"${jsonEscape(content)}",
          "sig":"$signature"
        }
        """.trimIndent()
    }

    private fun eventListJson(vararg events: String): String = events.joinToString(prefix = "[", postfix = "]")

    private fun tagsJson(tags: List<List<String>>): String =
        tags.joinToString(prefix = "[", postfix = "]") { tag ->
            tag.joinToString(prefix = "[", postfix = "]") { value -> "\"${jsonEscape(value)}\"" }
        }

    private fun jsonEscape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun testPubkey(marker: Int): String =
        Secp256k1InstanceKotlin
            .compressedPubKeyFor(ByteArray(32) { marker.toByte() })
            .copyOfRange(1, 33)
            .toHex()

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun String.hexBytes(): ByteArray =
        chunked(2).map { pair -> pair.toInt(16).toByte() }.toByteArray()
}
