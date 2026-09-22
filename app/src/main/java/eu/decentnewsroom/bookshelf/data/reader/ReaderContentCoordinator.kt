package eu.decentnewsroom.bookshelf.data.reader


import eu.decentnewsroom.bookshelf.data.mercury.MercuryBookRepository
import kotlinx.coroutines.CancellationException
import eu.decentnewsroom.bookshelf.data.rendering.ChapterHtmlCache
import eu.decentnewsroom.bookshelf.domain.BookChapter
import eu.decentnewsroom.bookshelf.domain.BookDetail
import eu.decentnewsroom.bookshelf.domain.BookSummary

class ReaderContentCoordinator(
    private val repository: MercuryBookRepository,
    private val offlineBookCache: OfflineBookCache,
    private val chapterHtmlCache: ChapterHtmlCache,
    private val isInternetAvailable: () -> Boolean,
) {
    suspend fun open(book: BookSummary, isSaved: Boolean): BookDetail {
        val cached = if (isSaved) offlineBookCache.load(book.coordinate) else null
        if (!isInternetAvailable()) return cached ?: throw OfflineBookUnavailableException
        val remote = try {
            repository.openBook(book)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            cached ?: throw exception
        }
        val merged = merge(cached, remote)
        val rendered = chapterHtmlCache.renderBook(merged)
        if (isSaved) saveSnapshot(rendered)
        return rendered
    }

    suspend fun cacheOpenedBook(detail: BookDetail) = saveSnapshot(detail)

    private suspend fun saveSnapshot(detail: BookDetail) {
        try {
            offlineBookCache.save(detail)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // A capacity or storage failure must not prevent reading or saving the book itself.
        }
    }
    private fun merge(cached: BookDetail?, remote: BookDetail): BookDetail {
        if (cached == null) return remote
        val cachedByCoordinate = cached.chapters.associateBy { it.reference.coordinate }
        val chapters = remote.chapters.map { chapter ->
            if (chapter.available) chapter
            else cachedByCoordinate[chapter.reference.coordinate]?.takeIf(BookChapter::available) ?: chapter
        }
        val available = chapters.count(BookChapter::available)
        return remote.copy(chapters = chapters, availableChapterCount = available, missingChapterCount = chapters.size - available)
    }
}

object OfflineBookUnavailableException : IllegalStateException()
