package eu.decentnewsroom.bookshelf.ui.settings

import eu.decentnewsroom.bookshelf.data.highlights.HighlightDeliveryState
import eu.decentnewsroom.bookshelf.data.highlights.HighlightOutboxEntry
import eu.decentnewsroom.bookshelf.data.highlights.HighlightPairDelivery
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightDeliveryDetailsTest {
    private val event = NostrEvent(id = "a".repeat(64), pubkey = "b".repeat(64), createdAt = 1, kind = 9802)

    @Test
    fun identifiesTheChapterBlockerEvenWhenTheHighlightIsAccepted() {
        val entry = HighlightOutboxEntry(
            event = event, chapterEvent = event, queuedAtMillis = 0, nextRetryAtMillis = 0,
            remoteRoutesResolved = true,
            remote = mapOf("wss://relay.example/" to HighlightPairDelivery(
                highlight = HighlightDeliveryState.ACCEPTED,
                chapter = HighlightDeliveryState.FAILED,
                chapterFailure = "invalid: event is too old",
            )),
        )

        val detail = pendingHighlightDetail(entry)

        assertTrue(detail.contains("wss://relay.example/ — cited chapter: invalid: event is too old"))
        assertFalse(detail.contains(" — highlight:"))
    }

    @Test
    fun legacyQueueStillShowsDiscoveryAndEntryLevelFailure() {
        val entry = HighlightOutboxEntry(
            event = event, chapterEvent = event, queuedAtMillis = 0, nextRetryAtMillis = 0,
            lastFailure = "Could not resolve highlight relay routes.",
        )

        val detail = pendingHighlightDetail(entry)

        assertTrue(detail.contains("Waiting to discover remote relays."))
        assertTrue(detail.contains(entry.lastFailure!!))
    }
}
