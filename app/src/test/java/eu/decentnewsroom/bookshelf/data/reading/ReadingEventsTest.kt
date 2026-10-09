package eu.decentnewsroom.bookshelf.data.reading

import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.utils.Secp256k1InstanceKotlin
import eu.decentnewsroom.bookshelf.data.nostr.AndroidExternalSigner
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ReadingEventsTest {
    private val privateKey = ByteArray(32) { 1 }
    private val pubkey = Secp256k1InstanceKotlin.compressedPubKeyFor(privateKey).copyOfRange(1, 33).toHex()
    private val coordinate = "30040:$pubkey:edition"
    private val now = 1_700_000_000L

    @Test
    fun suppliedReadingTupleRoundTripsWithOptionalSection() {
        val tracked = TrackedBook(coordinate, position = 0, total = 37, sectionId = "f".repeat(64), updatedAt = now)
        val draft = ReadingEvents.snapshotDraft(pubkey, listOf(tracked), now)
        val parsed = ReadingEvents.parseSnapshot(draft).single()
        assertEquals(listOf("book", coordinate, "0", "37", "f".repeat(64), now.toString()), draft.tags.first())
        assertEquals(tracked.copy(isPublic = true, status = "Public"), parsed)
        val noSection = ReadingEvents.snapshotDraft(pubkey, listOf(tracked.copy(sectionId = null)), now)
        assertNull(ReadingEvents.parseSnapshot(noSection).single().sectionId)
        assertTrue(ReadingEvents.parseSnapshot(ReadingEvents.snapshotDraft(pubkey, emptyList(), now)).isEmpty())
    }

    @Test
    fun extensionAndMalformedTagsArePreservedWithoutInterpretingProgress() {
        val valid = listOf("book", coordinate, "3", "37", "", now.toString())
        val malformed = listOf("book", "30040:$pubkey:other", "bad", "37")
        val extension = listOf("unknown", "some-value")
        val event = NostrEvent(kind = BookKinds.READING_LIST, createdAt = now,
            tags = listOf(valid, listOf("a", coordinate), malformed, extension))
        assertEquals(1, ReadingEvents.parseSnapshot(event).size)
        val preserved = ReadingEvents.preservedSnapshotTags(event)
        assertEquals(listOf(malformed, extension), preserved)
        val rebuilt = ReadingEvents.snapshotDraft(pubkey, ReadingEvents.parseSnapshot(event), now + 1, preserved)
        assertTrue(rebuilt.tags.contains(malformed))
        assertTrue(rebuilt.tags.contains(extension))
    }

    @Test
    fun snapshotRejectsInvalidOrdinalsAndDeduplicatesCoordinates() {
        val event = NostrEvent(kind = BookKinds.READING_LIST, tags = listOf(
            listOf("book", coordinate, "-1", "2"), listOf("book", coordinate, "2", "2"),
            listOf("book", coordinate, "0", "0"), listOf("book", "30040:bad:edition", "0", "1"),
            listOf("book", coordinate, "1", "2"), listOf("book", coordinate, "0", "2"),
        ))
        assertEquals(1, ReadingEvents.parseSnapshot(event).single().position)
    }

    @Test
    fun finishedLabelsRequireReadWithinUgcNamespace() {
        val draft = ReadingEvents.finishedDraft(pubkey, coordinate, now)
        assertEquals(BookKinds.FINISHED_LABEL, draft.kind)
        assertEquals("", draft.content)
        assertEquals(coordinate, ReadingEvents.parseFinished(draft).single().bookCoordinate)
        assertTrue(ReadingEvents.parseFinished(draft.copy(tags = listOf(listOf("l", "read", "other"), listOf("a", coordinate)))).isEmpty())
    }

    @Test
    fun latestSnapshotUsesLowestIdForEqualTimestampAndNeverUnionsBooks() {
        val older = NostrEvent(id = "0".repeat(64), kind = BookKinds.READING_LIST, createdAt = now - 1)
        val newest = older.copy(id = "a".repeat(64), createdAt = now)
        assertEquals(newest, ReadingEvents.newestSnapshot(listOf(older, newest, newest.copy(id = "b".repeat(64)))))
    }

    @Test
    fun unsupportedSnapshotContentCannotBecomeAnEmptyBaseline() {
        assertThrows(IllegalArgumentException::class.java) {
            ReadingEvents.parseSnapshot(NostrEvent(kind = BookKinds.READING_LIST, content = "opaque foreign format"))
        }
    }

    @Test
    fun signedReadingPayloadMustMatchDraftExactly() {
        val draft = ReadingEvents.finishedDraft(pubkey, coordinate, now)
        val signed = sign(draft)
        assertEquals(signed, ReadingEvents.decodeSigned(Json.encodeToString(signed), draft))
        val altered = sign(draft.copy(tags = draft.tags + listOf(listOf("unexpected", "tag"))))
        assertThrows(IllegalArgumentException::class.java) {
            ReadingEvents.decodeSigned(Json.encodeToString(altered), draft)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReadingEvents.decodeSigned(Json.encodeToString(signed.copy(sig = "0".repeat(128))), draft)
        }
    }

    @Test
    fun backgroundProviderSignatureOnlyResponseReconstructsSignedEvent() {
        val draft = ReadingEvents.snapshotDraft(pubkey, emptyList(), now)
        val signed = sign(draft)
        val reconstructed = AndroidExternalSigner.signedEventJsonFromProvider(draft, null, signed.sig)
        assertNotNull(reconstructed)
        assertEquals(signed, ReadingEvents.decodeSigned(requireNotNull(reconstructed), draft))
        assertNull(AndroidExternalSigner.signedEventJsonFromProvider(draft, null, "invalid"))
    }

    private fun sign(draft: NostrEvent): NostrEvent {
        val id = EventHasher.hashId(draft.pubkey, draft.createdAt, draft.kind,
            draft.tags.map { it.toTypedArray() }.toTypedArray(), draft.content)
        val sig = Secp256k1InstanceKotlin.signSchnorr(id.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            privateKey, ByteArray(32)).toHex()
        return draft.copy(id = id, sig = sig)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
