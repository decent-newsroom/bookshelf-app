package eu.decentnewsroom.bookshelf.ui.books

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.decentnewsroom.bookshelf.data.mercury.TrustedCoverImagePolicy
import eu.decentnewsroom.bookshelf.domain.BookSummary

@Composable
fun BookCard(
    book: BookSummary,
    isSaved: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(book)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(book.type.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(
                    text = "by ${book.authors.joinToString(", ").ifBlank { "Unknown author" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = if (book.chapterRefs.isEmpty()) {
                        "Library card · Full text unavailable" + if (isSaved) " · In My Books" else ""
                    } else {
                        "${book.chapterCount} chapters" + if (isSaved) " · In My Books" else ""
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun BookCover(
    book: BookSummary,
    modifier: Modifier = Modifier.size(width = 56.dp, height = 76.dp),
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    monogramColor: Color = MaterialTheme.colorScheme.primary,
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = book.title.trim().firstOrNull()?.uppercase() ?: "B",
            style = MaterialTheme.typography.headlineSmall,
            color = monogramColor,
            fontWeight = FontWeight.Bold,
        )
        TrustedCoverImagePolicy.sanitize(book.coverImageUrl)?.let { coverUrl ->
            AsyncImage(
                model = coverUrl,
                contentDescription = "Cover art for ${book.title}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
