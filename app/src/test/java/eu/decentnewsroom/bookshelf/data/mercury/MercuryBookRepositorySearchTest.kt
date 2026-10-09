package eu.decentnewsroom.bookshelf.data.mercury

import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import com.vitorpamplona.quartz.utils.Secp256k1InstanceKotlin
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.BookReference
import eu.decentnewsroom.bookshelf.domain.ChapterReference
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
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
    fun analyzedSectionHitIsRetainedWithoutInventedLiteralExcerpt() = runBlocking {
        val pubkey = testPubkey(1)
        val coordinate = "30041:$pubkey:chapter"
        val section = eventJson(pubkey, BookKinds.PUBLICATION_CONTENT, listOf(listOf("d", "chapter")), "walking through gardens")
        val parent = publicationEvent(testPubkey(2), "parent", "Parent", "Writer", listOf(coordinate))
        val server = RecordingHttpServer { request ->
            if (request.path.endsWith("/sections/search")) eventListJson(section) else eventListJson(parent)
        }
        server.use {
            val repository = MercuryBookRepository(MercuryApiClient(OkHttpClient(), server.baseUrl))
            val result = repository.search(BookSearchQuery("walked", SearchScope.CHAPTER_CONTENT)).single()
            assertEquals(setOf(MatchProvenance.CHAPTER_TEXT), result.provenance)
            assertEquals(null, result.excerpt)
            assertEquals(coordinate, result.matchedChapterCoordinate)
        }
    }

    @Test
    fun orphanSectionIsSuccessfulEmptyBookResult() = runBlocking {
        val section = eventJson(testPubkey(1), BookKinds.PUBLICATION_CONTENT, listOf(listOf("d", "chapter")), "needle")
        val server = RecordingHttpServer { request ->
            if (request.path.endsWith("/sections/search")) eventListJson(section) else "[]"
        }
        server.use {
            val repository = MercuryBookRepository(MercuryApiClient(OkHttpClient(), server.baseUrl))
            val outcome = repository.searchOutcome(BookSearchQuery("needle", SearchScope.CHAPTER_CONTENT))
            assertEquals(BookSearchStatus.COMPLETE, outcome.status)
            assertTrue(outcome.results.isEmpty())
        }
    }

    @Test
    fun sectionParentFailureKeepsMetadataResultsAsPartial() = runBlocking {
        val section = eventJson(testPubkey(1), BookKinds.PUBLICATION_CONTENT, listOf(listOf("d", "chapter")), "needle")
        val parent = publicationEvent(testPubkey(2), "parent", "Parent", "Writer", emptyList())
        val server = RecordingHttpServer { request ->
            when {
                request.path.endsWith("/sections/search") -> eventListJson(section)
                request.path.endsWith("/publications/search") -> eventListJson(parent)
                else -> TestHttpResponse(503, "Service Unavailable", "")
            }
        }
        server.use {
            val repository = MercuryBookRepository(
                MercuryApiClient(OkHttpClient(), server.baseUrl),
                searchResilience = MercurySearchResilience(MercurySearchRetryConfig(maxAttempts = 1)),
            )
            val outcome = repository.searchOutcome(BookSearchQuery("needle"))
            assertEquals(BookSearchStatus.PARTIAL, outcome.status)
            assertEquals("Parent", outcome.results.single().book.title)
        }
    }

    @Test
    fun sectionParentFailureIsUnavailableWithoutMetadataChannel() = runBlocking {
        val pubkey = testPubkey(1)
        val section = eventJson(pubkey, BookKinds.PUBLICATION_CONTENT, listOf(listOf("d", "chapter")), "needle")
        val server = RecordingHttpServer { request ->
            if (request.path.endsWith("/sections/search")) eventListJson(section)
            else TestHttpResponse(503, "Service Unavailable", "")
        }
        server.use {
            val repository = MercuryBookRepository(
                MercuryApiClient(OkHttpClient(), server.baseUrl),
                searchResilience = MercurySearchResilience(MercurySearchRetryConfig(maxAttempts = 1)),
            )
            val outcome = repository.searchOutcome(BookSearchQuery("needle", SearchScope.CHAPTER_CONTENT))
            assertEquals(BookSearchStatus.UNAVAILABLE, outcome.status)
            assertTrue(outcome.results.isEmpty())
            assertEquals(null, repository.cachedSearchOutcome(BookSearchQuery("needle", SearchScope.CHAPTER_CONTENT)))
        }
    }

    @Test
    fun sectionParentsMustReferenceMatchedCoordinateAndKeepNewestSection() = runBlocking {
        val pubkey = testPubkey(1)
        val coordinate = "30041:$pubkey:chapter"
        val oldSection = eventJson(pubkey, BookKinds.PUBLICATION_CONTENT, listOf(listOf("d", "chapter"), listOf("title", "Old section")), "needle", createdAt = 1)
        val newSection = eventJson(pubkey, BookKinds.PUBLICATION_CONTENT, listOf(listOf("d", "chapter"), listOf("title", "New section")), "needle", createdAt = 2)
        val parent = publicationEvent(testPubkey(2), "parent", "Parent", "Writer", listOf(coordinate))
        val shared = publicationEvent(testPubkey(3), "shared", "Shared", "Writer", listOf(coordinate))
        val unrelated = publicationEvent(testPubkey(4), "other", "Unrelated", "Writer", listOf("30041:$pubkey:other"))
        val server = RecordingHttpServer { request ->
            if (request.path.endsWith("/sections/search")) eventListJson(oldSection, newSection)
            else eventListJson(unrelated, parent, shared)
        }
        server.use {
            val repository = MercuryBookRepository(MercuryApiClient(OkHttpClient(), server.baseUrl))
            val results = repository.search(BookSearchQuery("needle", SearchScope.CHAPTER_CONTENT))
            assertEquals(setOf("Parent", "Shared"), results.map { it.book.title }.toSet())
            assertTrue(results.all { it.matchedChapterTitle == "New section" && it.matchedChapterCoordinate == coordinate })
        }
    }

    @Test
    fun newestMetadataRevisionCannotRetainRemovedChapterTarget() = runBlocking {
        val pubkey = testPubkey(1)
        val coordinate = "30041:$pubkey:chapter"
        val section = eventJson(pubkey, BookKinds.PUBLICATION_CONTENT, listOf(listOf("d", "chapter")), "needle")
        val oldParent = publicationEvent(testPubkey(2), "parent", "Old", "Writer", listOf(coordinate), createdAt = 1)
        val newParent = publicationEvent(testPubkey(2), "parent", "New", "Writer", emptyList(), createdAt = 2)
        val server = RecordingHttpServer { request ->
            when {
                request.path.endsWith("/sections/search") -> eventListJson(section)
                request.path.endsWith("/publications/search") -> eventListJson(newParent)
                else -> eventListJson(oldParent)
            }
        }
        server.use {
            val repository = MercuryBookRepository(MercuryApiClient(OkHttpClient(), server.baseUrl))
            val result = repository.search(BookSearchQuery("needle")).single()
            assertEquals("New", result.book.title)
            assertEquals(null, result.matchedChapterCoordinate)
            assertEquals(null, result.excerpt)
        }
    }

    @Test
    fun offlineSearchUsesOnlyExplicitFreshCacheAccessor() = runBlocking {
        var online = true
        var now = 0L
        val server = RecordingHttpServer { "[]" }
        server.use {
            val repository = MercuryBookRepository(MercuryApiClient(OkHttpClient(), server.baseUrl), nowMillis = { now }, isInternetAvailable = { online })
            val query = BookSearchQuery("needle", SearchScope.CHAPTER_CONTENT)
            assertEquals(BookSearchStatus.COMPLETE, repository.searchOutcome(query).status)
            val requestCount = server.requests.size
            online = false
            assertEquals(BookSearchStatus.COMPLETE, repository.cachedSearchOutcome(query)?.status)
            now = 31_000L
            assertEquals(null, repository.cachedSearchOutcome(query))
            assertEquals(BookSearchStatus.UNAVAILABLE, repository.searchOutcome(query).status)
            assertEquals(requestCount, server.requests.size)
        }
    }

    @Test
    fun preferredBooksApiIsUsedBeforeMercuryFallback() = runBlocking {
        val preferred = RecordingHttpServer { "[]" }
        val fallback = RecordingHttpServer { "[]" }

        preferred.use {
            fallback.use {
                val apiClient = MercuryApiClient(
                    OkHttpClient(),
                    preferred.baseUrl + "/books",
                    listOf(fallback.baseUrl),
                )

                apiClient.searchPublications(MercuryPublicationSearch(title = "Preferred"))

                assertEquals("/books/api/publications/search", preferred.requests.single().path)
                assertTrue(fallback.requests.isEmpty())
            }
        }
    }

    @Test
    fun mercuryFallbackServesWhenPreferredBooksApiReturns5xx() = runBlocking {
        val preferred = RecordingHttpServer { TestHttpResponse(503, "Service Unavailable", "{}") }
        val fallback = RecordingHttpServer { "[]" }

        preferred.use {
            fallback.use {
                val apiClient = MercuryApiClient(
                    OkHttpClient(),
                    preferred.baseUrl + "/books",
                    listOf(fallback.baseUrl),
                )

                apiClient.searchPublications(MercuryPublicationSearch(title = "Fallback"))

                assertEquals(1, preferred.requests.size)
                assertEquals("/api/publications/search", fallback.requests.single().path)
            }
        }
    }

    @Test
    fun apiEndpointDoesNotImplyWebSocketRelay() {
        assertEquals(null, MercuryApiClient(OkHttpClient(), "https://decentnewsroom.com/books").getRelayHint())
    }

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
    fun publicationIndexWithoutChaptersIsReturnedAsALibraryCard() = runBlocking {
        val libraryCard = publicationEvent(
            pubkey = testPubkey(1),
            identifier = "copyrighted-book",
            title = "Copyrighted Book",
            author = "Publisher",
            chapterCoordinates = emptyList(),
        )
        val server = RecordingHttpServer { request ->
            if (request.path == "/api/publications/search") eventListJson(libraryCard) else "[]"
        }

        server.use {
            val repository = MercuryBookRepository(MercuryApiClient(OkHttpClient(), server.baseUrl))

            val result = repository.search(BookSearchQuery("Copyrighted Book")).single().book

            assertEquals("Copyrighted Book", result.title)
            assertEquals(0, result.chapterCount)
            assertTrue(result.chapterRefs.isEmpty())
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
            assertEquals(coordinate, results.single().matchedChapterCoordinate)
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

            assertTrue(runCatching { repository.search("x".repeat(161)) }.exceptionOrNull() is IllegalArgumentException)
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
    fun myBooksLookupMergesReferencedPublicationFromKnownRelays() = runBlocking {
        val pubkey = testPubkey(6)
        val identifier = "non-gutenberg-book"
        val coordinate = "${BookKinds.PUBLICATION_INDEX}:$pubkey:$identifier"
        val relayEvent = NostrEvent(
            id = "relay-event",
            pubkey = pubkey,
            createdAt = 2,
            kind = BookKinds.PUBLICATION_INDEX,
            tags = listOf(
                listOf("d", identifier),
                listOf("title", "Relay book"),
                listOf("author", "Independent publisher"),
                listOf("a", "${BookKinds.PUBLICATION_CONTENT}:$pubkey:chapter-one"),
            ),
        )
        var requestedCoordinates = emptyList<String>()
        val server = RecordingHttpServer { "[]" }

        server.use {
            val repository = MercuryBookRepository(
                apiClient = MercuryApiClient(OkHttpClient(), server.baseUrl),
                publicationIndexRelaySource = PublicationIndexRelaySource { coordinates ->
                    requestedCoordinates = coordinates
                    listOf(relayEvent)
                },
            )

            val results = repository.getMyBooksForReferences(
                listOf(BookReference("a", coordinate, null, null, pubkey)),
            )

            assertEquals(listOf("Relay book"), results.map { it.title })
            assertEquals(listOf(coordinate), requestedCoordinates)
            assertTrue(server.requests.any { it.path == "/api/events/filter" })
        }
    }
    @Test
    fun naddrSearchQueriesRelaySourceForDecodedCoordinate() = runBlocking {
        val naddr = "naddr1qqthqeejxumnsvpdw3ex2ctnw4ex2ttfwdkxzmnyqywhwumn8ghj7mt9wf3h2une94ex2mrp0yhxjmthv9kxgtn9w5pzq0s66re6t57pyfzakaug23ky8t0rm97xuprvt98kq97ddn2pv35sqvzqqqr4tqpmnmyc"
        val query = BookSearchQuery.from(naddr)
        val coordinate = requireNotNull(query.coordinate)
        val parts = coordinate.split(":", limit = 3)
        val relayEvent = NostrEvent(
            id = "naddr-relay-event",
            pubkey = parts[1],
            createdAt = 2,
            kind = BookKinds.PUBLICATION_INDEX,
            tags = listOf(
                listOf("d", parts[2]),
                listOf("title", "Relay naddr book"),
                listOf("author", "Independent publisher"),
                listOf("a", "${BookKinds.PUBLICATION_CONTENT}:${parts[1]}:chapter-one"),
            ),
        )
        var requestedCoordinate: String? = null
        var requestedHints: List<String>? = null
        val server = RecordingHttpServer { "[]" }

        server.use {
            val repository = MercuryBookRepository(
                apiClient = MercuryApiClient(OkHttpClient(), server.baseUrl),
                naddrPublicationIndexRelaySource = NaddrPublicationIndexRelaySource { target, hints ->
                    requestedCoordinate = target
                    requestedHints = hints
                    listOf(relayEvent)
                },
            )

            val results = repository.search(query)

            assertEquals(listOf("Relay naddr book"), results.map { it.book.title })
            assertEquals(coordinate, requestedCoordinate)
            assertEquals(query.naddrRelayHints, requestedHints)
        }
    }

    @Test
    fun naddrIdentityRemainsExactAcrossHttpRelayMappingAndCache() = runBlocking {
        val pubkey = testPubkey(1)
        val identifier = "  édition:été/第三  "
        val coordinate = "${BookKinds.PUBLICATION_INDEX}:$pubkey:$identifier"
        val query = BookSearchQuery.from(NAddress.create(BookKinds.PUBLICATION_INDEX, pubkey, identifier, emptyList()))
        val httpEvent = publicationEvent(pubkey, identifier, "HTTP exact book", "Writer", emptyList())
        val otherBook = publicationEvent(pubkey, identifier.trim(), "Different book", "Writer", emptyList(), createdAt = 9)
        val relayEvent = Json.decodeFromString<NostrEvent>(
            publicationEvent(pubkey, identifier, "Relay exact book", "Writer", emptyList(), createdAt = 2),
        )
        var requestedCoordinate: String? = null
        val server = RecordingHttpServer { eventListJson(httpEvent, otherBook) }

        server.use {
            val api = MercuryApiClient(OkHttpClient(), server.baseUrl)
            // Verify the signed HTTP result independently of the relay result.
            val httpRepository = MercuryBookRepository(api)
            assertEquals(coordinate, httpRepository.search(query).single().book.coordinate)
            assertEquals(identifier, httpRepository.search(query).single().book.identifier)

            val repository = MercuryBookRepository(
                apiClient = api,
                naddrPublicationIndexRelaySource = NaddrPublicationIndexRelaySource { target, _ ->
                    requestedCoordinate = target
                    listOf(relayEvent)
                },
            )
            val result = repository.search(query).single().book
            assertEquals(coordinate, requestedCoordinate)
            assertEquals(coordinate, result.coordinate)
            assertEquals(identifier, result.identifier)
            assertEquals("Relay exact book", result.title)
            assertTrue(server.requests.all { it.body.contains("\"#d\":[\"$identifier\"]") })
            assertEquals(result, repository.cachedSearchOutcome(query)?.results?.single()?.book)
            assertNull(repository.cachedSearchOutcome(query.copy(coordinate = coordinate.trim())))
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
    fun independentPublicationUsesItsImageTagAndChapterRelayHint() = runBlocking {
        val pubkey = testPubkey(4)
        val coverUrl = "https://raw.githubusercontent.com/21-lessons/book/main/cover.jpg"
        val hintedRelay = "wss://thecitadel.nostr1.com"
        val book = publicationEvent(
            pubkey = pubkey,
            identifier = "21-lessons-by-der-gigi-v-1",
            title = "21 Lessons",
            author = "Der Gigi",
            chapterCoordinates = emptyList(),
            extraTags = listOf(
                listOf("image", coverUrl),
                listOf(
                    "a",
                    "${BookKinds.PUBLICATION_CONTENT}:$pubkey:21-lessons-title-page-1-by-der-gigi-v-1",
                    hintedRelay,
                ),
            ),
        )
        val server = RecordingHttpServer { request ->
            if (request.path == "/api/publications/search") eventListJson(book) else "[]"
        }

        server.use {
            val repository = MercuryBookRepository(MercuryApiClient(OkHttpClient(), server.baseUrl))

            val result = repository.search("21 Lessons").single()

            assertEquals(coverUrl, result.coverImageUrl)
            assertEquals(hintedRelay, result.chapterRefs.single().relay)
        }
    }

    @Test
    fun searchReturnsPartialMetadataResultsWhenSectionSearchGets503() = runBlocking {
        val book = publicationEvent(
            pubkey = testPubkey(1),
            identifier = "partial-book",
            title = "Partial Book",
            author = "Writer",
            chapterCoordinates = listOf("${BookKinds.PUBLICATION_CONTENT}:${testPubkey(1)}:chapter-one"),
        )
        val server = RecordingHttpServer { request ->
            when (request.path) {
                "/api/publications/search" -> eventListJson(book)
                "/api/publications/sections/search" ->
                    TestHttpResponse(503, "Service Unavailable", "", mapOf("Retry-After" to "3"))
                else -> "[]"
            }
        }

        server.use {
            val repository = MercuryBookRepository(
                apiClient = MercuryApiClient(OkHttpClient(), server.baseUrl),
                searchResilience = MercurySearchResilience(
                    MercurySearchRetryConfig(maxAttempts = 1, cooldownThreshold = 10),
                ),
            )
            val outcome = repository.searchOutcome(BookSearchQuery.from("partial book"))
            assertEquals(BookSearchStatus.PARTIAL, outcome.status)
            assertEquals(listOf("Partial Book"), outcome.results.map { it.book.title })
            assertEquals(3_000L, outcome.retryAfterMillis)
        }
    }

    @Test
    fun chapterModeWithMetadataPrefixCallsOnlyTheSectionSearch() = runBlocking {
        val server = RecordingHttpServer { "[]" }
        server.use {
            val repository = MercuryBookRepository(MercuryApiClient(OkHttpClient(), server.baseUrl))
            repository.searchOutcome(BookSearchQuery.from("title: hidden needle", SearchScope.CHAPTER_CONTENT))
            assertEquals(listOf("/api/publications/sections/search"), server.requests.map { it.path })
            assertTrue(server.requests.single().body.contains("title: hidden needle"))
        }
    }

    @Test
    fun contentOnlyModeUsesSeparateCacheAndSkipsMetadataMatching() = runBlocking {
        val chapterPubkey = testPubkey(1)
        val chapterCoordinate = "${BookKinds.PUBLICATION_CONTENT}:$chapterPubkey:chapter"
        val metadataBook = publicationEvent(testPubkey(2), "metadata", "Metadata Book", "Writer", emptyList())
        val contentBook = publicationEvent(testPubkey(3), "content", "Content Book", "Writer", listOf(chapterCoordinate))
        val chapter = eventJson(chapterPubkey, BookKinds.PUBLICATION_CONTENT, listOf(listOf("d", "chapter")), "hidden needle")
        val server = RecordingHttpServer { request ->
            when (request.path) {
                "/api/publications/search" -> eventListJson(metadataBook)
                "/api/publications/sections/search" -> eventListJson(chapter)
                "/api/events/filter" -> eventListJson(contentBook)
                else -> "[]"
            }
        }

        server.use {
            val repository = MercuryBookRepository(MercuryApiClient(OkHttpClient(), server.baseUrl))
            val metadataQuery = BookSearchQuery.from("hidden needle", SearchScope.METADATA)
            val contentQuery = BookSearchQuery.from("hidden needle", SearchScope.CHAPTER_CONTENT)

            val metadata = repository.searchOutcome(metadataQuery)
            assertEquals(BookSearchStatus.COMPLETE, metadata.status)
            assertEquals(listOf("Metadata Book"), metadata.results.map { it.book.title })
            assertEquals(listOf("/api/publications/search"), server.requests.map { it.path })
            assertEquals(null, repository.cachedSearchOutcome(contentQuery))

            val contents = repository.searchOutcome(contentQuery)
            assertEquals(BookSearchStatus.COMPLETE, contents.status)
            assertEquals(setOf("Content Book"), contents.results.map { it.book.title }.toSet())
            assertEquals(chapterCoordinate, contents.results.single { it.book.title == "Content Book" }.matchedChapterCoordinate)
            assertEquals(1, server.requests.count { it.path == "/api/publications/search" })
            assertEquals(1, server.requests.count { it.path == "/api/publications/sections/search" })
            assertEquals(1, server.requests.count { it.path == "/api/events/filter" })

            val requestCount = server.requests.size
            assertEquals(metadata, repository.searchOutcome(metadataQuery))
            assertEquals(contents, repository.searchOutcome(contentQuery))
            assertEquals(requestCount, server.requests.size)
        }
    }

    @Test
    fun completeSearchOutcomeIsCachedForRepeatedNormalizedQuery() = runBlocking {
        val book = publicationEvent(
            pubkey = testPubkey(2),
            identifier = "cached-book",
            title = "Cached Book",
            author = "Writer",
            chapterCoordinates = listOf("${BookKinds.PUBLICATION_CONTENT}:${testPubkey(2)}:chapter-one"),
        )
        val server = RecordingHttpServer { request ->
            if (request.path == "/api/publications/search") eventListJson(book) else "[]"
        }

        server.use {
            val repository = MercuryBookRepository(MercuryApiClient(OkHttpClient(), server.baseUrl))
            val first = repository.searchOutcome(BookSearchQuery.from("  cached book  "))
            val second = repository.searchOutcome(BookSearchQuery.from("cached book"))
            assertEquals(BookSearchStatus.COMPLETE, first.status)
            assertEquals(first, second)
            assertEquals(1, server.requests.count { it.path == "/api/publications/search" })
            assertEquals(1, server.requests.count { it.path == "/api/publications/sections/search" })
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
        private val responder: (RecordedHttpRequest) -> Any,
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
                val input = client.getInputStream().buffered()
                // HTTP headers are ASCII, and Content-Length counts body bytes, not UTF-8 characters.
                // Keep one byte stream so a character reader cannot read ahead into the body.
                fun readHeaderLine(): String? {
                    val line = StringBuilder()
                    while (true) {
                        val byte = input.read()
                        if (byte == -1) return line.toString().takeIf(String::isNotEmpty)
                        if (byte == '\n'.code) return line.toString().removeSuffix("\r")
                        line.append(byte.toChar())
                    }
                }
                val requestLine = readHeaderLine() ?: return
                var contentLength = 0
                while (true) {
                    val header = readHeaderLine() ?: break
                    if (header.isEmpty()) {
                        break
                    }
                    if (header.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = header.substringAfter(":").trim().toInt()
                    }
                }

                val bodyBuffer = input.readNBytes(contentLength)
                check(bodyBuffer.size == contentLength) { "Incomplete HTTP request body" }

                val request = RecordedHttpRequest(
                    path = requestLine.split(" ").getOrNull(1)?.substringBefore("?").orEmpty(),
                    body = bodyBuffer.toString(Charsets.UTF_8),
                )
                requests += request

                val response = when (val raw = responder(request)) {
                    is String -> TestHttpResponse(200, "OK", raw)
                    is TestHttpResponse -> raw
                    else -> error("Unsupported test response")
                }
                val body = response.body.toByteArray(Charsets.UTF_8)
                val extraHeaders = response.headers.entries.joinToString(separator = "") {
                    "${it.key}: ${it.value}\r\n"
                }
                val headers =
                    "HTTP/1.1 ${response.statusCode} ${response.reason}\r\n" +
                        "Content-Type: application/json\r\n" +
                        extraHeaders +
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

    private data class TestHttpResponse(
        val statusCode: Int,
        val reason: String,
        val body: String,
        val headers: Map<String, String> = emptyMap(),
    )

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
