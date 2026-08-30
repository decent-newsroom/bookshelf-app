package eu.decentnewsroom.bookshelf.data.mercury

import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.ChapterReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterSourcesTest {
    @Test
    fun defaultsContainMercuryAndCitadelMirrors() {
        assertEquals(
            listOf(
                "wss://mercury-relay.imwald.eu",
                "wss://thecitadel.nostr1.com",
                "wss://njump.me",
            ),
            ChapterRelayUrls.DEFAULTS,
        )
    }

    @Test
    fun relayUrlsAreTrimmedNormalizedAndDeduplicated() {
        val result = ChapterRelayUrls.parse(
            """
            WSS://MERCURY-RELAY.IMWALD.EU/
            wss://mercury-relay.imwald.eu
            wss://relay.example/nostr
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "wss://mercury-relay.imwald.eu",
                "wss://relay.example/nostr",
            ),
            result,
        )
    }

    @Test
    fun hintedRelaysAreMergedWithDefaultsAndInvalidHintsAreIgnored() {
        val result = ChapterRelayUrls.mergeWithHints(
            defaultRelayUrls = listOf("wss://default.example"),
            relayHints = listOf("WSS://HINT.EXAMPLE/", "wss://default.example", "https://invalid.example"),
        )

        assertEquals(
            listOf("wss://default.example", "wss://hint.example"),
            result,
        )
    }

    @Test
    fun hintedRelaysNeverExceedTheConnectionLimit() {
        val result = ChapterRelayUrls.mergeWithHints(
            defaultRelayUrls = listOf("wss://default.example"),
            relayHints = (1..10).map { "wss://hint$it.example" },
        )

        assertEquals(8, result.size)
        assertEquals("wss://default.example", result.first())
    }

    @Test
    fun emptyConfiguredRelayListUsesDefaultsBeforeAddingHints() {
        assertEquals(
            ChapterRelayUrls.DEFAULTS + "wss://hint.example",
            ChapterRelayUrls.mergeWithHints(
                defaultRelayUrls = emptyList(),
                relayHints = listOf("wss://hint.example"),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun relayUrlsRejectHttpSources() {
        ChapterRelayUrls.parse("https://mercury-relay.imwald.eu")
    }

    @Test(expected = IllegalArgumentException::class)
    fun relayUrlsRejectCleartextWebSockets() {
        ChapterRelayUrls.parse("ws://relay.example")
    }

    @Test(expected = IllegalArgumentException::class)
    fun relayUrlsRejectMoreThanEightUniqueSources() {
        ChapterRelayUrls.parse((1..9).joinToString("\n") { index -> "wss://relay$index.example" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun relayUrlsRejectOversizedUrls() {
        ChapterRelayUrls.parse("wss://${"a".repeat(2_049)}.example")
    }

    @Test
    fun chapterRequestIncludesIdAndCoordinateFilters() {
        val pubkey = "a".repeat(64)
        val eventId = "b".repeat(64)
        val references =
            listOf(
                chapterReference(pubkey, "chapter-one", eventId),
                chapterReference(pubkey, "chapter-two", null),
            )

        val request = chapterReqMessage("test-subscription", references)

        assertTrue(request.startsWith("[\"REQ\",\"test-subscription\""))
        assertTrue(request.contains("\"ids\":[\"$eventId\"]"))
        assertTrue(request.contains("\"authors\":[\"$pubkey\"]"))
        assertTrue(request.contains("\"#d\":[\"chapter-one\",\"chapter-two\"]"))
        assertTrue(request.contains("\"kinds\":[${BookKinds.PUBLICATION_CONTENT}]"))
        assertFalse(request.contains("https://"))
    }

    private fun chapterReference(pubkey: String, identifier: String, eventId: String?): ChapterReference =
        ChapterReference(
            coordinate = "${BookKinds.PUBLICATION_CONTENT}:$pubkey:$identifier",
            pubkey = pubkey,
            identifier = identifier,
            relay = null,
            eventId = eventId,
        )
}
