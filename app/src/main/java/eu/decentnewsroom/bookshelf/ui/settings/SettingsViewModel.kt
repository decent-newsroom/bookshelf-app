package eu.decentnewsroom.bookshelf.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.decentnewsroom.bookshelf.AppGraph
import eu.decentnewsroom.bookshelf.data.bookshelf.LocalBookshelfStore
import eu.decentnewsroom.bookshelf.data.connectivity.ValidatedInternetConnectivity
import eu.decentnewsroom.bookshelf.data.highlights.HighlightOutbox
import eu.decentnewsroom.bookshelf.data.highlights.HighlightOutboxDispatcher
import eu.decentnewsroom.bookshelf.data.mercury.ChapterSourceSettingsStore
import eu.decentnewsroom.bookshelf.data.mercury.ChapterRelayUrls
import eu.decentnewsroom.bookshelf.data.nostr.BookshelfRelaySync
import eu.decentnewsroom.bookshelf.data.nostr.BookshelfSyncState
import eu.decentnewsroom.bookshelf.data.nostr.LocalRelaySettingsStore
import eu.decentnewsroom.bookshelf.data.ratings.BookRatingsRepository
import eu.decentnewsroom.bookshelf.data.ratings.ReviewOutbox
import eu.decentnewsroom.bookshelf.data.ratings.ReviewOutboxDispatcher
import eu.decentnewsroom.bookshelf.data.reader.ParagraphAlignment
import eu.decentnewsroom.bookshelf.data.reader.ReaderFont
import eu.decentnewsroom.bookshelf.data.reader.ReaderSettingsStore
import eu.decentnewsroom.bookshelf.data.reader.ReaderTheme
import eu.decentnewsroom.bookshelf.data.reader.OfflineBookCache
import eu.decentnewsroom.bookshelf.data.rendering.ChapterHtmlCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val readerSettings: ReaderSettingsStore = AppGraph.readerSettings,
    private val chapterSources: ChapterSourceSettingsStore = AppGraph.chapterSourceSettings,
    private val localRelay: LocalRelaySettingsStore = AppGraph.localRelaySettings,
    private val relaySync: BookshelfRelaySync = AppGraph.relaySync,
    private val connectivity: ValidatedInternetConnectivity = AppGraph.connectivity,
    private val bookshelf: LocalBookshelfStore = AppGraph.localBookshelf,
    private val chapterCache: ChapterHtmlCache = AppGraph.chapterHtmlCache,
    private val offlineBookCache: OfflineBookCache = AppGraph.offlineBookCache,
    private val ratings: BookRatingsRepository = AppGraph.bookRatings,
    private val highlights: HighlightOutbox = AppGraph.highlightOutbox,
    private val highlightDispatcher: HighlightOutboxDispatcher = AppGraph.highlightDispatcher,
    private val reviews: ReviewOutbox = AppGraph.reviewOutbox,
    private val reviewDispatcher: ReviewOutboxDispatcher = AppGraph.reviewOutboxDispatcher,
) : ViewModel() {
    private var statsJob: Job? = null

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            readerPreferences = readerSettings.readerPreferences.value,
            chapterSources = chapterSources.relayUrls.value,
            localRelayUrl = localRelay.relayUrl.value,
            isOnline = connectivity.isOnline,
            savedBookCount = bookshelf.savedBooks.value.size,
            relayConfiguration = relaySync.relayConfiguration,
            syncState = relaySync.state.value,
            signerSession = relaySync.activeSession.value,
        ),
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { readerSettings.readerPreferences.collect { value -> _uiState.update { it.copy(readerPreferences = value) } } }
        viewModelScope.launch { chapterSources.relayUrls.collect { value -> _uiState.update { it.copy(chapterSources = value, chapterSourcesError = null) } } }
        viewModelScope.launch { localRelay.relayUrl.collect { value -> relaySync.setLocalRelayUrl(value); _uiState.update { it.copy(localRelayUrl = value, relayConfiguration = relaySync.relayConfiguration, localRelayError = null) } } }
        viewModelScope.launch { connectivity.online.collect { value -> _uiState.update { it.copy(isOnline = value) }; refreshStats() } }
        viewModelScope.launch { bookshelf.savedBooks.collect { value -> _uiState.update { it.copy(savedBookCount = value.size) } } }
        viewModelScope.launch { relaySync.state.collect { value -> _uiState.update { it.copy(syncState = value, relayConfiguration = relaySync.relayConfiguration) } } }
        viewModelScope.launch { relaySync.activeSession.collect { value -> _uiState.update { it.copy(signerSession = value) } } }
        refreshStats()
    }

    fun setFontSize(fontSizeSp: Float) = readerSettings.setFontSizeSp(fontSizeSp)
    fun setLineHeight(multiplier: Float) = readerSettings.setLineHeightMultiplier(multiplier)
    fun setTheme(theme: ReaderTheme) = readerSettings.setTheme(theme)
    fun setFont(font: ReaderFont) = readerSettings.setFontFamily(font)
    fun setParagraphAlignment(alignment: ParagraphAlignment) = readerSettings.setParagraphAlignment(alignment)

    fun addChapterSource(rawUrl: String) {
        runCatching { ChapterSourceList.add(chapterSources.relayUrls.value, rawUrl) }
            .onSuccess(::updateChapterSources)
            .onFailure(::setChapterError)
    }

    fun removeChapterSource(url: String) {
        runCatching { ChapterSourceList.remove(chapterSources.relayUrls.value, url) }
            .onSuccess(::updateChapterSources)
            .onFailure(::setChapterError)
    }

    fun restoreChapterSources() = updateChapterSources(ChapterSourceList.defaults())

    private fun updateChapterSources(urls: Iterable<String>) {
        runCatching { ChapterRelayUrls.normalize(urls).joinToString("\n") }
            .onSuccess { normalized -> runCatching { chapterSources.setRelayUrls(normalized) }.onFailure(::setChapterError) }
            .onFailure(::setChapterError)
    }

    private fun setChapterError(error: Throwable) { _uiState.update { it.copy(chapterSourcesError = error.message ?: "Invalid chapter source.") } }

    fun setLocalRelay(rawUrl: String) {
        runCatching { localRelay.setRelayUrl(rawUrl) }
            .onFailure { _uiState.update { state -> state.copy(localRelayError = it.message ?: "Invalid local relay URL.") } }
    }

    fun removeLocalRelay() = localRelay.setRelayUrl("")

    fun retryPendingPublications() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRetrying = true, message = null) }
            try {
                runCatching { highlightDispatcher.syncPending(force = true) }
                    .onFailure { failure -> if (failure is CancellationException) throw failure else _uiState.update { it.copy(message = failure.message) } }
                runCatching { reviewDispatcher.syncPending(force = true) }
                    .onFailure { failure -> if (failure is CancellationException) throw failure else _uiState.update { it.copy(message = failure.message) } }
                refreshStatsNow()
            } finally {
                _uiState.update { it.copy(isRetrying = false) }
            }
        }
    }

    fun refreshStats() {
        statsJob?.cancel()
        statsJob = viewModelScope.launch { refreshStatsNow() }
    }

    private suspend fun refreshStatsNow() {
        _uiState.update { it.copy(isRefreshingStats = true, message = null) }
        try {
            coroutineScope {
                val chapter = async { chapterCache.stats() }
                val rating = async { ratings.cacheStats() }
                val offlineBooks = async { offlineBookCache.stats() }
                val pendingHighlights = async { highlights.pending().size }
                val pendingReviews = async { reviews.pendingCount() }
                _uiState.update {
                    it.copy(
                        chapterCacheStats = chapter.await(),
                        ratingCacheStats = rating.await(),
                        offlineBookCacheStats = offlineBooks.await(),
                        pendingHighlightCount = pendingHighlights.await(),
                        pendingReviewCount = pendingReviews.await(),
                    )
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            _uiState.update { it.copy(message = failure.message ?: "Could not refresh storage statistics.") }
        } finally {
            _uiState.update { it.copy(isRefreshingStats = false) }
        }
    }
    fun clearChapterCache() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshingStats = true, message = null) }
            try { chapterCache.clear(); refreshStatsNow() }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { _uiState.update { it.copy(message = failure.message ?: "Could not clear chapter cache.") } }
            finally { _uiState.update { it.copy(isRefreshingStats = false) } }
        }
    }

    fun clearOfflineBookCache() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshingStats = true, message = null) }
            try { offlineBookCache.clear(); refreshStatsNow() }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { _uiState.update { it.copy(message = failure.message ?: "Could not clear offline books.") } }
            finally { _uiState.update { it.copy(isRefreshingStats = false) } }
        }
    }

    fun clearRatingCache() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshingStats = true, message = null) }
            try { ratings.clearCache(); refreshStatsNow() }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { _uiState.update { it.copy(message = failure.message ?: "Could not clear rating cache.") } }
            finally { _uiState.update { it.copy(isRefreshingStats = false) } }
        }
    }
}
