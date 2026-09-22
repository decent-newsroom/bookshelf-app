package eu.decentnewsroom.bookshelf.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.ui.components.EmptyScreen
import eu.decentnewsroom.bookshelf.ui.components.Notice
import eu.decentnewsroom.bookshelf.ui.books.BookCard

@Composable
fun MyBooksScreen(books: List<BookSummary>, savedCoordinates: Set<String>, onOpen: (BookSummary) -> Unit, error: String?, onLongPress: (BookSummary) -> Unit) {
    if (books.isEmpty()) {
        EmptyScreen("Your shelf is empty", "Save books from search results. They stay on this device even without a login.")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("My Books", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("${books.size} books saved on this device.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Notice(it) }
        }
        items(books, key = BookSummary::coordinate) { book -> BookCard(book, savedCoordinates.contains(book.coordinate), { onOpen(book) }, { onLongPress(book) }) }
    }
}
