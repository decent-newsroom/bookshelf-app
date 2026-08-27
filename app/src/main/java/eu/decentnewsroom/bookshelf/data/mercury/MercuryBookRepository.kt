package eu.decentnewsroom.bookshelf.data.mercury

import eu.decentnewsroom.bookshelf.domain.BookChapter
import eu.decentnewsroom.bookshelf.domain.BookDetail
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.BookReference
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.domain.ChapterReference
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URI
import java.util.Locale

class MercuryBookRepository(
    private val apiClient: MercuryApiClient,
    private val chapterEventSource: ChapterEventSource? = null,
) {
    suspend fun search(query: String): List<BookSummary> = coroutineScope {
        val plan = SearchPlan.from(query)
        if (plan.isEmpty) {
            return@coroutineScope emptyList()
        }

        val publicationSearches = plan.publicationSearches
        val publicationSearchResults =
            publicationSearches.map { search ->
                async { apiClient.searchPublications(search.request, PUBLICATION_SEARCH_LIMIT) }
            }
        val exactEventResult =
            plan.exactEventId?.let { eventId ->
                async { apiClient.getEvent(eventId)?.let(::listOf).orEmpty() }
            }
        val coordinateResult =
            plan.publicationCoordinate?.let { coordinate ->
                async { apiClient.getPublicationsByAuthors(listOf(coordinate.pubkey), PUBLICATION_COORDINATE_LIMIT) }
            }
        val sectionSearchResult =
            plan.sectionQuery?.let { sectionQuery ->
                async { apiClient.searchPublicationSections(sectionQuery, SECTION_SEARCH_LIMIT) }
            }

        val hits = linkedMapOf<String, SearchHit>()
        var sequence = 0

        fun record(book: BookSummary, score: Int) {
            val existing = hits[book.coordinate]
            if (existing == null) {
                hits[book.coordinate] = SearchHit(book = book, score = score, sequence = sequence)
                sequence += 1
                return
            }

            hits[book.coordinate] = existing.copy(
                book = if (book.createdAt > existing.book.createdAt) book else existing.book,
                score = maxOf(score, existing.score),
            )
        }

        exactEventResult?.await().orEmpty().forEachIndexed { index, event ->
            mapIndexEvent(event)?.let { book -> record(book, SCORE_EXACT_EVENT - index) }
        }

        val publicationCoordinate = plan.publicationCoordinate
        coordinateResult?.await().orEmpty().forEachIndexed { index, event ->
            val book = mapIndexEvent(event) ?: return@forEachIndexed
            if (publicationCoordinate != null && book.coordinate == publicationCoordinate.coordinate) {
                record(book, SCORE_EXACT_COORDINATE - index)
            }
        }

        publicationSearchResults.awaitAll().forEachIndexed { searchIndex, events ->
            val baseScore = publicationSearches[searchIndex].score
            events.forEachIndexed { index, event ->
                mapIndexEvent(event)?.let { book -> record(book, baseScore - index) }
            }
        }

        val sectionRanks = linkedMapOf<String, Int>()
        plan.chapterCoordinate?.let { coordinate -> sectionRanks[coordinate.coordinate] = 0 }
        exactEventResult?.await().orEmpty().forEachIndexed { index, event ->
            publicationContentCoordinate(event)?.let { coordinate ->
                sectionRanks.putIfAbsent(coordinate, index)
            }
        }
        sectionSearchResult?.await().orEmpty().forEachIndexed { index, event ->
            publicationContentCoordinate(event)?.let { coordinate ->
                sectionRanks.putIfAbsent(coordinate, index)
            }
        }

        if (sectionRanks.isNotEmpty()) {
            apiClient.getPublicationsReferencingChapters(
                chapterCoordinates = sectionRanks.keys.toList(),
                limit = SECTION_PUBLICATION_LIMIT,
            ).forEachIndexed { index, event ->
                val book = mapIndexEvent(event) ?: return@forEachIndexed
                val sectionRank = book.chapterRefs.mapNotNull { sectionRanks[it.coordinate] }.minOrNull() ?: index
                record(book, SCORE_SECTION - sectionRank)
            }
        }

        hits.values
            .sortedWith(
                compareByDescending<SearchHit> { it.score }
                    .thenBy { it.sequence }
                    .thenByDescending { it.book.createdAt },
            )
            .take(MAX_SEARCH_RESULTS)
            .map(SearchHit::book)
    }

    suspend fun getBook(eventId: String): BookDetail? {
        val indexEvent = apiClient.getEvent(eventId.lowercase()) ?: return null
        val book = mapIndexEvent(indexEvent) ?: return null
        val refs = book.chapterRefs.take(MAX_CHAPTERS)
        val eventsById = mutableMapOf<String, NostrEvent>()
        val eventsByCoordinate = mutableMapOf<String, NostrEvent>()

        chapterEventSource?.let { source ->
            runCatching { source.fetchChapters(refs) }
                .getOrDefault(emptyList())
                .let { events ->
                    indexChapterEvents(
                        events = events,
                        eventsById = eventsById,
                        eventsByCoordinate = eventsByCoordinate,
                    )
                }
        }

        val unresolvedEventIds =
            refs
                .filter { ref ->
                    ref.eventId != null &&
                        eventsById[ref.eventId] == null &&
                        eventsByCoordinate[ref.coordinate] == null
                }
                .mapNotNull(ChapterReference::eventId)

        indexChapterEvents(
            events = runCatching { apiClient.getEventsByIds(unresolvedEventIds) }.getOrDefault(emptyList()),
            eventsById = eventsById,
            eventsByCoordinate = eventsByCoordinate,
        )

        val unresolvedAuthors =
            refs
                .filter { ref ->
                    (ref.eventId == null || eventsById[ref.eventId] == null) &&
                        eventsByCoordinate[ref.coordinate] == null
                }
                .map(ChapterReference::pubkey)
                .distinct()

        if (unresolvedAuthors.isNotEmpty()) {
            indexChapterEvents(
                events =
                    runCatching {
                        apiClient.getChaptersByAuthors(unresolvedAuthors, MAX_CHAPTERS)
                    }.getOrDefault(emptyList()),
                eventsById = eventsById,
                eventsByCoordinate = eventsByCoordinate,
            )
        }

        val chapters =
            refs.mapIndexed { index, ref ->
                val event = ref.eventId?.let(eventsById::get) ?: eventsByCoordinate[ref.coordinate]
                mapChapter(ref, event, index + 1)
            }
        val availableCount = chapters.count(BookChapter::available)

        return BookDetail(
            summary = book,
            chapters = chapters,
            availableChapterCount = availableCount,
            missingChapterCount = chapters.size - availableCount,
            truncated = book.chapterRefs.size > MAX_CHAPTERS,
        )
    }

    suspend fun getBooksForReferences(references: List<BookReference>): List<BookSummary> {
        val eventsById = mutableMapOf<String, NostrEvent>()
        val eventsByCoordinate = mutableMapOf<String, NostrEvent>()
        val eventIds = references.mapNotNull(BookReference::eventId)
        val authors = references.mapNotNull(BookReference::pubkey).distinct()

        indexPublicationEvents(
            events = apiClient.getPublicationEventsByIds(eventIds),
            eventsById = eventsById,
            eventsByCoordinate = eventsByCoordinate,
        )

        val unresolvedCoordinates =
            references.any { reference ->
                reference.coordinate != null && eventsByCoordinate[reference.coordinate] == null
            }

        if (unresolvedCoordinates) {
            indexPublicationEvents(
                events = apiClient.getPublicationsByAuthors(
                    authors = authors,
                    limit = minOf(MAX_SEARCH_RESULTS * 5, 100),
                ),
                eventsById = eventsById,
                eventsByCoordinate = eventsByCoordinate,
            )
        }

        val seen = mutableSetOf<String>()

        return references.mapNotNull { reference ->
            val event = reference.eventId?.let(eventsById::get) ?: reference.coordinate?.let(eventsByCoordinate::get)
            val book = event?.let(::mapIndexEvent) ?: return@mapNotNull null

            book.takeIf { seen.add(it.coordinate) }
        }
    }

    private fun mapIndexEvent(event: NostrEvent): BookSummary? {
        if (event.kind != BookKinds.PUBLICATION_INDEX) {
            return null
        }

        val chapterRefs = extractChapterRefs(event.tags)
        if (chapterRefs.isEmpty()) {
            return null
        }

        val identifier = firstTagValue(event.tags, "d") ?: return null
        if (event.id.isBlank() || event.pubkey.isBlank() || identifier.isBlank()) {
            return null
        }

        val sourceUrl = httpUrlTag(event.tags, "source") ?: httpUrlTag(event.tags, "s")
        val coverImageUrl =
            httpUrlTag(event.tags, "image") ?: gutenbergCoverImageUrl(
                identifier = identifier,
                sourceUrl = sourceUrl,
            )

        return BookSummary(
            id = event.id.lowercase(),
            coordinate = "${BookKinds.PUBLICATION_INDEX}:${event.pubkey.lowercase()}:$identifier",
            pubkey = event.pubkey.lowercase(),
            identifier = identifier,
            title = firstTagValue(event.tags, "title") ?: firstTagValue(event.tags, "T") ?: identifier,
            summary = firstNonEmptyTagValue(event.tags, listOf("summary", "description")),
            authors = tagValues(event.tags, "author").ifEmpty { tagValues(event.tags, "N") },
            coverImageUrl = coverImageUrl,
            sourceUrl = sourceUrl,
            language = firstTagValue(event.tags, "l"),
            releaseDate = firstNonEmptyTagValue(event.tags, listOf("release_date", "published_on")),
            version = firstTagValue(event.tags, "version"),
            type = firstTagValue(event.tags, "type") ?: "book",
            topics = tagValues(event.tags, "t"),
            relay = apiClient.getRelayHint(),
            createdAt = event.createdAt,
            chapterCount = chapterRefs.size,
            chapterRefs = chapterRefs,
        )
    }

    private fun extractChapterRefs(tags: List<List<String>>): List<ChapterReference> {
        val seen = mutableSetOf<String>()

        return tags.mapNotNull { tag ->
            if (tag.getOrNull(0) != "a") {
                return@mapNotNull null
            }

            val coordinate = tag.getOrNull(1) ?: return@mapNotNull null
            val parts = coordinate.split(":", limit = 3)
            if (parts.size != 3 || parts[0].toIntOrNull() != BookKinds.PUBLICATION_CONTENT) {
                return@mapNotNull null
            }
            val normalizedCoordinate = "${BookKinds.PUBLICATION_CONTENT}:${parts[1].lowercase()}:${parts[2]}"
            if (!seen.add(normalizedCoordinate)) {
                return@mapNotNull null
            }

            ChapterReference(
                coordinate = normalizedCoordinate,
                pubkey = parts[1].lowercase(),
                identifier = parts[2],
                relay = tag.getOrNull(2)?.takeIf { it.startsWith("wss://") },
                eventId = tag.getOrNull(3)?.lowercase()?.takeIf { HEX_64.matches(it) },
            )
        }
    }

    private fun publicationContentCoordinate(event: NostrEvent): String? {
        if (event.kind != BookKinds.PUBLICATION_CONTENT || event.pubkey.isBlank()) {
            return null
        }

        val identifier = firstTagValue(event.tags, "d")?.takeIf(String::isNotBlank) ?: return null
        return "${BookKinds.PUBLICATION_CONTENT}:${event.pubkey.lowercase()}:$identifier"
    }

    private fun indexChapterEvents(
        events: List<NostrEvent>,
        eventsById: MutableMap<String, NostrEvent>,
        eventsByCoordinate: MutableMap<String, NostrEvent>,
    ) {
        events.forEach { event ->
            if (event.kind != BookKinds.PUBLICATION_CONTENT || event.id.isBlank() || event.pubkey.isBlank()) {
                return@forEach
            }

            val identifier = firstTagValue(event.tags, "d") ?: return@forEach
            val coordinate = "${BookKinds.PUBLICATION_CONTENT}:${event.pubkey.lowercase()}:$identifier"
            val current = eventsByCoordinate[coordinate]

            eventsById[event.id.lowercase()] = event
            if (current == null || event.createdAt > current.createdAt) {
                eventsByCoordinate[coordinate] = event
            }
        }
    }

    private fun indexPublicationEvents(
        events: List<NostrEvent>,
        eventsById: MutableMap<String, NostrEvent>,
        eventsByCoordinate: MutableMap<String, NostrEvent>,
    ) {
        events.forEach { event ->
            val book = mapIndexEvent(event) ?: return@forEach
            val current = eventsByCoordinate[book.coordinate]

            eventsById[book.id] = event
            if (current == null || event.createdAt > current.createdAt) {
                eventsByCoordinate[book.coordinate] = event
            }
        }
    }

    private fun mapChapter(ref: ChapterReference, event: NostrEvent?, position: Int): BookChapter {
        if (event == null) {
            return BookChapter(
                reference = ref,
                position = position,
                available = false,
                title = humanizeIdentifier(ref.identifier),
                summary = null,
                content = null,
                id = null,
                createdAt = null,
            )
        }

        return BookChapter(
            reference = ref,
            position = position,
            available = true,
            title = firstTagValue(event.tags, "title") ?: firstTagValue(event.tags, "T") ?: humanizeIdentifier(ref.identifier),
            summary = firstNonEmptyTagValue(event.tags, listOf("summary", "description")),
            content = event.content,
            id = event.id.lowercase(),
            createdAt = event.createdAt,
        )
    }

    private fun firstTagValue(tags: List<List<String>>, name: String): String? =
        tags.firstNotNullOfOrNull { tag ->
            tag.getOrNull(1)?.trim()?.takeIf { tag.getOrNull(0) == name }
        }

    private fun firstNonEmptyTagValue(tags: List<List<String>>, names: List<String>): String? =
        names.firstNotNullOfOrNull { name -> firstTagValue(tags, name)?.takeIf(String::isNotEmpty) }

    private fun tagValues(tags: List<List<String>>, name: String): List<String> =
        tags
            .mapNotNull { tag -> tag.getOrNull(1)?.trim()?.takeIf { tag.getOrNull(0) == name && it.isNotEmpty() } }
            .distinct()

    private fun httpUrlTag(tags: List<List<String>>, name: String): String? =
        normalizeHttpUrl(firstTagValue(tags, name))

    private fun gutenbergCoverImageUrl(identifier: String, sourceUrl: String?): String? {
        val ebookId = sourceUrl?.let(::gutenbergEbookIdFromUrl) ?: gutenbergEbookIdFromIdentifier(identifier)

        return ebookId?.let { id -> "https://www.gutenberg.org/cache/epub/$id/pg$id.cover.medium.jpg" }
    }

    private fun gutenbergEbookIdFromIdentifier(identifier: String): String? {
        val match = GUTENBERG_IDENTIFIER.matchEntire(identifier.trim()) ?: return null

        return match.groupValues[1]
    }

    private fun gutenbergEbookIdFromUrl(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.US) ?: return null
        if (host != "gutenberg.org" && !host.endsWith(".gutenberg.org")) {
            return null
        }

        val path = uri.path ?: return null
        val match = GUTENBERG_URL_PATH.find(path) ?: return null

        return match.groupValues[1]
    }

    private fun normalizeHttpUrl(url: String?): String? {
        val trimmed = url?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()

        return trimmed.takeIf { scheme in setOf("http", "https") && !uri.host.isNullOrBlank() }
    }

    private fun humanizeIdentifier(identifier: String): String {
        val words =
            identifier
                .replace(Regex("^pg\\d+-chapter-\\d+-?", RegexOption.IGNORE_CASE), "")
                .replace('-', ' ')
                .replace('_', ' ')
                .trim()

        if (words.isBlank()) {
            return identifier
        }

        return words
            .split(Regex("\\s+"))
            .joinToString(" ") { word ->
                if (ROMAN_NUMERAL.matches(word)) {
                    word.uppercase()
                } else {
                    word.lowercase().replaceFirstChar { it.titlecase() }
                }
            }
    }

    private data class SearchHit(
        val book: BookSummary,
        val score: Int,
        val sequence: Int,
    )

    private data class PublicationSearch(
        val request: MercuryPublicationSearch,
        val score: Int,
    )

    private data class ParsedCoordinate(
        val coordinate: String,
        val kind: Int,
        val pubkey: String,
        val identifier: String,
    )

    private data class FieldQuery(
        val name: String,
        val value: String,
    ) {
        fun toPublicationSearch(): PublicationSearch =
            when (name) {
                "title" -> PublicationSearch(MercuryPublicationSearch(title = value), SCORE_TITLE)
                "author" -> PublicationSearch(MercuryPublicationSearch(author = value), SCORE_AUTHOR)
                "subject", "topic" -> PublicationSearch(MercuryPublicationSearch(subject = slugify(value) ?: value), SCORE_SUBJECT)
                "language", "lang" -> PublicationSearch(MercuryPublicationSearch(language = value.lowercase(Locale.US)), SCORE_LANGUAGE)
                "identifier", "id", "source", "url" -> PublicationSearch(MercuryPublicationSearch(identifier = value), SCORE_IDENTIFIER)
                "d", "slug" -> PublicationSearch(MercuryPublicationSearch(d = slugify(value) ?: value), SCORE_IDENTIFIER)
                else -> PublicationSearch(MercuryPublicationSearch(q = value), SCORE_METADATA)
            }

        fun sectionSearchText(): String? =
            when (name) {
                "author", "subject", "topic", "language", "lang" -> null
                else -> value
            }
    }

    private data class SearchPlan(
        val publicationSearches: List<PublicationSearch>,
        val sectionQuery: String?,
        val exactEventId: String?,
        val publicationCoordinate: ParsedCoordinate?,
        val chapterCoordinate: ParsedCoordinate?,
    ) {
        val isEmpty: Boolean =
            publicationSearches.isEmpty() && sectionQuery == null && exactEventId == null &&
                publicationCoordinate == null && chapterCoordinate == null

        companion object {
            fun from(rawQuery: String): SearchPlan {
                val query = rawQuery.trim()
                if (query.isBlank()) {
                    return SearchPlan(
                        publicationSearches = emptyList(),
                        sectionQuery = null,
                        exactEventId = null,
                        publicationCoordinate = null,
                        chapterCoordinate = null,
                    )
                }

                val fieldQuery = parseFieldQuery(query)
                val searchText = fieldQuery?.value ?: query
                val coordinate = parseCoordinate(searchText)
                val publicationSearches = mutableListOf<PublicationSearch>()

                if (fieldQuery != null) {
                    publicationSearches += fieldQuery.toPublicationSearch()
                    publicationSearches += PublicationSearch(MercuryPublicationSearch(q = searchText), SCORE_METADATA_FALLBACK)
                } else {
                    publicationSearches += PublicationSearch(MercuryPublicationSearch(q = query), SCORE_METADATA)
                    publicationSearches += PublicationSearch(MercuryPublicationSearch(title = query), SCORE_TITLE)
                    publicationSearches += PublicationSearch(MercuryPublicationSearch(author = query), SCORE_AUTHOR)

                    if (looksLikeIdentifier(query)) {
                        publicationSearches += PublicationSearch(MercuryPublicationSearch(identifier = query), SCORE_IDENTIFIER)
                    }

                    slugify(query)?.let { slug ->
                        publicationSearches += PublicationSearch(MercuryPublicationSearch(d = slug), SCORE_IDENTIFIER)
                        publicationSearches += PublicationSearch(MercuryPublicationSearch(subject = slug), SCORE_SUBJECT)
                    }

                    if (LANGUAGE_CODE.matches(query)) {
                        publicationSearches += PublicationSearch(
                            MercuryPublicationSearch(language = query.lowercase(Locale.US)),
                            SCORE_LANGUAGE,
                        )
                    }
                }

                val sectionQuery = if (fieldQuery == null) query else fieldQuery.sectionSearchText()

                return SearchPlan(
                    publicationSearches = publicationSearches.distinctBy(PublicationSearch::request).take(MAX_PUBLICATION_SEARCHES),
                    sectionQuery = sectionQuery?.takeIf(::canSearchSections),
                    exactEventId = searchText.lowercase(Locale.US).takeIf { HEX_64.matches(it) },
                    publicationCoordinate = coordinate?.takeIf { it.kind == BookKinds.PUBLICATION_INDEX },
                    chapterCoordinate = coordinate?.takeIf { it.kind == BookKinds.PUBLICATION_CONTENT },
                )
            }
        }
    }

    private companion object {
        const val MAX_SEARCH_RESULTS = 40
        const val MAX_CHAPTERS = 500
        const val MAX_PUBLICATION_SEARCHES = 8
        const val PUBLICATION_SEARCH_LIMIT = 60
        const val SECTION_SEARCH_LIMIT = 60
        const val SECTION_PUBLICATION_LIMIT = 100
        const val PUBLICATION_COORDINATE_LIMIT = 100
        const val SCORE_EXACT_EVENT = 1_200
        const val SCORE_EXACT_COORDINATE = 1_150
        const val SCORE_METADATA = 1_000
        const val SCORE_IDENTIFIER = 960
        const val SCORE_TITLE = 940
        const val SCORE_AUTHOR = 900
        const val SCORE_SUBJECT = 850
        const val SCORE_LANGUAGE = 800
        const val SCORE_METADATA_FALLBACK = 760
        const val SCORE_SECTION = 700
        val HEX_64 = Regex("^[a-f0-9]{64}$", RegexOption.IGNORE_CASE)
        val ROMAN_NUMERAL = Regex("^[ivxlcdm]+$", RegexOption.IGNORE_CASE)
        val FIELD_QUERY = Regex(
            "^\\s*(title|author|subject|topic|language|lang|identifier|id|source|url|d|slug)\\s*:\\s*(.+?)\\s*$",
            RegexOption.IGNORE_CASE,
        )
        val LANGUAGE_CODE = Regex("^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})?$")
        val GUTENBERG_IDENTIFIER = Regex("^pg(\\d+)(?:[-_].*)?$", RegexOption.IGNORE_CASE)
        val GUTENBERG_URL_PATH = Regex("/(?:ebooks|files|cache/epub)/(\\d+)(?:/|$)")

        fun parseFieldQuery(query: String): FieldQuery? {
            val match = FIELD_QUERY.matchEntire(query) ?: return null
            val value = match.groupValues[2].trim().takeIf(String::isNotEmpty) ?: return null

            return FieldQuery(
                name = match.groupValues[1].lowercase(Locale.US),
                value = value,
            )
        }

        fun parseCoordinate(value: String): ParsedCoordinate? {
            val parts = value.trim().split(":", limit = 3)
            if (parts.size != 3 || !HEX_64.matches(parts[1]) || parts[2].isBlank()) {
                return null
            }

            val kind = parts[0].toIntOrNull() ?: return null
            if (kind !in setOf(BookKinds.PUBLICATION_INDEX, BookKinds.PUBLICATION_CONTENT)) {
                return null
            }

            return ParsedCoordinate(
                coordinate = "$kind:${parts[1].lowercase(Locale.US)}:${parts[2]}",
                kind = kind,
                pubkey = parts[1].lowercase(Locale.US),
                identifier = parts[2],
            )
        }

        fun slugify(value: String): String? {
            val slug =
                value
                    .trim()
                    .trim('"', '\'')
                    .lowercase(Locale.US)
                    .replace(Regex("[^a-z0-9]+"), "-")
                    .trim('-')

            return slug.takeIf { it.length >= 2 }
        }

        fun looksLikeIdentifier(value: String): Boolean {
            val trimmed = value.trim()
            val uri = runCatching { URI(trimmed) }.getOrNull()
            val isUrl = uri?.scheme?.lowercase(Locale.US) in setOf("http", "https") && !uri?.host.isNullOrBlank()

            return isUrl || HEX_64.matches(trimmed) || parseCoordinate(trimmed) != null ||
                trimmed.startsWith("pg", ignoreCase = true)
        }

        fun canSearchSections(value: String): Boolean = value.trim().trim('"', '\'').length >= 4
    }
}
