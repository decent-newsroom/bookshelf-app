@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.decentnewsroom.bookshelf.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import eu.decentnewsroom.bookshelf.data.discovery.CuratedShelf
import eu.decentnewsroom.bookshelf.data.highlights.ReaderHighlight
import eu.decentnewsroom.bookshelf.data.mercury.TrustedCoverImagePolicy
import eu.decentnewsroom.bookshelf.data.nostr.AndroidExternalSigner
import eu.decentnewsroom.bookshelf.data.nostr.AndroidSignerResult
import eu.decentnewsroom.bookshelf.data.onboarding.OnboardingTip
import eu.decentnewsroom.bookshelf.data.reader.ReaderPreferences
import eu.decentnewsroom.bookshelf.data.reader.ReaderTheme
import eu.decentnewsroom.bookshelf.data.reader.ReadingProgress
import eu.decentnewsroom.bookshelf.domain.BookChapter
import eu.decentnewsroom.bookshelf.domain.BookDetail
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.ui.theme.BookshelfTheme
import eu.decentnewsroom.bookshelf.ui.books.BookActionsSheet
import eu.decentnewsroom.bookshelf.ui.books.BookDetailsSheet
import eu.decentnewsroom.bookshelf.ui.books.BookOpeningScreen
import eu.decentnewsroom.bookshelf.ui.home.HomeScreen
import eu.decentnewsroom.bookshelf.ui.library.MyBooksScreen
import eu.decentnewsroom.bookshelf.ui.ratings.RatingComposerSheet
import eu.decentnewsroom.bookshelf.ui.ratings.RatingsSheet
import eu.decentnewsroom.bookshelf.ui.reader.ReaderScreen
import eu.decentnewsroom.bookshelf.ui.search.SearchScreen
import eu.decentnewsroom.bookshelf.ui.shell.rememberExternalSignerActions
import eu.decentnewsroom.bookshelf.ui.settings.AccountSettingsActions
import eu.decentnewsroom.bookshelf.ui.settings.AccountSettingsState
import eu.decentnewsroom.bookshelf.ui.settings.SettingsActions
import eu.decentnewsroom.bookshelf.ui.settings.SettingsScreen
import eu.decentnewsroom.bookshelf.ui.settings.SettingsViewModel
import eu.decentnewsroom.bookshelf.ui.theme.ReaderColors
import eu.decentnewsroom.bookshelf.ui.theme.readerColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookshelfApp(viewModel: BookshelfViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val homeListState = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.setReadingForeground(true)
            if (event == Lifecycle.Event.ON_STOP) viewModel.setReadingForeground(false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.setReadingForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setReadingForeground(false)
        }
    }
    BackHandler(
        enabled = state.selectedBook != null ||
            state.isLoadingBook ||
            state.isSearchOpen ||
            state.tab != BookshelfTab.Home,
    ) {
        viewModel.returnHome()
    }
    val signerActions = rememberExternalSignerActions(state, viewModel)
    val signerAvailable = signerActions.signerAvailable
    val startExternalSignerLogin = signerActions.startLogin

    LaunchedEffect(state.syncMessage) {
        val message = state.syncMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = message,
            withDismissAction = true,
            duration = SnackbarDuration.Short,
        )
        viewModel.dismissSyncMessage(message)
    }

    BookshelfTheme(theme = state.readerPreferences.theme) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (state.selectedBook == null) {
                    BookshelfBottomBar(
                        selected = state.tab,
                        onSelected = viewModel::selectTab,
                    )
                }
            },
        ) { padding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                color = MaterialTheme.colorScheme.background,
            ) {
                val selectedBook = state.selectedBook
                when {
                    selectedBook != null -> key(state.readerOpenRequestId) {
                        ReaderScreen(
                            detail = selectedBook,
                            isSaved = state.savedCoordinates.contains(selectedBook.summary.coordinate),
                            preferences = state.readerPreferences,
                            progress = state.readingProgress[selectedBook.summary.coordinate]
                                ?: ReadingProgress.initial(
                                    bookCoordinate = selectedBook.summary.coordinate,
                                    chapterCount = selectedBook.chapters.size,
                                ),
                            selectedTab = state.tab,
                            onBack = viewModel::returnHome,
                            onTabSelected = viewModel::selectTab,
                            onToggleSaved = { viewModel.toggleSaved(selectedBook.summary) },
                            onChapterProgressChanged = viewModel::recordReaderProgress,
                            onFontSizeChanged = viewModel::setReaderFontSize,
                            onLineHeightChanged = viewModel::setReaderLineHeight,
                            onThemeChanged = viewModel::setReaderTheme,
                            onParagraphAlignmentChanged = viewModel::setReaderParagraphAlignment,
                            highlights = state.highlights.filter { it.bookCoordinate == selectedBook.summary.coordinate },
                            highlightDelivery = state.highlightDelivery,
                            highlightComposer = state.highlightComposer,
                            onSaveHighlight = viewModel::saveHighlight,
                            onShowHighlightComposer = viewModel::showHighlightComposer,
                            onDeleteHighlight = viewModel::deletePrivateHighlight,
                            onUpdateHighlightComment = viewModel::updateHighlightComment,
                            onSubmitHighlight = viewModel::submitHighlight,
                            onDismissHighlightComposer = viewModel::dismissHighlightComposer,
                            seenTips = state.seenOnboardingTips,
                            onTipSeen = viewModel::markOnboardingTipSeen,
                            initialChapterIndex = state.readerInitialChapterIndex,
                            onInitialPositioned = viewModel::readerInitiallyPositioned,
                            readingState = state.readingState,
                            isSignedIn = state.signerSession != null,
                            inlineReviewComposer = state.ratingComposer?.takeIf { state.inlineRatingComposer && it.book.coordinate == selectedBook.summary.coordinate },
                            inlineReviewStatus = state.latestReviewDelivery?.takeIf { state.inlineReviewCoordinate == selectedBook.summary.coordinate },
                            onTrackReading = viewModel::trackReading,
                            onResetTracking = viewModel::resetReading,
                            onStopTracking = viewModel::stopReading,
                            onFinishBook = viewModel::finishReading,
                            onSyncReading = viewModel::syncReading,
                            onTrackedSectionChanged = viewModel::advanceReading,
                            onPrepareInlineReview = viewModel::prepareInlineReview,
                            onReviewStarsChanged = viewModel::updateRatingStars,
                            onReviewOpinionChanged = viewModel::updateRatingOpinion,
                            onSubmitInlineReview = viewModel::submitRatingReview,
                        )
                    }

                    state.isLoadingBook -> BookOpeningScreen(state.loadingBook)

                    else -> when {
                        state.isSearchOpen -> SearchScreen(
                            state = state,
                            onQueryChanged = viewModel::updateQuery,
                            onSearch = viewModel::submitSearch,
                            onSearchBookContentsChanged = viewModel::setSearchBookContents,
                            onOpenMatch = viewModel::openMatchingChapter,
                            onOpen = viewModel::openBook,
                            onLongPress = viewModel::showBookActions,
                        )

                        state.tab == BookshelfTab.Home -> HomeScreen(
                            shelves = state.curatedShelves,
                            isLoading = state.isLoadingShelves,
                            continueReading = mostRecentlyOpenedSavedBook(
                                savedBooks = state.savedBooks,
                                readingProgress = state.readingProgress,
                                finishedCoordinates = state.readingState.finished.map { it.bookCoordinate }.toSet(),
                                rereadingCoordinates = state.readingState.tracked.map { it.bookCoordinate }.toSet(),
                            ),
                            message = state.shelfMessage,
                            profileName = state.nostrProfile?.preferredName,
                            listState = homeListState,
                            onSearch = viewModel::openSearch,
                            onRetry = viewModel::retryShelves,
                            onOpen = viewModel::openBook,
                            onLongPress = viewModel::showBookActions,
                            readingNow = state.readingState.tracked,
                            onResolveReading = viewModel::openReadingCoordinate,
                        )

                        state.tab == BookshelfTab.MyBooks -> MyBooksScreen(
                            books = state.savedBooks,
                            savedCoordinates = state.savedCoordinates,
                            onOpen = viewModel::openBook,
                            error = state.error,
                            onLongPress = viewModel::showBookActions,
                            readingNow = state.readingState.tracked,
                            finishedBooks = state.readingState.finished,
                            onResolveReading = viewModel::openReadingCoordinate,
                        )

                        state.tab == BookshelfTab.Settings -> {
                            val settingsViewModel: SettingsViewModel = viewModel()
                            val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                            LaunchedEffect(settingsViewModel) { settingsViewModel.refreshStats() }
                            SettingsScreen(
                                state = settingsState,
                                actions = SettingsActions(
                                    setFontSize = settingsViewModel::setFontSize,
                                    setLineHeight = settingsViewModel::setLineHeight,
                                    setTheme = settingsViewModel::setTheme,
                                    setFont = settingsViewModel::setFont,
                                    setAlignment = settingsViewModel::setParagraphAlignment,
                                    addSource = settingsViewModel::addChapterSource,
                                    removeSource = settingsViewModel::removeChapterSource,
                                    restoreSources = settingsViewModel::restoreChapterSources,
                                    setLocalRelay = settingsViewModel::setLocalRelay,
                                    removeLocalRelay = settingsViewModel::removeLocalRelay,
                                    clearSelectedCaches = settingsViewModel::clearSelectedCaches,
                                    refreshStorage = settingsViewModel::refreshStats,
                                    setReadingPrivacy = settingsViewModel::setReadingPrivacy,
                                ),
                                account = AccountSettingsState(
                                    profileName = state.nostrProfile?.preferredName,
                                    pubkey = state.signerSession?.pubkey,
                                    signerPackage = state.signerSession?.packageName,
                                    signerAvailable = signerAvailable,
                                    syncState = state.syncState.settingsLabel(),
                                    isSyncing = state.isSyncingDirectory,
                                    isPublishing = state.isPublishingDirectory,
                                    userReadRelays = settingsState.relayConfiguration.userRead,
                                    userWriteRelays = settingsState.relayConfiguration.userWrite,
                                    pendingAuthRequest = state.pendingNostrAuthSignRequest != null,
                                    pendingHighlightCount = settingsState.pendingHighlightCount,
                                    pendingReviewCount = settingsState.pendingReviewCount,
                                ),
                                accountActions = AccountSettingsActions(
                                    login = startExternalSignerLogin,
                                    signOut = viewModel::signOut,
                                    syncToDirectory = viewModel::syncToRelays,
                                    syncFromDirectory = viewModel::syncFromRelays,
                                    retryNow = settingsViewModel::retryPendingPublications,
                                ),
                                onBackFromSettings = viewModel::returnHome,
                                onRetryReading = viewModel::syncReading,
                            )
                        }
                    }
                }
            }
        }
        state.bookActions?.let { book ->
            BookActionsSheet(
                book = book,
                isSaved = book.coordinate in state.savedCoordinates,
                localRelayConfigured = state.localRelayUrl != null,
                isBroadcasting = state.isBroadcastingBook,
                onDismiss = viewModel::dismissBookActions,
                onToggleSaved = {
                    viewModel.dismissBookActions()
                    viewModel.toggleSaved(book)
                },
                onDetails = { viewModel.showBookDetails(book) },
                onRateReview = { viewModel.showRatingComposer(book) },
                onBroadcast = { viewModel.broadcastBookToLocalRelay(book) },
            )
        }
        state.bookDetails?.let { details ->
            BookDetailsSheet(
                details = details,
                onDismiss = viewModel::dismissBookDetails,
                onShowRatings = { viewModel.showRatings(details.book) },
                onRead = { viewModel.openBook(details.book) },
                recommendations = state.recommendationPage?.takeIf { it.seed.id == details.book.id },
                isOnline = state.isOnline,
                savedCoordinates = state.savedCoordinates,
                onRetryRecommendations = viewModel::retryRecommendations,
                onOpenRecommendation = { book ->
                    if (book.chapterRefs.isEmpty()) viewModel.showBookDetails(book) else viewModel.openBook(book)
                },
                onRecommendationActions = viewModel::showBookActions,
            )
        }
        state.ratingsPage?.let { page ->
            RatingsSheet(
                page = page,
                activePubkey = state.signerSession?.pubkey,
                onDismiss = viewModel::dismissRatings,
                onAddReview = viewModel::showRatingComposer,
            )
        }
        state.ratingComposer?.takeUnless { state.inlineRatingComposer }?.let { composer ->
            RatingComposerSheet(
                composer = composer,
                onDismiss = viewModel::dismissRatingComposer,
                onStarsChanged = viewModel::updateRatingStars,
                onOpinionChanged = viewModel::updateRatingOpinion,
                onSubmit = viewModel::submitRatingReview,
            )
        }
    }
}

@Composable
private fun BookshelfBottomBar(
    selected: BookshelfTab,
    onSelected: (BookshelfTab) -> Unit,
) {
    NavigationBar {
        BookshelfTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = selected == tab,
                onClick = { onSelected(tab) },
                label = { Text(tab.label) },
                icon = {},
            )
        }
    }
}

private val BookshelfTab.label: String
    get() =
        when (this) {
            BookshelfTab.Home -> "Home"
            BookshelfTab.MyBooks -> "My Books"
            BookshelfTab.Settings -> "Settings"
        }

private fun Double.formatOneDecimal(): String = ((this * 10.0).roundToInt() / 10.0).toString()

private val ReaderTheme.label: String
    get() =
        when (this) {
            ReaderTheme.Paper -> "Paper"
            ReaderTheme.Sepia -> "Sepia"
            ReaderTheme.Night -> "Night"
        }

private fun eu.decentnewsroom.bookshelf.data.nostr.BookshelfSyncState.settingsLabel(): String = when (this) {
    eu.decentnewsroom.bookshelf.data.nostr.BookshelfSyncState.NotConfigured -> "Not configured"
    eu.decentnewsroom.bookshelf.data.nostr.BookshelfSyncState.SignedOut -> "Signed out"
    is eu.decentnewsroom.bookshelf.data.nostr.BookshelfSyncState.Ready -> "Ready to sync"
    is eu.decentnewsroom.bookshelf.data.nostr.BookshelfSyncState.Syncing -> "Syncing"
    is eu.decentnewsroom.bookshelf.data.nostr.BookshelfSyncState.Failed -> "Sync failed: $message"
}

private fun String.compactHex(): String =
    if (length <= 16) {
        this
    } else {
        "${take(8)}...${takeLast(8)}"
    }

private fun Float.formatOneDecimal(): String = ((this * 10f).roundToInt() / 10f).toString()
