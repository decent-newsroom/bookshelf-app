package eu.decentnewsroom.bookshelf.data.discovery

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.BookSummary
import java.net.URI

/** Builds a NIP-19 share reference for a publication index. */
object BookShareLinkEncoder {
    private const val MAX_TLV_BYTES = 255
    private val HEX_64 = Regex("^[a-fA-F0-9]{64}$")
    private val IP_LIKE_HOST = Regex("(?:[0-9]+|0x[0-9a-f]+)(?:\\.(?:[0-9]+|0x[0-9a-f]+))*")

    fun encode(book: BookSummary): String? = runCatching {
        val pubkey = book.pubkey
        val identifier = book.identifier
        if (!HEX_64.matches(pubkey) || identifier.isBlank() ||
            identifier.toByteArray(Charsets.UTF_8).size > MAX_TLV_BYTES
        ) {
            return null
        }
        if (book.coordinate != "${BookKinds.PUBLICATION_INDEX}:$pubkey:$identifier") return null

        val relay = publicRelayHint(book.relay)
        "nostr:" + NAddress.create(
            BookKinds.PUBLICATION_INDEX,
            pubkey.lowercase(),
            identifier,
            relay?.let(::listOf).orEmpty(),
        )
    }.getOrNull()

    private fun publicRelayHint(candidate: String?): NormalizedRelayUrl? {
        val value = candidate?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (value.toByteArray(Charsets.UTF_8).size > MAX_TLV_BYTES) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("wss", ignoreCase = true) || uri.host.isNullOrBlank()) return null
        // A trailing DNS dot must not bypass local-host classification. Never resolve DNS here.
        val host = uri.host.lowercase().trimEnd('.')
        if (!host.contains('.') || host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home") ||
            host.endsWith(".home.arpa") || host.endsWith(".localdomain") || host.endsWith(".corp") ||
            host.endsWith(".internal") || host.endsWith(".onion") || host.endsWith(".i2p") ||
            host.startsWith("[") || IP_LIKE_HOST.matches(host) ||
            uri.userInfo != null || uri.fragment != null
        ) return null
        return RelayUrlNormalizer.normalizeOrNull(value)?.takeIf {
            it.url.toByteArray(Charsets.UTF_8).size <= MAX_TLV_BYTES
        }
    }
}
