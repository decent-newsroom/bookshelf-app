package eu.decentnewsroom.bookshelf.data.highlights

import eu.decentnewsroom.bookshelf.data.nostr.NostrEventContext
import eu.decentnewsroom.bookshelf.data.nostr.NostrEventVerifier
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI

/** The exact unsigned input submitted to an external signer for a NIP-84 highlight. */
data class HighlightEventDraft(
    val pubkey: String,
    val createdAt: Long,
    val tags: List<List<String>>,
    val content: String,
    val kind: Int = BookKinds.HIGHLIGHT,
)

/** Builds and validates signed NIP-84 highlights of authenticated kind-30041 chapters. */
object HighlightEventFactory {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val hex64 = Regex("^[a-f0-9]{64}$", RegexOption.IGNORE_CASE)
    private val urlPattern = Regex("https?://[^\\s<>()\\[\\]\\\"']+", RegexOption.IGNORE_CASE)
    private val trackerParameters = setOf("fbclid", "gclid", "dclid", "mc_cid", "mc_eid", "ref", "ref_", "source")

    fun create(
        pubkey: String,
        chapter: NostrEvent,
        quote: String,
        context: String? = null,
        comment: String? = null,
        createdAt: Long = System.currentTimeMillis() / 1_000L,
    ): HighlightEventDraft {
        require(hex64.matches(pubkey)) { "Highlight author public key is invalid." }
        NostrEventVerifier.requireVerified(
            chapter,
            context = NostrEventContext(expectedKind = BookKinds.PUBLICATION_CONTENT),
        )
        val identifier = chapter.tags.firstNotNullOfOrNull { tag ->
            tag.getOrNull(1)?.takeIf { tag.getOrNull(0) == "d" && it.isNotBlank() }
        } ?: throw IllegalArgumentException("A highlighted chapter requires a d tag.")
        require(hex64.matches(chapter.pubkey)) { "Highlighted chapter publisher public key is invalid." }
        require(hex64.matches(chapter.id)) { "Highlighted chapter event ID is invalid." }

        val normalizedContext = context?.takeIf { it.isNotBlank() }
        val normalizedComment = comment?.takeIf { it.isNotBlank() }
        requireTagLength(normalizedContext, "Highlight context")
        requireTagLength(normalizedComment, "Highlight comment")
        val sourceCoordinate = "${BookKinds.PUBLICATION_CONTENT}:${chapter.pubkey.lowercase()}:$identifier"
        val tags = buildList {
            add(listOf("a", sourceCoordinate))
            add(listOf("e", chapter.id.lowercase()))
            // A chapter signer is the publisher of this source event, not necessarily its literary author.
            add(listOf("p", chapter.pubkey.lowercase(), "", "publisher"))
            chapterSourceUrl(chapter)?.let { add(listOf("r", it, "source")) }
            normalizedContext?.let { add(listOf("context", it)) }
            normalizedComment?.let { value ->
                add(listOf("comment", value))
                commentUrls(value).forEach { add(listOf("r", it, "mention")) }
            }
        }
        return HighlightEventDraft(
            pubkey = pubkey.lowercase(),
            createdAt = createdAt,
            tags = tags,
            content = quote,
        )
    }

    fun unsignedJson(draft: HighlightEventDraft): String {
        require(draft.kind == BookKinds.HIGHLIGHT) { "Highlight draft has the wrong event kind." }
        require(hex64.matches(draft.pubkey)) { "Highlight author public key is invalid." }
        require(draft.createdAt >= 0) { "Highlight creation time is invalid." }
        validateTags(draft.tags)
        return json.encodeToString(
            UnsignedNostrEvent(
                pubkey = draft.pubkey.lowercase(),
                createdAt = draft.createdAt,
                kind = draft.kind,
                tags = draft.tags,
                content = draft.content,
            ),
        )
    }

    /** Rejects signatures for a different draft, even where the event is otherwise authentic. */
    fun decodeSigned(eventJson: String, draft: HighlightEventDraft): NostrEvent {
        val event = json.decodeFromString<NostrEvent>(eventJson)
        require(event.pubkey == draft.pubkey.lowercase()) { "Signer returned an event for a different public key." }
        require(event.createdAt == draft.createdAt) { "Signer returned an event with a different creation time." }
        require(event.kind == draft.kind && event.kind == BookKinds.HIGHLIGHT) { "Signer returned the wrong event kind." }
        require(event.tags == draft.tags) { "Signer returned an event with different tags." }
        require(event.content == draft.content) { "Signer returned an event with different content." }
        validateTags(event.tags)
        NostrEventVerifier.requireVerified(
            event,
            context = NostrEventContext(expectedKind = BookKinds.HIGHLIGHT, expectedPubkey = draft.pubkey),
        )
        return event
    }

    private fun validateTags(tags: List<List<String>>) {
        val address = tags.filter { it.getOrNull(0) == "a" }
        require(address.size == 1) { "A highlight requires exactly one chapter address tag." }
        val coordinate = address.single().getOrNull(1).orEmpty()
        val coordinateParts = coordinate.split(':', limit = 3)
        require(
            coordinateParts.size == 3 &&
                coordinateParts[0] == BookKinds.PUBLICATION_CONTENT.toString() &&
                hex64.matches(coordinateParts[1]) &&
                coordinateParts[2].isNotBlank(),
        ) { "Highlight address must be a kind-30041 chapter coordinate." }

        val events = tags.filter { it.getOrNull(0) == "e" }
        require(events.size == 1 && hex64.matches(events.single().getOrNull(1).orEmpty())) {
            "A highlight requires exactly one chapter event tag."
        }
        tags.filter { it.getOrNull(0) == "p" }.forEach { tag ->
            require(hex64.matches(tag.getOrNull(1).orEmpty())) { "Highlight public-key tag is invalid." }
            require(tag.getOrNull(3) == "publisher" || tag.getOrNull(3) == "mention") {
                "Highlight public-key tags require publisher or mention roles."
            }
        }
        val urls = tags.filter { it.getOrNull(0) == "r" }
        require(urls.count { it.getOrNull(2) == "source" } <= 1) { "A highlight has too many source URL tags." }
        urls.forEach { tag ->
            when (tag.getOrNull(2)) {
                "source" -> require(cleanSourceUrl(tag.getOrNull(1)) == tag.getOrNull(1)) {
                    "Highlight source URL tags must be normalized."
                }
                "mention" -> require(validMentionUrl(tag.getOrNull(1))) {
                    "Highlight URL mention is invalid."
                }
                else -> throw IllegalArgumentException("Highlight URL tags require a source or mention marker.")
            }
        }
        val contexts = tags.filter { it.getOrNull(0) == "context" }
        require(contexts.size <= 1) { "A highlight has too many context tags." }
        contexts.singleOrNull()?.getOrNull(1)?.let { requireTagLength(it, "Highlight context") }
        val comments = tags.filter { it.getOrNull(0) == "comment" }
        require(comments.size <= 1) { "A highlight has too many comment tags." }
        comments.singleOrNull()?.getOrNull(1)?.let { requireTagLength(it, "Highlight comment") }
    }

    private fun chapterSourceUrl(chapter: NostrEvent): String? = chapter.tags.firstNotNullOfOrNull { tag ->
        tag.getOrNull(1)?.takeIf { tag.getOrNull(0) == "r" || tag.getOrNull(0) == "source" }?.let(::cleanSourceUrl)
    }

    /** URL mentions retain their original query and fragment; only source URLs are tracker-cleaned. */
    private fun commentUrls(comment: String): List<String> = urlPattern.findAll(comment)
        .map { it.value.trimEnd('.', ',', ';', ':', '!', '?') }
        .filter(::validMentionUrl)
        .distinct()
        .toList()

    /** Removes fragments and common tracking query parameters while retaining existing percent encoding. */
    private fun cleanSourceUrl(raw: String?): String? {
        val value = raw?.trim()?.takeIf(::validMentionUrl) ?: return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme.lowercase()
        val host = uri.host?.lowercase() ?: return null
        val query = uri.rawQuery
            ?.split('&')
            ?.filter { parameter ->
                val name = parameter.substringBefore('=').lowercase()
                name !in trackerParameters && !name.startsWith("utm_")
            }
            ?.joinToString("&")
            ?.takeIf { it.isNotBlank() }
        val authority = host + if (uri.port >= 0) ":${uri.port}" else ""
        return "$scheme://$authority${uri.rawPath.orEmpty()}" + query?.let { "?$it" }.orEmpty()
    }

    private fun validMentionUrl(value: String?): Boolean {
        val uri = value?.let { runCatching { URI(it) }.getOrNull() } ?: return false
        return (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
            uri.host != null && uri.rawUserInfo == null
    }

    private fun requireTagLength(value: String?, label: String) {
        require(value == null || value.toByteArray(Charsets.UTF_8).size <= NostrEventVerifier.MAX_TAG_ELEMENT_LENGTH) {
            "$label is too long."
        }
    }

    @Serializable
    private data class UnsignedNostrEvent(
        val pubkey: String,
        @SerialName("created_at") val createdAt: Long,
        val kind: Int,
        val tags: List<List<String>>,
        val content: String,
    )
}
