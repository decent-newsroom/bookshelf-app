package eu.decentnewsroom.bookshelf.data.reader


import eu.decentnewsroom.bookshelf.data.mercury.MercuryBookRepository
import kotlinx.coroutines.CancellationException
import eu.decentnewsroom.bookshelf.data.rendering.ChapterHtmlCache
import eu.decentnewsroom.bookshelf.domain.BookChapter
import eu.decentnewsroom.bookshelf.domain.BookDetail
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.domain.OutlineResolution
import eu.decentnewsroom.bookshelf.domain.PublicationOutlineNode
import eu.decentnewsroom.bookshelf.domain.effectiveOutline

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
        val merged = mergeReaderSnapshots(cached, remote)
        val rendered = chapterHtmlCache.renderBook(merged)
        if (isSaved) saveSnapshot(rendered)
        return rendered
    }

    suspend fun cacheOpenedBook(detail: BookDetail) = saveSnapshot(detail)

    suspend fun refresh(book: BookSummary): BookDetail {
        if (!isInternetAvailable()) throw IllegalStateException("Connect to the internet to refresh this book.")
        val cached = offlineBookCache.load(book.coordinate)
        val baseline = listOfNotNull(book, cached?.summary)
            .maxWith(compareBy<BookSummary> { it.createdAt }.thenBy { it.id })
        val remote = repository.refreshBook(baseline)
        check(remote.summary.sectionStreamKnown && !remote.truncated && remote.missingChapterCount == 0) {
            "The full updated book could not be downloaded. Your existing copy has been kept."
        }
        val cachedChapters = cached?.chapters.orEmpty().associateBy { it.reference.coordinate }
        check(remote.chapters.none { chapter ->
            val old = cachedChapters[chapter.reference.coordinate]
            old?.createdAt != null && chapter.createdAt != null &&
                (chapter.createdAt < old.createdAt ||
                    (chapter.createdAt == old.createdAt && chapter.id.orEmpty() < old.id.orEmpty()))
        }) { "Only older chapters are available. Your existing copy has been kept." }
        val rendered = chapterHtmlCache.renderBook(remote)
        // Unlike ordinary opening, refresh must report persistence failures to its caller.
        offlineBookCache.save(rendered)
        return rendered
    }

    private suspend fun saveSnapshot(detail: BookDetail) {
        try {
            val previous = offlineBookCache.load(detail.summary.coordinate)
            // A failed expansion must not replace a more useful durable offline copy.
            if (!detail.summary.sectionStreamKnown && previous != null &&
                previous.chapters.count(BookChapter::available) > detail.chapters.count(BookChapter::available)
            ) return
            offlineBookCache.save(detail)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // A capacity or storage failure must not prevent reading or saving the book itself.
        }
    }

}

object OfflineBookUnavailableException : IllegalStateException()

/** Current complete structure is authoritative; fallback is confined to unresolved matching branches. */
internal fun mergeReaderSnapshots(cached: BookDetail?, remote: BookDetail): BookDetail {
    if (cached == null || cached === remote) return remote
    val cachedByOccurrence = cached.chapters.associateBy { it.occurrenceKey }
    val cachedByCoordinate = cached.chapters.associateBy { it.reference.coordinate }
    val remoteByOccurrence = remote.chapters.associateBy { it.occurrenceKey }
    val remoteByCoordinate = remote.chapters.associateBy { it.reference.coordinate }
    val oldOutline = cached.effectiveOutline()
    val nodes = mutableListOf<PublicationOutlineNode>()
    var grafted = false
    var fallbackNodeCount = 0
    val currentOutline = remote.effectiveOutline()
    for ((nodeIndex, node) in currentOutline.withIndex()) {
        nodes.add(node)
        if (!node.isDivision || node.resolution == OutlineResolution.RESOLVED ||
            node.resolution == OutlineResolution.CYCLE || node.resolution == OutlineResolution.MALFORMED
        ) continue
        // A previous fallback (or a partial resolver result) already carries this branch.
        // Grafting it again would duplicate occurrence keys and reader rows.
        if (currentOutline.getOrNull(nodeIndex + 1)?.depth?.let { it > node.depth } == true) continue
        val oldIndex = oldOutline.indexOfFirst {
            it.occurrenceKey == node.occurrenceKey && it.reference.coordinate == node.reference.coordinate
        }
        if (oldIndex < 0) continue
        val descendants = oldOutline.drop(oldIndex + 1).takeWhile { it.depth > node.depth }
        if (descendants.isEmpty() || currentOutline.size + fallbackNodeCount + descendants.size > 5_000) continue
        nodes[nodes.lastIndex] = node.copy(resolution = OutlineResolution.STALE,
            diagnostic = "Cached division; current structure is unavailable.")
        nodes.addAll(descendants.map {
            if (it.resolution == OutlineResolution.MALFORMED) it else it.copy(resolution = OutlineResolution.STALE)
        })
        fallbackNodeCount += descendants.size
        grafted = true
    }
    if (!grafted) {
        var staleBody = false
        val chapters = remote.chapters.map { chapter ->
            if (chapter.available) chapter else {
                val fallback = cachedByOccurrence[chapter.occurrenceKey]
                    ?: cachedByCoordinate[chapter.reference.coordinate]
                fallback?.takeIf(BookChapter::available)?.let {
                    staleBody = true
                    it.copy(reference = chapter.reference, position = chapter.position,
                        occurrenceKey = chapter.occurrenceKey)
                } ?: chapter
            }
        }
        val available = chapters.count(BookChapter::available)
        return remote.copy(chapters = chapters, availableChapterCount = available,
            missingChapterCount = chapters.size - available, truncated = remote.truncated || staleBody)
    }
    var ordinal = 0
    val outline = nodes.map { node ->
        if (node.chapterIndex != null) node.copy(chapterIndex = ordinal++) else node
    }
    val leaves = outline.filter { it.chapterIndex != null }
    val chapters = leaves.take(500).map { node ->
        val fresh = remoteByOccurrence[node.occurrenceKey] ?: remoteByCoordinate[node.reference.coordinate]
        val old = cachedByOccurrence[node.occurrenceKey] ?: cachedByCoordinate[node.reference.coordinate]
        val chapter = fresh?.takeIf(BookChapter::available) ?: old?.takeIf(BookChapter::available) ?: fresh ?: old
        chapter?.copy(reference = node.reference.asChapter(), position = node.chapterIndex!! + 1,
            occurrenceKey = node.occurrenceKey) ?: BookChapter(node.reference.asChapter(), node.chapterIndex!! + 1,
            false, node.title, null, null, null, null, occurrenceKey = node.occurrenceKey)
    }
    val available = chapters.count(BookChapter::available)
    return remote.copy(
        summary = remote.summary.copy(chapterRefs = leaves.map { it.reference.asChapter() },
            chapterCount = leaves.size, sectionStreamKnown = false),
        outline = outline, chapters = chapters, availableChapterCount = available,
        missingChapterCount = chapters.size - available, truncated = remote.truncated || leaves.size > 500,
    )
}
