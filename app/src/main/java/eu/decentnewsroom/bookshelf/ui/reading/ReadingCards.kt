package eu.decentnewsroom.bookshelf.ui.reading

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.decentnewsroom.bookshelf.data.reading.FinishedBook
import eu.decentnewsroom.bookshelf.data.reading.TrackedBook
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.ui.RatingComposerState
import eu.decentnewsroom.bookshelf.ui.books.BookCover
import eu.decentnewsroom.bookshelf.ui.components.SecondaryButton
import eu.decentnewsroom.bookshelf.ui.ratings.RatingComposerForm
import java.text.DateFormat
import java.util.Date

@Composable
fun FinishBookCard(
    finished: FinishedBook?,
    deviceOnly: Boolean,
    isRereading: Boolean,
    onFinish: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (finished == null || isRereading) "Mark as finished" else "Finished", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (finished != null && !isRereading) {
                Text("You finished this book. Take a moment to celebrate!")
                Text(readingDeliveryLabel(finished.isPublic, finished.status), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(if (deviceOnly) "Save this finished book on this device." else "Publish a public finished label for this edition.")
                Button(onClick = onFinish) { Text("Mark as finished") }
            }
        }
    }
}

@Composable
fun InlineReviewCard(
    composer: RatingComposerState?,
    status: String?,
    onPrepare: () -> Unit,
    onStarsChanged: (Int) -> Unit,
    onOpinionChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onDiscard: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Leave a review", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Your rating and optional review are public.", style = MaterialTheme.typography.bodySmall)
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (composer != null) RatingComposerForm(composer, onStarsChanged, onOpinionChanged, onSubmit, onDiscard)
            else SecondaryButton(onClick = onPrepare, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (status == null) "Write your review" else "Edit your review") }
        }
    }
}

@Composable
fun ReadingNowCarousel(
    books: List<TrackedBook>,
    onOpen: (BookSummary) -> Unit,
    onLongPress: (BookSummary) -> Unit,
    onResolve: (String) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = (maxWidth - 40.dp).coerceAtLeast(0.dp).coerceAtMost(320.dp)
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(books, key = TrackedBook::bookCoordinate) { tracked ->
                ReadingNowBookCard(tracked, onOpen, onLongPress, onResolve, Modifier.width(cardWidth))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReadingNowBookCard(
    tracked: TrackedBook,
    onOpen: (BookSummary) -> Unit,
    onLongPress: (BookSummary) -> Unit,
    onResolve: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val book = tracked.book
    Card(modifier.combinedClickable(
        onClick = { if (book == null) onResolve(tracked.bookCoordinate) else onOpen(book) },
        onLongClick = { book?.let(onLongPress) },
    )) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (book != null) {
                BookCover(book, Modifier.size(width = 48.dp, height = 68.dp))
                Text(book.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            } else {
                Column(Modifier.weight(1f)) {
                    Text("Publication details unavailable", style = MaterialTheme.typography.titleSmall)
                    Text(tracked.bookCoordinate.substringAfterLast(':'), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { onResolve(tracked.bookCoordinate) }) { Text("Load details") }
                }
            }
            IconButton(onClick = { book?.let(onLongPress) }, enabled = book != null, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.MoreVert, contentDescription = book?.let { "Actions for ${it.title}" } ?: "Book actions unavailable")
            }
        }
    }
}

@Composable
fun TrackedBookCard(tracked: TrackedBook, onOpen: (BookSummary) -> Unit, onLongPress: (BookSummary) -> Unit, onResolve: (String) -> Unit) {
    ReadingBookCard(tracked.bookCoordinate, tracked.book, readingDeliveryLabel(tracked.isPublic, tracked.status), onOpen, onLongPress, onResolve) {
        Text(furthestSectionLabel(tracked), style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(progress = { trackedFraction(tracked) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun FinishedBookCard(finished: FinishedBook, onOpen: (BookSummary) -> Unit, onLongPress: (BookSummary) -> Unit, onResolve: (String) -> Unit) {
    ReadingBookCard(finished.bookCoordinate, finished.book, readingDeliveryLabel(finished.isPublic, finished.status), onOpen, onLongPress, onResolve) {
        Text("Finished ${DateFormat.getDateInstance().format(Date(finished.finishedAt * 1000L))}", style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReadingBookCard(
    coordinate: String,
    book: BookSummary?,
    status: String,
    onOpen: (BookSummary) -> Unit,
    onLongPress: (BookSummary) -> Unit,
    onResolve: (String) -> Unit,
    modifier: Modifier = Modifier,
    extra: @Composable () -> Unit,
) {
    Card(modifier.fillMaxWidth().combinedClickable(onClick = { if (book == null) onResolve(coordinate) else onOpen(book) }, onLongClick = { book?.let(onLongPress) })) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (book != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BookCover(book, Modifier.size(width = 48.dp, height = 68.dp))
                    Text(book.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Text("Publication details unavailable", style = MaterialTheme.typography.titleSmall)
                Text(coordinate.substringAfterLast(':'), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { onResolve(coordinate) }) { Text("Load details") }
            }
            extra()
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun trackedFraction(tracked: TrackedBook): Float =
    if (tracked.total <= 0) 0f else (tracked.position.toFloat() / tracked.total).coerceIn(0f, 0.99f)

private fun furthestSectionLabel(tracked: TrackedBook): String =
    if (tracked.total <= 0) "Furthest section: unavailable"
    else "Furthest section: ${tracked.position.coerceIn(0, tracked.total - 1) + 1} of ${tracked.total}"

private fun readingDeliveryLabel(isPublic: Boolean, status: String): String =
    if (!isPublic) "On this device" else status.ifBlank { "Public sync pending" }
