package eu.decentnewsroom.bookshelf.data.discovery

import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.BookReference
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress

object NaddrPublicationReferenceDecoder {
    fun decode(naddr: String): BookReference? = runCatching {
        val route = Nip19Parser.uriToRoute(naddr.trim())
        val address = route?.entity as? NAddress ?: return null
        if (address.kind != BookKinds.PUBLICATION_INDEX) return null
        val pubkey = address.author.trim().lowercase()
        val identifier = address.dTag.trim()
        if (!HEX_64.matches(pubkey) || identifier.isBlank()) return null
        BookReference(
            type = "a",
            coordinate = "${BookKinds.PUBLICATION_INDEX}:$pubkey:$identifier",
            relay = address.relay.firstOrNull()?.url,
            eventId = null,
            pubkey = pubkey,
        )
    }.getOrNull()

    private val HEX_64 = Regex("^[a-f0-9]{64}$", RegexOption.IGNORE_CASE)
}