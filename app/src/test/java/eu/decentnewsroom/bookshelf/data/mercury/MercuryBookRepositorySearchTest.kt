package eu.decentnewsroom.bookshelf.data.mercury

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
    @Test
    fun searchUsesPublicationAndSectionEndpoints() = runBlocking {
        val query = "hidden needle"
        val metadataPubkey = "1".repeat(64)
        val sectionPubkey = "2".repeat(64)
        val sectionBookPubkey = "3".repeat(64)
        val sectionCoordinate = "${BookKinds.PUBLICATION_CONTENT}:$sectionPubkey:chapter-one"
        val metadataBook = publicationEvent(
            id = "a".repeat(64),
            pubkey = metadataPubkey,
            identifier = "metadata-book",
            title = "Metadata Book",
            author = "Author Name",
            chapterCoordinates = listOf("${BookKinds.PUBLICATION_CONTENT}:$metadataPubkey:chapter-one"),
        )
        val sectionEvent = eventJson(
            id = "b".repeat(64),
            pubkey = sectionPubkey,
            kind = BookKinds.PUBLICATION_CONTENT,
            tags = listOf(listOf("d", "chapter-one"), listOf("title", "Hidden Chapter")),
            content = "This body contains the hidden needle text.",
        )
        val sectionBook = publicationEvent(
            id = "c".repeat(64),
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
                    it.path == "/api/publications/search" && it.body.contains("\"title\":\"$query\"")
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
    fun publicationReferenceLookupUsesExactDTagAndDoesNotFetchChapters() = runBlocking {
        val pubkey = "6".repeat(64)
        val wantedIdentifier = "pg1-wanted"
        val oldBook = publicationEvent(
            id = "1".repeat(64),
            pubkey = pubkey,
            identifier = wantedIdentifier,
            title = "Old title",
            author = "Author",
            chapterCoordinates = listOf("${BookKinds.PUBLICATION_CONTENT}:$pubkey:chapter-one"),
            createdAt = 1,
        )
        val newBook = publicationEvent(
            id = "2".repeat(64),
            pubkey = pubkey,
            identifier = wantedIdentifier,
            title = "New title",
            author = "Author",
            chapterCoordinates = listOf("${BookKinds.PUBLICATION_CONTENT}:$pubkey:chapter-one"),
            createdAt = 2,
        )
        val unrelated = publicationEvent(
            id = "3".repeat(64),
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
        val pubkey = "4".repeat(64)
        val book = publicationEvent(
            id = "d".repeat(64),
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
        val pubkey = "5".repeat(64)
        val book = publicationEvent(
            id = "e".repeat(64),
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
        id: String,
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
            id = id,
            pubkey = pubkey,
            kind = BookKinds.PUBLICATION_INDEX,
            tags = tags,
            createdAt = createdAt,
        )
    }

    private fun eventJson(
        id: String,
        pubkey: String,
        kind: Int,
        tags: List<List<String>>,
        content: String = "",
        createdAt: Long = 1,
    ): String =
        """
        {
          "id":"$id",
          "pubkey":"$pubkey",
          "created_at":$createdAt,
          "kind":$kind,
          "tags":${tagsJson(tags)},
          "content":"${jsonEscape(content)}",
          "sig":"${"f".repeat(128)}"
        }
        """.trimIndent()

    private fun eventListJson(vararg events: String): String = events.joinToString(prefix = "[", postfix = "]")

    private fun tagsJson(tags: List<List<String>>): String =
        tags.joinToString(prefix = "[", postfix = "]") { tag ->
            tag.joinToString(prefix = "[", postfix = "]") { value -> "\"${jsonEscape(value)}\"" }
        }

    private fun jsonEscape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
