package eu.decentnewsroom.bookshelf.data.highlights

import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.utils.Secp256k1InstanceKotlin
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightEventFactoryTest {
    private val createdAt = 1_700_000_000L
    private val privateKey = ByteArray(32) { 9 }
    private val author = Secp256k1InstanceKotlin.compressedPubKeyFor(privateKey).copyOfRange(1, 33).toHex()

    @Test
    fun createAddsChapterIdentityPublisherSourceAndMentionTags() {
        val chapter = chapter()

        val draft = HighlightEventFactory.create(
            pubkey = author,
            chapter = chapter,
            quote = "A useful passage",
            context = "The surrounding paragraph",
            comment = "Read more at https://example.com/article?utm_source=test&part=2",
            createdAt = createdAt,
        )

        assertEquals(author, draft.pubkey)
        assertEquals(BookKinds.HIGHLIGHT, draft.kind)
        assertEquals("A useful passage", draft.content)
        assertTrue(draft.tags.contains(listOf("a", "${BookKinds.PUBLICATION_CONTENT}:${chapter.pubkey}:chapter-one")))
        assertTrue(draft.tags.contains(listOf("e", chapter.id)))
        assertTrue(draft.tags.contains(listOf("p", chapter.pubkey, "", "publisher")))
        assertTrue(draft.tags.contains(listOf("r", "https://example.com/source", "source")))
        assertTrue(draft.tags.contains(listOf("context", "The surrounding paragraph")))
        assertTrue(draft.tags.contains(listOf("comment", "Read more at https://example.com/article?utm_source=test&part=2")))
        assertTrue(draft.tags.contains(listOf("r", "https://example.com/article?utm_source=test&part=2", "mention")))
    }

    @Test
    fun signedEventRoundTripsOnlyWhenItMatchesTheUnsignedDraft() {
        val draft = HighlightEventFactory.create(
            pubkey = author,
            chapter = chapter(),
            quote = "A useful passage",
            createdAt = createdAt,
        )
        val signed = sign(draft)

        val decoded = HighlightEventFactory.decodeSigned(json(signed), draft)

        assertEquals(signed, decoded)
    }

    @Test
    fun unsignedJsonUsesNostrFieldNamesAndDeclaredKind() {
        val draft = HighlightEventFactory.create(
            pubkey = author,
            chapter = chapter(),
            quote = "A useful passage",
            createdAt = createdAt,
        )

        val json = HighlightEventFactory.unsignedJson(draft)

        assertTrue(json.contains("\"created_at\":$createdAt"))
        assertTrue(json.contains("\"kind\":${BookKinds.HIGHLIGHT}"))
        assertTrue(json.contains("\"content\":\"A useful passage\""))
    }

    private fun chapter(): NostrEvent = signChapter(
        NostrEvent(
            createdAt = createdAt,
            kind = BookKinds.PUBLICATION_CONTENT,
            tags = listOf(
                listOf("d", "chapter-one"),
                listOf("r", "https://example.com/source?utm_source=test"),
            ),
            content = "A chapter passage",
            pubkey = author,
        ),
    )

    private fun signChapter(event: NostrEvent): NostrEvent {
        val tagArray = event.tags.map { it.toTypedArray() }.toTypedArray()
        val id = EventHasher.hashId(event.pubkey, event.createdAt, event.kind, tagArray, event.content)
        return event.copy(
            id = id,
            sig = Secp256k1InstanceKotlin.signSchnorr(id.hexBytes(), privateKey, ByteArray(32)).toHex(),
        )
    }
    private fun sign(draft: HighlightEventDraft): NostrEvent {
        val tagArray = draft.tags.map { it.toTypedArray() }.toTypedArray()
        val id = EventHasher.hashId(draft.pubkey, draft.createdAt, draft.kind, tagArray, draft.content)
        return NostrEvent(
            id = id,
            pubkey = draft.pubkey,
            createdAt = draft.createdAt,
            kind = draft.kind,
            tags = draft.tags,
            content = draft.content,
            sig = Secp256k1InstanceKotlin.signSchnorr(id.hexBytes(), privateKey, ByteArray(32)).toHex(),
        )
    }

    private fun json(event: NostrEvent): String = kotlinx.serialization.json.Json.encodeToString(NostrEvent.serializer(), event)

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
