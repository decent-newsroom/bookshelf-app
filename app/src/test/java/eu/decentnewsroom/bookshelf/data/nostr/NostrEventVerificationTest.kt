package eu.decentnewsroom.bookshelf.data.nostr

import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.utils.Secp256k1InstanceKotlin
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NostrEventVerificationTest {
    private val now = 1_700_000_000L
    private val privateKey = ByteArray(32) { 1 }
    private val pubkey = Secp256k1InstanceKotlin.compressedPubKeyFor(privateKey).copyOfRange(1, 33).toHex()

    @Test
    fun validEventIsAcceptedAndInvalidSignatureIsRejected() {
        val event = signed(now, "hello")

        assertNotNull(NostrEventVerifier.verify(event, now))
        assertNull(NostrEventVerifier.verify(event.copy(sig = "0".repeat(128)), now))
    }

    @Test
    fun mismatchedIdAndRequestContextAreRejected() {
        val event = signed(now, "hello")

        assertNull(NostrEventVerifier.verify(event.copy(id = "0".repeat(64)), now))
        assertNull(
            NostrEventVerifier.verify(
                event,
                now,
                NostrEventContext(requestedEventId = "0".repeat(64)),
            ),
        )
        assertNull(
            NostrEventVerifier.verify(
                event,
                now,
                NostrEventContext(expectedPubkey = "0".repeat(64)),
            ),
        )
        assertNull(NostrEventVerifier.verify(event, now, NostrEventContext(expectedKind = 2)))
        assertNull(NostrEventVerifier.verify(event, now, NostrEventContext(expectedDTag = "other")))
    }

    @Test
    fun futureAndOversizedEventsAreRejectedBeforeVerification() {
        val event = signed(now, "hello")

        assertNull(
            NostrEventVerifier.verify(
                signed(now + NostrEventVerifier.MAX_FUTURE_SECONDS + 1, "future"),
                now,
            ),
        )
        assertNull(
            NostrEventVerifier.verify(
                event.copy(content = "a".repeat(NostrEventVerifier.MAX_CONTENT_LENGTH + 1)),
                now,
            ),
        )
    }

    @Test
    fun forgedNewerEventCannotDisplaceValidOlderEvent() {
        val older = signed(now - 100, "valid")
        val forgedNewer = signed(now - 50, "forged").copy(sig = "0".repeat(128))

        assertEquals(older, NostrEventVerifier.newest(listOf(older, forgedNewer), now)?.event)
    }

    private fun signed(createdAt: Long, content: String): NostrEvent {
        val tags = listOf(listOf("d", "test"))
        val quartzTags = tags.map { it.toTypedArray() }.toTypedArray()
        val hash = EventHasher.hashId(pubkey, createdAt, 1, quartzTags, content)
        val signature = Secp256k1InstanceKotlin.signSchnorr(hash.hexBytes(), privateKey, ByteArray(32)).toHex()
        return NostrEvent(
            id = hash,
            pubkey = pubkey,
            createdAt = createdAt,
            kind = 1,
            tags = tags,
            content = content,
            sig = signature,
        )
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun String.hexBytes(): ByteArray =
        chunked(2).map { pair -> pair.toInt(16).toByte() }.toByteArray()
}
