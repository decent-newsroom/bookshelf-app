package eu.decentnewsroom.bookshelf.data.nostr

import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.utils.Secp256k1InstanceKotlin
import eu.decentnewsroom.bookshelf.domain.NostrEvent

/**
 * A Nostr event that has crossed the application's authenticity boundary.
 * The wrapped value is deliberately not constructible from untrusted input;
 * callers must obtain it from [NostrEventVerifier].
 */
class VerifiedNostrEvent internal constructor(
    val event: NostrEvent,
) {
    val id: String get() = event.id
    val pubkey: String get() = event.pubkey
    val createdAt: Long get() = event.createdAt
    val kind: Int get() = event.kind
    val tags: List<List<String>> get() = event.tags
    val content: String get() = event.content
    val sig: String get() = event.sig
}

data class NostrEventContext(
    val requestedEventId: String? = null,
    val expectedKind: Int? = null,
    val expectedPubkey: String? = null,
    val expectedDTag: String? = null,
)

/** The one place where decoded Nostr events become trusted application data. */
object NostrEventVerifier {
    const val MAX_CONTENT_LENGTH = 1_000_000
    const val MAX_MESSAGE_BYTES = 2_000_000
    const val MAX_TAGS = 1_000
    const val MAX_TAG_ELEMENTS = 20
    const val MAX_TAG_ELEMENT_LENGTH = 4_096
    const val MAX_FUTURE_SECONDS = 15 * 60L

    private val HEX_64 = Regex("^[a-f0-9]{64}$")
    private val HEX_128 = Regex("^[a-f0-9]{128}$")

    /** Returns null for any malformed, forged, or not-yet-valid event. */
    fun verify(
        event: NostrEvent,
        nowSeconds: Long = System.currentTimeMillis() / 1_000L,
        context: NostrEventContext = NostrEventContext(),
    ): VerifiedNostrEvent? {
        if (!isStructurallyValid(event, nowSeconds)) return null
        if (context.requestedEventId != null && event.id != context.requestedEventId.lowercase()) return null
        if (context.expectedKind != null && event.kind != context.expectedKind) return null
        if (context.expectedPubkey != null && event.pubkey != context.expectedPubkey.lowercase()) return null
        if (context.expectedDTag != null && !hasDTag(event, context.expectedDTag)) return null

        val quartzTags = event.tags.map { it.toTypedArray() }.toTypedArray()
        val hashBytes = runCatching {
            EventHasher.hashIdBytes(event.pubkey, event.createdAt, event.kind, quartzTags, event.content)
        }.getOrNull() ?: return null
        val computedId = runCatching {
            EventHasher.hashId(event.pubkey, event.createdAt, event.kind, quartzTags, event.content)
        }.getOrNull() ?: return null
        if (computedId != event.id) return null

        val signature = event.sig.hexBytes() ?: return null
        val pubkey = event.pubkey.hexBytes() ?: return null
        val validSignature = runCatching {
            Secp256k1InstanceKotlin.verifySchnorr(signature, hashBytes, pubkey)
        }.getOrDefault(false)
        return VerifiedNostrEvent(event).takeIf { validSignature }
    }

    fun requireVerified(
        event: NostrEvent,
        nowSeconds: Long = System.currentTimeMillis() / 1_000L,
        context: NostrEventContext = NostrEventContext(),
    ): VerifiedNostrEvent = verify(event, nowSeconds, context)
        ?: throw IllegalArgumentException("Nostr event failed authenticity or context verification.")

    /** Select newest only after verification; ID makes ties deterministic. */
    fun newest(
        events: Iterable<NostrEvent>,
        nowSeconds: Long = System.currentTimeMillis() / 1_000L,
        context: NostrEventContext = NostrEventContext(),
    ): VerifiedNostrEvent? = events.asSequence()
        .mapNotNull { verify(it, nowSeconds, context) }
        .maxWithOrNull(compareBy<VerifiedNostrEvent> { it.createdAt }.thenBy { it.id })

    private fun isStructurallyValid(event: NostrEvent, nowSeconds: Long): Boolean {
        if (!HEX_64.matches(event.id) || !HEX_64.matches(event.pubkey) || !HEX_128.matches(event.sig)) return false
        if (event.createdAt < 0 || event.createdAt > nowSeconds + MAX_FUTURE_SECONDS) return false
        if (event.kind !in 0..65_535) return false
        if (event.content.toByteArray(Charsets.UTF_8).size > MAX_CONTENT_LENGTH || event.tags.size > MAX_TAGS) return false
        return event.tags.all { tag ->
            tag.isNotEmpty() && tag.size <= MAX_TAG_ELEMENTS &&
                tag.all { value -> value.toByteArray(Charsets.UTF_8).size <= MAX_TAG_ELEMENT_LENGTH }
        }
    }

    private fun hasDTag(event: NostrEvent, expected: String): Boolean =
        event.tags.any { it.getOrNull(0) == "d" && it.getOrNull(1) == expected }

    private fun String.hexBytes(): ByteArray? {
        if (length % 2 != 0 || !matches(Regex("^[a-f0-9]+$"))) return null
        return runCatching {
            ByteArray(length / 2) { index ->
                ((this[index * 2].digitToInt(16) shl 4) or this[index * 2 + 1].digitToInt(16)).toByte()
            }
        }.getOrNull()
    }
}
