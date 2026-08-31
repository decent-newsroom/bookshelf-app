package eu.decentnewsroom.bookshelf.data.nostr

import org.junit.Assert.assertEquals
import org.junit.Test

class PublishReportTest {
    @Test
    fun failureMessageIncludesEachFailedRelayReason() {
        val report = PublishReport(
            acceptedRelays = 1,
            attemptedRelays = 3,
            eventId = "event",
            outcomes = listOf(
                RelayPublishOutcome("wss://accepted.example", RelayPublishOutcomeType.ACCEPTED),
                RelayPublishOutcome("wss://timeout.example", RelayPublishOutcomeType.TIMEOUT, "no response within timeout"),
                RelayPublishOutcome("wss://rejected.example", RelayPublishOutcomeType.REJECTED, "rate-limited: slow down"),
            ),
        )

        assertEquals(
            "wss://timeout.example: no response within timeout; wss://rejected.example: rate-limited: slow down",
            report.failureMessage(),
        )
    }

    @Test
    fun failureMessageFallsBackWhenNoOutcomesAreAvailable() {
        assertEquals("No relay accepted the directory update.", PublishReport(0, 3, "event").failureMessage())
    }
}
