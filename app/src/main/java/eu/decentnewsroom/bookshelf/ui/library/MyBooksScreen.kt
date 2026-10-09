package eu.decentnewsroom.bookshelf.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.decentnewsroom.bookshelf.data.reading.FinishedBook
import eu.decentnewsroom.bookshelf.data.reading.TrackedBook
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.ui.components.Notice
import eu.decentnewsroom.bookshelf.ui.books.BookCard
import eu.decentnewsroom.bookshelf.ui.reading.FinishedBookCard
import eu.decentnewsroom.bookshelf.ui.reading.TrackedBookCard

@Composable
fun MyBooksScreen(
    books: List<BookSummary>,
    savedCoordinates: Set<String>,
    onOpen: (BookSummary) -> Unit,
    error: String?,
    onLongPress: (BookSummary) -> Unit,
    readingNow: List<TrackedBook> = emptyList(),
    finishedBooks: List<FinishedBook> = emptyList(),
    onResolveReading: (String) -> Unit = {},
) {
    var selectedView by rememberSaveable { mutableIntStateOf(0) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("My Books", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            error?.let { Notice(it) }
        }
        item(key = "library-views") {
            SecondaryTabRow(
                selectedTabIndex = selectedView,
                containerColor = TabRowDefaults.primaryContainerColor,
                contentColor = TabRowDefaults.primaryContentColor,
            ) {
                listOf("Saved", "Reading", "Finished").forEachIndexed { index, title ->
                    Tab(
                        selected = selectedView == index,
                        onClick = { selectedView = index },
                        text = { Text(title) },
                    )
                }
            }
        }
        when (selectedView) {
            0 -> {
                item { Text("${books.size} books saved on this device.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (books.isEmpty()) item { Text("Save books from search results. They stay on this device even without a login.") }
                items(books, key = { "saved:${it.coordinate}" }) { book -> BookCard(book, savedCoordinates.contains(book.coordinate), { onOpen(book) }, { onLongPress(book) }) }
            }
            1 -> {
                if (readingNow.isEmpty()) item { Text("No books are tracked yet. Choose Track reading under a book's reader metadata to add one.") }
                items(readingNow.sortedByDescending { it.updatedAt }, key = { "reading:${it.bookCoordinate}" }) { tracked -> TrackedBookCard(tracked, onOpen, onLongPress, onResolveReading) }
            }
            2 -> {
                if (finishedBooks.isEmpty()) item { Text("No finished books yet. Mark a book as finished at the end of its reader.") }
                items(finishedBooks.sortedByDescending { it.finishedAt }, key = { "finished:${it.bookCoordinate}" }) { finished -> FinishedBookCard(finished, onOpen, onLongPress, onResolveReading) }
            }
        }
    }
}
