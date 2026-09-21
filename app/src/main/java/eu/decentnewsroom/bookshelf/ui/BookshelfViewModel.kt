package eu.decentnewsroom.bookshelf.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.decentnewsroom.bookshelf.AppGraph
import eu.decentnewsroom.bookshelf.data.highlights.HighlightAnchors
import eu.decentnewsroom.bookshelf.data.highlights.HighlightEventDraft
import eu.decentnewsroom.bookshelf.data.highlights.HighlightEventFactory
import eu.decentnewsroom.bookshelf.data.highlights.HighlightOutbox
import eu.decentnewsroom.bookshelf.data.highlights.HighlightOutboxDispatcher
import eu.decentnewsroom.bookshelf.data.highlights.HighlightStore
import eu.decentnewsroom.bookshelf.data.highlights.ReaderHighlight
import eu.decentnewsroom.bookshelf.data.nostr.NostrEventVerifier
import eu.decentnewsroom.bookshelf.domain.BookChapter
import eu.decentnewsroom.bookshelf.data.bookshelf.BookshelfDirectoryRules
import eu.decentnewsroom.bookshelf.data.connectivity.ValidatedInternetConnectivity
import eu.decentnewsroom.bookshelf.data.discovery.CuratedShelf
import eu.decentnewsroom.bookshelf.data.discovery.CuratedShelfRepository
import eu.decentnewsroom.bookshelf.data.bookshelf.LocalBookshelfStore
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
import eu.decentnewsroom.bookshelf.data.nostr.RatingEventDraft
import eu.decentnewsroom.bookshelf.data.nostr.PendingNostrAuthSignRequest
import eu.decentnewsroom.bookshelf.data.onboarding.OnboardingTip
import eu.decentnewsroom.bookshelf.data.onboarding.OnboardingTipStore
import eu.decentnewsroom.bookshelf.data.reader.ReaderPreferences
import eu.decentnewsroom.bookshelf.data.reader.ReaderSettingsStore
import eu.decentnewsroom.bookshelf.data.reader.ReaderTheme
import eu.decentnewsroom.bookshelf.data.reader.ReadingProgress
import eu.decentnewsroom.bookshelf.data.rendering.ChapterHtmlCache
import eu.decentnewsroom.bookshelf.data.ratings.BookRating
import eu.decentnewsroom.bookshelf.data.ratings.BookRatingAggregator
import eu.decentnewsroom.bookshelf.data.ratings.BookRatingsRepository
import eu.decentnewsroom.bookshelf.data.ratings.ReviewOutboxDispatcher
import eu.decentnewsroom.bookshelf.data.ratings.ReviewOutboxEntry
import eu.decentnewsroom.bookshelf.data.ratings.ReviewDeliveryState
import eu.decentnewsroom.bookshelf.domain.BookDetail
import eu.decentnewsroom.bookshelf.domain.BookSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.collections.emptyList
import kotlin.collections.sortedWith
import kotlin.math.roundToInt

class BookshelfViewModel(
    private val repository: MercuryBookRepository = AppGraph.mercuryBooks,
    private val chapterHtmlCache: ChapterHtmlCache = AppGraph.chapterHtmlCache,
    private val localBookshelf: LocalBookshelfStore = AppGraph.localBookshelf,
    private val readerSettings: ReaderSettingsStore = AppGraph.readerSettings,
    private val onboardingTips: OnboardingTipStore = AppGraph.onboardingTips,
    private val localRelaySettings: LocalRelaySettingsStore = AppGraph.localRelaySettings,
    private val relaySync: BookshelfRelaySync = AppGraph.relaySync,
    private val nostrProfiles: NostrProfileSource = AppGraph.nostrProfiles,
    private val curatedShelfRepository: CuratedShelfRepository = AppGraph.curatedShelves,
    private val bookRatings: BookRatingsRepository = AppGraph.bookRatings,
    private val reviewOutboxDispatcher: ReviewOutboxDispatcher = AppGraph.reviewOutboxDispatcher,
    private val connectivity: ValidatedInternetConnectivity = AppGraph.connectivity,
    private val highlightStore: HighlightStore = AppGraph.highlights,
    private val highlightOutbox: HighlightOutbox = AppGraph.highlightOutbox,
    private val highlightDispatcher: HighlightOutboxDispatcher = AppGraph.highlightDispatcher,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        BookshelfUiState(
            readerPreferences = readerSettings.readerPreferences.value,
        ),
    )
    val uiState: StateFlow<BookshelfUiState> = _uiState.asStateFlow()
    private var searchJob: Job? = null
    private var bookOpenJob: Job? = null
    private var bookDetailsJob: Job? = null

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
            relaySync.state.collect { syncState ->
                _uiState.update { it.copy(syncState = syncState) }
            }
        }
        viewModelScope.launch {
            localRelaySettings.relayUrl.collect { relayUrl ->
                relaySync.setLocalRelayUrl(relayUrl)
                _uiState.update { it.copy(localRelayUrl = relayUrl) }
            }
        }
        viewModelScope.launch {
            relaySync.activeSession.collect { session ->
                _uiState.update {
                    it.copy(
                        signerSession = session,
                        pendingHighlightSignRequest = it.pendingHighlightSignRequest?.takeIf { request -> request.session == session },
                        highlightComposer = it.highlightComposer?.let { composer ->
                            if (it.pendingHighlightSignRequest != null && it.pendingHighlightSignRequest.session != session)
                                composer.copy(isPublishing = false, requiresSignIn = session == null, error = "Account changed. Publish again with the selected account.")
                            else composer.copy(requiresSignIn = session == null)
                        },
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
        viewModelScope.launch {
            connectivity.online.collect { online ->
                if (online) runCatching { reviewOutboxDispatcher.syncPending() }
                retryPendingHighlights()
            }
        }
        relaySync.activeSession.value?.let { session ->
            viewModelScope.launch {
                syncRemoteDirectory(session.pubkey, announceEmpty = false)
            }
        }
        refreshCuratedShelves()
        viewModelScope.launch {
            refreshHighlights()
            while (isActive) {
                deliverPendingHighlights(force = false)
                delay(30_000)
            }
        }
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
                ratingsPage = null,
                ratingComposer = null,
                highlightComposer = it.highlightComposer?.takeIf { composer -> composer.isPublishing },
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
        if (book.chapterRefs.isEmpty()) {
            bookOpenJob?.cancel()
            _uiState.update {
                it.copy(
                    isLoadingBook = false,
                    selectedBook = null,
                    error = "This is a library card. Its full text is not available to read yet.",
                )
            }
            return
        }
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
                val detail = chapterHtmlCache.renderBook(repository.openBook(book))
                currentCoroutineContext().ensureActive()
                if (localBookshelf.isSaved(detail.summary.coordinate)) {
                    readerSettings.recordBookOpened(detail)
                }
                _uiState.update {
                    it.copy(
                        isLoadingBook = false,
                        selectedBook = detail,
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

    fun showBookActions(book: BookSummary) {
        _uiState.update { it.copy(bookActions = book) }
    }

    fun dismissBookActions() {
        _uiState.update { it.copy(bookActions = null) }
    }

    fun showBookDetails(book: BookSummary) {
        dismissBookActions()
        bookDetailsJob?.cancel()
        _uiState.update { it.copy(bookDetails = BookDetailsState(book)) }
        bookDetailsJob = viewModelScope.launch {
            val cached = runCatching { nostrProfiles.cachedProfile(book.pubkey) }.getOrNull()
            _uiState.update { state ->
                state.copy(bookDetails = state.bookDetails?.takeIf { it.book.coordinate == book.coordinate }
                    ?.copy(publisher = cached))
            }
            val publisher = runCatching { nostrProfiles.refreshProfile(book.pubkey) }.getOrNull() ?: cached
            val aggregate = runCatching { bookRatings.aggregateFor(book) }.getOrNull()
            val ratingSummary = aggregate?.let {
                RatingSummaryUi(
                    averageStars = it.averageStars,
                    normalizedAverage = it.averageNormalizedRating,
                    ratingCount = it.ratingCount,
                    isLoading = false,
                )
            } ?: RatingSummaryUi(isLoading = false)
            _uiState.update { state ->
                state.copy(bookDetails = state.bookDetails?.takeIf { it.book.coordinate == book.coordinate }
                    ?.copy(publisher = publisher, isLoadingPublisher = false, ratings = ratingSummary))
            }
        }
    }

    fun dismissBookDetails() {
        bookDetailsJob?.cancel()
        _uiState.update { it.copy(bookDetails = null) }
    }

    /** Opens the full, per-book community-rating view from the metadata sheet. */
    fun showRatings(book: BookSummary) {
        _uiState.update { it.copy(bookDetails = null, ratingsPage = RatingDetailsState(book, RatingSummaryUi())) }
        viewModelScope.launch {
            val ratings = runCatching { bookRatings.ratingsFor(book) }.getOrDefault(emptyList())
            val aggregate = BookRatingAggregator.aggregateForBook(book.coordinate, ratings)
            val summary = aggregate?.let { RatingSummaryUi(it.averageStars, it.averageNormalizedRating, it.ratingCount, isLoading = false) }
                ?: RatingSummaryUi(isLoading = false)
            val distribution = ratings.groupBy { it.displayStars.roundToInt().coerceIn(1, 5) }
                .map { (stars, values) -> RatingDistributionUi(stars, values.size) }
            val sortedRatings = ratings.sortedWith(compareByDescending<BookRating> { it.createdAt }.thenByDescending { it.eventId })
            val reviewerPubkeys = sortedRatings.map(BookRating::reviewerPubkey).distinct()
            val cachedProfiles = reviewerPubkeys.associateWith { pubkey ->
                try {
                    nostrProfiles.cachedProfile(pubkey)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    null
                }
            }
            val reviews = sortedRatings.map {
                RatingReviewUi(
                    eventId = it.eventId,
                    reviewerPubkey = it.reviewerPubkey,
                    stars = it.displayStars,
                    createdAtMillis = it.createdAt * 1_000,
                    opinion = it.review,
                    reviewerName = cachedProfiles[it.reviewerPubkey]?.preferredName,
                )
            }
            _uiState.update { state ->
                state.copy(ratingsPage = state.ratingsPage?.takeIf { it.book.coordinate == book.coordinate }
                    ?.copy(summary = summary, distribution = distribution, reviews = reviews, isLoadingReviews = false))
            }
            coroutineScope {
                reviewerPubkeys.chunked(MAX_CONCURRENT_REVIEWER_PROFILE_REFRESHES).forEach { batch ->
                    batch.map { pubkey -> async {
                        try {
                            pubkey to nostrProfiles.refreshProfile(pubkey)
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (_: Exception) {
                            pubkey to null
                        }
                    } }.awaitAll().forEach { (pubkey, profile) ->
                        val reviewerName = profile?.preferredName ?: return@forEach
                        _uiState.update { state ->
                            val page = state.ratingsPage?.takeIf { it.book.coordinate == book.coordinate }
                            state.copy(ratingsPage = page?.let { currentPage ->
                                currentPage.copy(reviews = currentPage.reviews.map { review ->
                                    if (review.reviewerPubkey == pubkey) review.copy(reviewerName = reviewerName) else review
                                })
                            })
                        }
                    }
                }
            }
        }
    }
    fun dismissRatings() {
        _uiState.update { it.copy(ratingsPage = null, ratingComposer = null) }
    }

    fun showRatingComposer() {
        val page = _uiState.value.ratingsPage ?: return
        _uiState.update {
            it.copy(
                ratingComposer = RatingComposerState(
                    book = page.book,
                    requiresSignIn = it.signerSession == null,
                ),
            )
        }
    }

    fun dismissRatingComposer() {
        _uiState.update { it.copy(ratingComposer = null) }
    }

    fun updateRatingStars(stars: Int) {
        _uiState.update { state ->
            state.copy(ratingComposer = state.ratingComposer?.copy(selectedStars = stars.coerceIn(1, 5)))
        }
    }

    fun updateRatingOpinion(opinion: String) {
        _uiState.update { state ->
            state.copy(ratingComposer = state.ratingComposer?.copy(opinion = opinion))
        }
    }

    fun saveHighlight(chapter: BookChapter, text: String, start: Int, end: Int) {
        val book = _uiState.value.selectedBook ?: return
        if (book.chapters.none { it.reference.coordinate == chapter.reference.coordinate && it.id == chapter.id }) return
        viewModelScope.launch {
            try {
                require(start >= 0 && end <= text.length && end > start) { "Select text within this chapter." }
                val quote = text.substring(start, end)
                require(quote.isNotBlank() && quote.length <= 16_384) { "Select a passage of up to 16,384 characters." }
                val chapterEvent = requireNotNull(chapter.sourceEvent) { "The original signed chapter is unavailable." }
                NostrEventVerifier.requireVerified(chapterEvent)
                val existing = highlightStore.all().firstOrNull {
                    it.bookCoordinate == book.summary.coordinate && it.chapterEvent.id == chapterEvent.id &&
                        it.startOffset == start && it.endOffset == end && it.quote == quote
                }
                if (existing == null) {
                    highlightStore.save(ReaderHighlight(
                        id = UUID.randomUUID().toString(),
                        bookCoordinate = book.summary.coordinate,
                        chapterCoordinate = chapter.reference.coordinate,
                        chapterTitle = chapter.title,
                        chapterEvent = chapterEvent,
                        quote = quote,
                        context = HighlightAnchors.context(text, start, end),
                        startOffset = start,
                        endOffset = end,
                        prefix = HighlightAnchors.prefix(text, start),
                        suffix = HighlightAnchors.suffix(text, end),
                        createdAtMillis = System.currentTimeMillis(),
                        displayedTextHash = HighlightAnchors.textHash(text),
                    ))
                }
                refreshHighlights()
                _uiState.update { it.copy(syncMessage = if (existing == null) "Highlight saved privately." else "This passage is already highlighted.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _uiState.update { it.copy(error = failure.message ?: "Could not save highlight.") }
            }
        }
    }

    fun showHighlightComposer(highlight: ReaderHighlight) {
        if (_uiState.value.pendingHighlightSignRequest != null) return
        _uiState.update { it.copy(highlightComposer = HighlightComposerState(
            highlight = highlight, comment = highlight.comment, requiresSignIn = it.signerSession == null,
        )) }
    }

    fun updateHighlightComment(comment: String) {
        _uiState.update { state -> state.copy(highlightComposer = state.highlightComposer?.let {
            if (it.isPublishing) it else it.copy(comment = comment.take(4_096), error = null)
        }) }
    }

    fun dismissHighlightComposer() {
        if (_uiState.value.highlightComposer?.isPublishing == true) return
        _uiState.update { it.copy(highlightComposer = null) }
    }

    fun submitHighlight() {
        val state = _uiState.value
        val composer = state.highlightComposer ?: return
        if (composer.isPublishing || state.pendingHighlightSignRequest != null) return
        val session = state.signerSession
        if (session == null) {
            _uiState.update { it.copy(highlightComposer = composer.copy(requiresSignIn = true, error = "Log in with an Android signer before publishing.")) }
            return
        }
        _uiState.update { it.copy(highlightComposer = composer.copy(isPublishing = true, error = null)) }
        viewModelScope.launch {
            try {
                require(composer.comment.toByteArray(Charsets.UTF_8).size <= NostrEventVerifier.MAX_TAG_ELEMENT_LENGTH) {
                    "The comment is too long. Use at most 4,096 UTF-8 bytes."
                }
                // Reconcile a signature persisted just before process death before offering another signature.
                refreshHighlights()
                val stored = highlightStore.all().firstOrNull { it.id == composer.highlight.id }
                    ?: error("This highlight is no longer available.")
                require(stored.publishedEventId == null && highlightOutbox.entries().none { it.localHighlightId == stored.id }) {
                    "This highlight is already published or queued. Use Retry for pending delivery."
                }
                require(_uiState.value.signerSession == session) { "Account changed. Try publishing again." }
                val draft = HighlightEventFactory.create(
                    pubkey = session.pubkey, chapter = stored.chapterEvent, quote = stored.quote,
                    context = stored.context.takeIf { it.toByteArray(Charsets.UTF_8).size <= NostrEventVerifier.MAX_TAG_ELEMENT_LENGTH },
                    comment = composer.comment,
                )
                // Save the comment privately before handing off to another application.
                highlightStore.save(stored.copy(comment = composer.comment))
                _uiState.update { it.copy(
                    highlightComposer = composer.copy(isPublishing = true, error = null),
                    pendingHighlightSignRequest = PendingHighlightSignRequest(
                        id = UUID.randomUUID().toString(), session = session,
                        unsignedEventJson = HighlightEventFactory.unsignedJson(draft),
                        draft = draft, highlightId = stored.id,
                    ),
                ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _uiState.update { it.copy(highlightComposer = it.highlightComposer?.copy(isPublishing = false, error = failure.message ?: "Could not prepare highlight.")) }
            }
        }
    }

    fun completeHighlightSignature(requestId: String?, signedEventJson: String) {
        val request = _uiState.value.pendingHighlightSignRequest ?: return
        if (request.id != requestId || _uiState.value.signerSession != request.session) return
        // Consume this result synchronously: duplicate Activity callbacks cannot enqueue another operation.
        _uiState.update { it.copy(pendingHighlightSignRequest = null) }
        viewModelScope.launch {
            try {
                val event = HighlightEventFactory.decodeSigned(signedEventJson, request.draft)
                val stored = highlightStore.all().firstOrNull { it.id == request.highlightId }
                    ?: error("This highlight is no longer available.")
                require(_uiState.value.signerSession == request.session) { "Account changed. Try publishing again." }
                highlightOutbox.enqueue(event, stored.chapterEvent, stored.id)
                // The outbox is authoritative if marking the private record fails or the process exits.
                refreshHighlights()
                _uiState.update { it.copy(highlightComposer = null, syncMessage = "Highlight queued for publishing.") }
                deliverPendingHighlights(force = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _uiState.update { it.copy(highlightComposer = it.highlightComposer?.copy(isPublishing = false, error = failure.message ?: "Could not save signed highlight.")) }
            }
        }
    }

    fun failPendingHighlightSignature(message: String) {
        _uiState.update { it.copy(
            pendingHighlightSignRequest = null,
            highlightComposer = it.highlightComposer?.copy(isPublishing = false, error = message),
        ) }
    }

    fun retryPendingHighlights() {
        viewModelScope.launch { deliverPendingHighlights(force = true) }
    }

    private suspend fun deliverPendingHighlights(force: Boolean) {
        try {
            highlightDispatcher.syncPending(force)
            refreshHighlights()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            _uiState.update { it.copy(error = failure.message ?: "Could not sync pending highlights.") }
        }
    }

    private suspend fun refreshHighlights() {
        try {
            val entries = highlightOutbox.entries()
            var saved = highlightStore.all()
            for (entry in entries) {
                val local = saved.firstOrNull { it.id == entry.localHighlightId } ?: continue
                if (local.publishedEventId != entry.event.id) {
                    val comment = entry.event.tags.firstOrNull { it.firstOrNull() == "comment" }?.getOrNull(1).orEmpty()
                    highlightStore.markPublished(local.id, entry.event.id, comment)
                }
            }
            saved = highlightStore.all()
            _uiState.update { it.copy(
                highlights = saved,
                highlightDelivery = entries.associate { entry ->
                    entry.localHighlightId to entry.deliveryLabel
                },
                pendingHighlightCount = entries.count { entry -> !entry.isComplete },
            ) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            _uiState.update { it.copy(error = failure.message ?: "Could not load highlights.") }
        }
    }

    fun submitRatingReview() {
        val composer = _uiState.value.ratingComposer ?: return
        val session = _uiState.value.signerSession
        val stars = composer.selectedStars
        if (session == null || stars == null) {
            _uiState.update { it.copy(ratingComposer = composer.copy(error = if (session == null) "Log in with an Android signer before publishing a review." else "Select a star rating first.")) }
            return
        }
        runCatching {
            relaySync.buildRatingDraft(session.pubkey, composer.book.coordinate, stars / 5.0, composer.opinion, entityType = composer.book.type)
        }.onSuccess { draft ->
            _uiState.update { it.copy(ratingComposer = composer.copy(isPublishing = true, error = null), pendingRatingSignRequest = PendingRatingSignRequest(UUID.randomUUID().toString(), session, relaySync.unsignedRatingJson(draft), draft)) }
        }.onFailure { failure -> _uiState.update { it.copy(ratingComposer = composer.copy(error = failure.message ?: "Could not prepare rating.")) } }
    }

    fun completeRatingSignature(requestId: String?, signedEventJson: String) {
        val request = _uiState.value.pendingRatingSignRequest
        if (request == null || request.id != requestId) return
        viewModelScope.launch {
            runCatching {
                reviewOutboxDispatcher.enqueueAndTryCitrine(
                    relaySync.decodeSignedRating(signedEventJson),
                    request.draft.publicationAuthorPubkey,
                )
            }.onSuccess { entry ->
                _uiState.update {
                    it.copy(
                        pendingRatingSignRequest = null,
                        ratingComposer = null,
                        latestReviewDelivery = entry.deliveryLabel(),
                        syncMessage = entry.deliveryLabel(),
                    )
                }
            }.onFailure { failure ->
                _uiState.update {
                    it.copy(
                        pendingRatingSignRequest = null,
                        ratingComposer = it.ratingComposer?.copy(
                            isPublishing = false,
                            error = failure.message ?: "Could not save review locally.",
                        ),
                    )
                }
            }
        }
    }
    fun failPendingRatingSignature(message: String) { _uiState.update { it.copy(pendingRatingSignRequest = null, ratingComposer = it.ratingComposer?.copy(isPublishing = false, error = message)) } }
    fun broadcastBookToLocalRelay(book: BookSummary) {
        dismissBookActions()
        val relayUrl = _uiState.value.localRelayUrl
        if (relayUrl == null) {
            _uiState.update { it.copy(error = "Configure a local relay in Settings first.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBroadcastingBook = true, error = null) }
            try {
                val events = repository.getBookEventsForBroadcast(book)
                var accepted = 0
                events.forEach { event ->
                    if (relaySync.publishToRelay(event, relayUrl).acceptedRelays > 0) accepted++
                }
                val missing = (book.chapterCount + 1 - events.size).coerceAtLeast(0)
                val suffix = if (missing == 0) "." else "; $missing chapter events were unavailable."
                _uiState.update {
                    it.copy(
                        isBroadcastingBook = false,
                        syncMessage = "Broadcast $accepted of ${events.size} queued events to the local relay$suffix",
                    )
                }
            } catch (failure: Throwable) {
                _uiState.update {
                    it.copy(
                        isBroadcastingBook = false,
                        error = failure.message ?: "Could not broadcast this book to the local relay.",
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

data class PendingRatingSignRequest(val id: String, val session: NostrSignerSession, val unsignedEventJson: String, val draft: RatingEventDraft)

data class PendingHighlightSignRequest(
    val id: String,
    val session: NostrSignerSession,
    val unsignedEventJson: String,
    val draft: HighlightEventDraft,
    val highlightId: String,
)

data class HighlightComposerState(
    val highlight: ReaderHighlight,
    val comment: String = "",
    val isPublishing: Boolean = false,
    val error: String? = null,
    val requiresSignIn: Boolean = false,
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
    val bookActions: BookSummary? = null,
    val bookDetails: BookDetailsState? = null,
    val ratingsPage: RatingDetailsState? = null,
    val ratingComposer: RatingComposerState? = null,
    val highlights: List<ReaderHighlight> = emptyList(),
    val highlightComposer: HighlightComposerState? = null,
    val pendingHighlightSignRequest: PendingHighlightSignRequest? = null,
    val highlightDelivery: Map<String, String> = emptyMap(),
    val pendingHighlightCount: Int = 0,
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
    val pendingRatingSignRequest: PendingRatingSignRequest? = null,
    val readerPreferences: ReaderPreferences = ReaderPreferences(),
    val readingProgress: Map<String, ReadingProgress> = emptyMap(),
    val seenOnboardingTips: Set<OnboardingTip> = emptySet(),
    val localRelayUrl: String? = null,
    val isBroadcastingBook: Boolean = false,
    val latestReviewDelivery: String? = null,
)

data class BookDetailsState(
    val book: BookSummary,
    val publisher: NostrProfile? = null,
    val isLoadingPublisher: Boolean = true,
    val ratings: RatingSummaryUi = RatingSummaryUi(),
)

/** UI-only projection until the ratings repository is connected to this ViewModel. */
private const val MAX_CONCURRENT_REVIEWER_PROFILE_REFRESHES = 4
data class RatingSummaryUi(
    val averageStars: Double? = null,
    val normalizedAverage: Double? = null,
    val ratingCount: Int = 0,
    val isLoading: Boolean = true,
    val isStale: Boolean = false,
    val error: String? = null,
)

data class RatingReviewUi(
    val eventId: String,
    val reviewerPubkey: String,
    val stars: Double,
    val createdAtMillis: Long,
    val opinion: String,
    val reviewerName: String? = null,
)

data class RatingDistributionUi(
    val stars: Int,
    val count: Int,
)

data class RatingDetailsState(
    val book: BookSummary,
    val summary: RatingSummaryUi,
    val distribution: List<RatingDistributionUi> = emptyList(),
    val reviews: List<RatingReviewUi> = emptyList(),
    val isLoadingReviews: Boolean = true,
    val canLoadMoreReviews: Boolean = false,
)

data class RatingComposerState(
    val book: BookSummary,
    val selectedStars: Int? = null,
    val opinion: String = "",
    val requiresSignIn: Boolean = false,
    val isPublishing: Boolean = false,
    val error: String? = null,
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

private fun ReviewOutboxEntry.deliveryLabel(): String = when {
    isComplete -> "Review delivered to configured relays."
    citrine == ReviewDeliveryState.ACCEPTED -> "Review saved locally and published to Citrine; remote relay sync is pending."
    lastFailure != null -> "Review saved locally; delivery needs retry."
    else -> "Review saved locally; syncing when a connection is available."
}
