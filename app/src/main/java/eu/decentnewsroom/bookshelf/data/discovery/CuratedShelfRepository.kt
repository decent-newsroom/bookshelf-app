package eu.decentnewsroom.bookshelf.data.discovery

import eu.decentnewsroom.bookshelf.data.mercury.MercuryBookRepository
import eu.decentnewsroom.bookshelf.domain.BookReference
import eu.decentnewsroom.bookshelf.domain.BookSummary
import kotlinx.coroutines.CancellationException

class CuratedShelfRepository(
    private val bookRepository: MercuryBookRepository,
    private val cache: ShelfMetadataCache,
    private val catalog: List<CuratedShelfSpec> = CuratedShelfCatalog.shelves,
) {
    suspend fun loadCached(): CuratedShelfLoadResult {
        val cached = cache.read()
        val decoded = decodedCatalog()
        val staleReferences = staleReferences(decoded, cached)

        return buildResult(
            decoded = decoded,
            booksByCoordinate = cached.mapValues { it.value.book },
            servedFromCache = true,
            needsRefresh = staleReferences.isNotEmpty(),
        )
    }

    suspend fun refresh(): CuratedShelfLoadResult {
        val cached = cache.read()
        val decoded = decodedCatalog()
        val staleReferences = staleReferences(decoded, cached)
        var refreshed = emptyList<BookSummary>()
        var error: String? = null

        if (staleReferences.isNotEmpty()) {
            try {
                refreshed = bookRepository.getBooksForReferences(staleReferences)
                if (refreshed.isNotEmpty()) {
                    cache.merge(refreshed)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                error = failure.message ?: "Mercury is unavailable."
            }
        }

        val booksByCoordinate = cached.mapValues { it.value.book }.toMutableMap()
        refreshed.forEach { booksByCoordinate[it.coordinate] = it }
        val freshCoordinates =
            cached.values.filter(cache::isFresh).mapTo(mutableSetOf()) { it.book.coordinate }
                .apply { addAll(refreshed.map(BookSummary::coordinate)) }
        val stillNeedsRefresh = decoded.any { (_, reference) -> reference.coordinate !in freshCoordinates }

        return buildResult(
            decoded = decoded,
            booksByCoordinate = booksByCoordinate,
            servedFromCache = refreshed.isEmpty(),
            needsRefresh = stillNeedsRefresh,
            error = error,
        )
    }

    private fun decodedCatalog(): List<Pair<String, BookReference>> =
        catalog.flatMap { shelf ->
            shelf.publicationNaddrs.mapNotNull { naddr ->
                NaddrPublicationReferenceDecoder.decode(naddr)?.let { naddr to it }
            }
        }

    private fun staleReferences(
        decoded: List<Pair<String, BookReference>>,
        cached: Map<String, CachedShelfBook>,
    ): List<BookReference> =
        decoded.map { it.second }.filter { reference ->
            val coordinate = reference.coordinate ?: return@filter false
            cached[coordinate]?.let(cache::isFresh) != true
        }.distinctBy(BookReference::coordinate)

    private fun buildResult(
        decoded: List<Pair<String, BookReference>>,
        booksByCoordinate: Map<String, BookSummary>,
        servedFromCache: Boolean,
        needsRefresh: Boolean,
        error: String? = null,
    ): CuratedShelfLoadResult {
        val decodedByNaddr = decoded.associate { it.first to it.second }
        val invalid =
            catalog.flatMap(CuratedShelfSpec::publicationNaddrs)
                .filterNot(decodedByNaddr::containsKey)
        val shelves = catalog.mapNotNull { shelf ->
            val books = shelf.publicationNaddrs.mapNotNull { naddr ->
                decodedByNaddr[naddr]?.coordinate?.let(booksByCoordinate::get)
            }
            CuratedShelf(shelf.id, shelf.title, books).takeIf { it.books.isNotEmpty() }
        }
        val unresolved =
            decoded.filter { (_, reference) -> reference.coordinate !in booksByCoordinate }
                .map { it.first }

        return CuratedShelfLoadResult(
            shelves = shelves,
            unresolvedNaddrs = (invalid + unresolved).distinct(),
            servedFromCache = servedFromCache,
            needsRefresh = needsRefresh,
            error = error,
        )
    }
}

data class CuratedShelf(val id: String, val title: String, val books: List<BookSummary>)
data class CuratedShelfLoadResult(
    val shelves: List<CuratedShelf>,
    val unresolvedNaddrs: List<String> = emptyList(),
    val servedFromCache: Boolean = false,
    val needsRefresh: Boolean = false,
    val error: String? = null,
)