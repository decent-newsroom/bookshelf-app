package eu.decentnewsroom.bookshelf.ui.search

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.decentnewsroom.bookshelf.data.mercury.BookSearchResult
import eu.decentnewsroom.bookshelf.ui.books.BookCardHeader

@Composable
internal fun ContentSearchResultCard(
    result: BookSearchResult,
    isSaved: Boolean,
    onOpen: () -> Unit,
    onOpenMatch: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        BookCardHeader(
            book = result.book,
            isSaved = isSaved,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                result.provenance.searchMatchLabel(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                result.matchedChapterTitle?.takeIf { it.isNotBlank() } ?: "Matching chapter",
                style = MaterialTheme.typography.titleSmall,
            )
            result.excerpt?.takeIf { it.isNotBlank() }?.let { excerpt ->
                Text(
                    excerpt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!result.matchedChapterCoordinate.isNullOrBlank()) {
                FilledTonalButton(onClick = onOpenMatch, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Open matching chapter")
                }
            }
        }
    }
}
