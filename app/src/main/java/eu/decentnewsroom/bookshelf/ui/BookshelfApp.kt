package eu.decentnewsroom.bookshelf.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import eu.decentnewsroom.bookshelf.data.nostr.AndroidExternalSigner
import eu.decentnewsroom.bookshelf.data.nostr.AndroidSignerResult
import eu.decentnewsroom.bookshelf.data.reader.ReaderPreferences
import eu.decentnewsroom.bookshelf.data.reader.ReaderTheme
import eu.decentnewsroom.bookshelf.data.reader.ReadingProgress
import eu.decentnewsroom.bookshelf.data.rendering.ChapterHtmlCacheStats
import eu.decentnewsroom.bookshelf.data.mercury.TrustedCoverImagePolicy
import eu.decentnewsroom.bookshelf.data.mercury.BookSearchResult
import eu.decentnewsroom.bookshelf.domain.BookChapter
import eu.decentnewsroom.bookshelf.data.discovery.CuratedShelf
import eu.decentnewsroom.bookshelf.domain.BookDetail
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.ui.theme.BookshelfTheme
import eu.decentnewsroom.bookshelf.ui.theme.ReaderColors
import eu.decentnewsroom.bookshelf.ui.theme.readerColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfApp(viewModel: BookshelfViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    BackHandler(
        enabled = state.selectedBook != null ||
            state.isLoadingBook ||
            state.isSearchOpen ||
            state.tab != BookshelfTab.Home,
    ) {
        viewModel.returnHome()
    }
    val context = LocalContext.current
    val signerAvailable = remember(context) { AndroidExternalSigner.isInstalled(context) }
    var launchedSignRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var launchedAuthSignRequestId by rememberSaveable { mutableStateOf<String?>(null) }

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

    val authSignLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val requestId = result.data?.getStringExtra("id") ?: launchedAuthSignRequestId
        when (val parsed = AndroidExternalSigner.parseSignEventResult(result.resultCode, result.data)) {
            is AndroidSignerResult.Success -> viewModel.completeNostrAuthSignature(requestId, parsed.value)
            is AndroidSignerResult.Failed -> viewModel.failPendingNostrAuthSignature(requestId, parsed.message)
        }
        launchedAuthSignRequestId = null
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

    LaunchedEffect(state.pendingNostrAuthSignRequest?.id) {
        val request = state.pendingNostrAuthSignRequest ?: return@LaunchedEffect
        launchedAuthSignRequestId = request.id
        runCatching {
            authSignLauncher.launch(
                AndroidExternalSigner.signEventIntent(
                    session = request.session,
                    unsignedEventJson = request.unsignedEventJson,
                    requestId = request.id,
                ),
            )
        }.onFailure { failure ->
            launchedAuthSignRequestId = null
            viewModel.failPendingNostrAuthSignature(request.id, failure.message ?: "Could not open Android signer.")
        }
    }

    LaunchedEffect(state.syncMessage) {
        val message = state.syncMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = message,
            withDismissAction = true,
            duration = SnackbarDuration.Short,
        )
        viewModel.dismissSyncMessage(message)
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
                        onBack = viewModel::returnHome,
                        onTabSelected = viewModel::selectTab,
                        onToggleSaved = { viewModel.toggleSaved(selectedBook.summary) },
                        onChapterProgressChanged = viewModel::recordReaderProgress,
                        onFontSizeChanged = viewModel::setReaderFontSize,
                        onLineHeightChanged = viewModel::setReaderLineHeight,
                        onThemeChanged = viewModel::setReaderTheme,
                    )

                    state.isLoadingBook -> LoadingScreen("Opening book...")

                    else -> when {
                        state.isSearchOpen -> SearchScreen(
                            state = state,
                            onQueryChanged = viewModel::updateQuery,
                            onSearch = viewModel::submitSearch,
                            onOpen = viewModel::openBook,
                            onToggleSaved = viewModel::toggleSaved,
                        )

                        state.tab == BookshelfTab.Home -> HomeScreen(
                            shelves = state.curatedShelves,
                            isLoading = state.isLoadingShelves,
                            continueReading = mostRecentlyOpenedSavedBook(
                                savedBooks = state.savedBooks,
                                readingProgress = state.readingProgress,
                            ),
                            message = state.shelfMessage,
                            profileName = state.nostrProfile?.preferredName,
                            onSearch = viewModel::openSearch,
                            onRetry = viewModel::retryShelves,
                            onOpen = viewModel::openBook,
                        )

                        state.tab == BookshelfTab.MyBooks -> MyBooksScreen(
                            books = state.savedBooks,
                            savedCoordinates = state.savedCoordinates,
                            onOpen = viewModel::openBook,
                            error = state.error,
                            onToggleSaved = viewModel::toggleSaved,
                        )

                        state.tab == BookshelfTab.Settings -> SettingsScreen(
                            state = state,
                            signerAvailable = signerAvailable,
                            onLogin = startExternalSignerLogin,
                            onSyncToRelays = viewModel::syncToRelays,
                            onSyncFromRelays = viewModel::syncFromRelays,
                            onSignOut = viewModel::signOut,
                            onClearChapterCache = viewModel::clearChapterHtmlCache,
                            onChapterRelayUrlsChanged = viewModel::setChapterRelayUrls,
                            onLocalRelayUrlChanged = viewModel::setLocalRelayUrl,
                            onThemeChanged = viewModel::setReaderTheme,
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
private fun HomeScreen(
    shelves: List<CuratedShelf>,
    isLoading: Boolean,
    continueReading: ContinueReadingBook?,
    message: String?,
    profileName: String?,
    onSearch: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (BookSummary) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = profileName?.let { "Hello, $it" } ?: "Discover",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSearch) { Text("Search") }
            }
        }
        continueReading?.let { item { ContinueReadingCard(it, onOpen = { onOpen(it.book) }) } }
        if (isLoading && shelves.isEmpty()) item { LoadingInline("Loading shelves...") }
        message?.let { text -> item { Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) { Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant); TextButton(onClick = onRetry) { Text("Retry") } } } }
        shelves.forEach { shelf ->
            item(key = shelf.id) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(shelf.title, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(shelf.books, key = BookSummary::coordinate) { book -> ShelfBookCard(book, onOpen = { onOpen(book) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueReadingCard(
    continueReading: ContinueReadingBook,
    onOpen: () -> Unit,
) {
    val book = continueReading.book
    val progress = continueReading.progress
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(book, Modifier.size(width = 64.dp, height = 92.dp))
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Continue reading",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { progress.progressFraction.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(999.dp)),
                )
                Text(
                    text = "Chapter ${progress.currentChapterNumber} of ${progress.chapterCount} | ${(progress.progressFraction * 100f).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ShelfBookCard(book: BookSummary, onOpen: () -> Unit) {
    Column(Modifier.width(124.dp).clickable(onClick = onOpen), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        BookCover(book, Modifier.fillMaxWidth().height(174.dp))
        Text(book.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(book.authors.joinToString(", ").ifBlank { "Unknown author" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    var hasInteracted by rememberSaveable {
        mutableStateOf(
            state.query.isNotEmpty() ||
                state.isSearching ||
                state.searchResults.isNotEmpty() ||
                state.searchMessage != null,
        )
    }
    val submitSearch = {
        hasInteracted = true
        onSearch()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!hasInteracted) {
                    Notice("Find books by title, author, source, or Nostr identifier.")
                }
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { query ->
                        hasInteracted = true
                        onQueryChanged(query)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search books") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                )
                Button(
                    onClick = submitSearch,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isSearching,
                ) {
                    Text("Search")
                }
            }
        }

        if (state.isSearching) {
            item { LoadingInline("Searching...") }
        }

        state.searchMessage?.let { message ->
            item { Notice(message) }
        }

        state.error?.let { message ->
            item { Notice(message) }
        }

        if (state.isPublishingDirectory) {
            item { LoadingInline("Sharing bookshelf...") }
        }

        items(state.searchResults, key = { it.book.coordinate }) { result ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BookCard(
                    book = result.book,
                    isSaved = state.savedCoordinates.contains(result.book.coordinate),
                    onOpen = { onOpen(result.book) },
                    onToggleSaved = { onToggleSaved(result.book) },
                )
                result.matchedChapterTitle?.let { title ->
                    Text(
                        text = "Chapter: $title",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                result.excerpt?.let { excerpt ->
                    Text(
                        text = excerpt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun MyBooksScreen(
    books: List<BookSummary>,
    savedCoordinates: Set<String>,
    onOpen: (BookSummary) -> Unit,
    error: String?,
    onToggleSaved: (BookSummary) -> Unit,
) {
    if (books.isEmpty()) {
        EmptyScreen(
            title = "Your shelf is empty",
            body = "Save books from search results. They stay on this device even without a login.",
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
                text = "${books.size} books saved on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let { Notice(it) }
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
    onSyncToRelays: () -> Unit,
    onSyncFromRelays: () -> Unit,
    onSignOut: () -> Unit,
    onClearChapterCache: () -> Unit,
    onChapterRelayUrlsChanged: (String) -> Unit,
    onLocalRelayUrlChanged: (String) -> Unit,
    onThemeChanged: (ReaderTheme) -> Unit,
) {
    var chapterRelayDraft by remember(state.chapterRelayUrls) { mutableStateOf(state.chapterRelayUrls.joinToString("\n")) }
    var localRelayDraft by remember(state.localRelayUrl) { mutableStateOf(state.localRelayUrl.orEmpty()) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        state.error?.let { message -> Notice(message) }
        SettingsSection("Appearance", initiallyExpanded = true) {
            Text("Color scheme", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ReaderTheme.entries.forEach { theme -> FilterChip(selected = state.readerPreferences.theme == theme, onClick = { onThemeChanged(theme) }, label = { Text(theme.label) }) } }
            Notice("Reader font: ${state.readerPreferences.fontSizeSp.roundToInt()}sp; line height: ${state.readerPreferences.lineHeightMultiplier}×")
        }
        SettingsSection("Account") {
            Notice("Login is optional. My Books works locally; login enables relay sync and sharing.")
            val session = state.signerSession
            if (session == null) {
                Notice(if (signerAvailable) "Not connected to relay sync." else "No Android Nostr signer found. Local My Books is still available.")
                Button(onClick = onLogin, enabled = signerAvailable && !state.isSyncingDirectory && !state.isPublishingDirectory) { Text("Log in to sync and share") }
            } else {
                val accountName = state.nostrProfile?.preferredName ?: session.pubkey.compactHex()
                Notice("Logged in as $accountName")
                Text("${session.pubkey.compactHex()} via ${session.packageName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSyncToRelays, enabled = !state.isSyncingDirectory && !state.isPublishingDirectory) { Text(if (state.syncState is eu.decentnewsroom.bookshelf.data.nostr.BookshelfSyncState.Failed) "Retry relay sync" else "Sync to relays") }
                    TextButton(onClick = onSyncFromRelays, enabled = !state.isSyncingDirectory && !state.isPublishingDirectory) { Text("Sync from relays") }
                    TextButton(onClick = onSignOut, enabled = !state.isSyncingDirectory && !state.isPublishingDirectory) { Text("Sign out") }
                }
            }
            Notice("Relay sync: ${state.syncState.label}")
            if (state.isSyncingDirectory) LoadingInline("Syncing bookshelf...")
            if (state.isPublishingDirectory) LoadingInline("Sharing bookshelf...")
        }
        SettingsSection("Relays") {
            SettingsRelayList("Search APIs", listOf("https://decentnewsroom.com/books/api", "https://mercury-relay.imwald.eu (fallback)"), "Defaults; not editable.")
            SettingsRelayList("Default publication relays", state.chapterRelayUrls, "Used for chapter sources. One wss:// relay URL per line.")
            OutlinedTextField(value = chapterRelayDraft, onValueChange = { chapterRelayDraft = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Chapter source relays") }, minLines = 2, maxLines = 5)
            Button(onClick = { onChapterRelayUrlsChanged(chapterRelayDraft) }) { Text("Save publication relays") }
            SettingsRelayList("Your read relays", state.relayConfiguration.userRead, "Hydrated from your NIP-65 kind 10002 event; not editable here.")
            SettingsRelayList("Your write relays", state.relayConfiguration.userWrite, "Hydrated from your NIP-65 kind 10002 event; not editable here.")
            Text("Local relay", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = localRelayDraft, onValueChange = { localRelayDraft = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Citrine relay URL") }, placeholder = { Text("ws://127.0.0.1:…") }, supportingText = { Text("Optional. Used alongside the default relays; leave empty to disable.") }, singleLine = true)
            Button(onClick = { onLocalRelayUrlChanged(localRelayDraft) }) { Text("Save local relay") }
            SettingsRelayList("Bootstrap relays in use", state.relayConfiguration.bootstrap, "Includes the optional local relay.")
        }
        SettingsSection("Cache") {
            Notice("Rendered chapter cache: ${state.chapterCacheStats.label}")
            Button(onClick = onClearChapterCache, enabled = state.chapterCacheStats.entryCount > 0 && !state.isClearingChapterCache) { Text(if (state.isClearingChapterCache) "Clearing..." else "Clear chapter cache") }
        }
    }
}

@Composable
private fun SettingsSection(title: String, initiallyExpanded: Boolean = false, content: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Collapse" else "Expand") }
            }
            if (expanded) content()
        }
    }
}

@Composable
private fun SettingsRelayList(title: String, relays: List<String>, description: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(relays.joinToString("\n").ifBlank { "Not available until a signed NIP-65 relay list is loaded." }, style = MaterialTheme.typography.bodySmall)
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
    onChapterProgressChanged: (BookDetail, Int) -> Unit,
    onFontSizeChanged: (Float) -> Unit,
    onLineHeightChanged: (Float) -> Unit,
    onThemeChanged: (ReaderTheme) -> Unit,
) {
    val initialListItemIndex = readerListItemIndexForChapter(progress.currentChapterIndex, detail.chapters.size)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialListItemIndex)
    val coroutineScope = rememberCoroutineScope()
    val colors = preferences.theme.readerColors
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showContents by rememberSaveable(detail.summary.coordinate) { mutableStateOf(false) }
    var showNavigationMenus by rememberSaveable(detail.summary.coordinate) { mutableStateOf(false) }
    var pendingChapterLinkUrl by rememberSaveable(detail.summary.coordinate) { mutableStateOf<String?>(null) }
    val currentChapterIndex = coerceReaderChapterIndex(progress.currentChapterIndex, detail.chapters.size)
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(detail.summary.coordinate, detail.chapters.size, listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                onChapterProgressChanged(
                    detail,
                    chapterIndexForReaderListItem(index, detail.chapters.size),
                )
            }
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

    if (showContents) {
        ModalBottomSheet(onDismissRequest = { showContents = false }) {
            ReaderContentsSheet(
                chapters = detail.chapters,
                currentChapterIndex = currentChapterIndex,
                colors = colors,
                onChapterSelected = { chapterIndex ->
                    showContents = false
                    showNavigationMenus = false
                    coroutineScope.launch {
                        listState.animateScrollToItem(
                            readerListItemIndexForChapter(chapterIndex, detail.chapters.size),
                        )
                    }
                },
            )
        }
    }

    pendingChapterLinkUrl?.let { url ->
        val link = ChapterLinkPolicy.parse(url)
        if (link == null) {
            pendingChapterLinkUrl = null
        } else {
            AlertDialog(
                onDismissRequest = { pendingChapterLinkUrl = null },
                title = { Text("Open external link?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("This chapter links outside Bookshelf.")
                        Text(link.host, fontWeight = FontWeight.SemiBold)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingChapterLinkUrl = null
                        runCatching { uriHandler.openUri(link.url) }
                    }) { Text("Open") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingChapterLinkUrl = null }) { Text("Cancel") }
                },
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
                    onShowContents = { showContents = true },
                    onShowSettings = { showSettings = true },
                )
            }

            itemsIndexed(
                items = detail.chapters,
                key = { _, chapter -> chapter.reference.coordinate },
            ) { index, chapter ->
                ChapterSection(
                    chapter = chapter,
                    preferences = preferences,
                    colors = colors,
                    onLinkClick = { url -> ChapterLinkPolicy.parse(url)?.let { pendingChapterLinkUrl = it.url } },
                    modifier = Modifier.padding(top = if (index == 0) 0.dp else 24.dp),
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
                onShowContents = { showContents = true },
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
    onShowContents: () -> Unit,
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
                TextButton(onClick = onShowContents) {
                    Text("Contents", color = colors.accent)
                }
                Spacer(Modifier.width(6.dp))
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
    onShowContents: () -> Unit,
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
            TextButton(onClick = onShowContents) {
                Text("Contents", color = colors.accent)
            }
            Spacer(Modifier.width(6.dp))
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            BookCover(
                book = detail.summary,
                modifier = Modifier.size(width = 88.dp, height = 124.dp),
                containerColor = colors.track,
                monogramColor = colors.accent,
            )
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = detail.summary.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.text,
                    fontWeight = FontWeight.Bold,
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
            }
        }
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
private fun ReaderContentsSheet(
    chapters: List<BookChapter>,
    currentChapterIndex: Int,
    colors: ReaderColors,
    onChapterSelected: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Contents",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (chapters.size == 1) "1 chapter" else "${chapters.size} chapters",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (chapters.isEmpty()) {
            Text(
                text = "No chapters are available from Mercury.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 18.dp),
            ) {
                itemsIndexed(chapters, key = { _, chapter -> chapter.reference.coordinate }) { index, chapter ->
                    ReaderContentsItem(
                        chapter = chapter,
                        selected = index == currentChapterIndex,
                        colors = colors,
                        onClick = { onChapterSelected(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderContentsItem(
    chapter: BookChapter,
    selected: Boolean,
    colors: ReaderColors,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.track else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = chapter.title,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) colors.accent else colors.text,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        chapter.summary?.let { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
            BookCover(book)
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
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = chapter.title,
            style = MaterialTheme.typography.headlineSmall,
            color = colors.text,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
        )
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(colors.accent),
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
                onLinkClick = onLinkClick,
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
    onLinkClick: (String) -> Unit,
) {
    val annotatedText = remember(html, colors.accent, onLinkClick) {
        AnnotatedString.fromHtml(
            htmlString = html.withReaderParagraphSpacing(),
            linkStyles = TextLinkStyles(
                style = SpanStyle(
                    color = colors.accent,
                    textDecoration = TextDecoration.Underline,
                ),
            ),
            linkInteractionListener = { link ->
                val url = (link as? androidx.compose.ui.text.LinkAnnotation.Url)?.url
                if (url != null) onLinkClick(url)
            },
        )
    }
    Text(
        text = annotatedText,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyLarge.copy(
            color = colors.text,
            fontFamily = FontFamily.Serif,
            fontSize = preferences.fontSizeSp.sp,
            lineHeight = (preferences.fontSizeSp * preferences.lineHeightMultiplier).sp,
        ),
    )
}

internal fun String.withReaderParagraphSpacing(): String =
    replace(ParagraphClosingTagRegex) { match -> "${match.value}<br>" }

private val ParagraphClosingTagRegex = Regex("</p\\s*>", RegexOption.IGNORE_CASE)

@Composable
private fun BookCover(
    book: BookSummary,
    modifier: Modifier = Modifier.size(width = 56.dp, height = 76.dp),
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    monogramColor: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = book.title.trim().firstOrNull()?.uppercase() ?: "B",
            style = MaterialTheme.typography.headlineSmall,
            color = monogramColor,
            fontWeight = FontWeight.Bold,
        )
        TrustedCoverImagePolicy.sanitize(book.coverImageUrl)?.let { coverUrl ->
            AsyncImage(
                model = coverUrl,
                contentDescription = "Cover art for ${book.title}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
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

private const val ReaderHeaderItemCount = 1

private fun readerListItemIndexForChapter(chapterIndex: Int, chapterCount: Int): Int =
    if (chapterCount <= 0) {
        0
    } else {
        coerceReaderChapterIndex(chapterIndex, chapterCount) + ReaderHeaderItemCount
    }

internal fun chapterIndexForReaderListItem(listItemIndex: Int, chapterCount: Int): Int =
    if (chapterCount <= 0) {
        0
    } else {
        (listItemIndex - ReaderHeaderItemCount).coerceIn(0, chapterCount - 1)
    }

internal fun coerceReaderChapterIndex(chapterIndex: Int, chapterCount: Int): Int =
    if (chapterCount <= 0) {
        0
    } else {
        chapterIndex.coerceIn(0, chapterCount - 1)
    }

private val BookshelfTab.label: String
    get() =
        when (this) {
            BookshelfTab.Home -> "Home"
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
