package eu.decentnewsroom.bookshelf.data.highlights

import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.utils.Secp256k1InstanceKotlin
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import eu.decentnewsroom.bookshelf.data.nostr.RelayPublishOutcome
import eu.decentnewsroom.bookshelf.data.nostr.BookshelfRelaySync
import eu.decentnewsroom.bookshelf.data.nostr.PublishReport
import eu.decentnewsroom.bookshelf.data.nostr.RelayPublishOutcomeType
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.lang.reflect.Proxy

class HighlightOutboxTest {
    private val createdAt = 1_700_000_000L
    private val privateKey = ByteArray(32) { 7 }
    private val pubkey = Secp256k1InstanceKotlin.compressedPubKeyFor(privateKey).copyOfRange(1, 33).toHex()

    @Test
    fun persistsBothSignedEventsAndThePrivateHighlightAssociation() = runBlocking {
        val directory = Files.createTempDirectory("highlight-outbox-test").toFile()
        val file = File(directory, "highlight-outbox-v1.json")
        val chapter = chapter()
        val highlight = highlight(chapter)

        val queued = HighlightOutbox(file).enqueue(highlight, chapter, localHighlightId = "private-42")
        val restored = HighlightOutbox(file).entries().single()

        assertEquals(highlight, queued.event)
        assertEquals(chapter, restored.chapterEvent)
        assertEquals("private-42", restored.localHighlightId)
        assertTrue(file.isFile)
    }

    @Test
    fun rejectsAHighlightWhoseTagsDoNotLinkToTheExactChapter() = runBlocking {
        val directory = Files.createTempDirectory("highlight-outbox-test").toFile()
        val chapter = chapter()
        val unlinked = signed(
            kind = 9802,
            tags = listOf(listOf("a", "${BookKinds.PUBLICATION_CONTENT}:$pubkey:chapter-one")),
            content = "Quoted passage",
        )

        val failure = runCatching { HighlightOutbox(File(directory, "outbox.json")).enqueue(unlinked, chapter) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun corruptQueueIsSurfacedWithoutReplacingTheFile() = runBlocking {
        val directory = Files.createTempDirectory("highlight-outbox-test").toFile()
        val file = File(directory, "highlight-outbox-v1.json").apply { writeText("not json") }

        val failure = runCatching { HighlightOutbox(file).entries() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("not json", file.readText())
    }

    @Test
    fun duplicateAcknowledgementCountsAsDeliveredForImmutableEvents() {
        assertTrue(
            RelayPublishOutcome("wss://relay.example", RelayPublishOutcomeType.REJECTED, "duplicate: already have this event")
                .isDurablyAccepted(),
        )
        assertTrue(
            RelayPublishOutcome("wss://relay.example", RelayPublishOutcomeType.REJECTED, "already have this event")
                .isDurablyAccepted(),
        )
        assertTrue(
            !RelayPublishOutcome("wss://relay.example", RelayPublishOutcomeType.REJECTED, "invalid: duplicate tag")
                .isDurablyAccepted(),
        )
    }

    @Test
    fun publishedHighlightRemainsPendingForOtherRelaysAndRetryKeepsSignedIds() = runBlocking {
        val file = File(Files.createTempDirectory("highlight-outbox-test").toFile(), "outbox.json")
        val outbox = HighlightOutbox(file)
        val chapter = chapter()
        val highlight = highlight(chapter)
        val routes = listOf("wss://first.example", "wss://second.example").map { requireNotNull(RelayUrlNormalizer.normalizeOrNull(it)).url }
        var rejectHighlight = true
        val sent = mutableListOf<Pair<String, List<String>>>()
        val dispatcher = dispatcher(outbox, routes) { event, destinations ->
            sent += event.id to destinations.toList()
            val outcomes = destinations.map { relay ->
                if (rejectHighlight && event.id == highlight.id && relay == routes.last()) {
                    RelayPublishOutcome(relay, RelayPublishOutcomeType.REJECTED, "blocked: writes disabled")
                } else {
                    RelayPublishOutcome(relay, RelayPublishOutcomeType.REJECTED, "duplicate: already have this event")
                }
            }
            PublishReport(0, outcomes.size, event.id, outcomes)
        }

        dispatcher.enqueueAndTryDeliver(highlight, chapter)
        val pending = HighlightOutbox(file).pending().single()
        assertTrue(pending.remote.getValue(routes.first()).isComplete)
        assertEquals(HighlightDeliveryState.ACCEPTED, pending.remote.getValue(routes.last()).chapter)
        assertEquals("blocked: writes disabled", pending.remote.getValue(routes.last()).highlightFailure)
        assertEquals("blocked: writes disabled", pending.lastFailure)

        rejectHighlight = false
        sent.clear()
        val revision = outbox.changes.value
        dispatcher.syncPending(force = true)

        assertEquals(listOf(highlight.id to listOf(routes.last())), sent)
        assertTrue(outbox.changes.value > revision)
        assertTrue(HighlightOutbox(file).pending().isEmpty())
        assertEquals(null, HighlightOutbox(file).entries().single().remote.getValue(routes.last()).highlightFailure)
    }

    @Test
    fun resolvedRemoteRouteMovedToLocalDoesNotRemainPendingForever() = runBlocking {
        val file = File(Files.createTempDirectory("highlight-outbox-test").toFile(), "outbox.json")
        val outbox = HighlightOutbox(file)
        val chapter = chapter()
        val highlight = highlight(chapter)
        val relay = requireNotNull(RelayUrlNormalizer.normalizeOrNull("wss://only.example")).url
        outbox.enqueue(highlight, chapter)
        outbox.update(highlight.id) {
            it.copy(remoteRoutesResolved = true, remote = mapOf(relay to HighlightPairDelivery()))
        }
        val dispatcher = dispatcher(outbox, listOf(relay), localRelay = relay) { event, routes ->
            PublishReport(routes.size, routes.size, event.id, routes.map {
                RelayPublishOutcome(it, RelayPublishOutcomeType.ACCEPTED)
            })
        }

        dispatcher.syncPending(force = true)

        val restored = HighlightOutbox(file).entries().single()
        assertTrue(restored.remote.isEmpty())
        assertTrue(restored.local.getValue(relay).isComplete)
        assertTrue(restored.isComplete)
        assertTrue(HighlightOutbox(file).pending().isEmpty())
        // Local-only delivery must still wait for initial remote route discovery.
        assertTrue(!restored.copy(remoteRoutesResolved = false).isComplete)
        assertTrue(!restored.copy(local = emptyMap()).isComplete)
    }

    @Test
    fun completionNotificationAllowsObserversToReadTheDurablyClearedPendingCount() = runBlocking {
        val file = File(Files.createTempDirectory("highlight-outbox-test").toFile(), "outbox.json")
        val outbox = HighlightOutbox(file)
        val chapter = chapter()
        val highlight = highlight(chapter)
        outbox.enqueue(highlight, chapter)
        val initialRevision = outbox.changes.value
        val observer = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) {
                outbox.changes.first { it > initialRevision }
                HighlightOutbox(file).pending().size
            }
        }

        outbox.update(highlight.id) {
            it.copy(remoteRoutesResolved = true, remote = mapOf(
                "wss://relay.example" to HighlightPairDelivery(
                    highlight = HighlightDeliveryState.ACCEPTED, chapter = HighlightDeliveryState.ACCEPTED,
                ),
            ))
        }

        assertEquals(0, observer.await())
    }

    @Test
    fun userCompletionPersistsWithoutRoutesAndStopsRetriesOfTheSameSignedPair() = runBlocking {
        val file = File(Files.createTempDirectory("highlight-outbox-test").toFile(), "outbox.json")
        val outbox = HighlightOutbox(file) { 123L }
        val chapter = chapter()
        val highlight = highlight(chapter)
        outbox.enqueue(highlight, chapter, "private-42")
        val dispatcher = dispatcher(outbox, listOf("wss://relay.example")) { _, _ ->
            error("Cleared events must not be sent again")
        }

        dispatcher.clearPending()
        val restored = HighlightOutbox(file).entries().single()
        assertTrue(restored.isComplete)
        assertEquals(123L, restored.completedByUserAtMillis)
        assertEquals(highlight, restored.event)
        assertEquals(chapter, restored.chapterEvent)
        assertEquals("private-42", restored.localHighlightId)
        assertTrue(restored.remote.isEmpty())
        assertEquals(0, dispatcher.syncPending(force = true))
        assertTrue(dispatcher.enqueueAndTryDeliver(highlight, chapter).isComplete)
        assertTrue(HighlightOutbox(file).pending().isEmpty())
    }

    @Test
    fun userCompletionClearsPairErrorsAndCannotBeUndoneByStaleUpdates() = runBlocking {
        val file = File(Files.createTempDirectory("highlight-outbox-test").toFile(), "outbox.json")
        val outbox = HighlightOutbox(file)
        val chapter = chapter()
        val highlight = highlight(chapter)
        outbox.enqueue(highlight, chapter)
        val failed = HighlightPairDelivery(
            highlight = HighlightDeliveryState.FAILED, chapter = HighlightDeliveryState.FAILED,
            highlightFailure = "highlight failed", chapterFailure = "chapter failed",
        )
        outbox.update(highlight.id) { it.copy(
            local = mapOf("ws://local.example" to failed),
            remote = mapOf("wss://relay.example" to failed),
            remoteRoutesResolved = true, lastFailure = "delivery failed",
        ) }
        outbox.markPendingDelivered()
        outbox.update(highlight.id) { it.copy(completedByUserAtMillis = null, lastFailure = "late failure") }

        val restored = HighlightOutbox(file).entries().single()
        assertTrue(restored.isComplete)
        assertEquals(null, restored.lastFailure)
        assertTrue((restored.local.values + restored.remote.values).all {
            it.highlightFailure == null && it.chapterFailure == null
        })
        // Keep the actual acknowledgement history separate from explicit user completion.
        assertEquals(HighlightDeliveryState.FAILED, restored.remote.values.single().highlight)
    }

    @Test
    fun clearPendingWaitsForActiveDeliveryAndRetiresItsFailure() = runBlocking {
        val file = File(Files.createTempDirectory("highlight-outbox-test").toFile(), "outbox.json")
        val outbox = HighlightOutbox(file)
        val chapter = chapter()
        outbox.enqueue(highlight(chapter), chapter)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var publications = 0
        val dispatcher = dispatcher(outbox, listOf("wss://relay.example")) { event, routes ->
            publications++
            started.complete(Unit)
            release.await()
            PublishReport(0, routes.size, event.id, routes.map {
                RelayPublishOutcome(it, RelayPublishOutcomeType.REJECTED, "blocked")
            })
        }

        withTimeout(5_000) {
            val delivery = async { dispatcher.syncPending(force = true) }
            started.await()
            val clearing = async(start = CoroutineStart.UNDISPATCHED) { dispatcher.clearPending() }
            assertTrue(!clearing.isCompleted)
            release.complete(Unit)
            delivery.await()
            clearing.await()
        }
        val completed = HighlightOutbox(file).entries().single()
        assertTrue(completed.isComplete)
        assertEquals(null, completed.lastFailure)
        val previousPublications = publications
        assertEquals(0, dispatcher.syncPending(force = true))
        assertEquals(previousPublications, publications)
    }

    private fun dispatcher(
        outbox: HighlightOutbox,
        routes: List<String>,
        localRelay: String? = null,
        publish: suspend (NostrEvent, Collection<String>) -> PublishReport,
    ): HighlightOutboxDispatcher = HighlightOutboxDispatcher(
        outbox = outbox,
        // Both relay operations are injected below; unexpected interface calls fail the test.
        relaySync = Proxy.newProxyInstance(
            BookshelfRelaySync::class.java.classLoader,
            arrayOf(BookshelfRelaySync::class.java),
        ) { _, method, _ -> error("Unexpected relay call: ${method.name}") } as BookshelfRelaySync,
        localRelayUrl = { localRelay },
        isOnline = { true },
        resolveRemoteRoutes = { _, _ -> routes },
        publishToRoutes = publish,
    )

    private fun chapter(): NostrEvent = signed(
        kind = BookKinds.PUBLICATION_CONTENT,
        tags = listOf(listOf("d", "chapter-one")),
        content = "A chapter passage",
    )

    private fun highlight(chapter: NostrEvent): NostrEvent = signed(
        kind = 9802,
        tags = listOf(
            listOf("a", "${BookKinds.PUBLICATION_CONTENT}:${chapter.pubkey}:chapter-one"),
            listOf("e", chapter.id),
        ),
        content = "A chapter passage",
    )

    private fun signed(kind: Int, tags: List<List<String>>, content: String): NostrEvent {
        val quartzTags = tags.map { it.toTypedArray() }.toTypedArray()
        val hash = EventHasher.hashId(pubkey, createdAt, kind, quartzTags, content)
        return NostrEvent(
            id = hash,
            pubkey = pubkey,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = Secp256k1InstanceKotlin.signSchnorr(hash.hexBytes(), privateKey, ByteArray(32)).toHex(),
        )
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
