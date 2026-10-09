package eu.decentnewsroom.bookshelf.data.reading

import eu.decentnewsroom.bookshelf.domain.BookSummary
import kotlinx.serialization.Serializable

@Serializable
data class ReadingPrivacy(
    val readingDeviceOnly: Boolean = true,
    val finishedDeviceOnly: Boolean = true,
)

@Serializable
data class TrackedBook(
    val bookCoordinate: String,
    val book: BookSummary? = null,
    val position: Int,
    val total: Int,
    val sectionId: String? = null,
    val updatedAt: Long,
    val isPublic: Boolean = false,
    val status: String = "On this device",
)

@Serializable
data class FinishedBook(
    val bookCoordinate: String,
    val book: BookSummary? = null,
    val finishedAt: Long,
    val isPublic: Boolean = false,
    val status: String = "On this device",
)

data class ReadingState(
    val preferences: ReadingPrivacy = ReadingPrivacy(),
    val tracked: List<TrackedBook> = emptyList(),
    val finished: List<FinishedBook> = emptyList(),
    val isSyncing: Boolean = false,
    val error: String? = null,
    val pendingCount: Int = 0,
    val privateReadingRemovals: List<TrackedBook> = emptyList(),
)
