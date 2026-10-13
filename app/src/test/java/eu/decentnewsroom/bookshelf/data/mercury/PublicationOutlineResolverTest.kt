package eu.decentnewsroom.bookshelf.data.mercury

import eu.decentnewsroom.bookshelf.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class PublicationOutlineResolverTest {
    private val author = "a".repeat(64)
    private fun coordinate(name: String, kind: Int = 30040) = "$kind:$author:$name"
    private fun event(name: String, vararg children: String) = NostrEvent(
        id = name.padEnd(64, 'b'), pubkey = author, kind = 30040,
        tags = listOf(listOf("d", name), listOf("title", "Title $name")) + children.map { listOf("a", it) },
    )
    private fun root(vararg children: String): BookSummary = requireNotNull(
        MercuryBookRepository(MercuryApiClient(OkHttpClient(), "http://127.0.0.1:1"))
            .mapIndexEvent(event("root", *children)),
    )

    @Test fun mixedTreeUsesPublisherOrderAndChapterOnlyOrdinals() = runBlocking {
        val indexes = listOf(event("book", coordinate("scene", 30041), coordinate("act")),
            event("act", coordinate("last", 30041))).associateBy { coordinate(it.tags[0][1]) }
        val result = PublicationOutlineResolver({ indexes[it.coordinate] }).resolve(root(
            coordinate("preface", 30041), coordinate("book"), coordinate("after", 30041)))
        assertTrue(result.complete)
        assertEquals(listOf("preface", "book", "scene", "act", "last", "after"), result.outline.map { it.reference.identifier })
        assertEquals(listOf(0, null, 1, null, 2, 3), result.outline.map { it.chapterIndex })
        assertEquals(listOf(0, 0, 1, 1, 2, 0), result.outline.map { it.depth })
        assertEquals("Title book", result.outline[1].title)
    }

    @Test fun sharedSubtreesExpandTwiceButFetchOnlyOnceAndHaveDistinctOccurrenceKeys() = runBlocking {
        val indexes = listOf(event("left", coordinate("shared")), event("right", coordinate("shared")),
            event("shared", coordinate("leaf", 30041))).associateBy { coordinate(it.tags[0][1]) }
        val requests = mutableListOf<String>()
        val result = PublicationOutlineResolver({ requests += it.coordinate; indexes[it.coordinate] })
            .resolve(root(coordinate("left"), coordinate("right")))
        assertTrue(result.complete)
        assertEquals(1, requests.count { it == coordinate("shared") })
        val leaves = result.outline.filter { it.chapterIndex != null }
        assertEquals(2, leaves.size)
        assertNotEquals(leaves[0].occurrenceKey, leaves[1].occurrenceKey)
    }

    @Test fun cyclesAndMissingSubtreesPreserveOtherReadableBranches() = runBlocking {
        val result = PublicationOutlineResolver({ if (it.identifier == "loop") event("loop", coordinate("root")) else null })
            .resolve(root(coordinate("loop"), coordinate("missing"), coordinate("leaf", 30041)))
        assertFalse(result.complete)
        assertEquals(OutlineResolution.CYCLE, result.outline[1].resolution)
        assertEquals(OutlineResolution.UNAVAILABLE, result.outline[2].resolution)
        assertEquals(0, result.outline.last().chapterIndex)
    }

    @Test fun parserDeduplicatesSiblingsAndPreservesExactColonIdentifiersAndMixedKinds() {
        val tags = listOf(listOf("a", "30040:${author.uppercase()}:part:one", "wss://relay.example"),
            listOf("a", "30041:$author:leaf"), listOf("a", "30040:$author:part:one"),
            listOf("a", "1:$author:ignored"), listOf("a", "30041:bad:"))
        val children = parsePublicationChildren(tags)
        assertEquals(listOf("part:one", "leaf", ""), children.map { it.identifier })
        assertEquals("wss://relay.example", children.first().relay)
        assertFalse(validPublicationChild(children.last()))
    }

    @Test fun emptyDivisionIsResolvedWithoutInventingAChapter() = runBlocking {
        val result = PublicationOutlineResolver({ event("empty") }).resolve(root(coordinate("empty")))
        assertTrue(result.complete)
        assertEquals(1, result.outline.size)
        assertNull(result.outline.single().chapterIndex)
    }

    @Test fun wrongCoordinateIsRejectedAndLimitsLeaveDiagnostics() = runBlocking {
        val wrong = PublicationOutlineResolver({ event("different") }).resolve(root(coordinate("wanted")))
        assertEquals(OutlineResolution.UNAVAILABLE, wrong.outline.single().resolution)
        val bounded = PublicationOutlineResolver({ event(it.identifier, coordinate("deeper")) },
            PublicationOutlineResolver.Limits(maxDepth = 1)).resolve(root(coordinate("first")))
        assertFalse(bounded.complete)
        assertEquals(OutlineResolution.LIMIT, bounded.outline.last().resolution)
    }

    @Test fun deadlineLeavesTimeoutDiagnosticAndExternalCancellationPropagates() = runBlocking {
        val timed = PublicationOutlineResolver({ delay(100); null },
            PublicationOutlineResolver.Limits(timeoutMillis = 1)).resolve(root(coordinate("slow")))
        assertEquals(OutlineResolution.TIMEOUT, timed.outline.single().resolution)
        try {
            PublicationOutlineResolver({ throw CancellationException("cancel") }).resolve(root(coordinate("cancel")))
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun malformedChildrenDoNotCertifyTheLeafTotal() = runBlocking {
        val result = PublicationOutlineResolver({ null }).resolve(root("30041:bad:", coordinate("valid", 30041)))
        assertFalse(result.complete)
        assertEquals(OutlineResolution.MALFORMED, result.outline.first().resolution)
        assertNull(result.outline.first().chapterIndex)
        assertEquals(0, result.outline.last().chapterIndex)
    }

    @Test fun siblingRequestsAreBoundedAndResponseTimingDoesNotChangeOrder() = runBlocking {
        var active = 0
        var maximum = 0
        val result = PublicationOutlineResolver({ reference ->
            active++
            maximum = maxOf(maximum, active)
            delay(if (reference.identifier == "0") 20 else 1)
            active--
            event(reference.identifier, coordinate("leaf-${reference.identifier}", 30041))
        }).resolve(root(*(0..5).map { coordinate(it.toString()) }.toTypedArray()))
        assertTrue(result.complete)
        assertTrue(maximum > 1)
        assertTrue(maximum <= 4)
        assertEquals((0..5).map { "leaf-$it" }, result.outline.filter { it.chapterIndex != null }.map { it.reference.identifier })
    }

    @Test fun exactNodeCapacityRemainsCompleteAndOverflowIsExplicit() = runBlocking {
        val limits = PublicationOutlineResolver.Limits(maxNodes = 2)
        val exact = PublicationOutlineResolver({ null }, limits).resolve(root(coordinate("one", 30041), coordinate("two", 30041)))
        assertTrue(exact.complete)
        val overflow = PublicationOutlineResolver({ null }, limits).resolve(root(
            coordinate("one", 30041), coordinate("two", 30041), coordinate("three", 30041)))
        assertEquals(2, overflow.outline.size)
        assertFalse(overflow.complete)
        assertEquals(OutlineResolution.LIMIT, overflow.outline.last().resolution)
    }

    @Test fun indexAndRetainedByteBudgetsAreExplicitAndDoNotTriggerRepeatedFetches() = runBlocking {
        var requests = 0
        val indexBound = PublicationOutlineResolver({ requests++; event(it.identifier) },
            PublicationOutlineResolver.Limits(maxIndexes = 2)).resolve(root(coordinate("first"), coordinate("second")))
        assertEquals(1, requests)
        assertFalse(indexBound.complete)
        assertEquals(OutlineResolution.LIMIT, indexBound.outline.last().resolution)
        val byteBound = PublicationOutlineResolver({ event(it.identifier).copy(content = "x".repeat(1000)) },
            PublicationOutlineResolver.Limits(maxRetainedIndexBytes = 100)).resolve(root(coordinate("large")))
        assertFalse(byteBound.complete)
        assertEquals(OutlineResolution.LIMIT, byteBound.outline.single().resolution)
        assertTrue(byteBound.indexes.isEmpty())
    }

    @Test fun referenceBudgetBoundsQueuedDescendantsAndPreservesAvailablePrefix() = runBlocking {
        val result = PublicationOutlineResolver({ event("part", coordinate("one", 30041),
            coordinate("two", 30041), coordinate("three", 30041)) },
            PublicationOutlineResolver.Limits(maxNodes = 2)).resolve(root(coordinate("part")))
        assertFalse(result.complete)
        assertEquals(2, result.outline.size)
        assertEquals(OutlineResolution.LIMIT, result.outline.first().resolution)
        assertEquals(0, result.outline.last().chapterIndex)
    }
}
