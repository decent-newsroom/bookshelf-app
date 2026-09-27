package eu.decentnewsroom.bookshelf.ui.books

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.decentnewsroom.bookshelf.domain.BookSummary

/** Shared by the Home shelves and the similar-publication section in book details. */
@Composable
fun BookCarousel(
    books: List<BookSummary>,
    onOpen: (BookSummary) -> Unit,
    onLongPress: (BookSummary) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(books, key = BookSummary::coordinate) { book ->
            ShelfBookCard(book, onOpen = { onOpen(book) }, onLongPress = { onLongPress(book) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfBookCard(book: BookSummary, onOpen: () -> Unit, onLongPress: () -> Unit) {
    Column(Modifier.width(124.dp).combinedClickable(onClick = onOpen, onLongClick = onLongPress), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        BookCover(book, Modifier.fillMaxWidth().height(174.dp))
        Text(book.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(book.authors.joinToString(", ").ifBlank { "Unknown author" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
