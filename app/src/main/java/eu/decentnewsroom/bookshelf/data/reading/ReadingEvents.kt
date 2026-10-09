package eu.decentnewsroom.bookshelf.data.reading

import eu.decentnewsroom.bookshelf.data.nostr.NostrEventVerifier
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** The supplied Alexandria reading tuple is the interoperability contract. */
object ReadingEvents {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val hex64 = Regex("^[a-f0-9]{64}$", RegexOption.IGNORE_CASE)

    fun validCoordinate(value: String): Boolean {
        val parts = value.split(':', limit = 3)
        return parts.size == 3 && parts[0] == BookKinds.PUBLICATION_INDEX.toString() &&
            hex64.matches(parts[1]) && parts[2].isNotBlank()
    }

    fun parseSnapshot(event: NostrEvent): List<TrackedBook> {
        if (event.kind != BookKinds.READING_LIST) return emptyList()
        require(event.content.isEmpty()) { "Unsupported reading snapshot content; preserve it before editing." }
        return event.tags.mapNotNull { parseBookTag(it, event.createdAt) }.distinctBy { it.bookCoordinate }
    }

    private fun parseBookTag(tag: List<String>, fallbackTime: Long): TrackedBook? {
        if (tag.getOrNull(0) != "book") return null
        val coordinate = tag.getOrNull(1)?.takeIf(::validCoordinate) ?: return null
        val position = tag.getOrNull(2)?.toIntOrNull() ?: return null
        val total = tag.getOrNull(3)?.toIntOrNull() ?: return null
        if (total <= 0 || position !in 0 until total) return null
        val sectionId = tag.getOrNull(4)?.takeIf { it.isNotEmpty() }
        if (sectionId != null && !hex64.matches(sectionId)) return null
        val timestamp = tag.getOrNull(5)?.let { it.toLongOrNull() ?: return null } ?: fallbackTime
        if (timestamp < 0) return null
        return TrackedBook(coordinate, position = position, total = total, sectionId = sectionId,
            updatedAt = timestamp, isPublic = true, status = "Public")
    }

    /** Keep extensions and unrecognized book tuples intact when editing a full snapshot. */
    fun preservedSnapshotTags(event: NostrEvent): List<List<String>> {
        val recognized = parseSnapshot(event).map { it.bookCoordinate }.toSet()
        return event.tags.filter { tag ->
            when (tag.getOrNull(0)) {
                "client" -> false
                "book" -> parseBookTag(tag, event.createdAt) == null
                "a" -> tag.getOrNull(1) !in recognized
                else -> true
            }
        }
    }

    fun snapshotDraft(
        pubkey: String,
        books: List<TrackedBook>,
        createdAt: Long,
        baseTags: List<List<String>> = emptyList(),
    ): NostrEvent {
        require(hex64.matches(pubkey) && createdAt >= 0)
        val unique = books.distinctBy { it.bookCoordinate }
        unique.forEach {
            require(validCoordinate(it.bookCoordinate) && it.total > 0 && it.position in 0 until it.total)
            require(it.sectionId == null || hex64.matches(it.sectionId))
            require(it.updatedAt >= 0)
        }
        val coordinates = unique.map { it.bookCoordinate }.toSet()
        return NostrEvent(pubkey = pubkey.lowercase(), createdAt = createdAt, kind = BookKinds.READING_LIST,
            tags = buildList {
                addAll(baseTags.filter { tag ->
                    tag.isNotEmpty() && tag.first() != "client" &&
                        !(tag.first() in setOf("book", "a") && tag.getOrNull(1) in coordinates)
                })
                unique.forEach { book ->
                    add(listOf("book", book.bookCoordinate, book.position.toString(), book.total.toString(),
                        book.sectionId.orEmpty(), book.updatedAt.toString()))
                    add(listOf("a", book.bookCoordinate))
                }
                add(listOf("client", "Bookshelf"))
            })
    }

    fun finishedDraft(pubkey: String, coordinate: String, createdAt: Long): NostrEvent {
        require(hex64.matches(pubkey) && validCoordinate(coordinate) && createdAt >= 0)
        return NostrEvent(pubkey = pubkey.lowercase(), createdAt = createdAt, kind = BookKinds.FINISHED_LABEL,
            tags = listOf(listOf("L", "ugc"), listOf("l", "read", "ugc"),
                listOf("a", coordinate), listOf("client", "Bookshelf")))
    }

    fun parseFinished(event: NostrEvent): List<FinishedBook> {
        if (event.kind != BookKinds.FINISHED_LABEL || event.createdAt < 0 ||
            event.tags.none { it.take(2) == listOf("L", "ugc") } ||
            event.tags.none { it.take(3) == listOf("l", "read", "ugc") }) return emptyList()
        return event.tags.filter { it.getOrNull(0) == "a" }.mapNotNull { it.getOrNull(1) }
            .filter(::validCoordinate).distinct().map {
                FinishedBook(it, finishedAt = event.createdAt, isPublic = true, status = "Public")
            }
    }

    /** Call only with candidates verified at the transport boundary. NIP-01 ties retain lowest ID. */
    fun newestSnapshot(events: Iterable<NostrEvent>): NostrEvent? = events
        .filter { it.kind == BookKinds.READING_LIST }
        .maxWithOrNull(compareBy<NostrEvent> { it.createdAt }.thenByDescending { it.id })

    fun unsignedJson(event: NostrEvent): String = json.encodeToString(
        UnsignedReadingEvent(event.pubkey, event.createdAt, event.kind, event.tags, event.content),
    )

    fun decodeSigned(eventJson: String, expected: NostrEvent): NostrEvent {
        val event = json.decodeFromString<NostrEvent>(eventJson)
        require(event.pubkey == expected.pubkey && event.createdAt == expected.createdAt &&
            event.kind == expected.kind && event.tags == expected.tags && event.content == expected.content) {
            "Signer changed the reading event payload."
        }
        return NostrEventVerifier.requireVerified(event).event
    }
}

@Serializable
private data class UnsignedReadingEvent(
    val pubkey: String,
    @SerialName("created_at") val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
)
