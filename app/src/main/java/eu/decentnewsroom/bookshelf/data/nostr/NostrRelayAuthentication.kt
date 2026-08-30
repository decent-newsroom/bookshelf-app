package eu.decentnewsroom.bookshelf.data.nostr

import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The NIP-42 event draft a relay authenticator must sign. */
data class NostrAuthEventDraft(
    val pubkey: String,
    val relayUrl: String,
    val challenge: String,
    val createdAt: Long,
) {
    val kind: Int = KIND
    val tags: List<List<String>> =
        listOf(
            listOf("relay", relayUrl),
            listOf("challenge", challenge),
        )
    val content: String = ""

    companion object {
        const val KIND = 22242
    }
}

/**
 * Signer-neutral NIP-42 boundary. Implementations may use an external signer,
 * a hardware wallet, or a test key; the relay client never receives private
 * key material.
 */
interface NostrRelayAuthenticator {
    val pubkey: String

    suspend fun signAuthEvent(draft: NostrAuthEventDraft): NostrEvent
}

data class PendingNostrAuthSignRequest(
    val id: String,
    val session: NostrSignerSession,
    val draft: NostrAuthEventDraft,
    val unsignedEventJson: String,
)

/** Activity-result bridge for an external signer; private keys never enter the app. */
class ExternalSignerNostrRelayAuthenticator(
    initialSession: NostrSignerSession? = null,
) : NostrRelayAuthenticator {
    private val requestMutex = Mutex()
    private val stateMutex = Mutex()
    private val _pending = MutableStateFlow<PendingNostrAuthSignRequest?>(null)
    @Volatile private var active: ActiveRequest? = null
    @Volatile private var session: NostrSignerSession? = initialSession

    override val pubkey: String
        get() = session?.pubkey.orEmpty()

    val pending: StateFlow<PendingNostrAuthSignRequest?> = _pending.asStateFlow()

    override suspend fun signAuthEvent(draft: NostrAuthEventDraft): NostrEvent = requestMutex.withLock {
        val request = stateMutex.withLock {
            val current = session
                ?: throw CancellationException("No Android signer session is active.")
            require(current.pubkey.equals(draft.pubkey, ignoreCase = true)) {
                "NIP-42 draft does not belong to the active signer session."
            }
            ActiveRequest(
                PendingNostrAuthSignRequest(
                    id = java.util.UUID.randomUUID().toString(),
                    session = current,
                    draft = draft,
                    unsignedEventJson = draft.unsignedNostrJson(),
                ),
                CompletableDeferred(),
            ).also {
                active = it
                _pending.value = it.request
            }
        }
        try {
            request.result.await()
        } finally {
            stateMutex.withLock {
                if (active === request) {
                    active = null
                    _pending.value = null
                }
            }
        }
    }

    suspend fun updateSession(newSession: NostrSignerSession?) {
        val cancelled = stateMutex.withLock {
            session = newSession
            active.also {
                active = null
                _pending.value = null
            }
        }
        cancelled?.result?.completeExceptionally(CancellationException("Signer session changed."))
    }

    fun completePending(requestId: String?, signedEventJson: String) {
        val request = active ?: return
        if (requestId == null || request.id != requestId) {
            request.result.completeExceptionally(IllegalArgumentException("Signer returned an unexpected NIP-42 request id."))
            return
        }
        val event = runCatching { AUTH_JSON.decodeFromString<NostrEvent>(signedEventJson) }
            .getOrElse { failure ->
                request.result.completeExceptionally(IllegalArgumentException("Signer returned invalid NIP-42 event JSON.", failure))
                return
            }
        request.result.complete(event)
    }

    fun failPending(requestId: String?, message: String) {
        val request = active ?: return
        if (requestId != null && request.id != requestId) return
        request.result.completeExceptionally(CancellationException(message))
    }

    private class ActiveRequest(
        val request: PendingNostrAuthSignRequest,
        val result: CompletableDeferred<NostrEvent>,
    ) {
        val id: String get() = request.id
    }

    private companion object {
        val AUTH_JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}

@Serializable
private data class UnsignedAuthEvent(
    val pubkey: String,
    @SerialName("created_at") val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
)

fun NostrAuthEventDraft.unsignedNostrJson(): String = Json.encodeToString(
    UnsignedAuthEvent(pubkey, createdAt, kind, tags, content),
)

/**
 * Per-connection NIP-42 protocol state. It deliberately contains no socket or
 * signer code, making retry bounds deterministic and easy to exercise in unit
 * tests.
 */
internal class NostrRelayAuthState(hasAuthenticator: Boolean = true) {
    private val hasAuthenticator = hasAuthenticator
    internal enum class Action { NONE, AUTHENTICATE, RETRY, FAIL }

    private var challenge: String? = null
    private var authAttempted = false
    private var authenticated = false
    private var retryRequested = false
    private var retryCount = 0

    @Synchronized
    fun onChallenge(value: String): Action {
        if (value.isBlank() || value.toByteArray(Charsets.UTF_8).size > NostrAuthEventValidator.MAX_CHALLENGE_LENGTH) {
            return Action.FAIL
        }
        challenge = value
        // Relays such as Pipe can advertise AUTH on public connections. Keep
        // the challenge, but authenticate lazily only after auth-required.
        return Action.NONE
    }

    @Synchronized
    fun onAuthRequired(): Action {
        if (!hasAuthenticator) return Action.FAIL
        if (retryCount > 0) return Action.FAIL
        retryRequested = true
        return when {
            authenticated -> retry()
            challenge != null && !authAttempted -> {
                authAttempted = true
                Action.AUTHENTICATE
            }
            challenge != null -> Action.NONE
            else -> Action.FAIL
        }
    }

    @Synchronized
    fun onAuthResult(accepted: Boolean): Action {
        if (!authAttempted) return Action.FAIL
        if (!accepted) return Action.FAIL
        authenticated = true
        return if (retryRequested) retry() else Action.NONE
    }

    private fun retry(): Action {
        if (!retryRequested || ++retryCount > 1) return Action.FAIL
        retryRequested = false
        return Action.RETRY
    }
}

/** Canonical construction and validation rules for NIP-42 authentication. */
object NostrAuthEventValidator {
    const val MAX_CHALLENGE_LENGTH = 4_096
    const val MAX_AGE_SECONDS = 10 * 60L

    fun draft(
        pubkey: String,
        relayUrl: String,
        challenge: String,
        createdAt: Long,
    ): NostrAuthEventDraft {
        require(pubkey.matches(HEX_64)) { "NIP-42 pubkey is invalid." }
        require(relayUrl.isNotBlank()) { "NIP-42 relay URL is required." }
        require(challenge.isNotBlank() && challenge.toByteArray(Charsets.UTF_8).size <= MAX_CHALLENGE_LENGTH) {
            "NIP-42 challenge is invalid."
        }
        return NostrAuthEventDraft(pubkey.lowercase(), relayUrl, challenge, createdAt)
    }

    fun verify(
        event: NostrEvent,
        draft: NostrAuthEventDraft,
        nowSeconds: Long,
    ): VerifiedNostrEvent? {
        if (event.content != "" || event.tags != draft.tags) return null
        if (kotlin.math.abs(event.createdAt - nowSeconds) > MAX_AGE_SECONDS) return null
        return NostrEventVerifier.verify(
            event,
            nowSeconds = nowSeconds,
            context = NostrEventContext(
                expectedKind = NostrAuthEventDraft.KIND,
                expectedPubkey = draft.pubkey,
            ),
        )
    }

    private val HEX_64 = Regex("^[a-f0-9]{64}$")
}
