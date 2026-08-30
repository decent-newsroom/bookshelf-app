package eu.decentnewsroom.bookshelf.data.mercury

import eu.decentnewsroom.bookshelf.domain.NostrEvent

/** Supplies already-verified kind 30040 events from the configured Nostr relays. */
fun interface PublicationIndexRelaySource {
    suspend fun fetchPublicationIndexes(coordinates: List<String>): List<NostrEvent>
}
