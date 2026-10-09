package eu.decentnewsroom.bookshelf.data.discovery

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip19Bech32.bech32.Bech32
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import eu.decentnewsroom.bookshelf.data.mercury.BookSearchQuery
import eu.decentnewsroom.bookshelf.domain.BookKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrBookLinkParserTest {
    @Test
    fun publicationAddressPreservesExactIdentifierAndRelayHints() {
        val identifier = "  édition:été/第三  "
        val relay = requireNotNull(RelayUrlNormalizer.normalizeOrNull("wss://relay.example"))
        val naddr = NAddress.create(BookKinds.PUBLICATION_INDEX, PUBKEY, identifier, listOf(relay))

        val result = NostrBookLinkParser.parse("nostr:$naddr") as NostrBookLinkParser.Result.Accepted

        assertEquals(naddr, result.naddr)
        assertEquals("30040:$PUBKEY:$identifier", result.target.coordinate)
        assertEquals(PUBKEY, result.target.pubkey)
        assertEquals(listOf("wss://relay.example/"), result.target.relayHints)
        // Search's existing NIP-19 input must retain the same identity as the external boundary.
        assertEquals(result.target.coordinate, BookSearchQuery.from(result.naddr).coordinate)
    }

    @Test
    fun uppercaseBech32NormalizesButMixedCaseIsRejected() {
        val naddr = address()
        val result = NostrBookLinkParser.parse("nostr:${naddr.uppercase()}") as NostrBookLinkParser.Result.Accepted
        assertEquals(naddr, result.naddr)
        assertEquals(NostrBookLinkParser.Result.Invalid, NostrBookLinkParser.parse("nostr:N${naddr.drop(1)}"))
    }

    @Test
    fun malformedUrisAndNonExactReferencesAreRejected() {
        val naddr = address()
        listOf(
            null, "", naddr, "https://example.com/$naddr", "nostr://$naddr", "nostr:@$naddr",
            " nostr:$naddr", "nostr:$naddr ", "nostr: $naddr", "nostr:$naddr\n",
            "nostr:$naddr?x=1", "nostr:$naddr#fragment", "nostr:$naddr/path", "nostr:$naddr!",
            "nostr:$naddr%20", "nostr:$naddr nostr:$naddr", "nostr:nostr:$naddr",
            "nostr:naddr1", "nostr:naddr1" + "q".repeat(NostrBookLinkParser.MAX_URI_LENGTH),
        ).forEach { uri ->
            assertEquals("Unexpected acceptance of $uri", NostrBookLinkParser.Result.Invalid, NostrBookLinkParser.parse(uri))
        }
    }

    @Test
    fun checksumFailuresAndAppendedBech32CharactersAreRejected() {
        val naddr = address()
        val altered = naddr.dropLast(1) + if (naddr.last() == 'q') "p" else "q"
        assertEquals(NostrBookLinkParser.Result.Invalid, NostrBookLinkParser.parse("nostr:$altered"))
        assertEquals(NostrBookLinkParser.Result.Invalid, NostrBookLinkParser.parse("nostr:${naddr}qq"))
        val bytes = Bech32.decodeBytes(naddr).second
        val bech32m = Bech32.encodeBytes("naddr", bytes, Bech32.Encoding.Bech32m)
        assertEquals(NostrBookLinkParser.Result.Invalid, NostrBookLinkParser.parse("nostr:$bech32m"))
    }

    @Test
    fun otherKindsAndEntitiesAreUnsupportedAndBlankBookIdentifierIsInvalid() {
        assertEquals(NostrBookLinkParser.Result.Unsupported, NostrBookLinkParser.parse("nostr:${address(BookKinds.PUBLICATION_CONTENT)}"))
        assertEquals(NostrBookLinkParser.Result.Invalid, NostrBookLinkParser.parse("nostr:${address(identifier = "   ")}"))
        listOf("npub", "nsec", "note", "nevent", "nprofile", "nrelay", "ncryptsec", "nembed").forEach { entity ->
            assertEquals(NostrBookLinkParser.Result.Unsupported, NostrBookLinkParser.parse("nostr:${entity}1${"q".repeat(58)}"))
        }
        assertTrue(NostrBookLinkParser.parse("nostr:${address()}") is NostrBookLinkParser.Result.Accepted)
    }

    private fun address(kind: Int = BookKinds.PUBLICATION_INDEX, identifier: String = "book") =
        NAddress.create(kind, PUBKEY, identifier, emptyList())

    private companion object {
        const val PUBKEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
