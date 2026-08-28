package eu.decentnewsroom.bookshelf.data.mercury

import eu.decentnewsroom.bookshelf.domain.BookChapter
import eu.decentnewsroom.bookshelf.domain.BookDetail
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.BookReference
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.domain.ChapterReference
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.Locale

class MercuryBookRepository(
    private val apiClient: MercuryApiClient,
    private val chapterEventSource: ChapterEventSource? = null,
    private val searchResilience: MercurySearchResilience = MercurySearchResilience(),
    nowMillis: () -> Long = System::currentTimeMillis,
    searchCacheTtlMillis: Long = SEARCH_CACHE_TTL_MILLIS,
) {
    private val searchCache = BookSearchCache(nowMillis, searchCacheTtlMillis, MAX_SEARCH_CACHE_ENTRIES)
    suspend fun search(query: String): List<BookSummary> =
        search(BookSearchQuery.from(query)).map(BookSearchResult::book)

    suspend fun search(query: BookSearchQuery): List<BookSearchResult> {
        val outcome = searchOutcome(query)
        if (outcome.status == BookSearchStatus.UNAVAILABLE) {
            throw MercuryApiException(
                message = "Mercury search is temporarily unavailable.",
                retryAfterMillis = outcome.retryAfterMillis,
            )
        }
        return outcome.results
    }

    suspend fun searchOutcome(query: BookSearchQuery): BookSearchOutcome {
        val normalizedQuery = query.normalizedForCache()
        searchCache.get(normalizedQuery)?.let { return it }
        val outcome = performSearch(normalizedQuery)
        if (outcome.status == BookSearchStatus.COMPLETE) searchCache.put(normalizedQuery, outcome)
        return outcome
    }

    private suspend fun performSearch(query: BookSearchQuery): BookSearchOutcome = supervisorScope {
        val plan = SearchPlan.from(query)
        if (plan.isEmpty) return@supervisorScope BookSearchOutcome(emptyList(), BookSearchStatus.COMPLETE)

        val publicationSearches = plan.publicationSearches
        val publicationResults = publicationSearches.map { search ->
            async { runSearchBranch { apiClient.searchPublications(search.request, PUBLICATION_SEARCH_LIMIT) } }
        }
        val exactResult = plan.exactEventId?.let { id ->
            async { runSearchBranch { apiClient.getEvent(id)?.let(::listOf).orEmpty() } }
        }
        val coordinateResult = plan.publicationCoordinate?.let { coordinate ->
            async {
                runSearchBranch {
                    apiClient.getPublicationsByCoordinates(
                        listOf(PublicationCoordinate(coordinate.pubkey, coordinate.identifier)),
                    )
                }
            }
        }
        val sectionResult = plan.sectionQuery?.let { q ->
            async { runSearchBranch { apiClient.searchPublicationSections(q, SECTION_SEARCH_LIMIT) } }
        }
        val chapterResult = plan.chapterCoordinate?.let { coordinate ->
            async { runSearchBranch { apiClient.getChaptersByCoordinates(listOf(coordinate.coordinate)) } }
        }

        val attempt = SearchAttempt()
        fun events(branch: SearchBranch<List<NostrEvent>>?): List<NostrEvent> {
            if (branch == null) return emptyList()
            attempt.record(branch)
            return when (branch) {
                is SearchBranch.Success -> branch.value
                is SearchBranch.Failure -> emptyList()
            }
        }

        val exactEvents = events(exactResult?.await())
        val coordinateEvents = events(coordinateResult?.await())
        val publicationEventLists = publicationResults.map { events(it.await()) }
        val sectionEvents = events(sectionResult?.await())
        val chapterEvents = events(chapterResult?.await())
        val hits = linkedMapOf<String, SearchHit>()
        var sequence = 0

        fun record(
            book: BookSummary,
            provenance: Set<MatchProvenance>,
            metadataRank: Int? = null,
            sectionRank: Int? = null,
            chapterCoordinate: String? = null,
            chapterTitle: String? = null,
            excerpt: String? = null,
        ) {
            val existing = hits[book.coordinate]
            if (existing == null) {
                hits[book.coordinate] = SearchHit(
                    book, provenance, metadataRank, sectionRank, chapterCoordinate,
                    chapterTitle, excerpt, sequence++,
                )
            } else {
                hits[book.coordinate] = existing.copy(
                    book = if (book.createdAt > existing.book.createdAt) book else existing.book,
                    provenance = existing.provenance + provenance,
                    metadataRank = minOfNullable(existing.metadataRank, metadataRank),
                    sectionRank = minOfNullable(existing.sectionRank, sectionRank),
                    chapterCoordinate = existing.chapterCoordinate ?: chapterCoordinate,
                    chapterTitle = existing.chapterTitle ?: chapterTitle,
                    excerpt = existing.excerpt ?: excerpt,
                )
            }
        }

        exactEvents.forEachIndexed { index, event ->
            mapIndexEvent(event)?.let { record(it, setOf(MatchProvenance.EXACT_EVENT), metadataRank = index) }
        }
        val publicationCoordinate = plan.publicationCoordinate
        coordinateEvents.forEachIndexed { index, event ->
            val book = mapIndexEvent(event) ?: return@forEachIndexed
            if (publicationCoordinate != null && book.coordinate == publicationCoordinate.coordinate) {
                record(book, setOf(MatchProvenance.EXACT_COORDINATE), metadataRank = index)
            }
        }
        publicationEventLists.forEachIndexed { searchIndex, publicationEvents ->
            val search = publicationSearches[searchIndex]
            publicationEvents.forEachIndexed { index, event ->
                mapIndexEvent(event)?.let { book ->
                    record(book, metadataProvenance(book, query.normalizedText, search.provenance), metadataRank = index)
                }
            }
        }

        val sectionRanks = linkedMapOf<String, Int>()
        plan.chapterCoordinate?.let { sectionRanks[it.coordinate] = 0 }
        exactEvents.forEachIndexed { index, event ->
            publicationContentCoordinate(event)?.let { sectionRanks.putIfAbsent(it, index) }
        }
        chapterEvents.forEachIndexed { index, event ->
            publicationContentCoordinate(event)?.let { sectionRanks[it] = index }
        }
        sectionEvents.forEachIndexed { index, event ->
            publicationContentCoordinate(event)?.let { sectionRanks.putIfAbsent(it, index) }
        }

        if (sectionRanks.isNotEmpty()) {
            val references = events(
                runSearchBranch {
                    apiClient.getPublicationsReferencingChapters(
                        chapterCoordinates = sectionRanks.keys.toList(),
                        limit = SECTION_PUBLICATION_LIMIT,
                    )
                },
            )
            references.forEachIndexed { index, event ->
                val book = mapIndexEvent(event) ?: return@forEachIndexed
                val sectionRank = book.chapterRefs.mapNotNull { sectionRanks[it.coordinate] }.minOrNull() ?: index
                val sectionEvent = (sectionEvents + chapterEvents).firstOrNull {
                    publicationContentCoordinate(it) in book.chapterRefs.map(ChapterReference::coordinate)
                }
                record(
                    book = book,
                    provenance = sectionEvent?.let { sectionProvenance(it, plan.sectionQuery) }
                        ?: setOf(MatchProvenance.CHAPTER_BODY),
                    sectionRank = sectionRank,
                    chapterCoordinate = sectionEvent?.let(::publicationContentCoordinate),
                    chapterTitle = sectionEvent?.let {
                        firstTagValue(it.tags, "title") ?: firstTagValue(it.tags, "T")
                    },
                    excerpt = sectionEvent?.let { boundedExcerpt(it.content, plan.sectionQuery) },
                )
            }
        }

        val results = hits.values
            .sortedWith(
                compareByDescending<SearchHit> { it.score }
                    .thenBy { it.sequence }
                    .thenByDescending { it.book.createdAt },
            )
            .take(MAX_SEARCH_RESULTS)
            .mapIndexed { rank, hit ->
                BookSearchResult(
                    book = hit.book,
                    provenance = hit.provenance,
                    matchedChapterCoordinate = hit.chapterCoordinate,
                    matchedChapterTitle = hit.chapterTitle,
                    excerpt = hit.excerpt,
                    rank = rank,
                )
            }

        BookSearchOutcome(results, attempt.status, attempt.retryAfterMillis)
    }

    private suspend fun <T> runSearchBranch(block: suspend () -> T): SearchBranch<T> =
        try {
            SearchBranch.Success(searchResilience.execute(block))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: MercuryApiException) {
            SearchBranch.Failure(exception)
        }

    suspend fun getBook(eventId: String): BookDetail? {
        val indexEvent = apiClient.getEvent(eventId.lowercase(), BookKinds.PUBLICATION_INDEX) ?: return null
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

        indexPublicationEvents(
            events = apiClient.getPublicationEventsByIds(eventIds),
            eventsById = eventsById,
            eventsByCoordinate = eventsByCoordinate,
        )

        val unresolvedCoordinates = references.mapNotNull { reference ->
            val coordinate = reference.coordinate ?: return@mapNotNull null
            if (eventsByCoordinate[coordinate] != null) return@mapNotNull null
            val parts = coordinate.split(":", limit = 3)
            if (parts.size != 3 || parts[0].toIntOrNull() != BookKinds.PUBLICATION_INDEX) return@mapNotNull null
            PublicationCoordinate(parts[1], parts[2])
        }

        if (unresolvedCoordinates.isNotEmpty()) {
            indexPublicationEvents(
                events = apiClient.getPublicationsByCoordinates(unresolvedCoordinates),
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
            TrustedCoverImagePolicy.sanitize(firstTagValue(event.tags, "image")) ?: gutenbergCoverImageUrl(
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
        val references = mutableListOf<ChapterReference>()
        for (tag in tags) {
            if (references.size >= MAX_CHAPTERS) break
            if (tag.getOrNull(0) != "a") {
                continue
            }

            val coordinate = tag.getOrNull(1) ?: continue
            val parts = coordinate.split(":", limit = 3)
            if (parts.size != 3 || parts[0].toIntOrNull() != BookKinds.PUBLICATION_CONTENT) {
                continue
            }
            val normalizedCoordinate = "${BookKinds.PUBLICATION_CONTENT}:${parts[1].lowercase()}:${parts[2]}"
            if (!seen.add(normalizedCoordinate)) {
                continue
            }

            references += ChapterReference(
                coordinate = normalizedCoordinate,
                pubkey = parts[1].lowercase(),
                identifier = parts[2],
                relay = tag.getOrNull(2)?.takeIf { it.startsWith("wss://") },
                eventId = tag.getOrNull(3)?.lowercase()?.takeIf { HEX_64.matches(it) },
            )
        }
        return references
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

    private fun sectionProvenance(event: NostrEvent, query: String?): Set<MatchProvenance> {
        val rawNeedle = query?.trim()?.takeIf(String::isNotBlank)
            ?: return setOf(MatchProvenance.CHAPTER_BODY)
        val quoted = isQuotedPhrase(rawNeedle)
        val needle = rawNeedle.trim { it == 34.toChar() || it == 39.toChar() }
        val title = firstTagValue(event.tags, "title") ?: firstTagValue(event.tags, "T").orEmpty()
        val matches = linkedSetOf<MatchProvenance>()
        if (matchesSearchTerms(title, needle, quoted)) matches += MatchProvenance.CHAPTER_TITLE
        if (matchesSearchTerms(event.content, needle, quoted)) matches += MatchProvenance.CHAPTER_BODY
        return matches.ifEmpty { setOf(MatchProvenance.CHAPTER_BODY) }
    }

    private fun metadataProvenance(
        book: BookSummary,
        query: String,
        fallback: MatchProvenance,
    ): Set<MatchProvenance> {
        val needle = query.trim().trim { it == 34.toChar() || it == 39.toChar() }
        if (needle.isBlank()) return setOf(fallback)
        val matches = linkedSetOf<MatchProvenance>()
        if (book.title.contains(needle, ignoreCase = true)) matches += MatchProvenance.TITLE
        if (book.authors.any { it.contains(needle, ignoreCase = true) }) matches += MatchProvenance.AUTHOR
        if (book.topics.any { it.contains(needle, ignoreCase = true) }) matches += MatchProvenance.SUBJECT
        if (book.identifier.contains(needle, ignoreCase = true)) matches += MatchProvenance.IDENTIFIER
        return matches.ifEmpty { setOf(fallback) }
    }

    private fun boundedExcerpt(content: String, query: String?, maxLength: Int = MAX_EXCERPT_LENGTH): String? {
        val rawNeedle = query?.trim()?.takeIf(String::isNotBlank) ?: return null
        val quoted = isQuotedPhrase(rawNeedle)
        val needle = rawNeedle.trim { it == 34.toChar() || it == 39.toChar() }
        val compact = content.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').replace(Regex(" +"), " ").trim()
        if (compact.isBlank() || !matchesSearchTerms(compact, needle, quoted)) return null
        val matchIndex = compact.indexOf(needle.split(Regex("\\s+")).first(), ignoreCase = true).coerceAtLeast(0)
        val start = (matchIndex - maxLength / 3).coerceAtLeast(0)
        val end = (start + maxLength).coerceAtMost(compact.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < compact.length) "…" else ""
        return (prefix + compact.substring(start, end).trim() + suffix).take(maxLength)
    }

    private fun matchesSearchTerms(value: String, query: String, quoted: Boolean = false): Boolean {
        val terms = query.split(Regex("\\s+")).filter(String::isNotBlank)
        return if (terms.size <= 1 || quoted) {
            value.contains(query, ignoreCase = true)
        } else {
            terms.all { value.contains(it, ignoreCase = true) }
        }
    }

    private fun isQuotedPhrase(value: String): Boolean =
        value.length >= 2 &&
            ((value.first() == 34.toChar() && value.last() == 34.toChar()) ||
                (value.first() == 39.toChar() && value.last() == 39.toChar()))

    private fun <T : Comparable<T>> minOfNullable(first: T?, second: T?): T? =
        when {
            first == null -> second
            second == null -> first
            else -> minOf(first, second)
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
        val provenance: Set<MatchProvenance>,
        val metadataRank: Int?,
        val sectionRank: Int?,
        val chapterCoordinate: String?,
        val chapterTitle: String?,
        val excerpt: String?,
        val sequence: Int,
    ) {
        val score: Double
            get() = (metadataRank?.let { 1.0 / (RRF_K + it + 1) } ?: 0.0) +
                (sectionRank?.let { 1.0 / (RRF_K + it + 1) } ?: 0.0)
    }

    private data class PublicationSearch(
        val request: MercuryPublicationSearch,
        val provenance: MatchProvenance,
    )

    private data class ParsedCoordinate(
        val coordinate: String,
        val kind: Int,
        val pubkey: String,
        val identifier: String,
    )

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
            fun from(query: BookSearchQuery): SearchPlan {
                val raw = query.normalizedText
                val coordinate = parseCoordinate(query.coordinate ?: raw)
                val eventId = query.eventId ?: raw.lowercase(Locale.US).takeIf { HEX_64.matches(it) }
                if ((raw.isBlank() && coordinate == null && eventId == null && query.language == null) || raw.length > MAX_SEARCH_QUERY_LENGTH) {
                    return SearchPlan(emptyList(), null, eventId, null, null)
                }
                val searchText = raw
                val metadataSearch = when (query.scope) {
                    SearchScope.TITLE -> PublicationSearch(MercuryPublicationSearch(title = searchText), MatchProvenance.TITLE)
                    SearchScope.AUTHOR -> PublicationSearch(MercuryPublicationSearch(author = searchText), MatchProvenance.AUTHOR)
                    SearchScope.SUBJECT -> PublicationSearch(MercuryPublicationSearch(subject = slugify(searchText) ?: searchText), MatchProvenance.SUBJECT)
                    SearchScope.IDENTIFIER -> PublicationSearch(MercuryPublicationSearch(identifier = searchText), MatchProvenance.IDENTIFIER)
                    SearchScope.SLUG -> PublicationSearch(MercuryPublicationSearch(d = slugify(searchText) ?: searchText), MatchProvenance.IDENTIFIER)
                    SearchScope.METADATA, SearchScope.ALL -> PublicationSearch(
                        MercuryPublicationSearch(q = searchText.takeIf(String::isNotBlank), language = query.language?.lowercase(Locale.US)),
                        MatchProvenance.METADATA,
                    )
                    SearchScope.CHAPTER_CONTENT -> null
                }
                val metadata = if (coordinate == null && eventId == null) listOfNotNull(metadataSearch) else emptyList()
                val section = when (query.scope) {
                    SearchScope.ALL, SearchScope.CHAPTER_CONTENT -> searchText.takeIf(::canSearchSections)
                    else -> null
                }
                return SearchPlan(
                    publicationSearches = metadata,
                    sectionQuery = section,
                    exactEventId = eventId,
                    publicationCoordinate = coordinate?.takeIf { it.kind == BookKinds.PUBLICATION_INDEX },
                    chapterCoordinate = coordinate?.takeIf { it.kind == BookKinds.PUBLICATION_CONTENT },
                )
            }

        }
    }

    private companion object {
        const val SEARCH_CACHE_TTL_MILLIS = 30_000L
        const val MAX_SEARCH_CACHE_ENTRIES = 20
        const val MAX_SEARCH_RESULTS = 40
        const val MAX_SEARCH_QUERY_LENGTH = 256
        const val MAX_EXCERPT_LENGTH = 320
        const val RRF_K = 60.0
        const val MAX_CHAPTERS = 500
        const val PUBLICATION_SEARCH_LIMIT = 60
        const val SECTION_SEARCH_LIMIT = 60
        const val SECTION_PUBLICATION_LIMIT = 100
        val HEX_64 = Regex("^[a-f0-9]{64}$", RegexOption.IGNORE_CASE)
        val ROMAN_NUMERAL = Regex("^[ivxlcdm]+$", RegexOption.IGNORE_CASE)
        val GUTENBERG_IDENTIFIER = Regex("^pg(\\d+)(?:[-_].*)?$", RegexOption.IGNORE_CASE)
        val GUTENBERG_URL_PATH = Regex("/(?:ebooks|files|cache/epub)/(\\d+)(?:/|$)")

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

        fun canSearchSections(value: String): Boolean = value.trim().trim('"', '\'').length >= 4
    }
}
private sealed interface SearchBranch<out T> {
    data class Success<T>(val value: T) : SearchBranch<T>
    data class Failure(val exception: MercuryApiException) : SearchBranch<Nothing>
}

private class SearchAttempt {
    private var successCount = 0
    private var failureCount = 0
    var retryAfterMillis: Long? = null
        private set

    val status: BookSearchStatus
        get() = when {
            failureCount == 0 -> BookSearchStatus.COMPLETE
            successCount > 0 -> BookSearchStatus.PARTIAL
            else -> BookSearchStatus.UNAVAILABLE
        }

    fun record(branch: SearchBranch<*>) {
        when (branch) {
            is SearchBranch.Success -> successCount += 1
            is SearchBranch.Failure -> {
                failureCount += 1
                branch.exception.retryAfterMillis?.let {
                    retryAfterMillis = maxOf(retryAfterMillis ?: 0, it)
                }
            }
        }
    }
}

private class BookSearchCache(
    private val nowMillis: () -> Long,
    private val ttlMillis: Long,
    private val maxEntries: Int,
) {
    private data class Entry(val outcome: BookSearchOutcome, val expiresAtMillis: Long)
    private val mutex = Mutex()
    private val entries = linkedMapOf<BookSearchQuery, Entry>()

    suspend fun get(query: BookSearchQuery): BookSearchOutcome? = mutex.withLock {
        removeExpired()
        entries[query]?.outcome
    }

    suspend fun put(query: BookSearchQuery, outcome: BookSearchOutcome) {
        if (ttlMillis <= 0 || maxEntries <= 0) return
        mutex.withLock {
            removeExpired()
            entries.remove(query)
            while (entries.size >= maxEntries) entries.remove(entries.keys.first())
            entries[query] = Entry(outcome, nowMillis() + ttlMillis)
        }
    }

    private fun removeExpired() {
        val now = nowMillis()
        entries.entries.removeAll { it.value.expiresAtMillis <= now }
    }
}

private fun BookSearchQuery.normalizedForCache(): BookSearchQuery =
    copy(
        text = normalizedText,
        language = language?.trim()?.lowercase(Locale.US),
        eventId = eventId?.lowercase(Locale.US),
        coordinate = coordinate?.trim(),
    )
