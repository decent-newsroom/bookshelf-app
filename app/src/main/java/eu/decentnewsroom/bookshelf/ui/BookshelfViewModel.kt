package eu.decentnewsroom.bookshelf.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.decentnewsroom.bookshelf.AppGraph
import eu.decentnewsroom.bookshelf.data.bookshelf.BookshelfDirectoryRules
import eu.decentnewsroom.bookshelf.data.bookshelf.LocalBookshelfStore
import eu.decentnewsroom.bookshelf.data.mercury.MercuryApiException
import eu.decentnewsroom.bookshelf.data.mercury.MercuryBookRepository
import eu.decentnewsroom.bookshelf.data.nostr.BookshelfRelaySync
import eu.decentnewsroom.bookshelf.data.nostr.BookshelfSyncState
import eu.decentnewsroom.bookshelf.data.nostr.NostrSignerSession
import eu.decentnewsroom.bookshelf.data.reader.ReaderPreferences
import eu.decentnewsroom.bookshelf.data.reader.ReaderSettingsStore
import eu.decentnewsroom.bookshelf.data.reader.ReaderTheme
import eu.decentnewsroom.bookshelf.data.reader.ReadingProgress
import eu.decentnewsroom.bookshelf.data.rendering.ChapterHtmlCache
import eu.decentnewsroom.bookshelf.data.rendering.ChapterHtmlCacheStats
import eu.decentnewsroom.bookshelf.domain.BookDetail
import eu.decentnewsroom.bookshelf.domain.BookSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class BookshelfViewModel(
    private val repository: MercuryBookRepository = AppGraph.mercuryBooks,
    private val chapterHtmlCache: ChapterHtmlCache = AppGraph.chapterHtmlCache,
    private val localBookshelf: LocalBookshelfStore = AppGraph.localBookshelf,
    private val readerSettings: ReaderSettingsStore = AppGraph.readerSettings,
    private val relaySync: BookshelfRelaySync = AppGraph.relaySync,
) : ViewModel() {
    private val _uiState = MutableStateFlow(BookshelfUiState())
    val uiState: StateFlow<BookshelfUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            localBookshelf.savedBooks.collect { savedBooks ->
                _uiState.update { it.copy(savedBooks = savedBooks) }
            }
        }
        viewModelScope.launch {
            localBookshelf.directoryTags.collect { tags ->
                val coordinates =
                    BookshelfDirectoryRules
                        .extractBookReferences(tags)
                        .mapNotNull { it.coordinate }
                        .toSet()
                _uiState.update { it.copy(savedCoordinates = coordinates) }
            }
        }
        viewModelScope.launch {
            readerSettings.readerPreferences.collect { preferences ->
                _uiState.update { it.copy(readerPreferences = preferences) }
            }
        }
        viewModelScope.launch {
            readerSettings.progress.collect { progress ->
                _uiState.update { it.copy(readingProgress = progress) }
            }
        }
        viewModelScope.launch {
            relaySync.state.collect { syncState ->
                _uiState.update { it.copy(syncState = syncState) }
            }
        }
        viewModelScope.launch {
            relaySync.activeSession.collect { session ->
                _uiState.update { it.copy(signerSession = session) }
            }
        }
        relaySync.activeSession.value?.let { session ->
            viewModelScope.launch {
                syncRemoteDirectory(session.pubkey, announceEmpty = false)
            }
        }
        refreshChapterCacheStats()
    }

    fun selectTab(tab: BookshelfTab) {
        _uiState.update { it.copy(tab = tab, selectedBook = null, error = null) }
    }

    fun updateQuery(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun submitSearch() {
        val query = _uiState.value.query.trim()
        if (query.length < 2) {
            _uiState.update {
                it.copy(
                    searchMessage = "Enter at least two characters to search.",
                    searchResults = emptyList(),
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSearching = true,
                    searchMessage = null,
                    error = null,
                )
            }

            try {
                val books = repository.search(query)
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        searchResults = books,
                        searchMessage = if (books.isEmpty()) "No matching books." else null,
                    )
                }
            } catch (exception: MercuryApiException) {
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        searchMessage = "Mercury is unavailable.",
                        error = exception.message,
                    )
                }
            }
        }
    }

    fun openBook(book: BookSummary) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingBook = true,
                    selectedBook = null,
                    error = null,
                )
            }

            try {
                val detail = repository.getBook(book.id)?.let { chapterHtmlCache.renderBook(it) }
                val cacheStats = chapterHtmlCache.stats()
                _uiState.update {
                    it.copy(
                        isLoadingBook = false,
                        selectedBook = detail,
                        chapterCacheStats = cacheStats,
                        error = if (detail == null) "Book not available." else null,
                    )
                }
            } catch (exception: MercuryApiException) {
                _uiState.update {
                    it.copy(
                        isLoadingBook = false,
                        error = exception.message ?: "Mercury is unavailable.",
                    )
                }
            }
        }
    }

    fun closeBook() {
        _uiState.update { it.copy(selectedBook = null, error = null) }
    }

    fun clearChapterHtmlCache() {
        viewModelScope.launch {
            _uiState.update { it.copy(isClearingChapterCache = true, error = null) }

            runCatching { chapterHtmlCache.clear() }
                .onSuccess { stats ->
                    _uiState.update {
                        it.copy(
                            isClearingChapterCache = false,
                            chapterCacheStats = stats,
                            syncMessage = "Chapter cache cleared.",
                            error = null,
                        )
                    }
                }.onFailure { failure ->
                    _uiState.update {
                        it.copy(
                            isClearingChapterCache = false,
                            error = failure.message ?: "Could not clear chapter cache.",
                        )
                    }
                }
        }
    }

    fun completeExternalSignerLogin(session: NostrSignerSession) {
        viewModelScope.launch {
            try {
                relaySync.signIn(session)
                syncRemoteDirectory(session.pubkey, announceEmpty = true)
            } catch (failure: Throwable) {
                _uiState.update {
                    it.copy(
                        isSyncingDirectory = false,
                        error = failure.message ?: "Could not sign in with the Android signer.",
                        syncMessage = null,
                    )
                }
            }
        }
    }

    fun reportExternalSignerFailure(message: String) {
        _uiState.update {
            it.copy(
                error = message,
                syncMessage = null,
                pendingDirectorySignRequest = null,
                isPublishingDirectory = false,
            )
        }
    }

    fun syncNow() {
        val session = _uiState.value.signerSession
        if (session == null) {
            _uiState.update { it.copy(error = "Log in with an Android signer first.") }
            return
        }

        viewModelScope.launch {
            syncRemoteDirectory(session.pubkey, announceEmpty = true)
        }
    }

    fun signOut() {
        viewModelScope.launch {
            relaySync.signOut()
            localBookshelf.replace(BookshelfDirectoryRules.emptyTags(), emptyList())
            _uiState.update {
                it.copy(
                    syncMessage = "Signed out.",
                    error = null,
                    pendingDirectorySignRequest = null,
                    isPublishingDirectory = false,
                    isSyncingDirectory = false,
                )
            }
        }
    }

    fun toggleSaved(book: BookSummary) {
        val state = _uiState.value
        val session = state.signerSession

        if (state.pendingDirectorySignRequest != null || state.isPublishingDirectory) {
            _uiState.update { it.copy(error = "Finish the current signer request first.") }
            return
        }
        if (session == null) {
            _uiState.update {
                it.copy(
                    tab = BookshelfTab.Settings,
                    error = "Log in with an Android signer before collecting books.",
                    syncMessage = null,
                )
            }
            return
        }

        runCatching {
            val nextTags = BookshelfDirectoryRules.toggleBook(localBookshelf.directoryTags.value, book)
            val draft = relaySync.buildDirectoryDraft(
                pubkey = session.pubkey,
                tags = nextTags,
            )
            PendingDirectorySignRequest(
                id = UUID.randomUUID().toString(),
                session = session,
                unsignedEventJson = relaySync.unsignedDirectoryJson(draft),
                tags = draft.tags,
                fallbackBook = book,
            )
        }.onSuccess { request ->
            _uiState.update {
                it.copy(
                    pendingDirectorySignRequest = request,
                    isPublishingDirectory = true,
                    error = null,
                    syncMessage = "Review the collection update in your signer.",
                )
            }
        }.onFailure { failure ->
            _uiState.update {
                it.copy(error = failure.message ?: "Could not prepare collection update.")
            }
        }
    }

    fun completeDirectorySignature(requestId: String?, signedEventJson: String) {
        viewModelScope.launch {
            val pending = _uiState.value.pendingDirectorySignRequest ?: return@launch
            if (requestId != null && requestId != pending.id) {
                _uiState.update {
                    it.copy(
                        pendingDirectorySignRequest = null,
                        isPublishingDirectory = false,
                        error = "Signer returned an unexpected request id.",
                        syncMessage = null,
                    )
                }
                return@launch
            }

            try {
                val event = relaySync.decodeSignedDirectory(signedEventJson)
                require(event.pubkey.equals(pending.session.pubkey, ignoreCase = true)) {
                    "Signer returned an event for a different account."
                }
                require(BookshelfDirectoryRules.normalizeEditableTags(event.tags) == pending.tags) {
                    "Signer changed the collection tags."
                }

                val report = relaySync.publishDirectory(event)
                val applied = applyDirectoryTags(event.tags, fallbackBooks = listOf(pending.fallbackBook))
                val publishMessage =
                    if (report.acceptedRelays > 0) {
                        "Published ${applied.referenceCount} collection items to ${report.acceptedRelays}/${report.attemptedRelays} relays."
                    } else {
                        "Collection saved locally, but no relay accepted it yet."
                    }

                _uiState.update {
                    it.copy(
                        pendingDirectorySignRequest = null,
                        isPublishingDirectory = false,
                        syncMessage = applied.warning ?: publishMessage,
                        error = if (report.acceptedRelays > 0) null else "No relay accepted the directory update.",
                    )
                }
            } catch (failure: Throwable) {
                _uiState.update {
                    it.copy(
                        pendingDirectorySignRequest = null,
                        isPublishingDirectory = false,
                        error = failure.message ?: "Could not publish collection update.",
                        syncMessage = null,
                    )
                }
            }
        }
    }

    fun failPendingDirectorySignature(message: String) {
        _uiState.update {
            it.copy(
                pendingDirectorySignRequest = null,
                isPublishingDirectory = false,
                error = message,
                syncMessage = null,
            )
        }
    }

    fun recordReaderProgress(book: BookDetail, firstVisibleItemIndex: Int) {
        readerSettings.recordProgress(book, firstVisibleItemIndex)
    }

    fun setReaderFontSize(fontSizeSp: Float) {
        readerSettings.setFontSizeSp(fontSizeSp)
    }

    fun setReaderLineHeight(lineHeightMultiplier: Float) {
        readerSettings.setLineHeightMultiplier(lineHeightMultiplier)
    }

    fun setReaderTheme(theme: ReaderTheme) {
        readerSettings.setTheme(theme)
    }

    private fun refreshChapterCacheStats() {
        viewModelScope.launch {
            val stats = chapterHtmlCache.stats()
            _uiState.update { it.copy(chapterCacheStats = stats) }
        }
    }

    private suspend fun syncRemoteDirectory(pubkey: String, announceEmpty: Boolean) {
        _uiState.update {
            it.copy(
                isSyncingDirectory = true,
                error = null,
                syncMessage = null,
            )
        }

        try {
            val event = relaySync.fetchLatestDirectory(pubkey)
            if (event == null) {
                localBookshelf.replace(BookshelfDirectoryRules.emptyTags(), emptyList())
                _uiState.update {
                    it.copy(
                        isSyncingDirectory = false,
                        syncMessage = if (announceEmpty) "No collection found yet." else null,
                    )
                }
                return
            }

            val applied = applyDirectoryTags(event.tags)
            _uiState.update {
                it.copy(
                    isSyncingDirectory = false,
                    error = applied.warning,
                    syncMessage = "Synced ${applied.referenceCount} collection items from relays.",
                )
            }
        } catch (failure: Throwable) {
            _uiState.update {
                it.copy(
                    isSyncingDirectory = false,
                    error = failure.message ?: "Could not sync collection.",
                    syncMessage = null,
                )
            }
        }
    }

    private suspend fun applyDirectoryTags(
        tags: List<List<String>>,
        fallbackBooks: List<BookSummary> = emptyList(),
    ): DirectoryApplyResult {
        val normalizedTags = BookshelfDirectoryRules.normalizeEditableTags(tags)
        val references = BookshelfDirectoryRules.extractBookReferences(normalizedTags)
        val resolvedBooks =
            runCatching {
                repository.getBooksForReferences(references)
            }

        val books =
            (resolvedBooks.getOrDefault(emptyList()) + fallbackBooks)
                .distinctBy(BookSummary::coordinate)

        localBookshelf.replace(normalizedTags, books)

        return DirectoryApplyResult(
            referenceCount = references.size,
            warning = resolvedBooks.exceptionOrNull()?.message,
        )
    }
}

enum class BookshelfTab {
    Search,
    MyBooks,
    Settings,
}

data class PendingDirectorySignRequest(
    val id: String,
    val session: NostrSignerSession,
    val unsignedEventJson: String,
    val tags: List<List<String>>,
    val fallbackBook: BookSummary,
)

data class BookshelfUiState(
    val tab: BookshelfTab = BookshelfTab.Search,
    val query: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<BookSummary> = emptyList(),
    val searchMessage: String? = null,
    val savedBooks: List<BookSummary> = emptyList(),
    val savedCoordinates: Set<String> = emptySet(),
    val selectedBook: BookDetail? = null,
    val isLoadingBook: Boolean = false,
    val error: String? = null,
    val syncState: BookshelfSyncState = BookshelfSyncState.NotConfigured,
    val signerSession: NostrSignerSession? = null,
    val syncMessage: String? = null,
    val isSyncingDirectory: Boolean = false,
    val isPublishingDirectory: Boolean = false,
    val pendingDirectorySignRequest: PendingDirectorySignRequest? = null,
    val readerPreferences: ReaderPreferences = ReaderPreferences(),
    val readingProgress: Map<String, ReadingProgress> = emptyMap(),
    val chapterCacheStats: ChapterHtmlCacheStats = ChapterHtmlCacheStats(),
    val isClearingChapterCache: Boolean = false,
)

private data class DirectoryApplyResult(
    val referenceCount: Int,
    val warning: String?,
)
