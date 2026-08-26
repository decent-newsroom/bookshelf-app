package eu.decentnewsroom.bookshelf.data.mercury

import eu.decentnewsroom.bookshelf.domain.BookChapter
import eu.decentnewsroom.bookshelf.domain.BookDetail
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.BookReference
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.domain.ChapterReference
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import java.net.URI

class MercuryBookRepository(
    private val apiClient: MercuryApiClient,
) {
    suspend fun search(query: String): List<BookSummary> {
        val books = linkedMapOf<String, BookSummary>()
        val order = mutableListOf<String>()

        apiClient.searchPublications(query).forEach { event ->
            val book = mapIndexEvent(event) ?: return@forEach
            val existing = books[book.coordinate]

            if (existing == null) {
                books[book.coordinate] = book
                order += book.coordinate
            } else if (book.createdAt > existing.createdAt) {
                books[book.coordinate] = book
            }
        }

        return order.take(MAX_SEARCH_RESULTS).mapNotNull(books::get)
    }

    suspend fun getBook(eventId: String): BookDetail? {
        val indexEvent = apiClient.getEvent(eventId.lowercase()) ?: return null
        val book = mapIndexEvent(indexEvent) ?: return null
        val refs = book.chapterRefs.take(MAX_CHAPTERS)
        val eventsById = mutableMapOf<String, NostrEvent>()
        val eventsByCoordinate = mutableMapOf<String, NostrEvent>()

        indexChapterEvents(
            events = apiClient.getEventsByIds(refs.mapNotNull(ChapterReference::eventId)),
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
                events = apiClient.getChaptersByAuthors(unresolvedAuthors, MAX_CHAPTERS),
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
                    limit = minOf(MAX_SEARCH_RESULTS * 5, 500),
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

        return BookSummary(
            id = event.id.lowercase(),
            coordinate = "${BookKinds.PUBLICATION_INDEX}:${event.pubkey.lowercase()}:$identifier",
            pubkey = event.pubkey.lowercase(),
            identifier = identifier,
            title = firstTagValue(event.tags, "title") ?: identifier,
            summary = firstNonEmptyTagValue(event.tags, listOf("summary", "description")),
            authors = tagValues(event.tags, "author"),
            coverImageUrl = httpUrlTag(event.tags, "image"),
            sourceUrl = httpUrlTag(event.tags, "source"),
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
            if (!seen.add(coordinate)) {
                return@mapNotNull null
            }

            ChapterReference(
                coordinate = coordinate,
                pubkey = parts[1].lowercase(),
                identifier = parts[2],
                relay = tag.getOrNull(2)?.takeIf { it.startsWith("wss://") },
                eventId = tag.getOrNull(3)?.lowercase()?.takeIf { HEX_64.matches(it) },
            )
        }
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
            title = firstTagValue(event.tags, "title") ?: humanizeIdentifier(ref.identifier),
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

    private companion object {
        const val MAX_SEARCH_RESULTS = 40
        const val MAX_CHAPTERS = 500
        val HEX_64 = Regex("^[a-f0-9]{64}$", RegexOption.IGNORE_CASE)
        val ROMAN_NUMERAL = Regex("^[ivxlcdm]+$", RegexOption.IGNORE_CASE)
    }
}
