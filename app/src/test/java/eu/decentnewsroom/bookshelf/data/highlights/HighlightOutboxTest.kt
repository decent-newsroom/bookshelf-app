package eu.decentnewsroom.bookshelf.data.highlights

import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.utils.Secp256k1InstanceKotlin
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HighlightOutboxTest {
    private val createdAt = 1_700_000_000L
    private val privateKey = ByteArray(32) { 7 }
    private val pubkey = Secp256k1InstanceKotlin.compressedPubKeyFor(privateKey).copyOfRange(1, 33).toHex()

    @Test
    fun persistsBothSignedEventsAndThePrivateHighlightAssociation() = runBlocking {
        val directory = Files.createTempDirectory("highlight-outbox-test").toFile()
        val file = File(directory, "highlight-outbox-v1.json")
        val chapter = chapter()
        val highlight = highlight(chapter)

        val queued = HighlightOutbox(file).enqueue(highlight, chapter, localHighlightId = "private-42")
        val restored = HighlightOutbox(file).entries().single()

        assertEquals(highlight, queued.event)
        assertEquals(chapter, restored.chapterEvent)
        assertEquals("private-42", restored.localHighlightId)
        assertTrue(file.isFile)
    }

    @Test
    fun rejectsAHighlightWhoseTagsDoNotLinkToTheExactChapter() = runBlocking {
        val directory = Files.createTempDirectory("highlight-outbox-test").toFile()
        val chapter = chapter()
        val unlinked = signed(
            kind = 9802,
            tags = listOf(listOf("a", "${BookKinds.PUBLICATION_CONTENT}:$pubkey:chapter-one")),
            content = "Quoted passage",
        )

        val failure = runCatching { HighlightOutbox(File(directory, "outbox.json")).enqueue(unlinked, chapter) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun corruptQueueIsSurfacedWithoutReplacingTheFile() = runBlocking {
        val directory = Files.createTempDirectory("highlight-outbox-test").toFile()
        val file = File(directory, "highlight-outbox-v1.json").apply { writeText("not json") }

        val failure = runCatching { HighlightOutbox(file).entries() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("not json", file.readText())
    }

    private fun chapter(): NostrEvent = signed(
        kind = BookKinds.PUBLICATION_CONTENT,
        tags = listOf(listOf("d", "chapter-one")),
        content = "A chapter passage",
    )

    private fun highlight(chapter: NostrEvent): NostrEvent = signed(
        kind = 9802,
        tags = listOf(
            listOf("a", "${BookKinds.PUBLICATION_CONTENT}:${chapter.pubkey}:chapter-one"),
            listOf("e", chapter.id),
        ),
        content = "A chapter passage",
    )

    private fun signed(kind: Int, tags: List<List<String>>, content: String): NostrEvent {
        val quartzTags = tags.map { it.toTypedArray() }.toTypedArray()
        val hash = EventHasher.hashId(pubkey, createdAt, kind, quartzTags, content)
        return NostrEvent(
            id = hash,
            pubkey = pubkey,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = Secp256k1InstanceKotlin.signSchnorr(hash.hexBytes(), privateKey, ByteArray(32)).toHex(),
        )
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
