package eu.decentnewsroom.bookshelf.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.decentnewsroom.bookshelf.AppGraph
import eu.decentnewsroom.bookshelf.data.bookshelf.BookshelfDirectoryRules
import eu.decentnewsroom.bookshelf.data.discovery.CuratedShelf
import eu.decentnewsroom.bookshelf.data.discovery.CuratedShelfRepository
import eu.decentnewsroom.bookshelf.data.bookshelf.LocalBookshelfStore
import eu.decentnewsroom.bookshelf.data.mercury.ChapterSourceSettingsStore
import eu.decentnewsroom.bookshelf.data.mercury.MercuryApiException
import eu.decentnewsroom.bookshelf.data.mercury.MercuryBookRepository
import eu.decentnewsroom.bookshelf.data.mercury.BookSearchQuery
import eu.decentnewsroom.bookshelf.data.mercury.BookSearchResult
import eu.decentnewsroom.bookshelf.data.mercury.BookSearchStatus
import eu.decentnewsroom.bookshelf.data.nostr.BookshelfRelaySync
import eu.decentnewsroom.bookshelf.data.nostr.BookshelfSyncState
import eu.decentnewsroom.bookshelf.data.nostr.NostrProfile
import eu.decentnewsroom.bookshelf.data.nostr.NostrProfileSource
import eu.decentnewsroom.bookshelf.data.nostr.NostrSignerSession
import eu.decentnewsroom.bookshelf.data.nostr.LocalRelaySettingsStore
import eu.decentnewsroom.bookshelf.data.nostr.RelayConfiguration
import eu.decentnewsroom.bookshelf.data.nostr.PendingNostrAuthSignRequest
import eu.decentnewsroom.bookshelf.data.onboarding.OnboardingTip
import eu.decentnewsroom.bookshelf.data.onboarding.OnboardingTipStore
import eu.decentnewsroom.bookshelf.data.reader.ReaderPreferences
import eu.decentnewsroom.bookshelf.data.reader.ReaderSettingsStore
import eu.decentnewsroom.bookshelf.data.reader.ReaderTheme
import eu.decentnewsroom.bookshelf.data.reader.ReadingProgress
import eu.decentnewsroom.bookshelf.data.rendering.ChapterHtmlCache
import eu.decentnewsroom.bookshelf.data.rendering.ChapterHtmlCacheStats
import eu.decentnewsroom.bookshelf.domain.BookDetail
import eu.decentnewsroom.bookshelf.domain.BookSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
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
    private val chapterSourceSettings: ChapterSourceSettingsStore = AppGraph.chapterSourceSettings,
    private val onboardingTips: OnboardingTipStore = AppGraph.onboardingTips,
    private val localRelaySettings: LocalRelaySettingsStore = AppGraph.localRelaySettings,
    private val relaySync: BookshelfRelaySync = AppGraph.relaySync,
    private val nostrProfiles: NostrProfileSource = AppGraph.nostrProfiles,
    private val curatedShelfRepository: CuratedShelfRepository = AppGraph.curatedShelves,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        BookshelfUiState(
            readerPreferences = readerSettings.readerPreferences.value,
        ),
    )
    val uiState: StateFlow<BookshelfUiState> = _uiState.asStateFlow()
    private var searchJob: Job? = null
    private var bookOpenJob: Job? = null

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
            onboardingTips.seenTips.collect { seenTips ->
                _uiState.update { it.copy(seenOnboardingTips = seenTips) }
            }
        }
        viewModelScope.launch {
            chapterSourceSettings.relayUrls.collect { relayUrls ->
                _uiState.update { it.copy(chapterRelayUrls = relayUrls) }
            }
        }
        viewModelScope.launch {
            relaySync.state.collect { syncState ->
                _uiState.update { it.copy(syncState = syncState, relayConfiguration = relaySync.relayConfiguration) }
            }
        }
        viewModelScope.launch {
            localRelaySettings.relayUrl.collect { relayUrl ->
                relaySync.setLocalRelayUrl(relayUrl)
                _uiState.update { it.copy(localRelayUrl = relayUrl, relayConfiguration = relaySync.relayConfiguration) }
            }
        }
        viewModelScope.launch {
            relaySync.activeSession.collect { session ->
                _uiState.update {
                    it.copy(
                        signerSession = session,
                        nostrProfile = it.nostrProfile?.takeIf { profile ->
                            session != null && profile.pubkey.equals(session.pubkey, ignoreCase = true)
                        },
                    )
                }
                if (session != null) {
                    loadNostrProfile(session.pubkey)
                }
            }
        }
        viewModelScope.launch {
            relaySync.pendingNostrAuthSignRequest.collect { request ->
                _uiState.update { it.copy(pendingNostrAuthSignRequest = request) }
            }
        }
        relaySync.activeSession.value?.let { session ->
            viewModelScope.launch {
                syncRemoteDirectory(session.pubkey, announceEmpty = false)
            }
        }
        refreshChapterCacheStats()
        refreshCuratedShelves()
    }

    fun selectTab(tab: BookshelfTab) {
        searchJob?.cancel()
        _uiState.update {
            it.copy(tab = tab, selectedBook = null, error = null, isSearchOpen = false, isSearching = false)
        }
    }

    fun openSearch() {
        _uiState.update { it.copy(isSearchOpen = true, tab = BookshelfTab.Home) }
    }

    fun closeSearch() {
        searchJob?.cancel()
        _uiState.update { it.copy(isSearchOpen = false, isSearching = false) }
    }

    fun returnHome() {
        searchJob?.cancel()
        bookOpenJob?.cancel()
        _uiState.update {
            it.copy(
                tab = BookshelfTab.Home,
                selectedBook = null,
                isLoadingBook = false,
                isSearchOpen = false,
                isSearching = false,
                error = null,
            )
        }
    }

    fun retryShelves() {
        refreshCuratedShelves()
    }

    fun updateQuery(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun submitSearch() {
        val query = _uiState.value.query.trim()
        searchJob?.cancel()
        if (query.length < 2) {
            _uiState.update {
                it.copy(
                    isSearching = false,
                    searchMessage = "Enter at least two characters to search.",
                    searchResults = emptyList(),
                )
            }
            return
        }

        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, searchMessage = null, error = null) }
            try {
                val outcome = repository.searchOutcome(BookSearchQuery.from(query))
                val message = when (outcome.status) {
                    BookSearchStatus.COMPLETE ->
                        if (outcome.results.isEmpty()) "No matching books." else null
                    BookSearchStatus.PARTIAL ->
                        if (outcome.results.isEmpty()) {
                            "Mercury returned an incomplete response. Try again."
                        } else {
                            null
                        }
                    BookSearchStatus.UNAVAILABLE -> "Mercury is temporarily busy. Try again shortly."
                }
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        searchResults = outcome.results,
                        searchMessage = message,
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
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
        bookOpenJob?.cancel()
        bookOpenJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingBook = true,
                    selectedBook = null,
                    error = null,
                )
            }

            try {
                val detail = chapterHtmlCache.renderBook(repository.getBook(book))
                currentCoroutineContext().ensureActive()
                val cacheStats = chapterHtmlCache.stats()
                if (localBookshelf.isSaved(detail.summary.coordinate)) {
                    readerSettings.recordBookOpened(detail)
                }
                _uiState.update {
                    it.copy(
                        isLoadingBook = false,
                        selectedBook = detail,
                        chapterCacheStats = cacheStats,
                        error = null,
                    )
                }
            } catch (exception: MercuryApiException) {
                _uiState.update {
                    it.copy(
                        isLoadingBook = false,
                        error = exception.message ?: "Mercury is unavailable.",
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                _uiState.update {
                    it.copy(
                        isLoadingBook = false,
                        error = "Could not open this book.",
                    )
                }

            }
        }
    }

    fun closeBook() {
        returnHome()
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

    fun dismissSyncMessage(message: String) {
        _uiState.update { state ->
            if (state.syncMessage == message) {
                state.copy(syncMessage = null)
            } else {
                state
            }
        }
    }

    fun setChapterRelayUrls(rawRelayUrls: String) {
        runCatching { chapterSourceSettings.setRelayUrls(rawRelayUrls) }
            .onSuccess {
                _uiState.update {
                    it.copy(
                        error = null,
                        syncMessage = "Chapter sources updated.",
                    )
                }
            }.onFailure { failure ->
                _uiState.update {
                    it.copy(
                        error = failure.message ?: "Could not update chapter sources.",
                        syncMessage = null,
                    )
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
        relaySync.failPendingNostrAuthSignature(null, message)
        _uiState.update {
            it.copy(
                error = message,
                syncMessage = null,
                pendingDirectorySignRequest = null,
                isPublishingDirectory = false,
            )
        }
    }

    fun completeNostrAuthSignature(requestId: String?, signedEventJson: String) {
        relaySync.completeNostrAuthSignature(requestId, signedEventJson)
    }

    fun failPendingNostrAuthSignature(requestId: String?, message: String) {
        relaySync.failPendingNostrAuthSignature(requestId, message)
    }

    /** Pulls the newest shared directory without replacing any local books. */
    fun syncFromRelays() {
        val session = _uiState.value.signerSession
        if (session == null) {
            _uiState.update { it.copy(error = "Log in with an Android signer first.") }
            return
        }

        viewModelScope.launch {
            syncRemoteDirectory(session.pubkey, announceEmpty = true)
        }
    }

    /**
     * Signs and publishes the complete current local directory. This remains
     * available after a rejected signer request or a relay failure so the user
     * can retry without changing their saved books.
     */
    fun syncToRelays() {
        val state = _uiState.value
        val session = state.signerSession
        if (session == null) {
            _uiState.update { it.copy(error = "Log in with an Android signer first.") }
            return
        }
        if (
            state.isSyncingDirectory ||
                state.isPublishingDirectory ||
                state.pendingDirectorySignRequest != null ||
                state.pendingNostrAuthSignRequest != null
        ) {
            _uiState.update { it.copy(error = "Finish the current sync request first.") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isPublishingDirectory = true,
                    error = null,
                    syncMessage = null,
                )
            }
            try {
                val draft = relaySync.buildDirectoryDraft(
                    pubkey = session.pubkey,
                    tags = localBookshelf.directoryTags.value,
                )
                _uiState.update {
                    it.copy(
                        pendingDirectorySignRequest = PendingDirectorySignRequest(
                            id = UUID.randomUUID().toString(),
                            session = session,
                            unsignedEventJson = relaySync.unsignedDirectoryJson(draft),
                            createdAt = draft.createdAt,
                            kind = draft.kind,
                            content = draft.content,
                            tags = draft.tags,
                        ),
                        isPublishingDirectory = true,
                        syncMessage = "Review the local bookshelf sync in your signer.",
                    )
                }
            } catch (failure: Throwable) {
                _uiState.update {
                    it.copy(
                        isPublishingDirectory = false,
                        error = failure.message ?: "Could not prepare the local bookshelf for sync.",
                    )
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            relaySync.signOut()
            _uiState.update {
                it.copy(
                    syncMessage = "Signed out. My Books remains saved on this device.",
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

        if (
            session != null &&
            (state.pendingDirectorySignRequest != null || state.pendingNostrAuthSignRequest != null || state.isPublishingDirectory)
        ) {
            _uiState.update { it.copy(error = "Finish the current signer request first.") }
            return
        }

        _uiState.update {
            it.copy(
                isPublishingDirectory = session != null,
                error = null,
                syncMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                val change = localBookshelf.toggle(book)
                if (session == null) {
                    _uiState.update {
                        it.copy(
                            syncMessage = if (change.isSaved) {
                                "Saved to My Books on this device."
                            } else {
                                "Removed from My Books on this device."
                            },
                        )
                    }
                    return@launch
                }

                val activePubkey = _uiState.value.signerSession?.pubkey
                if (!activePubkey.equals(session.pubkey, ignoreCase = true)) {
                    _uiState.update {
                        it.copy(
                            isPublishingDirectory = false,
                            syncMessage = "Bookshelf change saved on this device.",
                        )
                    }
                    return@launch
                }

                val draft = relaySync.buildDirectoryDraft(
                    pubkey = session.pubkey,
                    tags = change.tags,
                )
                val request = PendingDirectorySignRequest(
                    id = UUID.randomUUID().toString(),
                    session = session,
                    unsignedEventJson = relaySync.unsignedDirectoryJson(draft),
                    createdAt = draft.createdAt,
                    kind = draft.kind,
                    content = draft.content,
                    tags = draft.tags,
                    fallbackBooks = listOf(book),
                )
                _uiState.update {
                    it.copy(
                        pendingDirectorySignRequest = request,
                        isPublishingDirectory = true,
                        error = null,
                        syncMessage = "Saved locally. Review sharing in your signer.",
                    )
                }
            } catch (failure: Throwable) {
                _uiState.update {
                    it.copy(
                        isPublishingDirectory = false,
                        error = failure.message ?: "Could not update My Books.",
                    )
                }
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
                        syncMessage = "Local bookshelf remains saved on this device.",
                    )
                }
                return@launch
            }

            try {
                val event = relaySync.decodeSignedDirectory(signedEventJson)
                require(event.pubkey.equals(pending.session.pubkey, ignoreCase = true)) {
                    "Signer returned an event for a different account."
                }
                require(hasExpectedEditableDirectoryTags(event.tags, pending.tags)) {
                    "Signer changed the collection tags."
                }

                check(event.kind == pending.kind && event.content == pending.content && event.createdAt == pending.createdAt)
                val report = relaySync.publishDirectory(event)
                val applied =
                    if (pending.fallbackBooks.isEmpty()) {
                        DirectoryApplyResult(
                            referenceCount = BookshelfDirectoryRules.extractBookReferences(localBookshelf.directoryTags.value).size,
                            warning = null,
                        )
                    } else {
                        applyDirectoryTags(event.tags, fallbackBooks = pending.fallbackBooks)
                    }
                val publishMessage =
                    if (report.acceptedRelays > 0) {
                        val failedRelayDetail = report.failureMessage()
                            .takeIf { report.acceptedRelays < report.attemptedRelays }
                            ?.let { " Failed relays: $it" }
                            .orEmpty()
                        "Shared ${applied.referenceCount} bookshelf items with ${report.acceptedRelays}/${report.attemptedRelays} relays.$failedRelayDetail"
                    } else {
                        "Bookshelf saved locally, but no relay accepted it yet."
                    }

                _uiState.update {
                    it.copy(
                        pendingDirectorySignRequest = null,
                        isPublishingDirectory = false,
                        syncMessage = applied.warning ?: publishMessage,
                        error = if (report.acceptedRelays > 0) null else report.failureMessage(),
                    )
                }
            } catch (failure: Throwable) {
                _uiState.update {
                    it.copy(
                        pendingDirectorySignRequest = null,
                        isPublishingDirectory = false,
                        error = failure.message ?: "Could not share bookshelf update.",
                        syncMessage = "Local bookshelf remains saved on this device.",
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
                syncMessage = "Local bookshelf remains saved on this device.",
            )
        }
    }

    fun recordReaderProgress(book: BookDetail, chapterIndex: Int) {
        readerSettings.recordProgress(book, chapterIndex)
    }

    fun markOnboardingTipSeen(tip: OnboardingTip) {
        onboardingTips.markSeen(tip)
    }

    fun setLocalRelayUrl(rawRelayUrl: String) {
        runCatching { localRelaySettings.setRelayUrl(rawRelayUrl) }
            .onFailure { failure -> _uiState.update { it.copy(error = failure.message ?: "Could not save local relay.") } }
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

    private fun refreshCuratedShelves() {
        if (_uiState.value.isLoadingShelves) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingShelves = true, shelfMessage = null) }
            try {
                val cached = curatedShelfRepository.loadCached()
                _uiState.update {
                    it.copy(
                        curatedShelves = cached.shelves,
                        isLoadingShelves = cached.needsRefresh,
                    )
                }
                if (!cached.needsRefresh) {
                    return@launch
                }

                val refreshed = curatedShelfRepository.refresh()
                _uiState.update {
                    it.copy(
                        curatedShelves = refreshed.shelves,
                        isLoadingShelves = false,
                        shelfMessage = refreshed.error,
                    )
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                _uiState.update {
                    it.copy(
                        isLoadingShelves = false,
                        shelfMessage = failure.message ?: "Could not load shelves.",
                    )
                }
            }
        }
    }

    private fun refreshChapterCacheStats() {
        viewModelScope.launch {
            val stats = chapterHtmlCache.stats()
            _uiState.update { it.copy(chapterCacheStats = stats) }
        }
    }

    private suspend fun loadNostrProfile(pubkey: String) {
        nostrProfiles.cachedProfile(pubkey)?.let { cached ->
            updateNostrProfile(pubkey, cached)
        }

        runCatching { nostrProfiles.refreshProfile(pubkey) }
            .getOrNull()
            ?.let { refreshed -> updateNostrProfile(pubkey, refreshed) }
    }

    private fun updateNostrProfile(pubkey: String, profile: NostrProfile) {
        _uiState.update { state ->
            if (state.signerSession?.pubkey.equals(pubkey, ignoreCase = true)) {
                state.copy(nostrProfile = profile)
            } else {
                state
            }
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
                _uiState.update {
                    it.copy(
                        isSyncingDirectory = false,
                        syncMessage = if (announceEmpty) {
                            "No shared bookshelf found. Local books are unchanged."
                        } else {
                            null
                        },
                    )
                }
                return
            }

            val applied = applyDirectoryTags(event.tags)
            _uiState.update {
                it.copy(
                    isSyncingDirectory = false,
                    error = applied.warning,
                    syncMessage = "Merged ${applied.referenceCount} bookshelf items with this device.",
                )
            }
        } catch (failure: Throwable) {
            _uiState.update {
                it.copy(
                    isSyncingDirectory = false,
                    error = failure.message ?: "Could not sync bookshelf.",
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
                repository.getMyBooksForReferences(references)
            }

        val books =
            (resolvedBooks.getOrDefault(emptyList()) + fallbackBooks)
                .distinctBy(BookSummary::coordinate)

        localBookshelf.merge(normalizedTags, books)

        return DirectoryApplyResult(
            referenceCount =
                BookshelfDirectoryRules
                    .extractBookReferences(localBookshelf.directoryTags.value)
                    .size,
            warning = resolvedBooks.exceptionOrNull()?.message,
        )
    }
}

enum class BookshelfTab {
    Home,
    MyBooks,
    Settings,
}

data class PendingDirectorySignRequest(
    val id: String,
    val session: NostrSignerSession,
    val unsignedEventJson: String,
    val createdAt: Long,
    val kind: Int,
    val content: String,
    val tags: List<List<String>>,
    val fallbackBooks: List<BookSummary> = emptyList(),
)

data class BookshelfUiState(
    val tab: BookshelfTab = BookshelfTab.Home,
    val curatedShelves: List<CuratedShelf> = emptyList(),
    val isLoadingShelves: Boolean = false,
    val shelfMessage: String? = null,
    val isSearchOpen: Boolean = false,
    val query: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<BookSearchResult> = emptyList(),
    val searchMessage: String? = null,
    val savedBooks: List<BookSummary> = emptyList(),
    val savedCoordinates: Set<String> = emptySet(),
    val selectedBook: BookDetail? = null,
    val isLoadingBook: Boolean = false,
    val error: String? = null,
    val syncState: BookshelfSyncState = BookshelfSyncState.NotConfigured,
    val signerSession: NostrSignerSession? = null,
    val nostrProfile: NostrProfile? = null,
    val syncMessage: String? = null,
    val isSyncingDirectory: Boolean = false,
    val isPublishingDirectory: Boolean = false,
    val pendingDirectorySignRequest: PendingDirectorySignRequest? = null,
    val pendingNostrAuthSignRequest: PendingNostrAuthSignRequest? = null,
    val readerPreferences: ReaderPreferences = ReaderPreferences(),
    val readingProgress: Map<String, ReadingProgress> = emptyMap(),
    val chapterRelayUrls: List<String> = emptyList(),
    val seenOnboardingTips: Set<OnboardingTip> = emptySet(),
    val localRelayUrl: String? = null,
    val relayConfiguration: RelayConfiguration = RelayConfiguration(),
    val chapterCacheStats: ChapterHtmlCacheStats = ChapterHtmlCacheStats(),
    val isClearingChapterCache: Boolean = false,
)

data class ContinueReadingBook(
    val book: BookSummary,
    val progress: ReadingProgress,
)

internal fun mostRecentlyOpenedSavedBook(
    savedBooks: List<BookSummary>,
    readingProgress: Map<String, ReadingProgress>,
): ContinueReadingBook? =
    savedBooks
        .mapNotNull { book -> readingProgress[book.coordinate]?.let { ContinueReadingBook(book, it) } }
        .maxByOrNull { it.progress.updatedAtMillis }

/** Compares user-editable tags while allowing required publish-only metadata. */
internal fun hasExpectedEditableDirectoryTags(
    signedTags: List<List<String>>,
    expectedPublishedTags: List<List<String>>,
): Boolean =
    BookshelfDirectoryRules.normalizeEditableTags(signedTags) ==
        BookshelfDirectoryRules.normalizeEditableTags(expectedPublishedTags)

private data class DirectoryApplyResult(
    val referenceCount: Int,
    val warning: String?,
)
