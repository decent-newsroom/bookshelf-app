@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.decentnewsroom.bookshelf.ui.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.ui.components.LoadingInline
import eu.decentnewsroom.bookshelf.ui.components.SecondaryButton

@Composable
fun BookRecommendationsSheet(
    seed: BookSummary,
    books: List<BookSummary>,
    isLoading: Boolean,
    message: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onOpen: (BookSummary) -> Unit,
    onDetails: (BookSummary) -> Unit,
    savedCoordinates: Set<String>,
    onToggleSaved: (BookSummary) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("More like this", style = MaterialTheme.typography.titleLarge)
                    Text(seed.title, style = MaterialTheme.typography.titleMedium)
                    Text("Similar subjects and authors", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (isLoading) item { LoadingInline("Finding similar books...") }
            message?.let { item { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            if (!isLoading && message == null && books.isEmpty()) {
                item { Text("No similar books to show right now.") }
            }
            if (!isLoading && message != null) {
                item { SecondaryButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") } }
            }
            items(books, key = BookSummary::coordinate) { book ->
                Column {
                    BookCard(book, isSaved = book.coordinate in savedCoordinates,
                        onOpen = { if (book.chapterRefs.isEmpty()) onDetails(book) else onOpen(book) },
                        onLongPress = { onDetails(book) })
                    SecondaryButton(onClick = { onDetails(book) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Book details")
                    }
                    SecondaryButton(onClick = { onToggleSaved(book) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (book.coordinate in savedCoordinates) "Remove from My Books" else "Add to My Books")
                    }
                }
            }
        }
    }
}
