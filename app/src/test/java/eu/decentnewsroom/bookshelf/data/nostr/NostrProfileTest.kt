package eu.decentnewsroom.bookshelf.data.nostr

import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.utils.Secp256k1InstanceKotlin
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NostrProfileTest {
    private val privateKey = ByteArray(32) { 7 }
    private val pubkey = Secp256k1InstanceKotlin.compressedPubKeyFor(privateKey).copyOfRange(1, 33).toHex()

    @Test
    fun profileUsesDisplayNameBeforeNameAndNormalizesWhitespace() {
        val profile = profileEvent(
            content = """{"name":"alice","display_name":"  Alice   Example  "}""",
        ).toProfile()

        assertEquals("alice", profile?.name)
        assertEquals("Alice Example", profile?.displayName)
        assertEquals("Alice Example", profile?.preferredName)
    }

    @Test
    fun malformedOrWrongKindMetadataDoesNotBecomeAProfile() {
        assertNull(profileEvent(content = "not json").toProfile())
        assertNull(profileEvent(content = """{"name":"alice"}""").copy(kind = BookKinds.DIRECTORY).toProfile())
        assertNull(profileEvent(content = """{"name":{"unexpected":"object"}}""").toProfile()?.preferredName)
    }

    @Test
    fun cacheRoundTripsTheCompleteKindZeroEventByPubkey() = runBlocking {
        val root = Files.createTempDirectory("nostr-profile-cache").toFile()
        try {
            val cache = NostrProfileCache(root)
            val event = profileEvent("""{"name":"alice","about":"Cached metadata remains intact."}""")

            cache.write(event)

            assertEquals(event, cache.read(event.pubkey.uppercase()))
            assertFalse(File(root, "nostr-profiles/v1/${event.pubkey}.json.tmp").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptOrMismatchedCacheEntryIsIgnored() = runBlocking {
        val root = Files.createTempDirectory("nostr-profile-cache-corrupt").toFile()
        try {
            val pubkey = "a".repeat(64)
            val file = File(root, "nostr-profiles/v1/$pubkey.json")
            requireNotNull(file.parentFile).mkdirs()
            file.writeText("not json")
            assertNull(NostrProfileCache(root).read(pubkey))

            file.writeText("""{"pubkey":"${"b".repeat(64)}","kind":0,"content":"{}"}""")
            assertNull(NostrProfileCache(root).read(pubkey))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun profileRequestFiltersKindZeroAndAuthor() {
        val pubkey = "a".repeat(64)
        val request = profileReqMessage("profile-test", pubkey)

        assertTrue(request.startsWith("""["REQ","profile-test""""))
        assertTrue(request.contains(""""kinds":[0]"""))
        assertTrue(request.contains(""""authors":["$pubkey"]"""))
        assertTrue(request.contains(""""limit":1"""))
    }

    private fun profileEvent(content: String): NostrEvent =
        EventHasher.hashId(pubkey, 123L, BookKinds.PROFILE_METADATA, emptyArray(), content).let { id ->
            NostrEvent(
                id = id,
                pubkey = pubkey,
                createdAt = 123L,
                kind = BookKinds.PROFILE_METADATA,
                content = content,
                sig = Secp256k1InstanceKotlin.signSchnorr(id.hexBytes(), privateKey, ByteArray(32)).toHex(),
            )
        }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun String.hexBytes(): ByteArray =
        chunked(2).map { pair -> pair.toInt(16).toByte() }.toByteArray()
}
