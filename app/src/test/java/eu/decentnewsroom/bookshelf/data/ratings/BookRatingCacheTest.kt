package eu.decentnewsroom.bookshelf.data.ratings

import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.utils.Secp256k1InstanceKotlin
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class BookRatingCacheTest {
    private val privateKey = ByteArray(32) { 7 }
    private val reviewer = Secp256k1InstanceKotlin.compressedPubKeyFor(privateKey).copyOfRange(1, 33).toHex()
    private val coordinate = "30040:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:book"

    @Test
    fun olderRevisionCannotReplaceCachedNewerRevision() = runBlocking {
        val root = Files.createTempDirectory("rating-cache-test").toFile()
        try {
            val older = signed(createdAt = 1_700_000_000L, dTag = "books:$coordinate", rating = "0.2")
            val newer = signed(createdAt = 1_700_000_001L, dTag = "books:$coordinate", rating = "0.9")
            val cache = BookRatingCache(cacheRoot = root)

            cache.merge(listOf(older))
            assertEquals(listOf(older.id), cache.ratingsFor(coordinate).map(BookRating::eventId))
            cache.merge(listOf(newer))
            cache.merge(listOf(older))

            assertEquals(listOf(newer.id), cache.ratingsFor(coordinate).map(BookRating::eventId))
            assertEquals(1, cache.stats().entryCount)
            assertEquals(listOf(newer.id), BookRatingCache(cacheRoot = root).allRatings().map(BookRating::eventId))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun firstStatsReadPhysicallyCompactsLegacyRevisionsWithoutRelayMerge() = runBlocking {
        val root = Files.createTempDirectory("rating-cache-test").toFile()
        try {
            val older = signed(createdAt = 1_700_000_000L, dTag = "books:$coordinate", rating = "0.2")
            val newer = signed(createdAt = 1_700_000_001L, dTag = "books:$coordinate", rating = "0.9")
            val file = root.resolve("book-ratings/v1/events.json")
            file.parentFile.mkdirs()
            val olderJson = Json.encodeToString(NostrEvent.serializer(), older)
            val newerJson = Json.encodeToString(NostrEvent.serializer(), newer)
            file.writeText("""{"lastSuccessfulSyncAtMillis":123,"entries":[{"event":$olderJson,"bookCoordinate":"$coordinate","cachedAtMillis":100},{"event":$newerJson,"bookCoordinate":"$coordinate","cachedAtMillis":101}]}""")

            assertEquals(2, storedEventIds(file).size)
            val cache = BookRatingCache(cacheRoot = root)
            assertEquals(1, cache.stats().entryCount)
            assertEquals(listOf(newer.id), storedEventIds(file))
            assertEquals(listOf(newer.id), BookRatingCache(cacheRoot = root).allRatings().map(BookRating::eventId))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun storedEventIds(file: java.io.File): List<String> =
        Json.parseToJsonElement(file.readText()).jsonObject.getValue("entries").jsonArray.map {
            it.jsonObject.getValue("event").jsonObject.getValue("id").jsonPrimitive.content
        }

    @Test
    fun equalTimestampUsesEventIdAndDistinctDTagsAreRetained() = runBlocking {
        val root = Files.createTempDirectory("rating-cache-test").toFile()
        try {
            val first = signed(createdAt = 1_700_000_000L, dTag = "first", rating = "0.2")
            val second = signed(createdAt = 1_700_000_000L, dTag = "first", rating = "0.9")
            val otherAddress = signed(createdAt = 1_700_000_000L, dTag = "second", rating = "0.8")
            val cache = BookRatingCache(cacheRoot = root)

            cache.merge(listOf(first, second, otherAddress))

            assertEquals(setOf(maxOf(first.id, second.id), otherAddress.id),
                cache.ratingsFor(coordinate).map(BookRating::eventId).toSet())
            assertEquals(2, cache.stats().entryCount)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun signed(createdAt: Long, dTag: String, rating: String): NostrEvent {
        val tags = listOf(
            listOf("d", dTag),
            listOf("a", coordinate),
            listOf("rating", rating),
        )
        val hash = EventHasher.hashId(reviewer, createdAt, BookKinds.RATING,
            tags.map { it.toTypedArray() }.toTypedArray(), "A review")
        return NostrEvent(
            id = hash,
            pubkey = reviewer,
            createdAt = createdAt,
            kind = BookKinds.RATING,
            tags = tags,
            content = "A review",
            sig = Secp256k1InstanceKotlin.signSchnorr(hash.hexBytes(), privateKey, ByteArray(32)).toHex(),
        )
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
