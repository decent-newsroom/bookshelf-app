@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.decentnewsroom.bookshelf.ui.books

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.ui.BookDetailsState
import eu.decentnewsroom.bookshelf.ui.RatingSummaryUi
import eu.decentnewsroom.bookshelf.ui.components.LoadingInline
import eu.decentnewsroom.bookshelf.ui.ratings.RatingSummaryCard

@Composable
fun BookActionsSheet(
    book: BookSummary,
    isSaved: Boolean,
    localRelayConfigured: Boolean,
    isBroadcasting: Boolean,
    onDismiss: () -> Unit,
    onToggleSaved: () -> Unit,
    onDetails: () -> Unit,
    onBroadcast: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(book.title, style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            BookActionRow(if (isSaved) Icons.Outlined.BookmarkRemove else Icons.Outlined.BookmarkAdd, if (isSaved) "Remove from My Books" else "Add to My Books", onToggleSaved)
            BookActionRow(Icons.Outlined.Info, "See details", onDetails)
            if (localRelayConfigured) {
                BookActionRow(Icons.AutoMirrored.Outlined.Send, if (isBroadcasting) "Broadcasting…" else "Broadcast to local relay", onBroadcast, enabled = !isBroadcasting)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun BookActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    }
}

@Composable
fun BookDetailsSheet(details: BookDetailsState, onDismiss: () -> Unit, onShowRatings: () -> Unit) {
    val book = details.book
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                BookCover(book, Modifier.size(width = 104.dp, height = 148.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(book.title, style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text(book.authors.joinToString(", ").ifBlank { "Unknown author" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            book.summary?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            RatingSummaryCard(summary = details.ratings, onClick = onShowRatings)
            Text("Publisher", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            when {
                details.publisher != null -> { DetailRow("Profile", details.publisher.preferredName ?: "Unnamed profile"); DetailRow("Public key", book.pubkey) }
                details.isLoadingPublisher -> LoadingInline("Loading publisher profile…")
                else -> DetailRow("Public key", book.pubkey)
            }
            Text("Publication", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            DetailRow("Type", book.type)
            DetailRow("Chapters", book.chapterCount.toString())
            book.language?.let { DetailRow("Language", it) }
            book.releaseDate?.let { DetailRow("Published", it) }
            book.version?.let { DetailRow("Version", it) }
            if (book.topics.isNotEmpty()) DetailRow("Topics", book.topics.joinToString(", "))
            book.sourceUrl?.let { DetailRow("Source", it) }
            book.relay?.let { DetailRow("Relay", it) }
            DetailRow("Index created", java.text.DateFormat.getDateTimeInstance().format(java.util.Date(book.createdAt * 1_000)))
            Text("Event metadata", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            DetailRow("Identifier", book.identifier)
            DetailRow("Coordinate", book.coordinate)
            DetailRow("Event ID", book.id)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

