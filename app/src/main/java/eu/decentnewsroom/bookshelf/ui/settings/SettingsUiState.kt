package eu.decentnewsroom.bookshelf.ui.settings

import eu.decentnewsroom.bookshelf.data.nostr.BookshelfSyncState
import eu.decentnewsroom.bookshelf.data.nostr.NostrSignerSession
import eu.decentnewsroom.bookshelf.data.nostr.RelayConfiguration
import eu.decentnewsroom.bookshelf.data.reader.ReaderPreferences
import eu.decentnewsroom.bookshelf.data.reader.OfflineBookCacheStats
import eu.decentnewsroom.bookshelf.data.rendering.ChapterHtmlCacheStats
import eu.decentnewsroom.bookshelf.data.ratings.BookRatingCacheStats

data class SettingsUiState(
    val readerPreferences: ReaderPreferences,
    val chapterSources: List<String> = emptyList(),
    val chapterSourcesError: String? = null,
    val localRelayUrl: String? = null,
    val localRelayError: String? = null,
    val isOnline: Boolean = false,
    val savedBookCount: Int = 0,
    val pendingHighlightCount: Int = 0,
    val pendingReviewCount: Int = 0,
    val chapterCacheStats: ChapterHtmlCacheStats = ChapterHtmlCacheStats(),
    val ratingCacheStats: BookRatingCacheStats = BookRatingCacheStats(),
    val offlineBookCacheStats: OfflineBookCacheStats = OfflineBookCacheStats(),
    val isRefreshingStats: Boolean = false,
    val isRetrying: Boolean = false,
    val message: String? = null,
    val relayConfiguration: RelayConfiguration = RelayConfiguration(),
    val syncState: BookshelfSyncState = BookshelfSyncState.NotConfigured,
    val signerSession: NostrSignerSession? = null,
)
