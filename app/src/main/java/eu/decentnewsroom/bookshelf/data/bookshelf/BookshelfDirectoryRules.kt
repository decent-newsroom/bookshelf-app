package eu.decentnewsroom.bookshelf.data.bookshelf

import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.BookReference
import eu.decentnewsroom.bookshelf.domain.BookSummary
import java.net.URI

object BookshelfDirectoryRules {
    const val IDENTIFIER = "my-book-collection"
    const val CLIENT_NAME = "Bookshelf"
    const val MAX_ITEMS = 500

    fun emptyTags(): List<List<String>> = listOf(listOf("d", IDENTIFIER))

    fun toggleBook(tags: List<List<String>>, book: BookSummary): List<List<String>> {
        val normalized = normalizeEditableTags(tags)
        val hasBook = extractBookReferences(normalized).any { it.coordinate == book.coordinate }

        if (hasBook) {
            return normalized.filterNot { it.getOrNull(0) == "a" && it.getOrNull(1) == book.coordinate }
        }

        val itemCount = normalized.count { it.firstOrNull() in setOf("a", "e") }
        require(itemCount < MAX_ITEMS) { "My Books is limited to $MAX_ITEMS items." }

        return normalized + listOf(listOf("a", book.coordinate, book.relay.orEmpty(), book.id))
    }

    fun mergeEditableTags(
        localTags: List<List<String>>,
        remoteTags: List<List<String>>,
    ): List<List<String>> {
        val seen = mutableSetOf<String>()
        val merged = mutableListOf(listOf("d", IDENTIFIER))

        sequenceOf(localTags, remoteTags)
            .flatMap { normalizeEditableTags(it).drop(1).asSequence() }
            .forEach { tag ->
                val key = "${tag.firstOrNull()}:${tag.getOrNull(1)?.lowercase()}"
                if (seen.add(key) && merged.size < MAX_ITEMS + 1) {
                    merged += tag
                }
            }

        return merged
    }

    fun extractBookReferences(tags: List<List<String>>): List<BookReference> {
        val seen = mutableSetOf<String>()

        return tags.mapNotNull { tag ->
            when (tag.getOrNull(0)) {
                "a" -> extractATagReference(tag, seen)
                "e" -> extractETagReference(tag, seen)
                else -> null
            }
        }
    }

    fun normalizeEditableTags(tags: List<List<String>>): List<List<String>> {
        val normalized = mutableListOf(listOf("d", IDENTIFIER))

        tags.forEach { tag ->
            if (tag.firstOrNull() !in setOf("a", "e")) {
                return@forEach
            }

            val isValid =
                runCatching {
                    if (tag.first() == "a") {
                        assertValidATag(tag)
                    } else {
                        assertValidETag(tag)
                    }
                }.isSuccess

            if (isValid) {
                normalized += tag.map(String::trim)
            }
            if (normalized.size > MAX_ITEMS + 1) {
                return normalized.take(MAX_ITEMS + 1)
            }
        }

        return normalized
    }

    /** Adds the metadata required on a directory event without persisting it locally. */
    fun tagsForPublishing(tags: List<List<String>>): List<List<String>> =
        normalizeEditableTags(tags) + listOf(listOf("client", CLIENT_NAME))

    fun assertValidDirectory(tags: List<List<String>>, content: String) {
        require(content.isEmpty()) { "Directory content must be empty." }
        require(tags.size <= MAX_ITEMS + 3) { "Directory tags are invalid or exceed the item limit." }

        var dTagCount = 0
        var itemCount = 0
        var clientTagCount = 0
        var titleTagCount = 0

        tags.forEach { tag ->
            require(tag.isNotEmpty()) { "Directory tags must be arrays." }

            when (tag[0]) {
                "d" -> {
                    require(tag.getOrNull(1) == IDENTIFIER) { "Directory identifier is invalid." }
                    dTagCount++
                }

                "a" -> {
                    assertValidATag(tag)
                    itemCount++
                }

                "e" -> {
                    assertValidETag(tag)
                    itemCount++
                }

                "client" -> {
                    require(tag == listOf("client", CLIENT_NAME)) { "Directory client tag is invalid." }
                    clientTagCount++
                }

                "title" -> {
                    require(tag.getOrNull(1)?.isNotBlank() == true) { "Directory title tag is invalid." }
                    titleTagCount++
                }

                else -> error("Directory events may contain only d, a, e, client, and title tags.")
            }
        }

        require(dTagCount == 1 && clientTagCount == 1 && titleTagCount <= 1 && itemCount <= MAX_ITEMS) {
            "Directory must contain one identifier, one Bookshelf client tag, at most one title tag, and no more than $MAX_ITEMS items."
        }
    }

    private fun extractATagReference(tag: List<String>, seen: MutableSet<String>): BookReference? {
        val coordinate = tag.getOrNull(1) ?: return null
        val parts = coordinate.split(":", limit = 3)
        if (
            parts.size != 3 ||
            parts[0].toIntOrNull() != BookKinds.PUBLICATION_INDEX ||
            !HEX_64.matches(parts[1]) ||
            parts[2].isBlank()
        ) {
            return null
        }

        val normalizedCoordinate = "${BookKinds.PUBLICATION_INDEX}:${parts[1].lowercase()}:${parts[2]}"
        if (!seen.add("a:$normalizedCoordinate")) {
            return null
        }

        return BookReference(
            type = "a",
            coordinate = normalizedCoordinate,
            relay = normalizeRelay(tag.getOrNull(2)),
            eventId = normalizeEventId(tag.getOrNull(3)),
            pubkey = parts[1].lowercase(),
        )
    }

    private fun extractETagReference(tag: List<String>, seen: MutableSet<String>): BookReference? {
        val eventId = normalizeEventId(tag.getOrNull(1)) ?: return null
        if (!seen.add("e:$eventId")) {
            return null
        }

        return BookReference(
            type = "e",
            coordinate = null,
            relay = normalizeRelay(tag.getOrNull(2)),
            eventId = eventId,
            pubkey = tag.getOrNull(3)?.lowercase()?.takeIf { HEX_64.matches(it) },
        )
    }

    private fun assertValidATag(tag: List<String>) {
        val coordinate = tag.getOrNull(1) ?: error("Directory a tags require a coordinate.")
        val parts = coordinate.split(":", limit = 3)

        require(
            parts.size == 3 &&
                parts[0].toIntOrNull()?.let { it > 0 } == true &&
                HEX_64.matches(parts[1]) &&
                parts[2].isNotBlank(),
        ) {
            "Directory a tag coordinate is invalid."
        }

        assertRelay(tag.getOrNull(2))
        val eventId = tag.getOrNull(3)
        require(eventId.isNullOrBlank() || normalizeEventId(eventId) != null) {
            "Directory a tag event id is invalid."
        }
    }

    private fun assertValidETag(tag: List<String>) {
        require(normalizeEventId(tag.getOrNull(1)) != null) { "Directory e tag event id is invalid." }
        assertRelay(tag.getOrNull(2))

        val pubkey = tag.getOrNull(3)
        require(pubkey.isNullOrBlank() || HEX_64.matches(pubkey)) {
            "Directory e tag pubkey is invalid."
        }
    }

    private fun assertRelay(relay: String?) {
        require(relay.isNullOrBlank() || normalizeRelay(relay) != null) {
            "Directory relay hint is invalid."
        }
    }

    private fun normalizeRelay(relay: String?): String? {
        val trimmed = relay?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()

        return trimmed.takeIf { scheme in setOf("ws", "wss") && !uri.host.isNullOrBlank() }
    }

    private fun normalizeEventId(eventId: String?): String? =
        eventId?.trim()?.lowercase()?.takeIf { HEX_64.matches(it) }

    private val HEX_64 = Regex("^[a-f0-9]{64}$", RegexOption.IGNORE_CASE)
}
