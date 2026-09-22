package eu.decentnewsroom.bookshelf.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.ui.BookshelfUiState
import eu.decentnewsroom.bookshelf.ui.components.LoadingInline
import eu.decentnewsroom.bookshelf.ui.components.Notice
import eu.decentnewsroom.bookshelf.ui.books.BookCard

@Composable
fun SearchScreen(state: BookshelfUiState, onQueryChanged: (String) -> Unit, onSearch: () -> Unit, onOpen: (BookSummary) -> Unit, onLongPress: (BookSummary) -> Unit) {
    var hasInteracted by rememberSaveable { mutableStateOf(state.query.isNotEmpty() || state.isSearching || state.searchResults.isNotEmpty() || state.searchMessage != null) }
    val submitSearch = { hasInteracted = true; onSearch() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!hasInteracted) Notice("Find books by title, author, source, or Nostr identifier.")
                OutlinedTextField(value = state.query, onValueChange = { hasInteracted = true; onQueryChanged(it) }, modifier = Modifier.fillMaxWidth(), label = { Text("Search books") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submitSearch() }))
                Button(onClick = submitSearch, modifier = Modifier.fillMaxWidth(), enabled = !state.isSearching) { Text("Search") }
            }
        }
        if (state.isSearching) item { LoadingInline("Searching...") }
        state.searchMessage?.let { item { Notice(it) } }
        state.error?.let { item { Notice(it) } }
        if (state.isPublishingDirectory) item { LoadingInline("Sharing bookshelf...") }
        items(state.searchResults, key = { it.book.coordinate }) { result ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BookCard(result.book, state.savedCoordinates.contains(result.book.coordinate), { onOpen(result.book) }, { onLongPress(result.book) })
                result.matchedChapterTitle?.let { Text("Chapter: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                result.excerpt?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3) }
            }
        }
    }
}
