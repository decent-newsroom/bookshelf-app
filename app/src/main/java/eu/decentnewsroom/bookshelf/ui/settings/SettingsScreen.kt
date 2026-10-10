@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package eu.decentnewsroom.bookshelf.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import eu.decentnewsroom.bookshelf.ui.components.SecondaryButton
import eu.decentnewsroom.bookshelf.ui.components.BackCloseButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import eu.decentnewsroom.bookshelf.AppGraph
import eu.decentnewsroom.bookshelf.BuildConfig
import eu.decentnewsroom.bookshelf.R
import eu.decentnewsroom.bookshelf.data.mercury.ChapterRelayUrls
import eu.decentnewsroom.bookshelf.data.reader.ParagraphAlignment
import eu.decentnewsroom.bookshelf.data.reader.ReaderFont
import eu.decentnewsroom.bookshelf.data.reader.ReaderPreferences
import eu.decentnewsroom.bookshelf.data.reader.ReaderTheme
import eu.decentnewsroom.bookshelf.ui.reader.readerTextStyle
import eu.decentnewsroom.bookshelf.ui.tutorials.TutorialCatalog
import eu.decentnewsroom.bookshelf.ui.tutorials.TutorialDestination
import eu.decentnewsroom.bookshelf.ui.tutorials.TutorialScreen
import eu.decentnewsroom.bookshelf.ui.tutorials.TutorialTopic
import eu.decentnewsroom.bookshelf.ui.tutorials.TutorialsScreen
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
    val clearSelectedCaches: (Set<CacheSelection>) -> Unit = {},
    val refreshStorage: () -> Unit = {},
    val clearPending: () -> Unit = {},
    val setReadingPrivacy: (Boolean?, Boolean?, Set<String>) -> Unit = { _, _, _ -> },
)

private enum class SettingsSection { Index, Reading, ReadingProgress, Tutorials, Account, Sources, Relays, Storage, About }

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    account: AccountSettingsState,
    accountActions: AccountSettingsActions,
    onBackFromSettings: () -> Unit,
    onTutorialDestination: (TutorialDestination) -> Unit,
    onRetryReading: () -> Unit = {},
) {
    var section by rememberSaveable { mutableStateOf(SettingsSection.Index) }
    var selectedTutorial by rememberSaveable { mutableStateOf<TutorialTopic?>(null) }
    val savedSettingsState = rememberSaveableStateHolder()
    val navigateBack: () -> Unit = {
        if (section == SettingsSection.Index) onBackFromSettings() else section = SettingsSection.Index
    }
    BackHandler(enabled = selectedTutorial == null, onBack = navigateBack)
    LaunchedEffect(section) { if (section == SettingsSection.Storage) actions.refreshStorage() }
    if (selectedTutorial != null) {
        TutorialScreen(
            topic = selectedTutorial!!,
            onClose = { selectedTutorial = null },
            onAction = { destination ->
                selectedTutorial = null
                when (destination) {
                    TutorialDestination.Storage -> section = SettingsSection.Storage
                    TutorialDestination.ReadingProgress -> section = SettingsSection.ReadingProgress
                    TutorialDestination.Account -> section = SettingsSection.Account
                    TutorialDestination.Home,
                    TutorialDestination.Search,
                    TutorialDestination.MyBooks -> onTutorialDestination(destination)
                }
            },
        )
    } else {
        Column(Modifier.fillMaxSize()) {
            BackCloseButton(onClick = navigateBack, modifier = Modifier.padding(start = 20.dp, top = 12.dp))
            Box(Modifier.weight(1f)) {
                savedSettingsState.SaveableStateProvider("settings-${section.name}") {
                    when (section) {
                        SettingsSection.Index -> SettingsIndex(state, account) { section = it }
                        SettingsSection.Reading -> ReadingSettings(state, actions)
                        SettingsSection.ReadingProgress -> ReadingProgressSettings(
                            state, actions, account, onRetryReading,
                            onOpenTrackingTutorial = {
                                selectedTutorial = TutorialTopic.TrackingProgress
                            },
                        )
                        SettingsSection.Tutorials -> TutorialsScreen(onOpenTopic = {
                            selectedTutorial = it
                        })
                        SettingsSection.Account -> AccountSettings(state, account, accountActions)
                        SettingsSection.Sources -> SourcesSettings(state, actions)
                        SettingsSection.Relays -> RelaySettings(state, account, actions)
                        SettingsSection.Storage -> StorageSettings(state, actions, accountActions.retryNow)
                        SettingsSection.About -> AboutSettings()
                    }
                }
            }
        }
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
    item { IndexRow(Icons.Outlined.Book, "Reading progress & privacy", "Track progress · Reading lists · Sharing") { navigate(SettingsSection.ReadingProgress) } }
    item { IndexRow(Icons.Outlined.AccountCircle, "Account & Sync", account.profileName ?: if (account.pubkey == null) "Not connected" else account.pubkey.take(12) + "…") { navigate(SettingsSection.Account) } }
    item { IndexRow(Icons.Outlined.Search, "Discovery Sources", "${state.chapterSources.size} chapter relays") { navigate(SettingsSection.Sources) } }
    item { IndexRow(Icons.Outlined.Router, "Nostr Relays", "${AppGraph.defaultRelays.size} defaults · ${if (state.localRelayUrl == null) 0 else 1} local") { navigate(SettingsSection.Relays) } }
    item { IndexRow(Icons.Outlined.Storage, "Storage & Offline", "${state.chapterCacheStats.sizeBytes + state.ratingCacheStats.sizeBytes + state.offlineBookCacheStats.sizeBytes + state.recommendationCacheStats.sizeBytes} cached bytes") { navigate(SettingsSection.Storage) } }
    item { IndexRow(Icons.Outlined.Lightbulb, stringResource(R.string.tutorial_title), stringResource(R.string.tutorial_index_summary)) { navigate(SettingsSection.Tutorials) } }
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
private fun LazyListScope.detail(label: String, value: String) { item { SettingsDetail(label, value) } }

@Composable
internal fun SettingsDetail(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ChoiceRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun ReaderPreview(preferences: ReaderPreferences) {
    val colors = preferences.theme.readerColors
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.background),
        border = BorderStroke(1.dp, colors.muted),
        modifier = Modifier.fillMaxWidth(),
    ) {
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
    item { ReaderPreview(p) }
}

@Composable
private fun ReadingProgressSettings(
    state: SettingsUiState,
    actions: SettingsActions,
    account: AccountSettingsState,
    onRetryReading: () -> Unit,
    onOpenTrackingTutorial: () -> Unit,
) = SettingsScaffold("Reading progress & privacy") {
    sectionTitle("Track progress")
    if (!TutorialCatalog.get(TutorialTopic.TrackingProgress).hasContent) {
        item {
            Text("Your reading position is saved automatically on this device so you can continue where you left off.")
        }
        item {
            Text("Open the reader menu and choose Track progress to add a book to Reading now on Home. You can reset or stop tracking from the same controls. Finished books appear at the bottom of Home.")
        }
    }
    item { SecondaryButton(onClick = onOpenTrackingTutorial) { Text(stringResource(R.string.tutorial_tracking_help)) } }
    sectionTitle("Reading lists & finished books")
    item { ReadingPrivacySettings(state, actions, account, onRetryReading) }
}

@Composable
private fun ReadingPrivacySettings(
    state: SettingsUiState,
    actions: SettingsActions,
    account: AccountSettingsState,
    onRetryReading: () -> Unit,
) {
    // Nullable: true previews tracking, false previews finished history.
    var previewReading by remember { mutableStateOf<Boolean?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var previewOwner by remember { mutableStateOf<String?>(null) }
    val reading = state.readingState
    val signedIn = account.pubkey != null
    LaunchedEffect(account.pubkey) {
        previewReading = null
        selected = emptySet()
        previewOwner = null
    }
    fun preview(isReading: Boolean) {
        previewReading = isReading
        previewOwner = account.pubkey
        selected = emptySet()
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ReadingPrivacyToggle(
            "Keep reading list on this device only",
            reading.preferences.readingDeviceOnly,
            signedIn,
        ) { deviceOnly ->
            if (deviceOnly) actions.setReadingPrivacy(true, null, emptySet()) else preview(true)
        }
        ReadingPrivacyToggle(
            "Keep finished books on this device only",
            reading.preferences.finishedDeviceOnly,
            signedIn,
        ) { deviceOnly ->
            if (deviceOnly) actions.setReadingPrivacy(null, true, emptySet()) else preview(false)
        }
        Text(
            if (signedIn) "Public sharing publishes signed events to Nostr for other devices to read. Existing private entries are shared only when you select them. Turning device-only on pauses pending delivery; previously published records remain public."
            else "Reading lists and finished books work without an account. Connect an account to share publicly and sync between devices.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (signedIn) {
            if (!reading.preferences.readingDeviceOnly) {
                TextButton(onClick = { preview(true) }) { Text("Share selected reading entries") }
            }
            if (!reading.preferences.finishedDeviceOnly) {
                TextButton(onClick = { preview(false) }) { Text("Share selected finished entries") }
            }
            Text("Pending reading publications: ${reading.pendingCount}", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onRetryReading, enabled = !reading.isSyncing && !account.isPublishing) {
                Text(if (reading.isSyncing) "Syncing…" else "Sync reading lists")
            }
        }
        reading.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
    }
    previewReading?.let { isReading ->
        val entries = if (isReading) {
            reading.tracked.filter { !it.isPublic }.map { it.bookCoordinate to (it.book?.title ?: it.bookCoordinate) } +
                reading.privateReadingRemovals.map { it.bookCoordinate to "Remove from public reading list: ${it.book?.title ?: it.bookCoordinate}" }
        } else {
            reading.finished.filter { !it.isPublic }.map { it.bookCoordinate to (it.book?.title ?: it.bookCoordinate) }
        }
        AlertDialog(
            onDismissRequest = { previewReading = null },
            title = { Text(if (isReading) "Share reading list" else "Share finished books") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose existing private entries to publish. Unselected entries stay private. Future ${if (isReading) "tracking changes" else "finished marks"} will be public until device-only is turned on.")
                    if (entries.isEmpty()) Text("There are no private entries to share.")
                    else LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                        entries.forEach { (coordinate, title) -> item(key = coordinate) {
                            Row(
                                modifier = Modifier.fillMaxWidth().toggleable(
                                    value = coordinate in selected,
                                    role = Role.Checkbox,
                                    onValueChange = { checked -> selected = if (checked) selected + coordinate else selected - coordinate },
                                ).padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(checked = coordinate in selected, onCheckedChange = null)
                                Text(title, style = MaterialTheme.typography.bodyMedium)
                            }
                        } }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = signedIn && previewOwner == account.pubkey,
                    onClick = {
                        if (signedIn && previewOwner == account.pubkey) {
                            actions.setReadingPrivacy(if (isReading) false else null, if (isReading) null else false, selected.intersect(entries.map { it.first }.toSet()))
                            previewReading = null
                        }
                    },
                ) { Text("Enable public sharing") }
            },
            dismissButton = { TextButton(onClick = { previewReading = null }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun ReadingPrivacyToggle(label: String, deviceOnly: Boolean, signedIn: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
            value = deviceOnly,
            enabled = signedIn,
            role = Role.Switch,
            onValueChange = onChange,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = deviceOnly, onCheckedChange = null, enabled = signedIn)
    }
}

@Composable
private fun AccountSettings(state: SettingsUiState, account: AccountSettingsState, actions: AccountSettingsActions) = SettingsScaffold("Account & Sync") {
    sectionTitle("Account")
    detail("Name", account.profileName ?: "No profile name available")
    detail("Public key", account.pubkey ?: "Not connected")
    detail("Signer", account.signerPackage ?: if (account.signerAvailable) "Available" else "No Android signer found")
    item { AccountConnectionAction(account, actions) }
    sectionTitle("Bookshelf sync")
    detail("Relay sync", account.syncState ?: "Unknown")
    item { if (account.pendingAuthRequest) Text("Relay authorization requested in your signer.") }
    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = actions.syncToDirectory, enabled = account.pubkey != null && !account.isSyncing && !account.isPublishing) { Text("Sync to relays") }; SecondaryButton(onClick = actions.syncFromDirectory, enabled = account.pubkey != null && !account.isSyncing && !account.isPublishing) { Text("Sync from relays") } } }
    sectionTitle("Pending publications")
    detail("Highlights", account.pendingHighlightCount.toString())
    detail("Reviews", account.pendingReviewCount.toString())
    pendingHighlightDetails(state)
    item { Button(onClick = actions.retryNow, enabled = account.pendingHighlightCount + account.pendingReviewCount > 0 && !state.isRetrying && !state.isClearingPending) { Text(if (state.isRetrying) "Retrying…" else "Retry now") } }
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
            SecondaryButton(onClick = { actions.removeSource(source) }, enabled = state.chapterSources.size > 1) { Text("Remove") }
        }
    } }
    item { SecondaryButton(onClick = actions.restoreSources) { Text("Restore defaults") } }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { actions.setLocalRelay(draft) }) { Text("Save") }; SecondaryButton(onClick = { actions.removeLocalRelay(); draft = "" }, enabled = state.localRelayUrl != null) { Text("Remove") } }
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
private fun StorageSettings(state: SettingsUiState, actions: SettingsActions, retryNow: () -> Unit) = SettingsScaffold("Storage & Offline") {
    item { Text(if (state.isOnline) "Network: online" else "Network: offline") }
    detail("Saved books", state.savedBookCount.toString())
    detail("Pending highlights", state.pendingHighlightCount.toString())
    detail("Pending reviews", state.pendingReviewCount.toString())
    pendingHighlightDetails(state)
    item { if (state.pendingHighlightCount + state.pendingReviewCount > 0) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = retryNow, enabled = !state.isRetrying && !state.isClearingPending) { Text(if (state.isRetrying) "Retrying…" else "Retry now") }
            SecondaryButton(onClick = actions.clearPending, enabled = !state.isRetrying && !state.isClearingPending) { Text(if (state.isClearingPending) "Clearing…" else "Clear pending") }
        }
    } }
    item { if (state.pendingHighlightCount + state.pendingReviewCount > 0) Text("Clear pending accepts the current distribution as delivered, clears delivery errors, and stops retries for these highlights and reviews. Signed events stay saved.", style = MaterialTheme.typography.bodySmall) }
    sectionTitle("Disposable caches")
    item { if (state.isRefreshingStats || state.isClearingCaches) LinearProgressIndicator(Modifier.fillMaxWidth()) }
    item { CacheClearActions(state, actions) }
    item { Text("Clearing these caches keeps saved books, reading progress, highlights, and unpublished items. Ratings and recommendations may need to load again.", style = MaterialTheme.typography.bodySmall) }
    item { SecondaryButton(onClick = actions.refreshStorage, enabled = !state.isRefreshingStats && !state.isClearingCaches) { Text("Refresh statistics") } }
    item { state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}

@Composable
private fun CacheClearActions(state: SettingsUiState, actions: SettingsActions) {
    var selectedMask by rememberSaveable { mutableStateOf(0) }
    var confirmationMask by rememberSaveable { mutableStateOf(0) }
    val busy = state.isRefreshingStats || state.isClearingCaches
    val selected = CacheSelection.entries.filter { selectedMask and (1 shl it.ordinal) != 0 }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Select the caches you want to clear.", style = MaterialTheme.typography.bodyMedium)
        CacheSelection.entries.forEach { cache ->
            val bit = 1 shl cache.ordinal
            val checked = selectedMask and bit != 0
            val stats = when (cache) {
                CacheSelection.Chapters -> "${state.chapterCacheStats.entryCount} files · ${state.chapterCacheStats.sizeBytes.formatBytes()}"
                CacheSelection.Ratings -> "${state.ratingCacheStats.entryCount} events · ${state.ratingCacheStats.sizeBytes.formatBytes()}"
                CacheSelection.Recommendations -> "${state.recommendationCacheStats.entryCount} lists · ${state.recommendationCacheStats.sizeBytes.formatBytes()}"
                CacheSelection.OfflineBooks -> "${state.offlineBookCacheStats.entryCount} books · ${state.offlineBookCacheStats.sizeBytes.formatBytes()}"
            }
            CacheSelectionRow(
                cache = cache,
                stats = stats,
                checked = checked,
                enabled = !busy,
                onChange = { selectedMask = if (it) selectedMask or bit else selectedMask and bit.inv() },
            )
        }
        CacheClearButton(
            enabled = selected.isNotEmpty() && !busy,
            clearing = state.isClearingCaches,
            onClick = { confirmationMask = selectedMask },
        )
    }
    if (confirmationMask != 0) {
        val confirmed = CacheSelection.entries.filter { confirmationMask and (1 shl it.ordinal) != 0 }.toSet()
        AlertDialog(
            onDismissRequest = { confirmationMask = 0 },
            title = { Text("Clear selected caches?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(confirmed.joinToString("\n") { "• ${it.label}" })
                    Text("Cached content will need to load or be generated again. Saved books, reading progress, highlights, and pending signed publications stay saved.")
                    if (CacheSelection.OfflineBooks in confirmed) Text("Downloaded books will need to be downloaded again before offline reading.")
                }
            },
            confirmButton = {
                SecondaryButton(onClick = {
                    actions.clearSelectedCaches(confirmed)
                    selectedMask = 0
                    confirmationMask = 0
                }, enabled = !busy) { Text("Clear selected") }
            },
            dismissButton = { SecondaryButton(onClick = { confirmationMask = 0 }) { Text("Cancel") } },
        )
    }
}

private fun LazyListScope.pendingHighlightDetails(state: SettingsUiState) {
    if (state.pendingHighlightDetails.isEmpty()) return
    item { Text("A highlight can already be visible elsewhere while delivery to other relays or its cited chapter is still pending.", style = MaterialTheme.typography.bodySmall) }
    state.pendingHighlightDetails.forEach { detail ->
        item { Text(detail, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun AboutSettings() = SettingsScaffold("About") {
    item { Text("Bookshelf", style = MaterialTheme.typography.headlineSmall) }
    item {
        Text(
            "Bookshelf is a calm e-reader for discovering, saving, and enjoying books. It turns an open publishing network into a personal pocket library: browse curated shelves or search the public catalog, open a book, settle into a comfortable chapter view, and keep the titles you care about close at hand.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
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

/** Connection actions remain supplied by the account screen. */
@Composable
internal fun AccountConnectionAction(account: AccountSettingsState, actions: AccountSettingsActions) {
    if (account.pubkey == null) {
        Button(onClick = actions.login, enabled = account.signerAvailable) { Text("Connect account") }
    } else {
        SecondaryButton(onClick = actions.signOut, enabled = !account.isSyncing && !account.isPublishing) { Text("Disconnect") }
    }
}

/** A single cache choice; selection and clearing are owned by the caller. */
@Composable
internal fun CacheSelectionRow(
    cache: CacheSelection,
    stats: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(
            value = checked, enabled = enabled, role = Role.Switch,
            onValueChange = onChange,
        ).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(cache.label, style = MaterialTheme.typography.bodyLarge)
            Text(stats, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun CacheClearButton(enabled: Boolean, clearing: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(if (clearing) "Clearing caches…" else "Clear selected caches")
    }
}
