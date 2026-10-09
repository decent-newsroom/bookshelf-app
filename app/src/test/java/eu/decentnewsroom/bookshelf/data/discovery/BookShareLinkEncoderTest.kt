package eu.decentnewsroom.bookshelf.data.discovery

import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.BookSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookShareLinkEncoderTest {
    @Test
    fun encodedAddressRoundTripsExactPublicationIdentity() {
        val identifier = "  édition:été/第三  "
        val book = book(identifier = identifier)

        val encoded = requireNotNull(BookShareLinkEncoder.encode(book))
        assertTrue(encoded.startsWith("nostr:naddr1"))
        assertTrue(!encoded.startsWith("nostr:nostr:"))
        val address = Nip19Parser.uriToRoute(encoded)?.entity as NAddress

        assertEquals(BookKinds.PUBLICATION_INDEX, address.kind)
        assertEquals(PUBKEY, address.author)
        assertEquals(identifier, address.dTag)
    }

    @Test
    fun revisionsShareIndexCoordinateRegardlessOfEventId() {
        val firstRevision = book(id = "1".repeat(64))
        val laterRevision = book(id = "2".repeat(64), createdAt = 99)

        assertEquals(firstRevision.coordinate, laterRevision.coordinate)
        assertEquals(requireNotNull(BookShareLinkEncoder.encode(firstRevision)), requireNotNull(BookShareLinkEncoder.encode(laterRevision)))
    }

    @Test
    fun onlyPublicSecureWebSocketRelayHintsAreIncluded() {
        val publicLink = requireNotNull(BookShareLinkEncoder.encode(book(relay = "wss://relay.example")))
        val publicAddress = Nip19Parser.uriToRoute(publicLink)?.entity as NAddress
        // Quartz canonicalizes an empty relay path to a trailing slash.
        assertEquals(listOf("wss://relay.example/"), publicAddress.relay.map { it.url })

        listOf(
            "https://api.example", "ws://relay.example", "wss://localhost", "wss://192.168.1.2",
            "wss://relay.local", "wss://printer.local.", "wss://localhost.", "wss://relay.localdomain",
            "wss://relay.corp", "wss://relay", "wss://[::1]", "wss://relay.home.arpa",
            "wss://127.1", "wss://10.1", "wss://0x7f.0x1", "wss://192.168.1.2.",
            "wss://user:password@relay.example", "wss://relay.example/#fragment", "not a URL",
            "wss://relay.example/" + "a".repeat(255),
        )
            .forEach { relay ->
                val encoded = requireNotNull(BookShareLinkEncoder.encode(book(relay = relay)))
                val address = Nip19Parser.uriToRoute(encoded)?.entity as NAddress
                assertTrue("unexpected relay hint for $relay", address.relay.isEmpty())
            }
    }

    @Test
    fun malformedIdentityAndOverlongIdentifierAreRejected() {
        assertNull(BookShareLinkEncoder.encode(book(pubkey = "xyz")))
        assertNull(BookShareLinkEncoder.encode(book(coordinate = "30041:$PUBKEY:book")))
        assertNull(BookShareLinkEncoder.encode(book(coordinate = "30040:$PUBKEY:other-book")))
        assertNull(BookShareLinkEncoder.encode(book(identifier = "   ")))
        assertNull(BookShareLinkEncoder.encode(book(identifier = "é".repeat(128))))
    }

    @Test
    fun maximumUtf8IdentifierLengthRoundTripsWithoutTruncation() {
        val identifier = "é".repeat(127) + "a" // 255 UTF-8 bytes: the NIP-19 TLV length limit.
        val encoded = requireNotNull(BookShareLinkEncoder.encode(book(identifier = identifier)))
        val address = Nip19Parser.uriToRoute(encoded)?.entity as NAddress
        assertEquals(identifier, address.dTag)
    }

    private fun book(
        id: String = "a".repeat(64),
        identifier: String = "book",
        pubkey: String = PUBKEY,
        coordinate: String = "${BookKinds.PUBLICATION_INDEX}:$pubkey:$identifier",
        relay: String? = null,
        createdAt: Long = 1,
    ) = BookSummary(
        id = id,
        coordinate = coordinate,
        pubkey = pubkey,
        identifier = identifier,
        title = "Book",
        summary = null,
        authors = emptyList(),
        coverImageUrl = null,
        sourceUrl = null,
        language = null,
        releaseDate = null,
        version = null,
        type = "book",
        topics = emptyList(),
        relay = relay,
        createdAt = createdAt,
        chapterCount = 0,
        chapterRefs = emptyList(),
    )

    private companion object {
        const val PUBKEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
