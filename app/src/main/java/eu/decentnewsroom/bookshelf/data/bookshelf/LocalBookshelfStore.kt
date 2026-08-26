package eu.decentnewsroom.bookshelf.data.bookshelf

import eu.decentnewsroom.bookshelf.domain.BookSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocalBookshelfStore {
    private val _directoryTags = MutableStateFlow(BookshelfDirectoryRules.emptyTags())
    val directoryTags: StateFlow<List<List<String>>> = _directoryTags.asStateFlow()

    private val _savedBooks = MutableStateFlow<List<BookSummary>>(emptyList())
    val savedBooks: StateFlow<List<BookSummary>> = _savedBooks.asStateFlow()

    fun replace(tags: List<List<String>>, books: List<BookSummary>) {
        val normalizedTags = BookshelfDirectoryRules.normalizeEditableTags(tags)
        val coordinates =
            BookshelfDirectoryRules
                .extractBookReferences(normalizedTags)
                .mapNotNull { it.coordinate }

        val booksByCoordinate = books.associateBy(BookSummary::coordinate)

        _directoryTags.value = normalizedTags
        _savedBooks.value = coordinates.mapNotNull(booksByCoordinate::get)
    }
    fun toggle(book: BookSummary) {
        val wasSaved = isSaved(book.coordinate)
        _directoryTags.value = BookshelfDirectoryRules.toggleBook(_directoryTags.value, book)
        _savedBooks.value =
            if (wasSaved) {
                _savedBooks.value.filterNot { it.coordinate == book.coordinate }
            } else {
                _savedBooks.value + book
            }
    }

    fun isSaved(coordinate: String): Boolean =
        BookshelfDirectoryRules
            .extractBookReferences(_directoryTags.value)
            .any { it.coordinate == coordinate }
}
