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



/** An unsigned, R1-compatible rating of a kind-30040 books publication. */
data class RatingEventDraft(
    val pubkey: String,
    val publicationCoordinate: String,
    val normalizedRating: Double,
    val content: String = "",
    val createdAt: Long,
    val kind: Int = BookKinds.RATING,
) {
    val targetId: String get() = "$BOOKS_NAMESPACE:$publicationCoordinate"
    val publicationAuthorPubkey: String get() = publicationCoordinate.split(':', limit = 3)[1]
    companion object { const val BOOKS_NAMESPACE = "books" }
}
enum class RelayPublishOutcomeType {
    ACCEPTED,
    REJECTED,
    AUTHENTICATION_REQUIRED,
    AUTHENTICATION_FAILED,
    TRANSPORT_FAILURE,
    PROTOCOL_FAILURE,
    TIMEOUT,
}

data class RelayPublishOutcome(
    val relayUrl: String,
    val type: RelayPublishOutcomeType,
    val reason: String? = null,
)

data class PublishReport(
    val acceptedRelays: Int,
    val attemptedRelays: Int,
    val eventId: String?,
    val outcomes: List<RelayPublishOutcome> = emptyList(),
) {
    fun failureMessage(): String = outcomes
        .filter { it.type != RelayPublishOutcomeType.ACCEPTED }
        .joinToString(separator = "; ") { outcome ->
            "${outcome.relayUrl}: ${outcome.reason ?: outcome.type.name.lowercase().replace('_', ' ')}"
        }
        .take(MAX_FAILURE_MESSAGE_LENGTH)
        .ifBlank { "No relay accepted the directory update." }

    private companion object { const val MAX_FAILURE_MESSAGE_LENGTH = 500 }
}
data class RelayConfiguration(
    val bootstrap: List<String> = emptyList(),
    val userRead: List<String> = emptyList(),
    val userWrite: List<String> = emptyList(),
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
    val pendingNostrAuthSignRequest: StateFlow<PendingNostrAuthSignRequest?>
    val relayConfiguration: RelayConfiguration get() = RelayConfiguration()

    suspend fun signIn(session: NostrSignerSession)

    suspend fun signOut()

    fun completeNostrAuthSignature(requestId: String?, signedEventJson: String)

    fun failPendingNostrAuthSignature(requestId: String?, message: String)

    suspend fun fetchLatestDirectory(pubkey: String): NostrEvent?

    fun buildDirectoryDraft(
        pubkey: String,
        tags: List<List<String>>,
        createdAt: Long = System.currentTimeMillis() / 1_000L,
    ): DirectoryEventDraft

    fun unsignedDirectoryJson(draft: DirectoryEventDraft): String

    fun decodeSignedDirectory(eventJson: String): NostrEvent

    suspend fun publishDirectory(event: NostrEvent): PublishReport

    suspend fun publishToRelay(event: NostrEvent, relayUrl: String): PublishReport


    fun buildRatingDraft(pubkey: String, publicationCoordinate: String, normalizedRating: Double, content: String = "", createdAt: Long = System.currentTimeMillis() / 1_000L): RatingEventDraft
    fun unsignedRatingJson(draft: RatingEventDraft): String
    fun decodeSignedRating(eventJson: String): NostrEvent
    suspend fun publishRating(event: NostrEvent, publicationAuthorPubkey: String): PublishReport

    fun setLocalRelayUrl(relayUrl: String?) = Unit
}

class QuartzBookshelfRelaySync(
    private val relayClient: NostrRelayClient,
    private val sessionStore: NostrSignerSessionStore,
    private val authenticator: ExternalSignerNostrRelayAuthenticator,
    private val defaultRelayUrls: List<String>,
) : BookshelfRelaySync {
    private val _activeSession = MutableStateFlow(sessionStore.load())
    override val activeSession: StateFlow<NostrSignerSession?> = _activeSession.asStateFlow()
    override val pendingNostrAuthSignRequest: StateFlow<PendingNostrAuthSignRequest?> = authenticator.pending
    override val relayConfiguration: RelayConfiguration
        get() = RelayConfiguration(
            bootstrap = relayClient.configuredRelayUrls,
            userRead = relayClient.userReadRelayUrls,
            userWrite = relayClient.userWriteRelayUrls,
        )

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
        authenticator.updateSession(session)
        sessionStore.save(session)
        _activeSession.value = session
        try {
            relayClient.refreshUserRelayList(session.pubkey)
        } catch (_: NostrRelayException) {
            // Bootstrap relays remain usable when NIP-65 discovery is unavailable.
        }
        _state.value = BookshelfSyncState.Ready(session.pubkey, relayClient.relayUrls.size)
    }

    override suspend fun signOut() {
        authenticator.updateSession(null)
        sessionStore.clear()
        relayClient.clearUserRelayList()
        _activeSession.value = null
        _state.value = BookshelfSyncState.SignedOut
    }

    override fun completeNostrAuthSignature(requestId: String?, signedEventJson: String) {
        authenticator.completePending(requestId, signedEventJson)
    }

    override fun failPendingNostrAuthSignature(requestId: String?, message: String) {
        authenticator.failPending(requestId, message)
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
        val publishedTags = BookshelfDirectoryRules.tagsForPublishing(tags)
        BookshelfDirectoryRules.assertValidDirectory(publishedTags, "")

        return DirectoryEventDraft(
            pubkey = pubkey.lowercase(),
            createdAt = createdAt,
            tags = publishedTags,
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
                    BookshelfSyncState.Failed(report.failureMessage())
                }
        }.onFailure { failure ->
            _state.value = BookshelfSyncState.Failed(failure.message ?: "Could not publish directory.")
        }.getOrThrow()
    }

    override suspend fun publishToRelay(event: NostrEvent, relayUrl: String): PublishReport =
        relayClient.publishToRelay(event, relayUrl)


    override fun buildRatingDraft(
        pubkey: String,
        publicationCoordinate: String,
        normalizedRating: Double,
        content: String,
        createdAt: Long,
    ): RatingEventDraft {
        require(HEX_64.matches(pubkey)) { "Rating author public key is invalid." }
        requireValidRatingCoordinate(publicationCoordinate)
        require(normalizedRating.isFinite() && normalizedRating in 0.0..1.0) { "The R1 rating must be between 0 and 1 inclusive." }
        return RatingEventDraft(pubkey.lowercase(), publicationCoordinate.lowercaseCoordinate(), normalizedRating, content, createdAt)
    }

    override fun unsignedRatingJson(draft: RatingEventDraft): String {
        require(draft.kind == BookKinds.RATING) { "Rating draft has the wrong event kind." }
        require(HEX_64.matches(draft.pubkey)) { "Rating author public key is invalid." }
        requireValidRatingCoordinate(draft.publicationCoordinate)
        require(draft.normalizedRating.isFinite() && draft.normalizedRating in 0.0..1.0) { "The R1 rating must be between 0 and 1 inclusive." }
        return json.encodeToString(UnsignedNostrEvent(
            pubkey = draft.pubkey.lowercase(), createdAt = draft.createdAt, kind = draft.kind,
            tags = listOf(
                listOf("d", draft.targetId), listOf("m", RatingEventDraft.BOOKS_NAMESPACE),
                listOf("rating", formatNormalizedRating(draft.normalizedRating)),
            ), content = draft.content,
        ))
    }

    override fun decodeSignedRating(eventJson: String): NostrEvent {
        val event = json.decodeFromString<NostrEvent>(eventJson)
        require(event.kind == BookKinds.RATING) { "Signer returned the wrong event kind." }
        val target = event.tags.singleTagValue("d") ?: throw IllegalArgumentException("A rating requires exactly one d tag.")
        val coordinate = target.removePrefix("${RatingEventDraft.BOOKS_NAMESPACE}:")
        require(target != coordinate) { "A rating d tag must use the books namespace." }
        requireValidRatingCoordinate(coordinate)
        require(event.tags.valuesFor("m").let { it.size <= 1 && (it.isEmpty() || it.single() == RatingEventDraft.BOOKS_NAMESPACE) }) { "The rating m tag conflicts with the books target." }
        val rating = event.tags.singleTagValue("rating") ?: throw IllegalArgumentException("A rating requires exactly one rating tag.")
        require(parseNormalizedRating(rating) != null) { "The R1 rating must be between 0 and 1 inclusive." }
        NostrEventVerifier.requireVerified(event, context = NostrEventContext(expectedKind = BookKinds.RATING, expectedPubkey = event.pubkey))
        return event
    }

    override suspend fun publishRating(event: NostrEvent, publicationAuthorPubkey: String): PublishReport {
        _state.value = BookshelfSyncState.Syncing(event.pubkey)
        return runCatching { relayClient.publishRating(event, publicationAuthorPubkey) }
            .onSuccess { report -> _state.value = if (report.acceptedRelays > 0) BookshelfSyncState.Ready(event.pubkey, relayClient.relayUrls.size) else BookshelfSyncState.Failed(report.failureMessage()) }
            .onFailure { failure -> _state.value = BookshelfSyncState.Failed(failure.message ?: "Could not publish rating.") }
            .getOrThrow()
    }

    override fun setLocalRelayUrl(relayUrl: String?) {
        relayClient.setConfiguredRelayUrls(defaultRelayUrls + listOfNotNull(relayUrl))
        _activeSession.value?.let { session ->
            _state.value = BookshelfSyncState.Ready(session.pubkey, relayClient.relayUrls.size)
        }
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
    private fun requireValidRatingCoordinate(coordinate: String) {
        val parts = coordinate.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == BookKinds.PUBLICATION_INDEX.toString() && HEX_64.matches(parts[1]) && parts[2].isNotBlank()) { "Rating target must be a kind-30040 publication coordinate." }
    }

    private fun String.lowercaseCoordinate(): String {
        val parts = split(':', limit = 3)
        return "${parts[0]}:${parts[1].lowercase()}:${parts[2]}"
    }

    private fun formatNormalizedRating(value: Double): String = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    private fun parseNormalizedRating(value: String): Double? {
        if (!NORMALIZED_RATING.matches(value)) return null
        val decimal = value.toBigDecimalOrNull() ?: return null
        return decimal.takeIf { it >= java.math.BigDecimal.ZERO && it <= java.math.BigDecimal.ONE }?.toDouble()
    }
    private fun List<List<String>>.valuesFor(name: String): List<String> = asSequence().filter { it.getOrNull(0) == name }.mapNotNull { it.getOrNull(1) }.distinct().toList()
    private fun List<List<String>>.singleTagValue(name: String): String? = valuesFor(name).singleOrNull()
    private companion object {
        val HEX_64 = Regex("^[a-f0-9]{64}$", RegexOption.IGNORE_CASE)
        val NORMALIZED_RATING = Regex("^(?:0(?:\\.\\d+)?|1(?:\\.0+)?)$")
    }
}
