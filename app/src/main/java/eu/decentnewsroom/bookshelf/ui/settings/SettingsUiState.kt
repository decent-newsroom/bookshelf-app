package eu.decentnewsroom.bookshelf.ui.settings

import eu.decentnewsroom.bookshelf.data.discovery.BookRecommendationCacheStats
import eu.decentnewsroom.bookshelf.data.nostr.BookshelfSyncState
import eu.decentnewsroom.bookshelf.data.nostr.NostrSignerSession
import eu.decentnewsroom.bookshelf.data.nostr.RelayConfiguration
import eu.decentnewsroom.bookshelf.data.reader.ReaderPreferences
import eu.decentnewsroom.bookshelf.data.reader.OfflineBookCacheStats
import eu.decentnewsroom.bookshelf.data.rendering.ChapterHtmlCacheStats
import eu.decentnewsroom.bookshelf.data.ratings.BookRatingCacheStats
import eu.decentnewsroom.bookshelf.data.reading.ReadingState

enum class CacheSelection(val label: String) {
    Chapters("Chapter HTML"),
    Ratings("Community ratings"),
    Recommendations("Book recommendations"),
    OfflineBooks("Offline reading"),
}

data class SettingsUiState(
    val readerPreferences: ReaderPreferences,
    val chapterSources: List<String> = emptyList(),
    val chapterSourcesError: String? = null,
    val localRelayUrl: String? = null,
    val localRelayError: String? = null,
    val isOnline: Boolean = false,
    val savedBookCount: Int = 0,
    val pendingHighlightCount: Int = 0,
    val pendingHighlightDetails: List<String> = emptyList(),
    val pendingReviewCount: Int = 0,
    val chapterCacheStats: ChapterHtmlCacheStats = ChapterHtmlCacheStats(),
    val recommendationCacheStats: BookRecommendationCacheStats = BookRecommendationCacheStats(),
    val ratingCacheStats: BookRatingCacheStats = BookRatingCacheStats(),
    val offlineBookCacheStats: OfflineBookCacheStats = OfflineBookCacheStats(),
    val isRefreshingStats: Boolean = false,
    val isClearingCaches: Boolean = false,
    val isRetrying: Boolean = false,
    val isClearingPending: Boolean = false,
    val message: String? = null,
    val relayConfiguration: RelayConfiguration = RelayConfiguration(),
    val syncState: BookshelfSyncState = BookshelfSyncState.NotConfigured,
    val signerSession: NostrSignerSession? = null,
    val readingState: ReadingState = ReadingState(),
)
