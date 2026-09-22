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
import eu.decentnewsroom.bookshelf.domain.*
import eu.decentnewsroom.bookshelf.ui.*
import eu.decentnewsroom.bookshelf.ui.components.SecondaryButton
import eu.decentnewsroom.bookshelf.ui.onboarding.OnboardingTooltip
import eu.decentnewsroom.bookshelf.ui.theme.readerColors
import eu.decentnewsroom.bookshelf.ui.theme.ReaderColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, FlowPreview::class)
@Composable
internal fun ReaderScreen(
    detail: BookDetail, isSaved: Boolean, preferences: ReaderPreferences, progress: ReadingProgress,
    selectedTab: BookshelfTab, onBack: () -> Unit, onTabSelected: (BookshelfTab) -> Unit,
    onToggleSaved: () -> Unit, onChapterProgressChanged: (BookDetail, Int, Int) -> Unit,
    onFontSizeChanged: (Float) -> Unit, onLineHeightChanged: (Float) -> Unit, onThemeChanged: (ReaderTheme) -> Unit, onParagraphAlignmentChanged: (ParagraphAlignment) -> Unit,
    highlights: List<ReaderHighlight>, highlightDelivery: Map<String, String>, highlightComposer: HighlightComposerState?,
    onSaveHighlight: (BookChapter, String, Int, Int) -> Unit, onShowHighlightComposer: (ReaderHighlight) -> Unit,
    onDeleteHighlight: (ReaderHighlight) -> Unit, onUpdateHighlightComment: (String) -> Unit, onSubmitHighlight: () -> Unit, onDismissHighlightComposer: () -> Unit,
    seenTips: Set<OnboardingTip>, onTipSeen: (OnboardingTip) -> Unit,
) {
    val initialListItemIndex = readerListItemIndexForChapter(progress.currentChapterIndex, detail.chapters.size)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialListItemIndex,
        initialFirstVisibleItemScrollOffset = progress.chapterScrollOffsetPx.coerceAtLeast(0),
    )
    val coroutineScope = rememberCoroutineScope(); val colors = preferences.theme.readerColors
    var showSettings by rememberSaveable { mutableStateOf(false) }; var showContents by rememberSaveable(detail.summary.coordinate) { mutableStateOf(false) }
    var showHighlights by rememberSaveable(detail.summary.coordinate) { mutableStateOf(false) }; var showNavigationMenus by rememberSaveable(detail.summary.coordinate) { mutableStateOf(false) }
    var showReaderMenusTip by rememberSaveable(detail.summary.coordinate) {
        mutableStateOf(OnboardingTip.ReaderMenus !in seenTips)
    }
    var pendingChapterLinkUrl by rememberSaveable(detail.summary.coordinate) { mutableStateOf<String?>(null) }
    val currentChapterIndex = coerceReaderChapterIndex(progress.currentChapterIndex, detail.chapters.size); val uriHandler = LocalUriHandler.current
    LaunchedEffect(detail.summary.coordinate, detail.chapters.size, listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .map { (index, offset) -> chapterIndexForReaderListItem(index, detail.chapters.size) to readerScrollOffsetForListItem(index, offset) }
            .distinctUntilChanged()
            .debounce(500)
            .collect { (chapterIndex, scrollOffsetPx) -> onChapterProgressChanged(detail, chapterIndex, scrollOffsetPx) }
    }
    DisposableEffect(detail.summary.coordinate, detail.chapters.size, listState) {
        onDispose {
            onChapterProgressChanged(
                detail,
                chapterIndexForReaderListItem(listState.firstVisibleItemIndex, detail.chapters.size),
                readerScrollOffsetForListItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset),
            )
        }
    }
    // Consume this one-time impression when it is presented, not only after the
    // timeout. Leaving the reader early must not make the same tip recur.
    LaunchedEffect(showReaderMenusTip) {
        if (showReaderMenusTip) onTipSeen(OnboardingTip.ReaderMenus)
    }
    if (showSettings) ModalBottomSheet(onDismissRequest = { showSettings = false }) { ReaderSettingsSheet(preferences, onFontSizeChanged, onLineHeightChanged, onThemeChanged, onParagraphAlignmentChanged) }
    if (showHighlights) BookHighlightsSheet(highlights, highlightDelivery, { showHighlights = false }, { highlight -> showHighlights = false; val i = detail.chapters.indexOfFirst { it.reference.coordinate == highlight.chapterCoordinate }; if (i >= 0) coroutineScope.launch { listState.animateScrollToItem(readerListItemIndexForChapter(i, detail.chapters.size)) } }, { highlight -> showHighlights = false; onShowHighlightComposer(highlight) }, onDeleteHighlight)
    highlightComposer?.let { composer -> HighlightComposerSheet(composer, onDismissHighlightComposer, onUpdateHighlightComment, onSubmitHighlight) }
    if (showContents) ModalBottomSheet(onDismissRequest = { showContents = false }) { ReaderContentsSheet(detail.chapters, currentChapterIndex, colors) { i -> showContents = false; showNavigationMenus = false; coroutineScope.launch { listState.animateScrollToItem(readerListItemIndexForChapter(i, detail.chapters.size)) } } }
    pendingChapterLinkUrl?.let { url -> ChapterLinkPolicy.parse(url)?.let { link -> AlertDialog(onDismissRequest = { pendingChapterLinkUrl = null }, title = { Text("Open external link?") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("This chapter links outside Bookshelf."); Text(link.host, fontWeight = FontWeight.SemiBold) } }, confirmButton = { SecondaryButton({ pendingChapterLinkUrl = null; runCatching { uriHandler.openUri(link.url) } }) { Text("Open") } }, dismissButton = { SecondaryButton({ pendingChapterLinkUrl = null }) { Text("Cancel") } }) } ?: run { pendingChapterLinkUrl = null } }
    Box(Modifier.fillMaxSize().background(colors.background)) {
        OnboardingTooltip(showReaderMenusTip, "Tap anywhere while reading to show menus for navigation and reader settings.", { showReaderMenusTip = false }) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().pointerInput(detail.summary.coordinate) { detectTapGestures { showNavigationMenus = !showNavigationMenus } }, contentPadding = PaddingValues(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                item(key = "reader-header") { ReaderHeader(detail, isSaved, progress, colors, onBack, onToggleSaved, { showContents = true }, { showSettings = true }, !showReaderMenusTip && OnboardingTip.ReaderMenus in seenTips && OnboardingTip.BookListMembership !in seenTips) { onTipSeen(OnboardingTip.BookListMembership) } }
                itemsIndexed(detail.chapters, key = { _, chapter -> chapter.reference.coordinate }) { index, chapter -> if (chapter.available) ChapterSection(chapter, preferences, colors, { url -> ChapterLinkPolicy.parse(url)?.let { pendingChapterLinkUrl = it.url } }, highlights, onSaveHighlight, Modifier.padding(top = if (index == 0) 0.dp else 24.dp)) }
            }
        }
        if (showNavigationMenus) { ReaderControlsMenu(isSaved, progress, colors, onBack, onToggleSaved, { showContents = true }, { showSettings = true }, { showHighlights = true }, Modifier.align(Alignment.TopCenter)); ReaderBottomNavigationMenu(selectedTab, colors, onTabSelected, Modifier.align(Alignment.BottomCenter)) }
    }
}
