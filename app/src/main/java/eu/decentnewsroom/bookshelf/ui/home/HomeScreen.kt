package eu.decentnewsroom.bookshelf.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.decentnewsroom.bookshelf.data.discovery.CuratedShelf
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.ui.ContinueReadingBook
import eu.decentnewsroom.bookshelf.ui.components.LoadingInline
import eu.decentnewsroom.bookshelf.ui.books.BookCover
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    shelves: List<CuratedShelf>,
    isLoading: Boolean,
    continueReading: ContinueReadingBook?,
    message: String?,
    profileName: String?,
    listState: LazyListState,
    onSearch: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (BookSummary) -> Unit,
    onLongPress: (BookSummary) -> Unit,
) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(profileName?.let { "Hello, $it" } ?: "Discover", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSearch) { Text("Search") }
            }
        }
        continueReading?.let { item { ContinueReadingCard(it, onOpen = { onOpen(it.book) }, onLongPress = { onLongPress(it.book) }) } }
        if (isLoading && shelves.isEmpty()) item { LoadingInline("Loading shelves...") }
        message?.let { text -> item { Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) { Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant); TextButton(onClick = onRetry) { Text("Retry") } } } }
        shelves.forEach { shelf ->
            item(key = shelf.id) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(shelf.title, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(shelf.books, key = BookSummary::coordinate) { book -> ShelfBookCard(book, onOpen = { onOpen(book) }, onLongPress = { onLongPress(book) }) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContinueReadingCard(continueReading: ContinueReadingBook, onOpen: () -> Unit, onLongPress: () -> Unit) {
    val book = continueReading.book
    val progress = continueReading.progress
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp).combinedClickable(onClick = onOpen, onLongClick = onLongPress), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(book, Modifier.size(width = 64.dp, height = 92.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Continue reading", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator({ progress.progressFraction.coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)))
                Text("Chapter ${progress.currentChapterNumber} of ${progress.chapterCount} | ${(progress.progressFraction * 100f).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
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
