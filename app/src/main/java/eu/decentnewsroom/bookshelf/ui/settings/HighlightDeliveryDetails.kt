package eu.decentnewsroom.bookshelf.ui.settings

import eu.decentnewsroom.bookshelf.data.highlights.HighlightDeliveryState
import eu.decentnewsroom.bookshelf.data.highlights.HighlightOutboxEntry

internal fun pendingHighlightDetail(entry: HighlightOutboxEntry): String = buildString {
    append("Highlight ${entry.event.id.take(12)}")
    if (!entry.remoteRoutesResolved) append("\nWaiting to discover remote relays.")
    if (entry.remoteRoutesResolved && entry.remote.isEmpty()) append("\nNo remote delivery destinations remain.")
    (entry.local.entries + entry.remote.entries).forEach { (relay, pair) ->
        if (pair.highlight != HighlightDeliveryState.ACCEPTED) {
            append("\n$relay — highlight: ${pair.highlightFailure ?: pair.highlight.description()}")
        }
        if (pair.chapter != HighlightDeliveryState.ACCEPTED) {
            append("\n$relay — cited chapter: ${pair.chapterFailure ?: pair.chapter.description()}")
        }
    }
    // Older queue files have only an entry-level failure reason.
    entry.lastFailure?.let { append("\nLast delivery error: $it") }
}

private fun HighlightDeliveryState.description(): String = when (this) {
    HighlightDeliveryState.PENDING -> "awaiting acknowledgement"
    HighlightDeliveryState.FAILED -> "delivery failed; will retry"
    HighlightDeliveryState.ACCEPTED -> "accepted"
}
