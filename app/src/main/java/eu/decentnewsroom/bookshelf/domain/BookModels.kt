package eu.decentnewsroom.bookshelf.domain

import kotlinx.serialization.Serializable

object BookKinds {
    const val PROFILE_METADATA = 0
    const val PUBLICATION_INDEX = 30040
    const val PUBLICATION_CONTENT = 30041
    const val DIRECTORY = 30045
}

@Serializable
data class BookSummary(
    val id: String,
    val coordinate: String,
    val pubkey: String,
    val identifier: String,
    val title: String,
    val summary: String?,
    val authors: List<String>,
    val coverImageUrl: String?,
    val sourceUrl: String?,
    val language: String?,
    val releaseDate: String?,
    val version: String?,
    val type: String,
    val topics: List<String>,
    val relay: String?,
    val createdAt: Long,
    val chapterCount: Int,
    val chapterRefs: List<ChapterReference>,
)

data class BookDetail(
    val summary: BookSummary,
    val chapters: List<BookChapter>,
    val availableChapterCount: Int,
    val missingChapterCount: Int,
    val truncated: Boolean,
)

@Serializable
data class ChapterReference(
    val coordinate: String,
    val pubkey: String,
    val identifier: String,
    val relay: String?,
    val eventId: String?,
)

data class BookChapter(
    val reference: ChapterReference,
    val position: Int,
    val available: Boolean,
    val title: String,
    val summary: String?,
    val content: String?,
    val id: String?,
    val createdAt: Long?,
    val renderedHtml: String? = null,
    val renderedHtmlCachePath: String? = null,
)

data class BookReference(
    val type: String,
    val coordinate: String?,
    val relay: String?,
    val eventId: String?,
    val pubkey: String?,
)
