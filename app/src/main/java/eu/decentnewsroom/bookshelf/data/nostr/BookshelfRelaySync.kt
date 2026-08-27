package eu.decentnewsroom.bookshelf.data.nostr

import eu.decentnewsroom.bookshelf.data.bookshelf.BookshelfDirectoryRules
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class NostrSignerSession(
    val pubkey: String,
    val packageName: String,
)

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
    val activeSession: StateFlow<NostrSignerSession?>

    suspend fun signIn(session: NostrSignerSession)

    suspend fun signOut()

    suspend fun fetchLatestDirectory(pubkey: String): NostrEvent?

    fun buildDirectoryDraft(
        pubkey: String,
        tags: List<List<String>>,
        createdAt: Long = System.currentTimeMillis() / 1_000L,
    ): DirectoryEventDraft

    fun unsignedDirectoryJson(draft: DirectoryEventDraft): String

    fun decodeSignedDirectory(eventJson: String): NostrEvent

    suspend fun publishDirectory(event: NostrEvent): PublishReport
}

class QuartzBookshelfRelaySync(
    private val relayClient: NostrRelayClient,
    private val sessionStore: NostrSignerSessionStore,
) : BookshelfRelaySync {
    private val _activeSession = MutableStateFlow(sessionStore.load())
    override val activeSession: StateFlow<NostrSignerSession?> = _activeSession.asStateFlow()

    private val _state =
        MutableStateFlow<BookshelfSyncState>(
            _activeSession.value?.let { BookshelfSyncState.Ready(it.pubkey, relayClient.relayUrls.size) }
                ?: BookshelfSyncState.SignedOut,
        )
    override val state: StateFlow<BookshelfSyncState> = _state.asStateFlow()

    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    override suspend fun signIn(session: NostrSignerSession) {
        sessionStore.save(session)
        _activeSession.value = session
        _state.value = BookshelfSyncState.Ready(session.pubkey, relayClient.relayUrls.size)
    }

    override suspend fun signOut() {
        sessionStore.clear()
        _activeSession.value = null
        _state.value = BookshelfSyncState.SignedOut
    }

    override suspend fun fetchLatestDirectory(pubkey: String): NostrEvent? {
        _state.value = BookshelfSyncState.Syncing(pubkey)
        return runCatching {
            relayClient.fetchLatestDirectory(pubkey)
        }.onSuccess {
            _state.value = BookshelfSyncState.Ready(pubkey, relayClient.relayUrls.size)
        }.onFailure { failure ->
            _state.value = BookshelfSyncState.Failed(failure.message ?: "Could not sync directory.")
        }.getOrThrow()
    }

    override fun buildDirectoryDraft(
        pubkey: String,
        tags: List<List<String>>,
        createdAt: Long,
    ): DirectoryEventDraft {
        val normalizedTags = BookshelfDirectoryRules.normalizeEditableTags(tags)
        BookshelfDirectoryRules.assertValidDirectory(normalizedTags, "")

        return DirectoryEventDraft(
            pubkey = pubkey.lowercase(),
            createdAt = createdAt,
            tags = normalizedTags,
        )
    }

    override fun unsignedDirectoryJson(draft: DirectoryEventDraft): String {
        BookshelfDirectoryRules.assertValidDirectory(draft.tags, draft.content)
        return json.encodeToString(
            UnsignedNostrEvent(
                pubkey = draft.pubkey.lowercase(),
                createdAt = draft.createdAt,
                kind = draft.kind,
                tags = draft.tags,
                content = draft.content,
            ),
        )
    }

    override fun decodeSignedDirectory(eventJson: String): NostrEvent {
        val event = json.decodeFromString<NostrEvent>(eventJson)

        require(event.kind == BookKinds.DIRECTORY) { "Signer returned the wrong event kind." }
        BookshelfDirectoryRules.assertValidDirectory(event.tags, event.content)

        NostrEventVerifier.requireVerified(
            event,
            context = NostrEventContext(
                expectedKind = BookKinds.DIRECTORY,
                expectedPubkey = event.pubkey,
                expectedDTag = BookshelfDirectoryRules.IDENTIFIER,
            ),
        )
        return event
    }

    override suspend fun publishDirectory(event: NostrEvent): PublishReport {
        _state.value = BookshelfSyncState.Syncing(event.pubkey)
        return runCatching {
            relayClient.publishDirectory(event)
        }.onSuccess { report ->
            _state.value =
                if (report.acceptedRelays > 0) {
                    BookshelfSyncState.Ready(event.pubkey, relayClient.relayUrls.size)
                } else {
                    BookshelfSyncState.Failed("No relay accepted the directory update.")
                }
        }.onFailure { failure ->
            _state.value = BookshelfSyncState.Failed(failure.message ?: "Could not publish directory.")
        }.getOrThrow()
    }

    @Serializable
    private data class UnsignedNostrEvent(
        val pubkey: String,
        @SerialName("created_at")
        val createdAt: Long,
        val kind: Int,
        val tags: List<List<String>>,
        val content: String,
    )
}
