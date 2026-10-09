package eu.decentnewsroom.bookshelf.ui

import eu.decentnewsroom.bookshelf.data.reader.BookReadingPresentation
import eu.decentnewsroom.bookshelf.data.reader.ReadingProgress
import eu.decentnewsroom.bookshelf.data.reader.resolveBookReadingPresentation
import eu.decentnewsroom.bookshelf.data.reading.ReadingState
import eu.decentnewsroom.bookshelf.domain.BookSummary

/** A transient signer/repository handoff must not display the previous account's lists. */
internal fun activeReadingState(reading: ReadingState, ownerPubkey: String?, signerPubkey: String?): ReadingState =
    if (ownerPubkey == signerPubkey) reading else ReadingState()

/** Pure projection, shared by every cover without opening books or starting relay work. */
internal fun bookReadingPresentations(
    progress: Map<String, ReadingProgress>,
    reading: ReadingState,
    books: List<BookSummary>,
): Map<String, BookReadingPresentation> {
    val summaries = books.associateBy { it.coordinate }
    val tracked = reading.tracked.associateBy { it.bookCoordinate }
    val finished = reading.finished.associateBy { it.bookCoordinate }
    return (progress.keys + tracked.keys + finished.keys).associateWith { coordinate ->
        resolveBookReadingPresentation(progress[coordinate], tracked[coordinate], finished[coordinate], summaries[coordinate])
    }
}
