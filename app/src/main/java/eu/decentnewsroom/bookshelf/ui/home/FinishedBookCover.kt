package eu.decentnewsroom.bookshelf.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.decentnewsroom.bookshelf.data.reading.FinishedBook
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.ui.books.BookCover

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FinishedBookCover(
    finished: FinishedBook,
    width: Dp,
    onOpen: (BookSummary) -> Unit,
    onLongPress: (BookSummary) -> Unit,
    onResolve: (String) -> Unit,
) {
    val book = finished.book
    val title = book?.title ?: finished.bookCoordinate.substringAfterLast(':')
    val modifier = Modifier
        .size(width = width, height = width * (80f / 56f))
        .semantics(mergeDescendants = true) {
            contentDescription = if (book == null) {
                "Finished book: $title. Publication details unavailable. Load details."
            } else {
                "Finished book: $title"
            }
        }
        .combinedClickable(
            onClickLabel = if (book == null) "Load details" else "Open book",
            onLongClickLabel = if (book == null) null else "Book actions",
            onClick = { if (book == null) onResolve(finished.bookCoordinate) else onOpen(book) },
            onLongClick = book?.let { { onLongPress(it) } },
        )
    if (book != null) {
        Box(modifier) {
            BookCover(book, Modifier.fillMaxSize().clearAndSetSemantics {})
        }
    } else {
        Box(
            modifier.clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("?", style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}
