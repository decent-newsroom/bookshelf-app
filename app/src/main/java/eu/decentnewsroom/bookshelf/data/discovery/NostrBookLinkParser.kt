package eu.decentnewsroom.bookshelf.data.discovery

import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.bech32.Bech32
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import eu.decentnewsroom.bookshelf.domain.BookKinds

/** Strict external-link boundary; unlike text search, this never extracts a link from prose. */
object NostrBookLinkParser {
    const val MAX_URI_LENGTH = 4096

    sealed interface Result {
        data class Accepted(
            val naddr: String,
            val target: NaddrPublicationReferenceDecoder.PublicationTarget,
        ) : Result

        data object Invalid : Result
        data object Unsupported : Result
    }

    fun parse(uri: String?): Result {
        if (uri == null || uri.length > MAX_URI_LENGTH ||
            !uri.startsWith("nostr:", ignoreCase = true)
        ) return Result.Invalid

        val encoded = uri.substring("nostr:".length)
        // Bech32 permits either case, but never a mix. URI delimiters and whitespace are invalid.
        if (encoded != encoded.lowercase() && encoded != encoded.uppercase()) return Result.Invalid
        val normalized = encoded.lowercase()
        if (!NIP19_ENTITY.matches(normalized)) return Result.Invalid
        // Never decode private-key entities or route unsupported references into book search.
        if (!normalized.startsWith("naddr1")) return Result.Unsupported

        return runCatching {
            if (Bech32.decode(normalized).third != Bech32.Encoding.Bech32) return Result.Invalid
            val route = Nip19Parser.uriToRoute(normalized) ?: return Result.Invalid
            if (route.nip19raw != normalized || !route.additionalChars.isNullOrEmpty()) {
                return Result.Invalid
            }
            val address = route.entity as? NAddress ?: return Result.Invalid
            if (address.kind != BookKinds.PUBLICATION_INDEX) return Result.Unsupported
            val target = NaddrPublicationReferenceDecoder.decodeTarget(normalized) ?: return Result.Invalid
            Result.Accepted(normalized, target)
        }.getOrDefault(Result.Invalid)
    }

    private val NIP19_ENTITY = Regex(
        "(?:naddr|nevent|note|npub|nprofile|nrelay|nsec|ncryptsec|nembed)1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+",
    )
}
