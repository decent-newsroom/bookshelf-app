package eu.decentnewsroom.bookshelf.ui.reader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import eu.decentnewsroom.bookshelf.data.highlights.ReaderHighlight
import eu.decentnewsroom.bookshelf.data.onboarding.OnboardingTip
import eu.decentnewsroom.bookshelf.data.reader.*
import eu.decentnewsroom.bookshelf.data.reading.ReadingState
import eu.decentnewsroom.bookshelf.ui.reading.*
import eu.decentnewsroom.bookshelf.domain.*
import eu.decentnewsroom.bookshelf.ui.*
import eu.decentnewsroom.bookshelf.ui.components.SecondaryButton
import eu.decentnewsroom.bookshelf.ui.books.LocalBookReadingPresentations
import eu.decentnewsroom.bookshelf.ui.onboarding.OnboardingTooltip
import eu.decentnewsroom.bookshelf.ui.theme.readerColors
import eu.decentnewsroom.bookshelf.ui.theme.ReaderColors
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, FlowPreview::class)
@Composable
internal fun ReaderScreen(
    detail: BookDetail, isSaved: Boolean, preferences: ReaderPreferences, progress: ReadingProgress,
    selectedTab: BookshelfTab, onBack: () -> Unit, onTabSelected: (BookshelfTab) -> Unit,
    onToggleSaved: () -> Unit,
    onFontSizeChanged: (Float) -> Unit, onLineHeightChanged: (Float) -> Unit, onThemeChanged: (ReaderTheme) -> Unit, onParagraphAlignmentChanged: (ParagraphAlignment) -> Unit,
    highlights: List<ReaderHighlight>, highlightDelivery: Map<String, String>, highlightComposer: HighlightComposerState?,
    onSaveHighlight: (BookChapter, String, Int, Int) -> Unit, onShowHighlightComposer: (ReaderHighlight) -> Unit,
    onDeleteHighlight: (ReaderHighlight) -> Unit, onUpdateHighlightComment: (String) -> Unit, onSubmitHighlight: () -> Unit, onDismissHighlightComposer: () -> Unit,
    seenTips: Set<OnboardingTip>, onTipSeen: (OnboardingTip) -> Unit,
    initialChapterIndex: Int? = null,
    onInitialPositioned: (BookDetail, Int, Int) -> Unit,
    readingState: ReadingState = ReadingState(),
    isSignedIn: Boolean = false,
    inlineReviewComposer: RatingComposerState? = null,
    inlineReviewStatus: String? = null,
    onTrackReading: (Int) -> Unit = {},
    onResetTracking: () -> Unit = {},
    onStopTracking: () -> Unit = {},
    onFinishBook: () -> Unit = {},
    onSyncReading: () -> Unit = {},
    onPrepareInlineReview: () -> Unit = {},
    onReviewStarsChanged: (Int) -> Unit = {},
    onReviewOpinionChanged: (String) -> Unit = {},
    onSubmitInlineReview: () -> Unit = {},
    onReaderObservation: (BookDetail, ReaderObservation, Boolean) -> Unit = { _, _, _ -> },
) {
    val tracked = readingState.tracked.firstOrNull { it.bookCoordinate == detail.summary.coordinate }
    val finished = readingState.finished.firstOrNull { it.bookCoordinate == detail.summary.coordinate }
    val presentation = LocalBookReadingPresentations.current[detail.summary.coordinate]
        ?: resolveBookReadingPresentation(progress, tracked, finished, detail.summary)
    val latestProgress by rememberUpdatedState(progress)
    val latestMarkedFinished by rememberUpdatedState(presentation.isMarkedFinished)
    val streamKnown = detail.summary.sectionStreamKnown && detail.summary.chapterCount > 0
    val showFinishCards = readerHasTerminalActions(detail.summary.sectionStreamKnown, detail.summary.chapterCount, detail.chapters.size, detail.truncated)
    val latestReaderObservation by rememberUpdatedState(onReaderObservation)
    val latestPrepareReview by rememberUpdatedState(onPrepareInlineReview)
    val latestInlineReviewComposer by rememberUpdatedState(inlineReviewComposer)
    val startPosition = readerStartPosition(progress, initialChapterIndex)
    val currentPublicationFingerprint = remember(detail.summary) { readingPublicationFingerprint(detail.summary) }
    val initialListItemIndex = readerListItemIndexForChapter(startPosition.chapterIndex, detail.chapters.size)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialListItemIndex,
        initialFirstVisibleItemScrollOffset = startPosition.scrollOffsetPx,
    )
    val resumePositionTracker = remember(detail.summary.coordinate, currentPublicationFingerprint, detail.chapters.size) {
        ReaderResumePositionTracker(detail.chapters.size, startPosition.chapterIndex to startPosition.scrollOffsetPx)
    }
    val completeContent = readerHasCompleteContent(
        detail.summary.sectionStreamKnown && detail.summary.chapterRefs.size == detail.summary.chapterCount,
        detail.summary.chapterCount,
        detail.chapters.size,
        detail.chapters.all { it.available },
        detail.truncated,
    )
    val savedEndpoint = initialChapterIndex == null && progress.reachedEnd && progress.completeContent &&
        progress.fullChapterCount == detail.summary.chapterCount && completeContent &&
        progress.publicationFingerprint == currentPublicationFingerprint
    var latestObservation by remember(detail.summary.coordinate, currentPublicationFingerprint) { mutableStateOf<ReaderObservation?>(null) }
    var explicitNavigationGeneration by remember(detail.summary.coordinate) { mutableStateOf(0) }
    var consumedNavigationGeneration by remember(detail.summary.coordinate, currentPublicationFingerprint) { mutableStateOf(0) }
    var initialPositionApplied by rememberSaveable(detail.summary.coordinate, initialChapterIndex) {
        mutableStateOf(initialChapterIndex == null)
    }
    LaunchedEffect(listState, initialChapterIndex) {
        if (!initialPositionApplied) {
            snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
            val position = resumePositionTracker.positionOnExit(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                ?: (startPosition.chapterIndex to startPosition.scrollOffsetPx)
            onInitialPositioned(
                detail,
                position.first,
                position.second,
            )
            initialPositionApplied = true
        }
    }
    val coroutineScope = rememberCoroutineScope(); val colors = preferences.theme.readerColors
    var showSettings by rememberSaveable { mutableStateOf(false) }; var showContents by rememberSaveable(detail.summary.coordinate) { mutableStateOf(false) }
    var showHighlights by rememberSaveable(detail.summary.coordinate) { mutableStateOf(false) }; var showNavigationMenus by rememberSaveable(detail.summary.coordinate) { mutableStateOf(false) }
    var showReaderMenusTip by rememberSaveable(detail.summary.coordinate) {
        mutableStateOf(OnboardingTip.ReaderMenus !in seenTips)
    }
    var pendingChapterLinkUrl by rememberSaveable(detail.summary.coordinate) { mutableStateOf<String?>(null) }
    val currentChapterIndex = coerceReaderChapterIndex(progress.currentChapterIndex, detail.chapters.size); val uriHandler = LocalUriHandler.current
    LaunchedEffect(detail.summary.coordinate, currentPublicationFingerprint, detail.chapters.size, listState, initialPositionApplied) {
        if (initialPositionApplied) {
            var previous: ReaderObservation? = ReaderObservation(
                chapterIndex = startPosition.chapterIndex,
                scrollOffsetPx = startPosition.scrollOffsetPx,
                reachedEnd = savedEndpoint,
                readingActivity = false,
            )
            consumedNavigationGeneration = explicitNavigationGeneration
            var publishedPosition: ReaderObservation? = null
            snapshotFlow {
                val layout = listState.layoutInfo
                ReaderLayoutSnapshot(
                    listItemIndex = listState.firstVisibleItemIndex,
                    scrollOffsetPx = listState.firstVisibleItemScrollOffset,
                    canScrollForward = listState.canScrollForward,
                    hasVisibleItems = layout.visibleItemsInfo.isNotEmpty(),
                    viewportSizePx = layout.viewportEndOffset - layout.viewportStartOffset,
                    isScrollInProgress = listState.isScrollInProgress,
                    explicitNavigationGeneration = explicitNavigationGeneration,
                )
            }.distinctUntilChanged()
                .onEach { snapshot ->
                    if (snapshot.hasVisibleItems && snapshot.viewportSizePx > 0) {
                        val resume = resumePositionTracker.observe(snapshot.listItemIndex, snapshot.scrollOffsetPx)
                            ?: resumePositionTracker.positionOnExit(snapshot.listItemIndex, snapshot.scrollOffsetPx)
                            ?: (startPosition.chapterIndex to startPosition.scrollOffsetPx)
                        val reachedEnd = readerIsAtVerifiedEnd(
                            completeContent = completeContent,
                            canScrollForward = snapshot.canScrollForward,
                            hasVisibleItems = snapshot.hasVisibleItems,
                            viewportSizePx = snapshot.viewportSizePx,
                        )
                        val chapterIndex = resume.first
                        val prior = previous
                        val explicitNavigation = snapshot.explicitNavigationGeneration != consumedNavigationGeneration
                        val observation = ReaderObservation(
                            chapterIndex = chapterIndex,
                            scrollOffsetPx = resume.second,
                            reachedEnd = reachedEnd,
                            readingActivity = prior?.let {
                                readerObservationHasActivity(
                                    previous = it,
                                    chapterIndex = chapterIndex,
                                    scrollOffsetPx = resume.second,
                                    reachedEnd = reachedEnd,
                                    isScrollInProgress = snapshot.isScrollInProgress,
                                    explicitNavigation = explicitNavigation,
                                )
                            } ?: false,
                            trackingChapterIndex = readerTrackingChapterIndexForListItem(
                                snapshot.listItemIndex,
                                detail.chapters.size,
                                reachedEnd,
                            ),
                        )
                        previous = observation
                        // Keep navigation intent until the positioned location changes;
                        // snapshotFlow can see the intent before scrollToItem has applied it.
                        if (!explicitNavigation || prior?.chapterIndex != chapterIndex || prior.scrollOffsetPx != resume.second) {
                            consumedNavigationGeneration = snapshot.explicitNavigationGeneration
                        }
                        latestObservation = observation
                        val activityNeedsPublication = observation.readingActivity &&
                            (latestProgress.readingCycleStartedAtMillis == 0L || latestMarkedFinished)
                        if (readerShouldPublishPosition(publishedPosition, observation, activityNeedsPublication)) {
                            publishedPosition = observation
                            latestReaderObservation(detail, observation, false)
                        }
                    }
                }
                .debounce(500.milliseconds)
                .collect { snapshot ->
                    if (snapshot.hasVisibleItems && snapshot.viewportSizePx > 0) {
                        latestObservation?.let { latestReaderObservation(detail, it, true) }
                        consumedNavigationGeneration = snapshot.explicitNavigationGeneration
                    }
                }
        }
    }
    LaunchedEffect(detail.summary.coordinate, isSignedIn, showFinishCards, listState) {
        if (isSignedIn && showFinishCards && inlineReviewComposer == null) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == "reader-review" } }.first { it }
            if (latestInlineReviewComposer == null) latestPrepareReview()
        }
    }
    DisposableEffect(detail.summary.coordinate, currentPublicationFingerprint, detail.chapters.size, listState) {
        onDispose {
            if (initialPositionApplied && listState.layoutInfo.totalItemsCount > 0) {
                resumePositionTracker.positionOnExit(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                    ?.let { (chapterIndex, offset) ->
                        val layout = listState.layoutInfo
                        val reachedEnd = readerIsAtVerifiedEnd(
                            completeContent = completeContent,
                            canScrollForward = listState.canScrollForward,
                            hasVisibleItems = layout.visibleItemsInfo.isNotEmpty(),
                            viewportSizePx = layout.viewportEndOffset - layout.viewportStartOffset,
                        )
                        val prior = latestObservation
                        latestReaderObservation(
                            detail,
                            ReaderObservation(
                                chapterIndex = chapterIndex,
                                scrollOffsetPx = offset,
                                reachedEnd = reachedEnd,
                                readingActivity = readerObservationHasActivity(
                                    previous = prior ?: ReaderObservation(startPosition.chapterIndex, startPosition.scrollOffsetPx, savedEndpoint, false),
                                    chapterIndex = chapterIndex,
                                    scrollOffsetPx = offset,
                                    reachedEnd = reachedEnd,
                                    isScrollInProgress = listState.isScrollInProgress,
                                    explicitNavigation = explicitNavigationGeneration != consumedNavigationGeneration,
                                ),
                                trackingChapterIndex = readerTrackingChapterIndexForListItem(
                                    listState.firstVisibleItemIndex,
                                    detail.chapters.size,
                                    reachedEnd,
                                ),
                            ),
                            true,
                        )
                    }
            }
        }
    }
    // Consume this one-time impression when it is presented, not only after the
    // timeout. Leaving the reader early must not make the same tip recur.
    LaunchedEffect(showReaderMenusTip) {
        if (showReaderMenusTip) onTipSeen(OnboardingTip.ReaderMenus)
    }
    if (showSettings) ModalBottomSheet(onDismissRequest = { showSettings = false }) { ReaderSettingsSheet(preferences, onFontSizeChanged, onLineHeightChanged, onThemeChanged, onParagraphAlignmentChanged) }
    if (showHighlights) BookHighlightsSheet(highlights, highlightDelivery, { showHighlights = false }, { highlight -> showHighlights = false; val i = detail.chapters.indexOfFirst { it.reference.coordinate == highlight.chapterCoordinate }; if (i >= 0) { explicitNavigationGeneration += 1; coroutineScope.launch { listState.animateScrollToItem(readerListItemIndexForChapter(i, detail.chapters.size)) } } }, { highlight -> showHighlights = false; onShowHighlightComposer(highlight) }, onDeleteHighlight)
    highlightComposer?.let { composer -> HighlightComposerSheet(composer, onDismissHighlightComposer, onUpdateHighlightComment, onSubmitHighlight) }
    if (showContents) ModalBottomSheet(onDismissRequest = { showContents = false }) {
        ReaderContentsSheet(detail.chapters, currentChapterIndex, colors) { chapterIndex ->
            showContents = false
            showNavigationMenus = false
            explicitNavigationGeneration += 1
            coroutineScope.launch {
                listState.scrollToItem(readerListItemIndexForChapter(chapterIndex, detail.chapters.size))
            }
        }
    }
    pendingChapterLinkUrl?.let { url -> ChapterLinkPolicy.parse(url)?.let { link -> AlertDialog(onDismissRequest = { pendingChapterLinkUrl = null }, title = { Text("Open external link?") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("This chapter links outside Bookshelf."); Text(link.host, fontWeight = FontWeight.SemiBold) } }, confirmButton = { SecondaryButton({ pendingChapterLinkUrl = null; runCatching { uriHandler.openUri(link.url) } }) { Text("Open") } }, dismissButton = { SecondaryButton({ pendingChapterLinkUrl = null }) { Text("Cancel") } }) } ?: run { pendingChapterLinkUrl = null } }
    Box(Modifier.fillMaxSize().background(colors.background)) {
        OnboardingTooltip(showReaderMenusTip, "Tap anywhere while reading to show menus for navigation and reader settings.", { showReaderMenusTip = false }) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().pointerInput(detail.summary.coordinate) { detectTapGestures { showNavigationMenus = !showNavigationMenus } }, contentPadding = PaddingValues(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                item(key = "reader-header") {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ReaderHeader(detail, isSaved, progress, colors, onBack, onToggleSaved, { showContents = true }, { showSettings = true }, !showReaderMenusTip && OnboardingTip.ReaderMenus in seenTips && OnboardingTip.BookListMembership !in seenTips) { onTipSeen(OnboardingTip.BookListMembership) }
                        ReadingTrackingControls(tracked, streamKnown, readingState.preferences.readingDeviceOnly,
                            { onTrackReading(chapterIndexForReaderListItem(listState.firstVisibleItemIndex, detail.chapters.size)) },
                            onResetTracking, onStopTracking, onSyncReading)
                        if (readingState.isSyncing) Text("Syncing reading lists…", color = colors.muted)
                        else if (readingState.pendingCount > 0) Text("${readingState.pendingCount} reading changes pending sync", color = colors.muted)
                        readingState.error?.let { Text(it, color = colors.muted) }
                        if (detail.truncated) Text("This reader loaded only part of the publication. The end of the book is not available here.", color = colors.muted)
                        if (detail.missingChapterCount > 0) Text("${detail.missingChapterCount} sections are unavailable. Their positions remain in the reading order.", color = colors.muted)
                    }
                }
                itemsIndexed(detail.chapters, key = { _, chapter -> chapter.reference.coordinate }) { index, chapter ->
                    if (chapter.available) ChapterSection(chapter, preferences, colors, { url -> ChapterLinkPolicy.parse(url)?.let { pendingChapterLinkUrl = it.url } }, highlights, onSaveHighlight, Modifier.padding(top = if (index == 0) 0.dp else 24.dp))
                    else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(chapter.title, style = MaterialTheme.typography.titleLarge, color = colors.text)
                        Text("This section is unavailable. Retry opening the book when its relay is reachable.", color = colors.muted)
                    }
                }
                if (showFinishCards) {
                    item(key = "reader-finish") { FinishBookCard(finished, readingState.preferences.finishedDeviceOnly,
                        tracked != null || (finished != null && !presentation.isMarkedFinished), onFinishBook) }
                    if (isSignedIn) item(key = "reader-review") {
                        InlineReviewCard(inlineReviewComposer?.takeIf { it.book.coordinate == detail.summary.coordinate }, inlineReviewStatus, onPrepareInlineReview, onReviewStarsChanged, onReviewOpinionChanged, onSubmitInlineReview)
                    }
                }
            }
        }
        if (showNavigationMenus) { ReaderControlsMenu(isSaved, progress, colors, onBack, onToggleSaved, { showContents = true }, { showSettings = true }, { showHighlights = true }, Modifier.align(Alignment.TopCenter)); ReaderBottomNavigationMenu(selectedTab, colors, onTabSelected, Modifier.align(Alignment.BottomCenter)) }
    }
}
