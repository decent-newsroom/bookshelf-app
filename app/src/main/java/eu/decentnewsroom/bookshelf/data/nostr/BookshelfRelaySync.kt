package eu.decentnewsroom.bookshelf.data.nostr

import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DirectoryEventDraft(
    val pubkey: String,
    val createdAt: Long,
    val tags: List<List<String>>,
    val content: String = "",
    val kind: Int = BookKinds.DIRECTORY,
)

data class PublishReport(
    val acceptedRelays: Int,
    val attemptedRelays: Int,
    val eventId: String?,
)

sealed interface BookshelfSyncState {
    data object NotConfigured : BookshelfSyncState
    data object SignedOut : BookshelfSyncState
    data class Ready(val pubkey: String, val relayCount: Int) : BookshelfSyncState
    data class Syncing(val pubkey: String) : BookshelfSyncState
    data class Failed(val message: String) : BookshelfSyncState
}

interface BookshelfRelaySync {
    val state: StateFlow<BookshelfSyncState>

    suspend fun fetchLatestDirectory(pubkey: String): NostrEvent?

    suspend fun signDirectory(draft: DirectoryEventDraft): NostrEvent

    suspend fun publishDirectory(event: NostrEvent): PublishReport
}

class QuartzBookshelfRelaySync : BookshelfRelaySync {
    private val _state = MutableStateFlow<BookshelfSyncState>(BookshelfSyncState.NotConfigured)
    override val state: StateFlow<BookshelfSyncState> = _state.asStateFlow()

    override suspend fun fetchLatestDirectory(pubkey: String): NostrEvent? {
        TODO("Wire Quartz NostrClient with kind=30045, author=pubkey, and #d=my-book-collection.")
    }

    override suspend fun signDirectory(draft: DirectoryEventDraft): NostrEvent {
        TODO("Wire Quartz NostrSignerExternal first, then add NIP-46/internal signer options.")
    }

    override suspend fun publishDirectory(event: NostrEvent): PublishReport {
        TODO("Publish through Quartz to NIP-65 write relays and return OK acknowledgements.")
    }
}
