package eu.decentnewsroom.bookshelf.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import eu.decentnewsroom.bookshelf.data.mercury.BookSearchResult
import eu.decentnewsroom.bookshelf.data.mercury.MatchProvenance
import eu.decentnewsroom.bookshelf.data.mercury.SearchScope
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.ui.BookshelfUiState
import eu.decentnewsroom.bookshelf.ui.components.LoadingInline
import eu.decentnewsroom.bookshelf.ui.components.Notice
import eu.decentnewsroom.bookshelf.ui.books.BookCard

@Composable
fun SearchScreen(
    state: BookshelfUiState,
    onQueryChanged: (String) -> Unit,
    onScopeChanged: (SearchScope) -> Unit,
    onSearch: () -> Unit,
    onOpen: (BookSummary) -> Unit,
    onOpenMatch: (BookSearchResult) -> Unit,
    onLongPress: (BookSummary) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Find books by metadata or text inside chapters.")
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search books") },
                    supportingText = { Text(if (state.searchScope == SearchScope.CHAPTER_CONTENT) "Enter 4–160 characters." else "Choose a scope, then search. Results are limited.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(searchScopes, key = { it.first }) { (scope, label) ->
                        FilterChip(
                            selected = state.searchScope == scope,
                            onClick = { onScopeChanged(scope) },
                            label = { Text(label) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                Button(onClick = onSearch, modifier = Modifier.fillMaxWidth()) { Text(if (state.isSearching) "Search again" else "Search") }
            }
        }
        if (state.isSearching) item { LoadingInline("Searching…") }
        state.searchMessage?.let { item { Notice(it) } }
        state.error?.let { item { Notice(it) } }
        items(state.searchResults, key = { it.book.coordinate }) { result ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BookCard(result.book, state.savedCoordinates.contains(result.book.coordinate), { onOpen(result.book) }, { onLongPress(result.book) })
                Text(result.provenance.searchMatchLabel(), style = MaterialTheme.typography.labelMedium)
                result.matchedChapterTitle?.let { Text("Chapter: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                result.excerpt?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3) }
                if (result.matchedChapterCoordinate != null) {
                    TextButton(onClick = { onOpenMatch(result) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open matching chapter") }
                }
            }
        }
    }
}

private val searchScopes = listOf(
    SearchScope.ALL to "All",
    SearchScope.TITLE to "Title",
    SearchScope.AUTHOR to "Author",
    SearchScope.SUBJECT to "Subject",
    SearchScope.CHAPTER_CONTENT to "Inside books",
)

internal fun Set<MatchProvenance>.searchMatchLabel(): String {
    val chapter = any { it == MatchProvenance.CHAPTER_TITLE || it == MatchProvenance.CHAPTER_BODY || it == MatchProvenance.CHAPTER_TEXT }
    val metadata = any { it != MatchProvenance.CHAPTER_TITLE && it != MatchProvenance.CHAPTER_BODY && it != MatchProvenance.CHAPTER_TEXT }
    return when {
        chapter && metadata -> "Metadata and text match"
        chapter -> "Text match"
        MatchProvenance.EXACT_EVENT in this || MatchProvenance.EXACT_COORDINATE in this -> "Exact reference"
        else -> "Metadata match"
    }
}
