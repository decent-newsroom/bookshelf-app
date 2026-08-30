package eu.decentnewsroom.bookshelf.data.nostr

import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.utils.Secp256k1InstanceKotlin
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NostrRelayAuthenticationTest {
    private val now = 1_700_000_000L
    private val privateKey = ByteArray(32) { 7 }
    private val pubkey = Secp256k1InstanceKotlin.compressedPubKeyFor(privateKey).copyOfRange(1, 33).toHex()

    @Test
    fun draftUsesCanonicalKindAndExactlyRelayAndChallengeTags() {
        val draft = NostrAuthEventValidator.draft(pubkey, "wss://relay.example", "challenge", now)

        assertEquals(22242, draft.kind)
        assertEquals(listOf(listOf("relay", "wss://relay.example"), listOf("challenge", "challenge")), draft.tags)
        assertEquals("", draft.content)
    }

    @Test
    fun signedAuthEventMustMatchItsRelayAndChallengeContext() {
        val draft = NostrAuthEventValidator.draft(pubkey, "wss://relay.example", "challenge", now)
        val event = signed(draft)

        assertNotNull(NostrAuthEventValidator.verify(event, draft, now))
        assertNull(NostrAuthEventValidator.verify(event, draft.copy(challenge = "other"), now))
        assertNull(NostrAuthEventValidator.verify(event, draft.copy(relayUrl = "wss://other.example"), now))
        assertNull(NostrAuthEventValidator.verify(event.copy(tags = event.tags + listOf(listOf("x", "unexpected"))), draft, now))
    }

    @Test
    fun malformedAndStaleAuthEventsAreRejected() {
        val draft = NostrAuthEventValidator.draft(pubkey, "wss://relay.example", "challenge", now)
        val event = signed(draft)

        assertNull(NostrAuthEventValidator.verify(event.copy(content = "not-empty"), draft, now))
        assertNull(NostrAuthEventValidator.verify(event, draft, now + NostrAuthEventValidator.MAX_AGE_SECONDS + 1))
    }

    private fun signed(draft: NostrAuthEventDraft): NostrEvent {
        val quartzTags = draft.tags.map { it.toTypedArray() }.toTypedArray()
        val id = EventHasher.hashId(pubkey, draft.createdAt, draft.kind, quartzTags, draft.content)
        val sig = Secp256k1InstanceKotlin.signSchnorr(id.hexBytes(), privateKey, ByteArray(32)).toHex()
        return NostrEvent(id, pubkey, draft.createdAt, draft.kind, draft.tags, draft.content, sig)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun String.hexBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

