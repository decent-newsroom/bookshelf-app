package eu.decentnewsroom.bookshelf.domain

import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable
data class PublicationChildReference(
    val kind: Int,
    val coordinate: String,
    val pubkey: String,
    val identifier: String,
    val relay: String? = null,
    val eventId: String? = null,
) {
    fun asChapter() = ChapterReference(coordinate, pubkey, identifier, relay, eventId)

    companion object {
        fun fromChapter(reference: ChapterReference) = PublicationChildReference(
            BookKinds.PUBLICATION_CONTENT, reference.coordinate, reference.pubkey,
            reference.identifier, reference.relay, reference.eventId,
        )
    }
}

@Serializable
enum class OutlineResolution { RESOLVED, UNAVAILABLE, CYCLE, LIMIT, TIMEOUT, STALE, MALFORMED }

@Serializable
data class PublicationOutlineNode(
    val occurrenceKey: String,
    val parentKey: String? = null,
    val reference: PublicationChildReference,
    /** Direct children have depth zero. */
    val depth: Int = 0,
    val title: String = reference.identifier,
    val resolution: OutlineResolution = OutlineResolution.RESOLVED,
    val diagnostic: String? = null,
    /** Zero-based depth-first leaf ordinal; null for divisions. */
    val chapterIndex: Int? = null,
    val eventId: String? = null,
) {
    val isDivision: Boolean get() = reference.kind == BookKinds.PUBLICATION_INDEX
}

fun publicationOccurrenceKey(parentKey: String, coordinate: String): String =
    MessageDigest.getInstance("SHA-256").digest("$parentKey\u0000$coordinate".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** Compatibility projection for snapshots and callers created before hierarchical outlines. */
fun BookDetail.effectiveOutline(): List<PublicationOutlineNode> = outline.ifEmpty {
    chapters.mapIndexed { index, chapter ->
        PublicationOutlineNode(
            occurrenceKey = chapter.occurrenceKey,
            reference = PublicationChildReference.fromChapter(chapter.reference),
            title = chapter.title,
            chapterIndex = index,
            eventId = chapter.id,
        )
    }
}
