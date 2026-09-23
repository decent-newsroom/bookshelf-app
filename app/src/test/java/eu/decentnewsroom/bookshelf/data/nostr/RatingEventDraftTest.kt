package eu.decentnewsroom.bookshelf.data.nostr

import eu.decentnewsroom.bookshelf.domain.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingEventDraftTest {
    @Test
    fun targetUsesDeclaredEntityTypeAndDefaultsToBook() {
        val coordinate = "30040:${"a".repeat(64)}:example"

        assertEquals("novel:$coordinate", RatingEventDraft("b".repeat(64), coordinate, 0.8, createdAt = 1, entityType = "novel").targetId)
        assertEquals("book:$coordinate", RatingEventDraft("b".repeat(64), coordinate, 0.8, createdAt = 1, entityType = " ").targetId)
    }

    @Test
    fun revisionRetainsExactOriginalTargetAndFormatsNewRating() {
        val coordinate = "30040:${"a".repeat(64)}:example"
        val originalTarget = "novel:30040:${"A".repeat(64)}:example"
        val draft = RatingEventDraft(
            pubkey = "b".repeat(64),
            publicationCoordinate = coordinate,
            normalizedRating = 0.6,
            content = "Revised opinion",
            createdAt = 1_700_000_001L,
            entityType = "novel",
            targetIdOverride = originalTarget,
        )

        assertEquals(originalTarget, draft.targetId)
        assertEquals(listOf("d", originalTarget), draft.tags.first())
        assertEquals(listOf("rating", "0.6"), draft.tags.last())
    }

    @Test
    fun signedPayloadMustMatchEveryFieldOfPendingDraft() {
        val draft = RatingEventDraft(
            pubkey = "b".repeat(64),
            publicationCoordinate = "30040:${"a".repeat(64)}:example",
            normalizedRating = 0.8,
            content = "Revised opinion",
            createdAt = 1_700_000_001L,
        )
        val matching = NostrEvent(
            pubkey = draft.pubkey,
            createdAt = draft.createdAt,
            kind = draft.kind,
            tags = draft.tags,
            content = draft.content,
        )

        draft.requireMatchingSignedPayload(matching)
        listOf(
            matching.copy(pubkey = "c".repeat(64)),
            matching.copy(createdAt = draft.createdAt + 1),
            matching.copy(kind = draft.kind + 1),
            matching.copy(tags = draft.tags.dropLast(1) + listOf(listOf("rating", "0.2"))),
            matching.copy(tags = draft.tags + listOf(listOf("client", "unexpected"))),
            matching.copy(content = "Different opinion"),
        ).forEach { changed ->
            assertTrue(runCatching { draft.requireMatchingSignedPayload(changed) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}