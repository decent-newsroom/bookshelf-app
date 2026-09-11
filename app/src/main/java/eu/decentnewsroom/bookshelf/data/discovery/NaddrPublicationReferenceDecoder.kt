package eu.decentnewsroom.bookshelf.data.discovery

import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.BookReference
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress

object NaddrPublicationReferenceDecoder {
    data class PublicationTarget(
        val coordinate: String,
        val pubkey: String,
        val relayHints: List<String>,
    )

    fun decode(naddr: String): BookReference? = decodeTarget(naddr)?.let { target ->
        BookReference(
            type = "a",
            coordinate = target.coordinate,
            relay = target.relayHints.firstOrNull(),
            eventId = null,
            pubkey = target.pubkey,
        )
    }

    /** Decodes the exact publication coordinate and relay hints carried by an naddr. */
    fun decodeTarget(naddr: String): PublicationTarget? = runCatching {
        val route = Nip19Parser.uriToRoute(naddr.trim())
        val address = route?.entity as? NAddress ?: return null
        if (address.kind != BookKinds.PUBLICATION_INDEX) return null
        val pubkey = address.author.trim().lowercase()
        val identifier = address.dTag.trim()
        if (!HEX_64.matches(pubkey) || identifier.isBlank()) return null
        PublicationTarget(
            coordinate = "${BookKinds.PUBLICATION_INDEX}:$pubkey:$identifier",
            pubkey = pubkey,
            relayHints = address.relay.map { it.url.trim() }.filter(String::isNotBlank).distinct(),
        )
    }.getOrNull()

    private val HEX_64 = Regex("^[a-f0-9]{64}$", RegexOption.IGNORE_CASE)
}