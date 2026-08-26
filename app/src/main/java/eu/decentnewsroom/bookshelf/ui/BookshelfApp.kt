package eu.decentnewsroom.bookshelf.ui

import android.graphics.Typeface
import android.text.Html
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.decentnewsroom.bookshelf.data.nostr.AndroidExternalSigner
import eu.decentnewsroom.bookshelf.data.nostr.AndroidSignerResult
import eu.decentnewsroom.bookshelf.data.reader.ReaderPreferences
import eu.decentnewsroom.bookshelf.data.reader.ReaderTheme
import eu.decentnewsroom.bookshelf.data.reader.ReadingProgress
import eu.decentnewsroom.bookshelf.data.rendering.ChapterHtmlCacheStats
import eu.decentnewsroom.bookshelf.domain.BookChapter
import eu.decentnewsroom.bookshelf.domain.BookDetail
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.ui.theme.BookshelfTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfApp(viewModel: BookshelfViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val signerAvailable = remember(context) { AndroidExternalSigner.isInstalled(context) }
    var launchedSignRequestId by rememberSaveable { mutableStateOf<String?>(null) }

    val loginLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        when (val parsed = AndroidExternalSigner.parseLoginResult(result.resultCode, result.data)) {
            is AndroidSignerResult.Success -> viewModel.completeExternalSignerLogin(parsed.value)
            is AndroidSignerResult.Failed -> viewModel.reportExternalSignerFailure(parsed.message)
        }
    }

    val directorySignLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val requestId = result.data?.getStringExtra("id") ?: launchedSignRequestId
        when (val parsed = AndroidExternalSigner.parseSignEventResult(result.resultCode, result.data)) {
            is AndroidSignerResult.Success -> viewModel.completeDirectorySignature(requestId, parsed.value)
            is AndroidSignerResult.Failed -> viewModel.failPendingDirectorySignature(parsed.message)
        }
        launchedSignRequestId = null
    }

    LaunchedEffect(state.pendingDirectorySignRequest?.id) {
        val request = state.pendingDirectorySignRequest ?: return@LaunchedEffect
        launchedSignRequestId = request.id
        runCatching {
            directorySignLauncher.launch(
                AndroidExternalSigner.signEventIntent(
                    session = request.session,
                    unsignedEventJson = request.unsignedEventJson,
                    requestId = request.id,
                ),
            )
        }.onFailure { failure ->
            launchedSignRequestId = null
            viewModel.failPendingDirectorySignature(failure.message ?: "Could not open Android signer.")
        }
    }

    val startExternalSignerLogin: () -> Unit = {
        if (!signerAvailable) {
            viewModel.reportExternalSignerFailure("No Android Nostr signer found.")
        } else {
            runCatching {
                loginLauncher.launch(AndroidExternalSigner.loginIntent())
            }.onFailure { failure ->
                viewModel.reportExternalSignerFailure(failure.message ?: "Could not open Android signer.")
            }
        }
    }

    BookshelfTheme {
        Scaffold(
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
                    selectedBook != null -> ReaderScreen(
                        detail = selectedBook,
                        isSaved = state.savedCoordinates.contains(selectedBook.summary.coordinate),
                        preferences = state.readerPreferences,
                        progress = state.readingProgress[selectedBook.summary.coordinate]
                            ?: ReadingProgress.initial(
                                bookCoordinate = selectedBook.summary.coordinate,
                                chapterCount = selectedBook.chapters.size,
                            ),
                        selectedTab = state.tab,
                        onBack = viewModel::closeBook,
                        onTabSelected = viewModel::selectTab,
                        onToggleSaved = { viewModel.toggleSaved(selectedBook.summary) },
                        onProgressChanged = viewModel::recordReaderProgress,
                        onFontSizeChanged = viewModel::setReaderFontSize,
                        onLineHeightChanged = viewModel::setReaderLineHeight,
                        onThemeChanged = viewModel::setReaderTheme,
                    )

                    state.isLoadingBook -> LoadingScreen("Opening book...")

                    else -> when (state.tab) {
                        BookshelfTab.Search -> SearchScreen(
                            state = state,
                            onQueryChanged = viewModel::updateQuery,
                            onSearch = viewModel::submitSearch,
                            onOpen = viewModel::openBook,
                            onToggleSaved = viewModel::toggleSaved,
                        )

                        BookshelfTab.MyBooks -> MyBooksScreen(
                            books = state.savedBooks,
                            savedCoordinates = state.savedCoordinates,
                            onOpen = viewModel::openBook,
                            onToggleSaved = viewModel::toggleSaved,
                        )

                        BookshelfTab.Settings -> SettingsScreen(
                            state = state,
                            signerAvailable = signerAvailable,
                            onLogin = startExternalSignerLogin,
                            onSyncNow = viewModel::syncNow,
                            onSignOut = viewModel::signOut,
                            onClearChapterCache = viewModel::clearChapterHtmlCache,
                        )
                    }
                }
            }
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

@Composable
private fun SearchScreen(
    state: BookshelfUiState,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onOpen: (BookSummary) -> Unit,
    onToggleSaved: (BookSummary) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Search Mercury",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChanged,
                    modifier = Modifier.weight(1f),
                    label = { Text("Title, author, source, or identifier") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSearch, enabled = !state.isSearching) {
                    Text("Search")
                }
            }
        }

        if (state.isSearching) {
            item { LoadingInline("Searching Mercury...") }
        }

        state.searchMessage?.let { message ->
            item { Notice(message) }
        }

        state.error?.let { message ->
            item { Notice(message) }
        }

        state.syncMessage?.let { message ->
            item { Notice(message) }
        }

        if (state.isPublishingDirectory) {
            item { LoadingInline("Publishing collection...") }
        }

        items(state.searchResults, key = BookSummary::coordinate) { book ->
            BookCard(
                book = book,
                isSaved = state.savedCoordinates.contains(book.coordinate),
                onOpen = { onOpen(book) },
                onToggleSaved = { onToggleSaved(book) },
            )
        }
    }
}

@Composable
private fun MyBooksScreen(
    books: List<BookSummary>,
    savedCoordinates: Set<String>,
    onOpen: (BookSummary) -> Unit,
    onToggleSaved: (BookSummary) -> Unit,
) {
    if (books.isEmpty()) {
        EmptyScreen(
            title = "Your shelf is empty",
            body = "Save books from search results to keep a personal Nostr directory here.",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "My Books",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${books.size} books in your local directory.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items(books, key = BookSummary::coordinate) { book ->
            BookCard(
                book = book,
                isSaved = savedCoordinates.contains(book.coordinate),
                onOpen = { onOpen(book) },
                onToggleSaved = { onToggleSaved(book) },
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    state: BookshelfUiState,
    signerAvailable: Boolean,
    onLogin: () -> Unit,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
    onClearChapterCache: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        state.error?.let { message -> Notice(message) }
        state.syncMessage?.let { message -> Notice(message) }

        Notice("Mercury: https://mercury-relay.imwald.eu")
        Notice("Reader: ${state.readerPreferences.fontSizeSp.roundToInt()}sp, ${state.readerPreferences.theme.label}")
        Notice("Chapter cache: ${state.chapterCacheStats.label}")
        Button(
            onClick = onClearChapterCache,
            enabled = state.chapterCacheStats.entryCount > 0 && !state.isClearingChapterCache,
        ) {
            Text(if (state.isClearingChapterCache) "Clearing..." else "Clear chapter cache")
        }

        Text(
            text = "Nostr",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        val session = state.signerSession
        if (session == null) {
            Notice(if (signerAvailable) "Signed out." else "No Android Nostr signer found.")
            Button(
                onClick = onLogin,
                enabled = signerAvailable && !state.isSyncingDirectory && !state.isPublishingDirectory,
            ) {
                Text("Log in with signer")
            }
        } else {
            Notice("Signed in: ${session.pubkey.compactHex()} via ${session.packageName}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSyncNow,
                    enabled = !state.isSyncingDirectory && !state.isPublishingDirectory,
                ) {
                    Text("Sync")
                }
                TextButton(
                    onClick = onSignOut,
                    enabled = !state.isSyncingDirectory && !state.isPublishingDirectory,
                ) {
                    Text("Sign out")
                }
            }
        }

        Notice("Relay sync: ${state.syncState.label}")

        if (state.isSyncingDirectory) {
            LoadingInline("Syncing collection...")
        }
        if (state.isPublishingDirectory) {
            LoadingInline("Publishing collection...")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderScreen(
    detail: BookDetail,
    isSaved: Boolean,
    preferences: ReaderPreferences,
    progress: ReadingProgress,
    selectedTab: BookshelfTab,
    onBack: () -> Unit,
    onTabSelected: (BookshelfTab) -> Unit,
    onToggleSaved: () -> Unit,
    onProgressChanged: (BookDetail, Int) -> Unit,
    onFontSizeChanged: (Float) -> Unit,
    onLineHeightChanged: (Float) -> Unit,
    onThemeChanged: (ReaderTheme) -> Unit,
) {
    val listState = rememberLazyListState()
    val colors = preferences.theme.readerColors
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showNavigationMenus by rememberSaveable(detail.summary.coordinate) { mutableStateOf(false) }

    LaunchedEffect(detail.summary.coordinate, detail.chapters.size, listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index -> onProgressChanged(detail, index) }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            ReaderSettingsSheet(
                preferences = preferences,
                onFontSizeChanged = onFontSizeChanged,
                onLineHeightChanged = onLineHeightChanged,
                onThemeChanged = onThemeChanged,
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(detail.summary.coordinate) {
                    detectTapGestures(
                        onTap = { showNavigationMenus = !showNavigationMenus },
                    )
                },
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item(key = "reader-header") {
                ReaderHeader(
                    detail = detail,
                    isSaved = isSaved,
                    progress = progress,
                    colors = colors,
                    onBack = onBack,
                    onToggleSaved = onToggleSaved,
                    onShowSettings = { showSettings = true },
                )
            }

            items(detail.chapters, key = { chapter -> chapter.reference.coordinate }) { chapter ->
                ChapterSection(
                    chapter = chapter,
                    preferences = preferences,
                    colors = colors,
                )
            }
        }

        if (showNavigationMenus) {
            ReaderControlsMenu(
                isSaved = isSaved,
                progress = progress,
                colors = colors,
                onBack = onBack,
                onToggleSaved = onToggleSaved,
                onShowSettings = { showSettings = true },
                modifier = Modifier.align(Alignment.TopCenter),
            )
            ReaderBottomNavigationMenu(
                selected = selectedTab,
                colors = colors,
                onSelected = onTabSelected,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun ReaderControlsMenu(
    isSaved: Boolean,
    progress: ReadingProgress,
    colors: ReaderColors,
    onBack: () -> Unit,
    onToggleSaved: () -> Unit,
    onShowSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(8.dp),
        color = colors.controls,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Text("Back", color = colors.accent)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onShowSettings) {
                    Text("Aa", color = colors.accent, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(6.dp))
                Button(onClick = onToggleSaved) {
                    Text(if (isSaved) "Remove" else "Save")
                }
            }
            LinearProgressIndicator(
                progress = { progress.progressFraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = colors.accent,
                trackColor = colors.track,
            )
            Text(
                text = "Chapter ${progress.currentChapterNumber} of ${progress.chapterCount} | ${(progress.progressFraction * 100f).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = colors.muted,
            )
        }
    }
}

@Composable
private fun ReaderBottomNavigationMenu(
    selected: BookshelfTab,
    colors: ReaderColors,
    onSelected: (BookshelfTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier.fillMaxWidth(),
        containerColor = colors.controls,
    ) {
        BookshelfTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = selected == tab,
                onClick = { onSelected(tab) },
                label = { Text(tab.label) },
                icon = {},
                colors = NavigationBarItemDefaults.colors(
                    selectedTextColor = colors.accent,
                    selectedIconColor = colors.accent,
                    indicatorColor = colors.track,
                    unselectedTextColor = colors.muted,
                    unselectedIconColor = colors.muted,
                ),
            )
        }
    }
}

@Composable
private fun ReaderHeader(
    detail: BookDetail,
    isSaved: Boolean,
    progress: ReadingProgress,
    colors: ReaderColors,
    onBack: () -> Unit,
    onToggleSaved: () -> Unit,
    onShowSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("Back", color = colors.accent)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onShowSettings) {
                Text("Aa", color = colors.accent, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(6.dp))
            Button(onClick = onToggleSaved) {
                Text(if (isSaved) "Remove" else "Save")
            }
        }

        LinearProgressIndicator(
            progress = { progress.progressFraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp)),
            color = colors.accent,
            trackColor = colors.track,
        )

        Text(
            text = "Chapter ${progress.currentChapterNumber} of ${progress.chapterCount} | ${(progress.progressFraction * 100f).roundToInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = colors.muted,
        )

        Text(
            text = detail.summary.title,
            style = MaterialTheme.typography.headlineMedium,
            color = colors.text,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Start,
        )
        val authors = detail.summary.authors.joinToString(", ").ifBlank { "Unknown author" }
        Text(
            text = "by $authors",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.muted,
        )
        Text(
            text = "${detail.availableChapterCount} / ${detail.summary.chapterCount} chapters available",
            style = MaterialTheme.typography.labelLarge,
            color = colors.muted,
        )
        detail.summary.summary?.let { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
            )
        }
        if (detail.truncated || detail.missingChapterCount > 0) {
            ReaderNotice("Some referenced chapters are not currently available from Mercury.", colors)
        }
    }
}

@Composable
private fun ReaderSettingsSheet(
    preferences: ReaderPreferences,
    onFontSizeChanged: (Float) -> Unit,
    onLineHeightChanged: (Float) -> Unit,
    onThemeChanged: (ReaderTheme) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "Reader",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        SettingHeader("Font size", "${preferences.fontSizeSp.roundToInt()}sp")
        Slider(
            value = preferences.fontSizeSp,
            onValueChange = onFontSizeChanged,
            valueRange = 14f..28f,
            steps = 13,
        )
        SettingHeader("Line height", "${preferences.lineHeightMultiplier.formatOneDecimal()}x")
        Slider(
            value = preferences.lineHeightMultiplier,
            onValueChange = onLineHeightChanged,
            valueRange = 1.2f..2.0f,
            steps = 7,
        )
        Text(
            text = "Theme",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderTheme.entries.forEach { theme ->
                FilterChip(
                    selected = preferences.theme == theme,
                    onClick = { onThemeChanged(theme) },
                    label = { Text(theme.label) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SettingHeader(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BookCard(
    book: BookSummary,
    isSaved: Boolean,
    onOpen: () -> Unit,
    onToggleSaved: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookMonogram(book.title)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.type.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val authors = book.authors.joinToString(", ").ifBlank { "Unknown author" }
                Text(
                    text = "by $authors",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${book.chapterCount} chapters",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Button(onClick = onOpen) {
                    Text("Read")
                }
                TextButton(onClick = onToggleSaved) {
                    Text(if (isSaved) "Remove" else "Save")
                }
            }
        }
    }
}

@Composable
private fun ChapterSection(
    chapter: BookChapter,
    preferences: ReaderPreferences,
    colors: ReaderColors,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Chapter ${chapter.position}",
            style = MaterialTheme.typography.labelMedium,
            color = colors.accent,
        )
        Text(
            text = chapter.title,
            style = MaterialTheme.typography.titleLarge,
            color = colors.text,
            fontWeight = FontWeight.SemiBold,
        )
        chapter.summary?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
            )
        }
        val renderedHtml = chapter.renderedHtml
        if (renderedHtml != null) {
            HtmlChapterText(
                html = renderedHtml,
                preferences = preferences,
                colors = colors,
            )
        } else {
            Text(
                text = chapter.content ?: "This chapter is not available from Mercury at this time.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.text,
                fontFamily = FontFamily.Serif,
                fontSize = preferences.fontSizeSp.sp,
                lineHeight = (preferences.fontSizeSp * preferences.lineHeightMultiplier).sp,
            )
        }
    }
}

@Composable
private fun HtmlChapterText(
    html: String,
    preferences: ReaderPreferences,
    colors: ReaderColors,
) {
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context ->
            TextView(context).apply {
                setIncludeFontPadding(false)
                linksClickable = true
                movementMethod = LinkMovementMethod.getInstance()
                typeface = Typeface.SERIF
            }
        },
        update = { view ->
            view.text = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
            view.setTextColor(colors.text.toArgb())
            view.setLinkTextColor(colors.accent.toArgb())
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, preferences.fontSizeSp)
            view.setLineSpacing(0f, preferences.lineHeightMultiplier)
            view.typeface = Typeface.SERIF
            view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        },
    )
}

@Composable
private fun BookMonogram(title: String) {
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 76.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.trim().firstOrNull()?.uppercase() ?: "B",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun LoadingScreen(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LoadingInline(message)
    }
}

@Composable
private fun LoadingInline(message: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(message)
    }
}

@Composable
private fun EmptyScreen(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Notice(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ReaderNotice(message: String, colors: ReaderColors) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = colors.notice,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text,
        )
    }
}

private data class ReaderColors(
    val background: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val track: Color,
    val notice: Color,
    val controls: Color,
)

private val ReaderTheme.readerColors: ReaderColors
    get() =
        when (this) {
            ReaderTheme.Paper -> ReaderColors(
                background = Color(0xFFFAFAF7),
                text = Color(0xFF1F2623),
                muted = Color(0xFF66746E),
                accent = Color(0xFF24564B),
                track = Color(0xFFE2E9E4),
                notice = Color(0xFFECEFE9),
                controls = Color(0xFFFFFFFF),
            )

            ReaderTheme.Sepia -> ReaderColors(
                background = Color(0xFFF4ECD8),
                text = Color(0xFF2B2118),
                muted = Color(0xFF715E4B),
                accent = Color(0xFF7A5534),
                track = Color(0xFFE4D5B7),
                notice = Color(0xFFE8DCC4),
                controls = Color(0xFFFFF7E6),
            )

            ReaderTheme.Night -> ReaderColors(
                background = Color(0xFF111816),
                text = Color(0xFFE8EFEA),
                muted = Color(0xFFAAB7B0),
                accent = Color(0xFF86D6C1),
                track = Color(0xFF27332F),
                notice = Color(0xFF1C2522),
                controls = Color(0xFF19211E),
            )
        }

private val BookshelfTab.label: String
    get() =
        when (this) {
            BookshelfTab.Search -> "Search"
            BookshelfTab.MyBooks -> "My Books"
            BookshelfTab.Settings -> "Settings"
        }

private val ChapterHtmlCacheStats.label: String
    get() {
        if (entryCount == 0) {
            return "empty"
        }

        val files = if (entryCount == 1) "1 file" else "$entryCount files"
        return "$files, ${sizeBytes.formatByteCount()}"
    }

private fun Long.formatByteCount(): String {
    if (this < 1024L) {
        return "$this B"
    }

    val kib = this / 1024.0
    if (this < 1024L * 1024L) {
        return "${kib.formatOneDecimal()} KB"
    }

    return "${(kib / 1024.0).formatOneDecimal()} MB"
}

private fun Double.formatOneDecimal(): String = ((this * 10.0).roundToInt() / 10.0).toString()

private val ReaderTheme.label: String
    get() =
        when (this) {
            ReaderTheme.Paper -> "Paper"
            ReaderTheme.Sepia -> "Sepia"
            ReaderTheme.Night -> "Night"
        }

private val eu.decentnewsroom.bookshelf.data.nostr.BookshelfSyncState.label: String
    get() =
        when (this) {
            eu.decentnewsroom.bookshelf.data.nostr.BookshelfSyncState.NotConfigured -> "Not configured"
            eu.decentnewsroom.bookshelf.data.nostr.BookshelfSyncState.SignedOut -> "Signed out"
            is eu.decentnewsroom.bookshelf.data.nostr.BookshelfSyncState.Ready -> "Ready (${relayCount} relays)"
            is eu.decentnewsroom.bookshelf.data.nostr.BookshelfSyncState.Syncing -> "Syncing ${pubkey.compactHex()}"
            is eu.decentnewsroom.bookshelf.data.nostr.BookshelfSyncState.Failed -> "Failed: ${message}"
        }

private fun String.compactHex(): String =
    if (length <= 16) {
        this
    } else {
        "${take(8)}...${takeLast(8)}"
    }

private fun Float.formatOneDecimal(): String = ((this * 10f).roundToInt() / 10f).toString()

