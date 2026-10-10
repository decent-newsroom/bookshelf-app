package eu.decentnewsroom.bookshelf.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import eu.decentnewsroom.bookshelf.data.mercury.BookSearchResult
import eu.decentnewsroom.bookshelf.data.mercury.MatchProvenance
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.ui.BookshelfUiState
import eu.decentnewsroom.bookshelf.ui.components.LoadingInline
import eu.decentnewsroom.bookshelf.ui.components.Notice
import eu.decentnewsroom.bookshelf.ui.books.BookCard

@Composable
fun SearchScreen(
    state: BookshelfUiState,
    onQueryChanged: (String) -> Unit,
    onSearchBookContentsChanged: (Boolean) -> Unit,
    onSearch: () -> Unit,
    onOpen: (BookSummary) -> Unit,
    onOpenMatch: (BookSearchResult) -> Unit,
    onLongPress: (BookSummary) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SearchForm(
                query = state.query,
                searchBookContents = state.searchBookContents,
                isSearching = state.isSearching,
                onQueryChanged = onQueryChanged,
                onSearchBookContentsChanged = onSearchBookContentsChanged,
                onSearch = onSearch,
            )
        }
        if (state.isSearching) item { LoadingInline("Searching…") }
        state.searchMessage?.let { item { Notice(it) } }
        state.error?.let { item { Notice(it) } }
        items(state.searchResults, key = { it.book.coordinate }) { result ->
            if (result.provenance.isContentMatch()) {
                ContentSearchResultCard(
                    result = result,
                    isSaved = state.savedCoordinates.contains(result.book.coordinate),
                    onOpen = { onOpen(result.book) },
                    onOpenMatch = { onOpenMatch(result) },
                    onLongPress = { onLongPress(result.book) },
                )
            } else {
                BookCard(result.book, state.savedCoordinates.contains(result.book.coordinate), { onOpen(result.book) }, { onLongPress(result.book) })
            }
        }
    }
}

internal fun Set<MatchProvenance>.searchMatchLabel(): String {
    val chapter = isContentMatch()
    val metadata = any { it != MatchProvenance.CHAPTER_TITLE && it != MatchProvenance.CHAPTER_BODY && it != MatchProvenance.CHAPTER_TEXT }
    return when {
        chapter && metadata -> "Metadata and text match"
        chapter -> "Text match"
        MatchProvenance.EXACT_EVENT in this || MatchProvenance.EXACT_COORDINATE in this -> "Exact reference"
        else -> "Metadata match"
    }
}

internal fun Set<MatchProvenance>.isContentMatch(): Boolean = any {
    it == MatchProvenance.CHAPTER_TITLE || it == MatchProvenance.CHAPTER_BODY || it == MatchProvenance.CHAPTER_TEXT
}

/** Shared search controls; callers own all state and actions. */
@Composable
internal fun SearchForm(
    query: String,
    searchBookContents: Boolean,
    isSearching: Boolean,
    onQueryChanged: (String) -> Unit,
    onSearchBookContentsChanged: (Boolean) -> Unit,
    onSearch: () -> Unit,
    readOnly: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (searchBookContents) "Search within book chapters."
            else "Search all titles, authors, subjects, and other book details."
        )
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search books") },
            singleLine = true,
            readOnly = readOnly,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(
                    value = searchBookContents,
                    role = Role.Switch,
                    onValueChange = onSearchBookContentsChanged,
                ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Search book contents")
                Text(
                    "Search within chapters. Use 4 to 160 characters; results may take longer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = searchBookContents, onCheckedChange = null)
        }
        Button(onClick = onSearch, modifier = Modifier.fillMaxWidth()) { Text(if (isSearching) "Search again" else "Search") }
    }
}
