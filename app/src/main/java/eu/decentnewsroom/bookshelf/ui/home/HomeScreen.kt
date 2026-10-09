package eu.decentnewsroom.bookshelf.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import eu.decentnewsroom.bookshelf.ui.components.SecondaryButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.decentnewsroom.bookshelf.data.discovery.CuratedShelf
import eu.decentnewsroom.bookshelf.data.reading.FinishedBook
import eu.decentnewsroom.bookshelf.data.reading.TrackedBook
import eu.decentnewsroom.bookshelf.ui.reading.ReadingNowCarousel
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.ui.ContinueReadingBook
import eu.decentnewsroom.bookshelf.ui.components.LoadingInline
import eu.decentnewsroom.bookshelf.ui.books.BookCover
import eu.decentnewsroom.bookshelf.ui.books.BookCarousel
import eu.decentnewsroom.bookshelf.ui.books.LocalBookReadingPresentations
import eu.decentnewsroom.bookshelf.ui.books.chapterReadingProgressLabel
import eu.decentnewsroom.bookshelf.ui.books.resolveUiReadingPresentation

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
    readingNow: List<TrackedBook> = emptyList(),
    finishedBooks: List<FinishedBook> = emptyList(),
    onResolveReading: (String) -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val availableWidth = (maxWidth - 40.dp).coerceAtLeast(0.dp)
        val coverWidth = availableWidth.coerceAtMost(56.dp)
        val columns = ((availableWidth + 12.dp) / (56.dp + 12.dp)).toInt().coerceIn(1, 7)
        val finishedRows = remember(finishedBooks, columns) {
            finishedBooks.sortedWith(compareByDescending<FinishedBook> { it.finishedAt }
                .thenBy { it.bookCoordinate }).chunked(columns)
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(profileName?.let { "Hello, $it" } ?: "Discover", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    SecondaryButton(onClick = onSearch) { Text("Search") }
                }
            }
            continueReading?.let { item { ContinueReadingCard(it, onOpen = { onOpen(it.book) }, onLongPress = { onLongPress(it.book) }) } }
            if (readingNow.isNotEmpty()) item(key = "reading-now") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Reading now", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    ReadingNowCarousel(readingNow, onOpen, onLongPress, onResolveReading)
                }
            }
            if (isLoading && shelves.isEmpty()) item { LoadingInline("Loading shelves...") }
            message?.let { text -> item { Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) { Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant); SecondaryButton(onClick = onRetry) { Text("Retry") } } } }
            shelves.forEach { shelf ->
                item(key = shelf.id) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(shelf.title, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        BookCarousel(shelf.books, onOpen = onOpen, onLongPress = onLongPress, contentPadding = PaddingValues(horizontal = 20.dp))
                    }
                }
            }
            if (finishedRows.isNotEmpty()) {
                item(key = "finished-heading") {
                    Text("Finished", Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                items(finishedRows, key = { "finished-row:${it.first().bookCoordinate}" }) { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    ) {
                        row.forEach { finished ->
                            key(finished.bookCoordinate) {
                                FinishedBookCover(finished, coverWidth, onOpen, onLongPress, onResolveReading)
                            }
                        }
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
    val presentation = resolveUiReadingPresentation(progress, book, LocalBookReadingPresentations.current[book.coordinate])
    val fraction = presentation.fraction
    val progressLabel = chapterReadingProgressLabel(progress, presentation)
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp).combinedClickable(onClick = onOpen, onLongClick = onLongPress), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(book, Modifier.size(width = 64.dp, height = 92.dp), readingPresentation = null)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Continue reading", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                fraction?.let { value -> LinearProgressIndicator({ value }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp))) }
                Text(progressLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}
