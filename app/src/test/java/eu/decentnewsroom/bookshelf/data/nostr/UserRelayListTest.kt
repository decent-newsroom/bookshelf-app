package eu.decentnewsroom.bookshelf.data.nostr

import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class UserRelayListTest {
    @Test
    fun nip65RolesRouteRelaysToTheCorrectDirection() {
        val list = relayListFromVerifiedEvent(
            relayListEvent(
                listOf(
                    listOf("r", "WSS://BOTH.EXAMPLE/"),
                    listOf("r", "wss://read.example", "read"),
                    listOf("r", "wss://write.example", "write"),
                    listOf("r", "ws://cleartext.example", "read"),
                    listOf("r", "wss://unknown-role.example", "other"),
                ),
            ),
        )

        assertEquals(listOf("wss://both.example/", "wss://read.example/"), list.read)
        assertEquals(listOf("wss://both.example/", "wss://write.example/"), list.write)
    }

    @Test
    fun relayListFilterUsesTheNip65KindAndNormalizedAuthor() {
        val pubkey = "a".repeat(64)
        val filter = userRelayListFilter(pubkey.uppercase())

        assertEquals(listOf(BookKinds.USER_RELAY_LIST), filter.kinds)
        assertEquals(listOf(pubkey), filter.authors)
        assertEquals(1, filter.limit)
    }

    @Test
    fun relayListBoundsEachRoleToTwelveRelays() {
        val list = relayListFromVerifiedEvent(
            relayListEvent((1..13).map { index -> listOf("r", "wss://relay$index.example") }),
        )

        assertEquals(12, list.read.size)
        assertEquals(12, list.write.size)
        assertEquals("wss://relay1.example/", list.read.first())
        assertEquals("wss://relay12.example/", list.write.last())
    }

    private fun relayListEvent(tags: List<List<String>>) = NostrEvent(
        id = "0".repeat(64),
        pubkey = "a".repeat(64),
        createdAt = 1L,
        kind = BookKinds.USER_RELAY_LIST,
        tags = tags,
        content = "",
        sig = "0".repeat(128),
    )
}