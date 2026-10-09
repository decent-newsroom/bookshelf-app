package eu.decentnewsroom.bookshelf.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.decentnewsroom.bookshelf.data.reading.ReadingState
import eu.decentnewsroom.bookshelf.data.reading.TrackedBook
import eu.decentnewsroom.bookshelf.ui.reading.ReadingTrackingControls

@Composable
internal fun ReaderTrackingSheet(
    bookTitle: String,
    progressLabel: String,
    tracked: TrackedBook?,
    streamKnown: Boolean,
    readingState: ReadingState,
    onTrack: () -> Unit,
    onReset: () -> Unit,
    onStop: () -> Unit,
    onSync: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Track progress", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(bookTitle, style = MaterialTheme.typography.titleMedium)
        Text(progressLabel, style = MaterialTheme.typography.bodyMedium)
        Text(
            "Your reading position is saved automatically on this device. Tracking adds this book to Reading now on Home and Reading in My Books, and remembers the furthest section you reach.",
            style = MaterialTheme.typography.bodyMedium,
        )
        ReadingTrackingControls(
            tracked = tracked,
            streamKnown = streamKnown,
            deviceOnly = readingState.preferences.readingDeviceOnly,
            onTrack = onTrack,
            onReset = onReset,
            onStop = onStop,
            onSync = onSync,
        )
        Text(
            if (readingState.preferences.readingDeviceOnly) "New tracking entries stay on this device."
            else "New tracking entries are shared publicly on Nostr for other devices to read.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Reset starts section tracking from the beginning. Stop removes this book from the reading list. Both keep your saved reading position and finished history.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Change sharing in Settings → Reading progress & privacy.", style = MaterialTheme.typography.bodySmall)
        if (readingState.isSyncing) Text("Syncing reading lists…", style = MaterialTheme.typography.bodySmall)
        else if (readingState.pendingCount > 0) Text("${readingState.pendingCount} reading changes pending sync", style = MaterialTheme.typography.bodySmall)
        readingState.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
