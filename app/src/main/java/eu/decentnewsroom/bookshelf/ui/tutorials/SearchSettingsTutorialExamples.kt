package eu.decentnewsroom.bookshelf.ui.tutorials

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.decentnewsroom.bookshelf.R
import eu.decentnewsroom.bookshelf.data.mercury.BookSearchResult
import eu.decentnewsroom.bookshelf.data.mercury.MatchProvenance
import eu.decentnewsroom.bookshelf.ui.search.ContentSearchResultCard
import eu.decentnewsroom.bookshelf.ui.search.SearchForm
import eu.decentnewsroom.bookshelf.ui.settings.AccountConnectionAction
import eu.decentnewsroom.bookshelf.ui.settings.AccountSettingsActions
import eu.decentnewsroom.bookshelf.ui.settings.AccountSettingsState
import eu.decentnewsroom.bookshelf.ui.settings.CacheClearButton
import eu.decentnewsroom.bookshelf.ui.settings.CacheSelection
import eu.decentnewsroom.bookshelf.ui.settings.CacheSelectionRow
import eu.decentnewsroom.bookshelf.ui.settings.ReadingPrivacyToggle
import eu.decentnewsroom.bookshelf.ui.settings.SettingsDetail

/** Bundled examples only: no services, persisted state, or production actions. */
@Composable
internal fun SearchSettingsTutorialExample(example: TutorialExample) {
    when (example) {
        TutorialExample.SearchBooks, TutorialExample.SearchChapters -> SearchForm(
            query = if (example == TutorialExample.SearchBooks) TutorialSamples.book().title
                else stringResource(R.string.tutorial_example_search_phrase),
            searchBookContents = example == TutorialExample.SearchChapters,
            isSearching = false,
            onQueryChanged = {},
            onSearchBookContentsChanged = {},
            onSearch = {},
            readOnly = true,
        )
        TutorialExample.OpenMatch -> {
            val chapter = TutorialSamples.chapters().first()
            ContentSearchResultCard(
                result = BookSearchResult(
                    book = TutorialSamples.book(),
                    provenance = setOf(MatchProvenance.CHAPTER_BODY),
                    matchedChapterCoordinate = chapter.reference.coordinate,
                    matchedChapterTitle = chapter.title,
                    excerpt = TutorialSamples.passage(),
                ),
                isSaved = false,
                onOpen = {},
                onOpenMatch = {},
                onLongPress = {},
            )
        }
        TutorialExample.RestoreDownloads -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.tutorial_example_storage_title), style = MaterialTheme.typography.titleMedium)
            CacheSelectionRow(
                cache = CacheSelection.OfflineBooks,
                stats = stringResource(R.string.tutorial_example_offline_cache_stats),
                checked = true,
                enabled = true,
                onChange = {},
            )
            CacheClearButton(enabled = true, clearing = false, onClick = {})
            Text(
                stringResource(R.string.tutorial_example_offline_cache_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TutorialExample.ConnectSigner -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.tutorial_example_account_title), style = MaterialTheme.typography.titleMedium)
            SettingsDetail(
                stringResource(R.string.tutorial_example_public_key_label),
                stringResource(R.string.tutorial_example_not_connected),
            )
            AccountConnectionAction(
                account = AccountSettingsState(signerAvailable = true),
                actions = AccountSettingsActions(),
            )
        }
        TutorialExample.ReviewSharing -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ReadingPrivacyToggle(
                label = stringResource(R.string.tutorial_example_reading_device_only),
                deviceOnly = true,
                signedIn = true,
                onChange = {},
            )
            ReadingPrivacyToggle(
                label = stringResource(R.string.tutorial_example_finished_device_only),
                deviceOnly = true,
                signedIn = true,
                onChange = {},
            )
        }
        else -> Unit
    }
}
