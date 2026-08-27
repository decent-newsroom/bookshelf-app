package eu.decentnewsroom.bookshelf.data.nostr

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
        NostrEvent(
            id = "b".repeat(64),
            pubkey = "a".repeat(64),
            createdAt = 123L,
            kind = BookKinds.PROFILE_METADATA,
            content = content,
            sig = "c".repeat(128),
        )
}
