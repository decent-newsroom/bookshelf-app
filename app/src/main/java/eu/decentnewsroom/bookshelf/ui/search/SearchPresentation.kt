package eu.decentnewsroom.bookshelf.ui.search

import eu.decentnewsroom.bookshelf.data.mercury.BookSearchOutcome
import eu.decentnewsroom.bookshelf.data.mercury.BookSearchStatus

internal fun searchOutcomeMessage(outcome: BookSearchOutcome): String? = when (outcome.status) {
    BookSearchStatus.COMPLETE -> if (outcome.results.isEmpty()) "No matching books in the returned results." else null
    BookSearchStatus.PARTIAL -> "Some search results could not be loaded. Available matches are shown; try again for more."
    BookSearchStatus.UNAVAILABLE -> "Search is temporarily unavailable. Try again shortly."
}
