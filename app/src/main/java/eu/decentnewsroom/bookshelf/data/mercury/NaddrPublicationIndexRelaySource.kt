package eu.decentnewsroom.bookshelf.data.mercury

import eu.decentnewsroom.bookshelf.domain.NostrEvent

/** Resolves a decoded naddr through its relay hints and the app's configured read relays. */
fun interface NaddrPublicationIndexRelaySource {
    suspend fun fetchPublicationIndex(coordinate: String, relayHints: List<String>): List<NostrEvent>
}