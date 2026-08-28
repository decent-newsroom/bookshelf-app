package eu.decentnewsroom.bookshelf.data.mercury

import eu.decentnewsroom.bookshelf.domain.BookSummary

/** The user-visible part of the Mercury search surface. */
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
) {
    val normalizedText: String get() = text.trim()

    companion object {
        fun from(raw: String, scope: SearchScope = SearchScope.ALL, language: String? = null): BookSearchQuery {
            val trimmed = raw.trim()
            val match = FIELD_QUERY.matchEntire(trimmed)
            val parsedScope = match?.groupValues?.getOrNull(1)?.lowercase()?.let {
                when (it) {
                    "title" -> SearchScope.TITLE
                    "author" -> SearchScope.AUTHOR
                    "subject", "topic" -> SearchScope.SUBJECT
                    "identifier", "id", "source", "url" -> SearchScope.IDENTIFIER
                    "d", "slug" -> SearchScope.SLUG
                    "language", "lang" -> SearchScope.METADATA
                    else -> null
                }
            }
            val value = match?.groupValues?.getOrNull(2)?.trim().takeIf { !it.isNullOrBlank() } ?: trimmed
            val parsedLanguage = match?.groupValues?.getOrNull(1)?.lowercase()?.let { name ->
                value.takeIf { name == "language" || name == "lang" }
            }
            val coordinate = value.split(":", limit = 3).takeIf { it.size == 3 && it[1].matches(HEX_64) && it[2].isNotBlank() }
            val eventId = value.lowercase().takeIf { it.matches(HEX_64) }
            return BookSearchQuery(
                text = if (coordinate != null || eventId != null || parsedLanguage != null) "" else value,
                scope = parsedScope ?: scope,
                language = parsedLanguage ?: language,
                eventId = eventId,
                coordinate = coordinate?.let { "${it[0].toIntOrNull() ?: return@let null}:${it[1].lowercase()}:${it[2]}" },
            )
        }

        private val FIELD_QUERY = Regex("^\\s*(title|author|subject|topic|language|lang|identifier|id|source|url|d|slug)\\s*:\\s*(.+?)\\s*$", RegexOption.IGNORE_CASE)
        private val HEX_64 = Regex("^[a-f0-9]{64}$", RegexOption.IGNORE_CASE)
    }
}

enum class MatchProvenance {
    METADATA,
    TITLE,
    AUTHOR,
    SUBJECT,
    IDENTIFIER,
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
