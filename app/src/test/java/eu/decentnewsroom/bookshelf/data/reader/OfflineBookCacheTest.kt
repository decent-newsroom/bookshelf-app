package eu.decentnewsroom.bookshelf.data.reader

import eu.decentnewsroom.bookshelf.domain.PublicationChildReference
import eu.decentnewsroom.bookshelf.domain.PublicationOutlineNode
import eu.decentnewsroom.bookshelf.domain.OutlineResolution
import eu.decentnewsroom.bookshelf.domain.publicationOccurrenceKey
import eu.decentnewsroom.bookshelf.domain.BookChapter
import eu.decentnewsroom.bookshelf.domain.BookDetail
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.domain.ChapterReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OfflineBookCacheTest {
    @Test
    fun legacyFiveHundredSectionPrefixRemainsReadableWithoutClaimingBookEnd() = runBlocking {
        val root = Files.createTempDirectory("reader-cache-legacy-cap").toFile()
        try {
            val cache = OfflineBookCache(root)
            val book = bookWithSections(500)
            cache.store(book)
            removeKnownStreamFlag(root)

            val loaded = requireNotNull(cache.load(book.summary.coordinate))
            assertEquals(500, loaded.chapters.size)
            assertEquals("<p>cached</p>", loaded.chapters.first().renderedHtml)
            assertTrue(loaded.truncated)
            assertTrue(loaded.summary.sectionStreamKnown.not())

            cache.store(book)
            val refreshed = requireNotNull(cache.load(book.summary.coordinate))
            assertTrue(refreshed.summary.sectionStreamKnown)
            assertTrue(refreshed.truncated.not())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun legacyShortBookKeepsKnownStreamCompatibility() = runBlocking {
        val root = Files.createTempDirectory("reader-cache-legacy-short").toFile()
        try {
            val cache = OfflineBookCache(root)
            val book = bookWithSections(499)
            cache.store(book)
            removeKnownStreamFlag(root)

            val loaded = requireNotNull(cache.load(book.summary.coordinate))
            assertTrue(loaded.summary.sectionStreamKnown)
            assertTrue(loaded.truncated.not())
            assertEquals(499, loaded.chapters.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun roundTripOmitsSourceEventAndPreservesRenderedReaderContent() = runBlocking {
        val root = Files.createTempDirectory("reader-cache").toFile()
        try {
            val book = bookDetail()
            val cache = OfflineBookCache(root)

            cache.store(book)

            val loaded = cache.load(book.summary.coordinate)
            assertEquals(book.summary, loaded?.summary)
            assertEquals(book.chapters.size, loaded?.chapters?.size)
            assertEquals("<p>cached</p>", loaded?.chapters?.single()?.renderedHtml)
            assertNull(loaded?.chapters?.single()?.sourceEvent)
            assertEquals(1, cache.stats().entryCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptDataIsAnUnreadableCacheMiss() = runBlocking {
        val root = Files.createTempDirectory("reader-cache-corrupt").toFile()
        try {
            val cache = OfflineBookCache(root)
            val coordinate = bookDetail().summary.coordinate
            cache.store(bookDetail())
            val entry = root.resolve("bookshelf/reader-cache-v1").listFiles()!!.single()
            entry.writeText("not json")
            assertNull(cache.load(coordinate))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun removeAndClearAreIndependentAndAtomicTemporaryFilesDoNotCount() = runBlocking {
        val root = Files.createTempDirectory("reader-cache-clear").toFile()
        try {
            val cache = OfflineBookCache(root)
            val book = bookDetail()
            cache.store(book)
            val directory = root.resolve("bookshelf/reader-cache-v1")
            File(directory, "unrelated.tmp").writeText("temporary")
            assertEquals(1, cache.stats().entryCount)
            cache.remove(book.summary.coordinate)
            assertNull(cache.load(book.summary.coordinate))
            cache.store(book)
            cache.clear()
            assertEquals(0, cache.stats().entryCount)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nestedSnapshotPreservesDivisionAndLeafOccurrenceWithoutSignedSource() = runBlocking {
        val root = Files.createTempDirectory("nested-reader-cache").toFile()
        try {
            val cache = OfflineBookCache(root)
            val book = nestedBook()
            cache.save(book)
            val loaded = requireNotNull(cache.load(book.summary.coordinate))
            assertEquals(book.outline, loaded.outline)
            assertEquals(book.chapters.single().occurrenceKey, loaded.chapters.single().occurrenceKey)
            assertNull(loaded.chapters.single().sourceEvent)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun rejectsSnapshotWithInvalidParentPath() = runBlocking {
        val root = Files.createTempDirectory("nested-reader-invalid").toFile()
        try {
            val cache = OfflineBookCache(root)
            val book = nestedBook()
            cache.save(book)
            val entry = root.resolve("bookshelf/reader-cache-v1").listFiles()!!.single()
            entry.writeText(entry.readText().replace(book.outline.first().occurrenceKey, "invalid-parent"))
            assertNull(cache.load(book.summary.coordinate))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun missingSubtreeUsesStaleCacheButCompleteRemovalIsAuthoritative() {
        val cached = nestedBook()
        val unresolved = cached.copy(
            summary = cached.summary.copy(chapterRefs = emptyList(), chapterCount = 0, sectionStreamKnown = false),
            chapters = emptyList(), availableChapterCount = 0,
            outline = listOf(cached.outline.first().copy(resolution = OutlineResolution.UNAVAILABLE)),
        )
        val merged = mergeReaderSnapshots(cached, unresolved)
        assertEquals(1, merged.chapters.size)
        assertEquals(OutlineResolution.STALE, merged.outline.first().resolution)
        assertTrue(!merged.summary.sectionStreamKnown)
        assertEquals(cached.chapters.single().occurrenceKey, merged.chapters.single().occurrenceKey)
        val removed = unresolved.copy(
            summary = unresolved.summary.copy(sectionStreamKnown = true),
            outline = listOf(cached.outline.first()),
        )
        assertTrue(mergeReaderSnapshots(cached, removed).chapters.isEmpty())
    }

    @Test
    fun staleSubtreeMergeIsIdempotentAfterRemoteFailureOrRepeatedRefresh() {
        val cached = nestedBook()
        val partial = cached.copy(
            summary = cached.summary.copy(chapterRefs = emptyList(), chapterCount = 0, sectionStreamKnown = false),
            chapters = emptyList(), availableChapterCount = 0,
            outline = listOf(cached.outline.first().copy(resolution = OutlineResolution.UNAVAILABLE)),
        )
        val stale = mergeReaderSnapshots(cached, partial)
        assertEquals(stale, mergeReaderSnapshots(stale, stale))
        val mergedAgain = mergeReaderSnapshots(stale, stale.copy())
        assertEquals(stale.outline, mergedAgain.outline)
        assertEquals(stale.chapters, mergedAgain.chapters)
        assertEquals(mergedAgain.outline.size, mergedAgain.outline.map { it.occurrenceKey }.distinct().size)
    }

    @Test
    fun staleGraftBudgetIncludesFreshNodesThatFollowTheBranch() {
        val cached = nestedBook()
        val division = cached.outline.first()
        val remaining = (1 until 5_000).map { index ->
            val ref = division.reference.copy(coordinate = "30040:${division.reference.pubkey}:act-$index",
                identifier = "act-$index")
            division.copy(reference = ref,
                occurrenceKey = publicationOccurrenceKey(cached.summary.coordinate, ref.coordinate))
        }
        val partial = cached.copy(
            summary = cached.summary.copy(chapterRefs = emptyList(), chapterCount = 0, sectionStreamKnown = false),
            chapters = emptyList(), availableChapterCount = 0,
            outline = listOf(division.copy(resolution = OutlineResolution.UNAVAILABLE,
                diagnostic = "Unavailable")) + remaining,
        )
        val merged = mergeReaderSnapshots(cached, partial)
        assertEquals(5_000, merged.outline.size)
        assertEquals(OutlineResolution.UNAVAILABLE, merged.outline.first().resolution)
        assertEquals("Unavailable", merged.outline.first().diagnostic)
        assertTrue(merged.chapters.isEmpty())
        assertTrue(!merged.summary.sectionStreamKnown)
    }

    @Test
    fun rejectsSnapshotClaimingKnownOrderForUnavailableDivision() = runBlocking {
        val root = Files.createTempDirectory("nested-reader-false-total").toFile()
        try {
            val cache = OfflineBookCache(root)
            val book = nestedBook()
            cache.save(book)
            val entry = root.resolve("bookshelf/reader-cache-v1").listFiles()!!.single()
            entry.writeText(entry.readText().replace("\"resolution\":\"RESOLVED\"", "\"resolution\":\"UNAVAILABLE\""))
            assertNull(cache.load(book.summary.coordinate))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun resumePrefersRepeatedOccurrenceThenFallsBackToFirstMatchingCoordinate() {
        val original = nestedBook()
        val first = original.chapters.single()
        val repeated = original.copy(chapters = listOf(first, first.copy(occurrenceKey = "second-occurrence")),
            summary = original.summary.copy(chapterRefs = listOf(first.reference, first.reference)))
        val progress = ReadingProgress(repeated.summary.coordinate, 0, 2, 1,
            chapterCoordinate = first.reference.coordinate, readerEntryKey = "second-occurrence")
        assertEquals(1, resolveReaderChapterIndex(progress, repeated, 2, "fingerprint"))
        assertEquals(0, resolveReaderChapterIndex(progress.copy(readerEntryKey = "removed"), repeated, 2, "fingerprint"))
        // A division retains its preceding leaf position, including a repeated occurrence.
        val headingProgress = progress.copy(currentChapterIndex = 1, readerEntryKey = original.outline.first().occurrenceKey,
            publicationFingerprint = "fingerprint")
        assertEquals(1, resolveReaderChapterIndex(headingProgress, repeated, 2, "fingerprint"))
        assertEquals(0, resolveReaderChapterIndex(headingProgress, repeated, 2, "changed-edition"))
    }

    private fun nestedBook(): BookDetail {
        val original = bookDetail()
        val pubkey = original.summary.pubkey
        val divisionRef = PublicationChildReference(30040, "30040:$pubkey:act", pubkey, "act")
        val leafRef = original.chapters.single().reference.copy(coordinate = "30041:$pubkey:scene", identifier = "scene")
        val divisionKey = publicationOccurrenceKey(original.summary.coordinate, divisionRef.coordinate)
        val leafKey = publicationOccurrenceKey(divisionKey, leafRef.coordinate)
        return original.copy(
            summary = original.summary.copy(chapterRefs = listOf(leafRef), childRefs = listOf(divisionRef),
                structureFingerprint = "nested"),
            chapters = listOf(original.chapters.single().copy(reference = leafRef, occurrenceKey = leafKey)),
            outline = listOf(
                PublicationOutlineNode(divisionKey, reference = divisionRef, title = "Act I"),
                PublicationOutlineNode(leafKey, parentKey = divisionKey,
                    reference = PublicationChildReference.fromChapter(leafRef), depth = 1,
                    title = "Scene I", chapterIndex = 0),
            ),
        )
    }

    private fun bookDetail(): BookDetail {
        val pubkey = "1".repeat(64)
        val coordinate = "${BookKinds.PUBLICATION_INDEX}:$pubkey:offline"
        val reference = ChapterReference(coordinate, pubkey, "chapter-1", null, "event")
        val summary = BookSummary(
            id = "a".repeat(64), coordinate = coordinate, pubkey = pubkey, identifier = "offline",
            title = "Offline", summary = "Summary", authors = listOf("Author"), coverImageUrl = null,
            sourceUrl = null, language = "en", releaseDate = null, version = null, type = "book",
            topics = emptyList(), relay = null, createdAt = 1, chapterCount = 1, chapterRefs = listOf(reference),
        )
        return BookDetail(
            summary = summary,
            chapters = listOf(BookChapter(reference, 0, true, "Chapter", null, "source", "id", 1, "<p>cached</p>")),
            availableChapterCount = 1,
            missingChapterCount = 0,
            truncated = false,
        )
    }

    private fun bookWithSections(count: Int): BookDetail {
        val original = bookDetail()
        val reference = original.summary.chapterRefs.single()
        val references = (0 until count).map { index -> reference.copy(
            coordinate = "30041:${reference.pubkey}:chapter-$index", identifier = "chapter-$index",
        ) }
        return original.copy(
            summary = original.summary.copy(chapterCount = count, chapterRefs = references),
            chapters = references.mapIndexed { index, ref -> original.chapters.single().copy(reference = ref, position = index) },
            availableChapterCount = count,
        )
    }

    /** Recreate the pre-tracking v1 wire shape, including its absent default field. */
    private fun removeKnownStreamFlag(root: File) {
        val entry = root.resolve("bookshelf/reader-cache-v1").listFiles()!!.single()
        val encoded = Json.parseToJsonElement(entry.readText()).jsonObject
        val legacySummary = JsonObject(encoded.getValue("summary").jsonObject - "sectionStreamKnown")
        entry.writeText(JsonObject((encoded - "outline") + mapOf(
            "summary" to legacySummary, "version" to kotlinx.serialization.json.JsonPrimitive(1),
        )).toString())
    }
}

