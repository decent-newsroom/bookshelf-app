@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.decentnewsroom.bookshelf.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import eu.decentnewsroom.bookshelf.AppGraph
import eu.decentnewsroom.bookshelf.BuildConfig
import eu.decentnewsroom.bookshelf.data.mercury.ChapterRelayUrls
import eu.decentnewsroom.bookshelf.data.reader.ParagraphAlignment
import eu.decentnewsroom.bookshelf.data.reader.ReaderFont
import eu.decentnewsroom.bookshelf.data.reader.ReaderPreferences
import eu.decentnewsroom.bookshelf.data.reader.ReaderTheme
import eu.decentnewsroom.bookshelf.ui.reader.readerTextStyle
import eu.decentnewsroom.bookshelf.ui.theme.readerColors
import kotlin.math.roundToInt

data class AccountSettingsState(
    val profileName: String? = null,
    val pubkey: String? = null,
    val signerPackage: String? = null,
    val signerAvailable: Boolean = false,
    val syncState: String? = null,
    val isSyncing: Boolean = false,
    val isPublishing: Boolean = false,
    val userReadRelays: List<String> = emptyList(),
    val userWriteRelays: List<String> = emptyList(),
    val pendingAuthRequest: Boolean = false,
    val pendingHighlightCount: Int = 0,
    val pendingReviewCount: Int = 0,
)

data class AccountSettingsActions(
    val login: () -> Unit = {},
    val signOut: () -> Unit = {},
    val syncToDirectory: () -> Unit = {},
    val syncFromDirectory: () -> Unit = {},
    val retryNow: () -> Unit = {},
)

data class SettingsActions(
    val setFontSize: (Float) -> Unit = {},
    val setLineHeight: (Float) -> Unit = {},
    val setTheme: (ReaderTheme) -> Unit = {},
    val setFont: (ReaderFont) -> Unit = {},
    val setAlignment: (ParagraphAlignment) -> Unit = {},
    val addSource: (String) -> Unit = {},
    val removeSource: (String) -> Unit = {},
    val restoreSources: () -> Unit = {},
    val setLocalRelay: (String) -> Unit = {},
    val removeLocalRelay: () -> Unit = {},
    val clearChapterCache: () -> Unit = {},
    val clearRatingCache: () -> Unit = {},
    val clearOfflineBookCache: () -> Unit = {},
    val refreshStorage: () -> Unit = {},
)

private enum class SettingsSection { Index, Reading, Account, Sources, Relays, Storage, About }

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    account: AccountSettingsState,
    accountActions: AccountSettingsActions,
    onBackFromSettings: () -> Unit,
) {
    var section by rememberSaveable { mutableStateOf(SettingsSection.Index) }
    BackHandler { if (section == SettingsSection.Index) onBackFromSettings() else section = SettingsSection.Index }
    LaunchedEffect(section) { if (section == SettingsSection.Storage) actions.refreshStorage() }
    when (section) {
        SettingsSection.Index -> SettingsIndex(state, account) { section = it }
        SettingsSection.Reading -> ReadingSettings(state, actions)
        SettingsSection.Account -> AccountSettings(state, account, accountActions)
        SettingsSection.Sources -> SourcesSettings(state, actions)
        SettingsSection.Relays -> RelaySettings(state, account, actions)
        SettingsSection.Storage -> StorageSettings(state, actions)
        SettingsSection.About -> AboutSettings()
    }
}

@Composable
private fun SettingsScaffold(title: String, isIndex: Boolean = false, content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                title,
                style = if (isIndex) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        content()
    }
}

@Composable
private fun SettingsIndex(state: SettingsUiState, account: AccountSettingsState, navigate: (SettingsSection) -> Unit) = SettingsScaffold("Settings", isIndex = true) {
    item { IndexRow(Icons.Outlined.Book, "Reading & Display", "${state.readerPreferences.theme.name} · ${state.readerPreferences.fontSizeSp.roundToInt()} sp · ${state.readerPreferences.lineHeightMultiplier}×") { navigate(SettingsSection.Reading) } }
    item { IndexRow(Icons.Outlined.AccountCircle, "Account & Sync", account.profileName ?: if (account.pubkey == null) "Not connected" else account.pubkey.take(12) + "…") { navigate(SettingsSection.Account) } }
    item { IndexRow(Icons.Outlined.Search, "Discovery Sources", "${state.chapterSources.size} chapter relays") { navigate(SettingsSection.Sources) } }
    item { IndexRow(Icons.Outlined.Router, "Nostr Relays", "${AppGraph.defaultRelays.size} defaults · ${if (state.localRelayUrl == null) 0 else 1} local") { navigate(SettingsSection.Relays) } }
    item { IndexRow(Icons.Outlined.Storage, "Storage & Offline", "${state.chapterCacheStats.sizeBytes + state.ratingCacheStats.sizeBytes + state.offlineBookCacheStats.sizeBytes} cached bytes") { navigate(SettingsSection.Storage) } }
    item { IndexRow(Icons.Outlined.Info, "About", "Version ${BuildConfig.VERSION_NAME}") { navigate(SettingsSection.About) } }
}

@Composable
private fun IndexRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null)
        }
    }
}

private fun LazyListScope.sectionTitle(title: String) { item { Text(title, style = MaterialTheme.typography.titleMedium) } }
private fun LazyListScope.detail(label: String, value: String) { item { Column { Text(label, style = MaterialTheme.typography.labelLarge); Text(value, style = MaterialTheme.typography.bodyMedium) } } }

@Composable
private fun ChoiceRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun ReaderPreview(preferences: ReaderPreferences) {
    val colors = preferences.theme.readerColors
    Card(colors = CardDefaults.cardColors(containerColor = colors.background), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Preview", style = MaterialTheme.typography.labelMedium, color = colors.muted)
            Text(
                "The pages grew quiet as the light faded. Every line should feel comfortable to read, wherever the story takes you.",
                style = readerTextStyle(preferences).copy(color = colors.text),
            )
        }
    }
}

@Composable
private fun ReadingSettings(state: SettingsUiState, actions: SettingsActions) = SettingsScaffold("Reading & Display") {
    val p = state.readerPreferences
    item { ReaderPreview(p) }
    sectionTitle("Appearance")
    item { Text("Theme") }
    item { ChoiceRow { ReaderTheme.entries.forEach { theme -> FilterChip(selected = p.theme == theme, onClick = { actions.setTheme(theme) }, label = { Text(theme.name) }) } } }
    sectionTitle("Typography")
    item { Text("Font") }
    item { ChoiceRow { ReaderFont.entries.forEach { font -> FilterChip(selected = p.fontFamily == font, onClick = { actions.setFont(font) }, label = { Text(if (font == ReaderFont.SansSerif) "Sans Serif" else font.name) }) } } }
    item { Text("Text size · ${p.fontSizeSp.roundToInt()} sp") }
    item { Slider(value = p.fontSizeSp, onValueChange = actions.setFontSize, valueRange = 14f..28f, steps = 13) }
    item { Text("Line spacing · ${p.lineHeightMultiplier}×") }
    item { Slider(value = p.lineHeightMultiplier, onValueChange = actions.setLineHeight, valueRange = 1.2f..2f, steps = 7) }
    item { Text("Paragraph alignment") }
    item { ChoiceRow { ParagraphAlignment.entries.forEach { alignment -> FilterChip(selected = p.paragraphAlignment == alignment, onClick = { actions.setAlignment(alignment) }, label = { Text(alignment.name) }) } } }
}

@Composable
private fun AccountSettings(state: SettingsUiState, account: AccountSettingsState, actions: AccountSettingsActions) = SettingsScaffold("Account & Sync") {
    sectionTitle("Account")
    detail("Name", account.profileName ?: "No profile name available")
    detail("Public key", account.pubkey ?: "Not connected")
    detail("Signer", account.signerPackage ?: if (account.signerAvailable) "Available" else "No Android signer found")
    item { if (account.pubkey == null) Button(onClick = actions.login, enabled = account.signerAvailable) { Text("Connect account") } else TextButton(onClick = actions.signOut, enabled = !account.isSyncing && !account.isPublishing) { Text("Disconnect") } }
    sectionTitle("Bookshelf sync")
    detail("Relay sync", account.syncState ?: "Unknown")
    item { if (account.pendingAuthRequest) Text("Relay authorization requested in your signer.") }
    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = actions.syncToDirectory, enabled = account.pubkey != null && !account.isSyncing && !account.isPublishing) { Text("Sync to relays") }; TextButton(onClick = actions.syncFromDirectory, enabled = account.pubkey != null && !account.isSyncing && !account.isPublishing) { Text("Sync from relays") } } }
    sectionTitle("Pending publications")
    detail("Highlights", account.pendingHighlightCount.toString())
    detail("Reviews", account.pendingReviewCount.toString())
    item { Button(onClick = actions.retryNow, enabled = account.pendingHighlightCount + account.pendingReviewCount > 0 && !state.isRetrying) { Text(if (state.isRetrying) "Retrying…" else "Retry now") } }
    item { if (!state.isOnline) Text("Offline. A configured local relay can still receive pending publications; remote delivery will resume online.", style = MaterialTheme.typography.bodySmall) }
    item { state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
}

@Composable
private fun SourcesSettings(state: SettingsUiState, actions: SettingsActions) = SettingsScaffold("Discovery Sources") {
    item {
        var draft by rememberSaveable { mutableStateOf("") }
        LaunchedEffect(state.chapterSources) { draft = "" }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Add chapter relay") }, placeholder = { Text("wss://relay.example") }, isError = state.chapterSourcesError != null, singleLine = true)
            state.chapterSourcesError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { actions.addSource(draft) }) { Text("Add source") }
        }
    }
    sectionTitle("Chapter relays")
    state.chapterSources.forEach { source -> item(key = source) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) { Text(source); Text(if (source in ChapterRelayUrls.DEFAULTS) "Default" else "Custom", style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { actions.removeSource(source) }, enabled = state.chapterSources.size > 1) { Text("Remove") }
        }
    } }
    item { TextButton(onClick = actions.restoreSources) { Text("Restore defaults") } }
    sectionTitle("Search services")
    detail("Decent Newsroom Books", "https://decentnewsroom.com/books")
    detail("Mercury fallback", "https://mercury-relay.imwald.eu")
    item { Text("Search service addresses are app defaults.", style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun RelaySettings(state: SettingsUiState, account: AccountSettingsState, actions: SettingsActions) = SettingsScaffold("Nostr Relays") {
    sectionTitle("Default relays")
    AppGraph.defaultRelays.forEach { relay -> detail("Relay", relay) }
    sectionTitle("Local relay")
    item {
        var draft by remember(state.localRelayUrl) { mutableStateOf(state.localRelayUrl.orEmpty()) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Local relay URL") }, placeholder = { Text("ws://127.0.0.1:4869") }, isError = state.localRelayError != null, singleLine = true)
            state.localRelayError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { actions.setLocalRelay(draft) }) { Text("Save") }; TextButton(onClick = { actions.removeLocalRelay(); draft = "" }, enabled = state.localRelayUrl != null) { Text("Remove") } }
        }
    }
    sectionTitle("Effective relay set")
    val effective = (state.relayConfiguration.bootstrap + account.userReadRelays + account.userWriteRelays).distinct()
    item { Text("${effective.size} configured relays. Connection status appears when a sync is attempted.") }
    effective.forEach { relay -> detail("Relay", relay) }
    sectionTitle("Your account relays")
    item { Text("Read and write routes come from the account's signed relay list and are read-only here.", style = MaterialTheme.typography.bodySmall) }
    detail("Read", account.userReadRelays.joinToString("\n").ifEmpty { "No account read relays loaded" })
    detail("Write", account.userWriteRelays.joinToString("\n").ifEmpty { "No account write relays loaded" })
    item { if (!state.isOnline) Text("Offline: remote relay work is paused; local configuration remains available.") }
}

@Composable
private fun StorageSettings(state: SettingsUiState, actions: SettingsActions) = SettingsScaffold("Storage & Offline") {
    item { Text(if (state.isOnline) "Network: online" else "Network: offline") }
    detail("Saved books", state.savedBookCount.toString())
    detail("Pending highlights", state.pendingHighlightCount.toString())
    detail("Pending reviews", state.pendingReviewCount.toString())
    sectionTitle("Disposable caches")
    detail("Chapter HTML", "${state.chapterCacheStats.entryCount} files · ${state.chapterCacheStats.sizeBytes.formatBytes()}")
    detail("Community ratings", "${state.ratingCacheStats.entryCount} events · ${state.ratingCacheStats.sizeBytes.formatBytes()}")
    detail("Offline reading", "${state.offlineBookCacheStats.entryCount} books · ${state.offlineBookCacheStats.sizeBytes.formatBytes()}")
    item { if (state.isRefreshingStats) LinearProgressIndicator(Modifier.fillMaxWidth()) }
    item { CacheClearActions(state, actions) }
    item { Text("Clearing these caches keeps saved books, reading progress, highlights, and unpublished items. Ratings may need to load again.", style = MaterialTheme.typography.bodySmall) }
    item { TextButton(onClick = actions.refreshStorage) { Text("Refresh statistics") } }
    item { state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
}

@Composable
private fun CacheClearActions(state: SettingsUiState, actions: SettingsActions) {
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { target = "chapter" }, enabled = state.chapterCacheStats.entryCount > 0 && !state.isRefreshingStats) { Text("Clear chapters") }
        TextButton(onClick = { target = "rating" }, enabled = state.ratingCacheStats.entryCount > 0 && !state.isRefreshingStats) { Text("Clear ratings") }
        TextButton(onClick = { target = "offline" }, enabled = state.offlineBookCacheStats.entryCount > 0 && !state.isRefreshingStats) { Text("Clear offline books") }
    }
    target?.let { cache ->
        AlertDialog(
            onDismissRequest = { target = null },
            title = { Text(if (cache == "chapter") "Clear chapter cache?" else if (cache == "rating") "Clear rating cache?" else "Clear offline books?") },
            text = { Text(if (cache == "chapter") "Rendered chapters will be regenerated. Books and reading progress stay saved." else if (cache == "rating") "Cached community ratings will need to reload. Pending signed reviews stay queued." else "Downloaded reader content will be removed. Books, progress, highlights, and unpublished items stay saved.") },
            confirmButton = { TextButton(onClick = { if (cache == "chapter") actions.clearChapterCache() else if (cache == "rating") actions.clearRatingCache() else actions.clearOfflineBookCache(); target = null }) { Text("Clear cache") } },
            dismissButton = { TextButton(onClick = { target = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AboutSettings() = SettingsScaffold("About") {
    item { Text("Bookshelf", style = MaterialTheme.typography.headlineSmall) }
    item { Text("Version ${BuildConfig.VERSION_NAME}") }
    item {
        Column {
            Text(
                text = buildAnnotatedString {
                    withLink(LinkAnnotation.Url("https://github.com/decent-newsroom/bookshelf-app")) {
                        append("Source code")
                    }
                },
            )
            Text(
                "decent-newsroom/bookshelf-app",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Long.formatBytes(): String = when {
    this < 1024 -> "$this B"
    this < 1024 * 1024 -> "${this / 1024} KB"
    else -> "${this / (1024 * 1024)} MB"
}
