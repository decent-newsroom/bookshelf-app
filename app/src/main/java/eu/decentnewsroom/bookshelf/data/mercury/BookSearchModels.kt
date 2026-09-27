package eu.decentnewsroom.bookshelf.data.mercury

import eu.decentnewsroom.bookshelf.data.discovery.NaddrPublicationReferenceDecoder
import eu.decentnewsroom.bookshelf.domain.BookSummary

object BookSearchLimits {
    const val TEXT = 160
    const val LANGUAGE = 32
    const val IDENTIFIER = 512
    const val SECTION_MIN = 4
}

/** Search channels selected by the content toggle or an explicit query prefix. */
enum class SearchScope {
    ALL,
    METADATA,
    TITLE,
    AUTHOR,
    SUBJECT,
    IDENTIFIER,
    SLUG,
    CHAPTER_CONTENT,
}

/** A normalized, typed search request. Excerpts and query text are never persisted. */
data class BookSearchQuery(
    val text: String = "",
    val scope: SearchScope = SearchScope.ALL,
    val language: String? = null,
    val eventId: String? = null,
    val coordinate: String? = null,
    /** Non-null only when input was a valid publication naddr; it may have no relay hints. */
    val naddrRelayHints: List<String>? = null,
) {
    val normalizedText: String get() = text.trim()

    fun validationMessage(): String? {
        if (eventId != null || coordinate != null) return null
        if (language != null && language.trim().length > BookSearchLimits.LANGUAGE) {
            return "Language must be at most ${BookSearchLimits.LANGUAGE} characters."
        }
        val maximum = if (scope == SearchScope.IDENTIFIER) BookSearchLimits.IDENTIFIER else BookSearchLimits.TEXT
        if (normalizedText.length > maximum) return "Search must be at most $maximum characters."
        if (scope == SearchScope.CHAPTER_CONTENT && normalizedText.length < BookSearchLimits.SECTION_MIN) {
            return "Enter at least ${BookSearchLimits.SECTION_MIN} characters to search inside books."
        }
        return null
    }

    companion object {
        fun from(raw: String, scope: SearchScope = SearchScope.ALL, language: String? = null): BookSearchQuery {
            val trimmed = raw.trim()
            // An explicitly selected chapter search must never switch to a metadata channel.
            // Other prefixes remain literal chapter text; content: is optional shorthand.
            val match = FIELD_QUERY.matchEntire(trimmed)?.takeIf {
                scope != SearchScope.CHAPTER_CONTENT || it.groupValues[1].equals("content", ignoreCase = true)
            }
            val parsedScope = match?.groupValues?.getOrNull(1)?.lowercase()?.let {
                when (it) {
                    "title" -> SearchScope.TITLE
                    "author" -> SearchScope.AUTHOR
                    "subject", "topic" -> SearchScope.SUBJECT
                    "content" -> SearchScope.CHAPTER_CONTENT
                    "identifier", "id", "source", "url" -> SearchScope.IDENTIFIER
                    "d", "slug" -> SearchScope.SLUG
                    "language", "lang" -> SearchScope.METADATA
                    else -> null
                }
            }
            val value = match?.groupValues?.getOrNull(2)?.trim().takeIf { !it.isNullOrBlank() } ?: trimmed
            val naddrValue = value.removeNostrPrefix()
            val naddrTarget = NaddrPublicationReferenceDecoder.decodeTarget(naddrValue)
            val searchValue = if (naddrTarget != null) naddrValue else value
            val parsedLanguage = match?.groupValues?.getOrNull(1)?.lowercase()?.let { name ->
                value.takeIf { name == "language" || name == "lang" }
            }
            val coordinate = naddrTarget?.coordinate?.split(":", limit = 3)
                ?: searchValue.split(":", limit = 3).takeIf { it.size == 3 && it[0].toIntOrNull() != null && it[1].matches(HEX_64) && it[2].isNotBlank() }
            val eventId = searchValue.lowercase().takeIf { it.matches(HEX_64) }
            return BookSearchQuery(
                text = if (coordinate != null || eventId != null || parsedLanguage != null) "" else searchValue,
                scope = parsedScope ?: scope,
                language = parsedLanguage ?: language,
                eventId = eventId,
                coordinate = coordinate?.let { "${it[0].toIntOrNull() ?: return@let null}:${it[1].lowercase()}:${it[2]}" },
                naddrRelayHints = naddrTarget?.relayHints,
            )
        }

        private val FIELD_QUERY = Regex("^\\s*(title|author|subject|topic|content|language|lang|identifier|id|source|url|d|slug)\\s*:\\s*(.+?)\\s*$", RegexOption.IGNORE_CASE)
        private val HEX_64 = Regex("^[a-f0-9]{64}$", RegexOption.IGNORE_CASE)

        private fun String.removeNostrPrefix(): String =
            if (startsWith("nostr:", ignoreCase = true)) substring("nostr:".length).trimStart() else this
    }
}

enum class MatchProvenance {
    METADATA,
    TITLE,
    AUTHOR,
    SUBJECT,
    IDENTIFIER,
    CHAPTER_TEXT,
    CHAPTER_TITLE,
    CHAPTER_BODY,
    EXACT_EVENT,
    EXACT_COORDINATE,
}

/** Transient discovery data. Only [book] belongs in the saved-books store. */
data class BookSearchResult(
    val book: BookSummary,
    val provenance: Set<MatchProvenance>,
    val matchedChapterCoordinate: String? = null,
    val matchedChapterTitle: String? = null,
    val excerpt: String? = null,
    val rank: Int = 0,
) {
    val summary: BookSummary get() = book
}

enum class BookSearchStatus {
    COMPLETE,
    PARTIAL,
    UNAVAILABLE,
}

/**
 * A search may return useful matches even when one Mercury branch is unavailable.
 * Only complete outcomes are eligible for the repository's short-lived cache.
 */
data class BookSearchOutcome(
    val results: List<BookSearchResult>,
    val status: BookSearchStatus,
    val retryAfterMillis: Long? = null,
)
