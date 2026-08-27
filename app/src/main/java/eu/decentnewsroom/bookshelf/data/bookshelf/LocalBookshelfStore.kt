package eu.decentnewsroom.bookshelf.data.bookshelf

import android.content.Context
import eu.decentnewsroom.bookshelf.domain.BookSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

data class LocalBookshelfChange(
    val isSaved: Boolean,
    val tags: List<List<String>>,
)

class LocalBookshelfStore private constructor(
    private val file: File,
) {
    constructor(context: Context) : this(storageFile(context.applicationContext.filesDir))

    internal constructor(
        storageRoot: File,
        storageDirectoryName: String = STORAGE_DIRECTORY,
    ) : this(File(File(storageRoot, storageDirectoryName), STORAGE_FILE_NAME))

    private val mutex = Mutex()
    private val initial = readBlocking()

    private val _directoryTags = MutableStateFlow(initial.tags)
    val directoryTags: StateFlow<List<List<String>>> = _directoryTags.asStateFlow()

    private val _savedBooks = MutableStateFlow(initial.books)
    val savedBooks: StateFlow<List<BookSummary>> = _savedBooks.asStateFlow()

    suspend fun replace(tags: List<List<String>>, books: List<BookSummary>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                persistAndApply(tags, books)
            }
        }

    suspend fun merge(tags: List<List<String>>, books: List<BookSummary>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val mergedTags = BookshelfDirectoryRules.mergeEditableTags(_directoryTags.value, tags)
                persistAndApply(mergedTags, _savedBooks.value + books)
            }
        }

    suspend fun toggle(book: BookSummary): LocalBookshelfChange =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val nextTags = BookshelfDirectoryRules.toggleBook(_directoryTags.value, book)
                val isSaved = BookshelfDirectoryRules
                    .extractBookReferences(nextTags)
                    .any { it.coordinate == book.coordinate }
                val nextBooks =
                    if (isSaved) {
                        _savedBooks.value + book
                    } else {
                        _savedBooks.value.filterNot { it.coordinate == book.coordinate }
                    }

                persistAndApply(nextTags, nextBooks)
                LocalBookshelfChange(isSaved = isSaved, tags = nextTags)
            }
        }

    fun isSaved(coordinate: String): Boolean =
        BookshelfDirectoryRules
            .extractBookReferences(_directoryTags.value)
            .any { it.coordinate == coordinate }

    private fun persistAndApply(tags: List<List<String>>, books: List<BookSummary>) {
        val normalizedTags = BookshelfDirectoryRules.normalizeEditableTags(tags)
        val coordinates =
            BookshelfDirectoryRules
                .extractBookReferences(normalizedTags)
                .mapNotNull { it.coordinate }
        val booksByCoordinate = books.associateBy(BookSummary::coordinate)
        val normalizedBooks = coordinates.mapNotNull(booksByCoordinate::get)

        writeBlocking(StoredBookshelf(normalizedTags, normalizedBooks))
        _directoryTags.value = normalizedTags
        _savedBooks.value = normalizedBooks
    }

    private fun readBlocking(): StoredBookshelf =
        runCatching {
            if (!file.isFile) {
                return StoredBookshelf()
            }
            val stored = json.decodeFromString<StoredBookshelf>(file.readText(Charsets.UTF_8))
            val normalizedTags = BookshelfDirectoryRules.normalizeEditableTags(stored.tags)
            val coordinates =
                BookshelfDirectoryRules
                    .extractBookReferences(normalizedTags)
                    .mapNotNull { it.coordinate }
            val booksByCoordinate = stored.books.associateBy(BookSummary::coordinate)
            StoredBookshelf(normalizedTags, coordinates.mapNotNull(booksByCoordinate::get))
        }.getOrDefault(StoredBookshelf())

    private fun writeBlocking(stored: StoredBookshelf) {
        requireNotNull(file.parentFile).mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(json.encodeToString(StoredBookshelf.serializer(), stored), Charsets.UTF_8)
        runCatching {
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }.recoverCatching {
            Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING)
        }.getOrElse {
            temporary.delete()
            throw IllegalStateException("Could not update the local bookshelf.", it)
        }
    }

    @Serializable
    private data class StoredBookshelf(
        val tags: List<List<String>> = BookshelfDirectoryRules.emptyTags(),
        val books: List<BookSummary> = emptyList(),
    )

    private companion object {
        const val STORAGE_DIRECTORY = "bookshelf"
        const val STORAGE_FILE_NAME = "local-v1.json"
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun storageFile(filesRoot: File): File =
            File(File(filesRoot, STORAGE_DIRECTORY), STORAGE_FILE_NAME)
    }
}
