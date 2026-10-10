package eu.decentnewsroom.bookshelf.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.decentnewsroom.bookshelf.data.reader.BookReadingPresentation
import eu.decentnewsroom.bookshelf.data.reader.ReadingProgress
import eu.decentnewsroom.bookshelf.data.reading.ReadingState
import eu.decentnewsroom.bookshelf.data.reading.TrackedBook
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.ui.books.chapterReadingProgressLabel
import eu.decentnewsroom.bookshelf.ui.components.SecondaryButton
import eu.decentnewsroom.bookshelf.ui.theme.ReaderColors

/** The same position and tracking controls in the reader header and overlay menu. */
@Composable
internal fun ReaderProgressControls(
    progress: ReadingProgress,
    presentation: BookReadingPresentation,
    book: BookSummary,
    tracked: TrackedBook?,
    streamKnown: Boolean,
    readingState: ReadingState,
    isSignedIn: Boolean,
    colors: ReaderColors,
    onTrack: () -> Unit,
    onReset: () -> Unit,
    onStop: () -> Unit,
    onSync: () -> Unit,
) {
    val position = readerPositionPresentation(progress, presentation, book)
    val deviceOnly = !isSignedIn || readingState.preferences.readingDeviceOnly
    val canSync = tracked?.isPublic == true || (tracked == null && !deviceOnly) ||
        (isSignedIn && readingState.pendingCount > 0)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Reading progress",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            color = colors.text,
        )
        position.fraction?.let { fraction ->
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(999.dp)),
                color = colors.accent,
                trackColor = colors.track,
            )
        }
        Text(chapterReadingProgressLabel(progress, position), style = MaterialTheme.typography.labelMedium, color = colors.muted)
        readerFurthestTrackedLabel(progress, tracked, book)?.let { label ->
            Text(label, style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        Text(
            text = when {
                tracked != null -> "Tracking · ${if (tracked.isPublic) "Public on Nostr" else "On this device"}"
                deviceOnly -> "New tracking · On this device"
                else -> "New tracking · Public on Nostr"
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (tracked == null) {
                Button(onClick = onTrack, enabled = streamKnown, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Track progress")
                }
            } else {
                SecondaryButton(onClick = onReset, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Reset tracking") }
                SecondaryButton(onClick = onStop, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Stop tracking") }
            }
            if (canSync) {
                SecondaryButton(onClick = onSync, enabled = !readingState.isSyncing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Sync")
                }
            }
        }
        if (tracked == null && !streamKnown) {
            Text("Section order unavailable; tracking isn't ready.", style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        // Delivery belongs to the tracked entry; pending counts and errors belong to all reading lists.
        if (tracked?.isPublic == true) {
            Text(tracked.status.ifBlank { "Public sync pending" }, style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        if (readingState.isSyncing) {
            Text("Syncing reading lists…", style = MaterialTheme.typography.bodySmall, color = colors.muted)
        } else if (readingState.pendingCount > 0) {
            Text("${readingState.pendingCount} reading changes pending sync", style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        readingState.error?.let { error ->
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
