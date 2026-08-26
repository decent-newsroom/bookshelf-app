package eu.decentnewsroom.bookshelf.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.decentnewsroom.bookshelf.AppGraph
import eu.decentnewsroom.bookshelf.data.bookshelf.BookshelfDirectoryRules
import eu.decentnewsroom.bookshelf.data.bookshelf.LocalBookshelfStore
import eu.decentnewsroom.bookshelf.data.mercury.MercuryApiException
import eu.decentnewsroom.bookshelf.data.mercury.MercuryBookRepository
import eu.decentnewsroom.bookshelf.data.nostr.BookshelfSyncState
import eu.decentnewsroom.bookshelf.data.reader.ReaderPreferences
import eu.decentnewsroom.bookshelf.data.reader.ReaderSettingsStore
import eu.decentnewsroom.bookshelf.data.reader.ReaderTheme
import eu.decentnewsroom.bookshelf.data.reader.ReadingProgress
import eu.decentnewsroom.bookshelf.domain.BookDetail
import eu.decentnewsroom.bookshelf.domain.BookSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BookshelfViewModel(
    private val repository: MercuryBookRepository = AppGraph.mercuryBooks,
    private val localBookshelf: LocalBookshelfStore = AppGraph.localBookshelf,
    private val readerSettings: ReaderSettingsStore = AppGraph.readerSettings,
) : ViewModel() {
    private val _uiState = MutableStateFlow(BookshelfUiState())
    val uiState: StateFlow<BookshelfUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            localBookshelf.savedBooks.collect { savedBooks ->
                _uiState.update { it.copy(savedBooks = savedBooks) }
            }
        }
        viewModelScope.launch {
            localBookshelf.directoryTags.collect { tags ->
                val coordinates =
                    BookshelfDirectoryRules
                        .extractBookReferences(tags)
                        .mapNotNull { it.coordinate }
                        .toSet()
                _uiState.update { it.copy(savedCoordinates = coordinates) }
            }
        }
        viewModelScope.launch {
            readerSettings.readerPreferences.collect { preferences ->
                _uiState.update { it.copy(readerPreferences = preferences) }
            }
        }
        viewModelScope.launch {
            readerSettings.progress.collect { progress ->
                _uiState.update { it.copy(readingProgress = progress) }
            }
        }
    }

    fun selectTab(tab: BookshelfTab) {
        _uiState.update { it.copy(tab = tab, selectedBook = null, error = null) }
    }

    fun updateQuery(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun submitSearch() {
        val query = _uiState.value.query.trim()
        if (query.length < 2) {
            _uiState.update {
                it.copy(
                    searchMessage = "Enter at least two characters to search.",
                    searchResults = emptyList(),
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSearching = true,
                    searchMessage = null,
                    error = null,
                )
            }

            try {
                val books = repository.search(query)
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        searchResults = books,
                        searchMessage = if (books.isEmpty()) "No matching books." else null,
                    )
                }
            } catch (exception: MercuryApiException) {
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        searchMessage = "Mercury is unavailable.",
                        error = exception.message,
                    )
                }
            }
        }
    }

    fun openBook(book: BookSummary) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingBook = true,
                    selectedBook = null,
                    error = null,
                )
            }

            try {
                val detail = repository.getBook(book.id)
                _uiState.update {
                    it.copy(
                        isLoadingBook = false,
                        selectedBook = detail,
                        error = if (detail == null) "Book not available." else null,
                    )
                }
            } catch (exception: MercuryApiException) {
                _uiState.update {
                    it.copy(
                        isLoadingBook = false,
                        error = exception.message ?: "Mercury is unavailable.",
                    )
                }
            }
        }
    }

    fun closeBook() {
        _uiState.update { it.copy(selectedBook = null, error = null) }
    }

    fun toggleSaved(book: BookSummary) {
        runCatching { localBookshelf.toggle(book) }
            .onFailure { failure ->
                _uiState.update { it.copy(error = failure.message ?: "Could not update My Books.") }
            }
    }

    fun recordReaderProgress(book: BookDetail, firstVisibleItemIndex: Int) {
        readerSettings.recordProgress(book, firstVisibleItemIndex)
    }

    fun setReaderFontSize(fontSizeSp: Float) {
        readerSettings.setFontSizeSp(fontSizeSp)
    }

    fun setReaderLineHeight(lineHeightMultiplier: Float) {
        readerSettings.setLineHeightMultiplier(lineHeightMultiplier)
    }

    fun setReaderTheme(theme: ReaderTheme) {
        readerSettings.setTheme(theme)
    }
}

enum class BookshelfTab {
    Search,
    MyBooks,
    Settings,
}

data class BookshelfUiState(
    val tab: BookshelfTab = BookshelfTab.Search,
    val query: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<BookSummary> = emptyList(),
    val searchMessage: String? = null,
    val savedBooks: List<BookSummary> = emptyList(),
    val savedCoordinates: Set<String> = emptySet(),
    val selectedBook: BookDetail? = null,
    val isLoadingBook: Boolean = false,
    val error: String? = null,
    val syncState: BookshelfSyncState = BookshelfSyncState.NotConfigured,
    val readerPreferences: ReaderPreferences = ReaderPreferences(),
    val readingProgress: Map<String, ReadingProgress> = emptyMap(),
)
