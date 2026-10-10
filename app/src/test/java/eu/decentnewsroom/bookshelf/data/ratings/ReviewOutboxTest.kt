package eu.decentnewsroom.bookshelf.data.ratings

import eu.decentnewsroom.bookshelf.data.nostr.BookshelfRelaySync
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

class ReviewOutboxTest {
    @Test
    fun userCompletionPersistsClearsErrorsAndSkipsDeliveryEvenWithoutResolvedRoutes() = runBlocking {
        val file = File(Files.createTempDirectory("review-outbox-test").toFile(), "outbox.json")
        val outbox = ReviewOutbox(file, now = { 123L })
        val event = NostrEvent(id = "signed-review", content = "A review", sig = "original-signature")
        val saved = outbox.enqueue(event, "author")
        outbox.update(event.id) { it.copy(
            citrine = ReviewDeliveryState.FAILED,
            remote = mapOf("wss://relay.example" to ReviewDeliveryState.FAILED),
            lastFailure = "delivery failed",
        ) }
        val relay = Proxy.newProxyInstance(
            BookshelfRelaySync::class.java.classLoader, arrayOf(BookshelfRelaySync::class.java),
        ) { _, method, _ -> error("Unexpected relay call: ${method.name}") } as BookshelfRelaySync
        val dispatcher = ReviewOutboxDispatcher(outbox, relay, { "ws://local.example" }, { true })

        dispatcher.clearPending()
        outbox.update(event.id) { it.copy(completedByUserAtMillis = null, lastFailure = "late failure") }
        val restored = requireNotNull(ReviewOutbox(file).entry(event.id))
        assertTrue(restored.isComplete)
        assertEquals(123L, restored.completedByUserAtMillis)
        assertEquals(null, restored.lastFailure)
        assertEquals(event, restored.event)
        assertEquals(ReviewDeliveryState.FAILED, restored.citrine)
        assertEquals(0, dispatcher.syncPending(force = true))
        assertTrue(dispatcher.deliverSaved(saved).isComplete)
        assertEquals(0, ReviewOutbox(file).pendingCount())

        outbox.enqueue(event.copy(id = "new-review"), "author")
        assertEquals(1, outbox.pendingCount())
    }

    @Test
    fun clearingCorruptQueueFailsWithoutOverwritingSignedEvents() = runBlocking {
        val file = File(Files.createTempDirectory("review-outbox-test").toFile(), "outbox.json")
            .apply { writeText("not json") }
        val failure = runCatching { ReviewOutbox(file).markPendingDelivered() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("not json", file.readText())
    }
}
