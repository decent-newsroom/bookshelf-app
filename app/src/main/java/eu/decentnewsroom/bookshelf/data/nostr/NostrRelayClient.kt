package eu.decentnewsroom.bookshelf.data.nostr

import android.util.Log
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.PublishResult
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetchAll
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.publishAndCollectResults
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.AuthMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.OkMessage
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.AuthCmd
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import com.vitorpamplona.quartz.nip42RelayAuth.RelayAuthEvent
import eu.decentnewsroom.bookshelf.data.bookshelf.BookshelfDirectoryRules
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

class NostrRelayException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Quartz-backed directory/profile relay transport. */
class NostrRelayClient(
    httpClient: OkHttpClient,
    val relayUrls: List<String>,
    private val timeoutMillis: Long = FETCH_IDLE_TIMEOUT_MILLIS,
    authenticator: NostrRelayAuthenticator? = null,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relays = relayUrls.mapNotNull(RelayUrlNormalizer::normalizeOrNull).toCollection(LinkedHashSet())
    private val client = NostrClient(BasicOkHttpWebSocket.Builder { httpClient }, scope)
    private val lazyAuthenticator = QuartzLazyNip42Authenticator(client, authenticator, nowSeconds)

    suspend fun fetchLatestDirectory(pubkey: String): NostrEvent? =
        fetchLatest(
            Filter(
                kinds = listOf(BookKinds.DIRECTORY),
                authors = listOf(pubkey.lowercase()),
                tags = mapOf("d" to listOf(BookshelfDirectoryRules.IDENTIFIER)),
                limit = 1,
            ),
        ) { event ->
            NostrEventVerifier.verify(
                event,
                context = NostrEventContext(
                    expectedKind = BookKinds.DIRECTORY,
                    expectedPubkey = pubkey,
                    expectedDTag = BookshelfDirectoryRules.IDENTIFIER,
                ),
            )?.event?.takeIf { it.isDirectoryFor(pubkey) }
        }

    suspend fun fetchLatestProfile(pubkey: String): NostrEvent? =
        fetchLatest(
            profileFilter(pubkey),
        ) { event ->
            NostrEventVerifier.verify(
                event,
                context = NostrEventContext(expectedKind = BookKinds.PROFILE_METADATA, expectedPubkey = pubkey),
            )?.event?.takeIf { it.isProfileFor(pubkey) }
        }

    /** Resolves exact kind 30040 coordinates from the configured known relays. */
    suspend fun fetchPublicationIndexes(coordinates: List<String>): List<NostrEvent> =
        coordinates.mapNotNull(::parsePublicationCoordinate).distinct().mapNotNull { coordinate ->
            fetchLatest(
                Filter(
                    kinds = listOf(BookKinds.PUBLICATION_INDEX),
                    authors = listOf(coordinate.pubkey),
                    tags = mapOf("d" to listOf(coordinate.identifier)),
                    limit = 1,
                ),
            ) { event ->
                NostrEventVerifier.verify(
                    event,
                    context = NostrEventContext(
                        expectedKind = BookKinds.PUBLICATION_INDEX,
                        expectedPubkey = coordinate.pubkey,
                        expectedDTag = coordinate.identifier,
                    ),
                )?.event
            }
        }

    private suspend fun fetchLatest(
        filter: Filter,
        verifyEvent: (NostrEvent) -> NostrEvent?,
    ): NostrEvent? {
        if (relays.isEmpty()) return null
        val filters = relays.associateWith { listOf(filter) }
        val events = runCatching {
            client.fetchAll(filters = filters, timeoutMs = timeoutMillis, maxTotalMs = FETCH_MAX_TOTAL_MILLIS)
        }.getOrElse { failure ->
            throw NostrRelayException("Could not reach configured relays.", failure)
        }
        return events.mapNotNull(::toDomainEvent).mapNotNull(verifyEvent)
            .maxWithOrNull(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
    }

    suspend fun publishDirectory(event: NostrEvent): PublishReport {
        if (NostrEventVerifier.verify(event) == null) {
            return failedPublishReport(event, RelayPublishOutcomeType.PROTOCOL_FAILURE, "The signed event failed local verification.")
        }
        val quartzEvent = runCatching { Event.fromJson(json.encodeToString(event)) }.getOrElse { failure ->
            return failedPublishReport(event, RelayPublishOutcomeType.PROTOCOL_FAILURE, safeReason(failure.message))
        }
        val initial = runCatching { client.publishAndCollectResults(quartzEvent, relays, PUBLISH_TIMEOUT_SECONDS) }
            .getOrElse { failure ->
                return failedPublishReport(event, RelayPublishOutcomeType.TRANSPORT_FAILURE, safeReason(failure.message))
            }
        val outcomes = initial.map { (relay, result) -> publishOutcome(relay, result) }.toMutableList()
        retryAuthenticationRequiredPublishes(quartzEvent, outcomes)
        outcomes.forEach { logOutcome(event.id, it) }
        return PublishReport(
            acceptedRelays = outcomes.count { it.type == RelayPublishOutcomeType.ACCEPTED },
            attemptedRelays = outcomes.size,
            eventId = event.id.takeIf(String::isNotBlank),
            outcomes = outcomes.sortedBy { relayUrls.indexOf(it.relayUrl).let { index -> if (index < 0) Int.MAX_VALUE else index } },
        )
    }

    private fun failedPublishReport(
        event: NostrEvent,
        type: RelayPublishOutcomeType,
        reason: String,
    ) = PublishReport(
        acceptedRelays = 0,
        attemptedRelays = relays.size,
        eventId = event.id.takeIf(String::isNotBlank),
        outcomes = relays.map { RelayPublishOutcome(it.url, type, reason) },
    )

    private suspend fun retryAuthenticationRequiredPublishes(
        event: Event,
        outcomes: MutableList<RelayPublishOutcome>,
    ) {
        outcomes.indices.forEach { index ->
            val current = outcomes[index]
            if (current.type != RelayPublishOutcomeType.AUTHENTICATION_REQUIRED) return@forEach
            val relay = relays.firstOrNull { it.url == current.relayUrl }
                ?: return@forEach
            when (val authentication = lazyAuthenticator.authenticate(relay)) {
                is AuthAttempt.Accepted -> {
                    val retry = runCatching {
                        client.publishAndCollectResults(event, setOf(relay), PUBLISH_TIMEOUT_SECONDS).getValue(relay)
                    }.getOrElse { failure ->
                        outcomes[index] = RelayPublishOutcome(
                            relay.url,
                            RelayPublishOutcomeType.TRANSPORT_FAILURE,
                            safeReason(failure.message),
                        )
                        return@forEach
                    }
                    val retryOutcome = publishOutcome(relay, retry)
                    outcomes[index] = if (retryOutcome.type == RelayPublishOutcomeType.AUTHENTICATION_REQUIRED) {
                        RelayPublishOutcome(
                            relay.url,
                            RelayPublishOutcomeType.AUTHENTICATION_FAILED,
                            "The relay still requires authentication after the approved retry.",
                        )
                    } else {
                        retryOutcome
                    }
                }
                is AuthAttempt.Failed -> outcomes[index] = RelayPublishOutcome(
                    relay.url,
                    RelayPublishOutcomeType.AUTHENTICATION_FAILED,
                    authentication.reason,
                )
            }
        }
    }

    private fun publishOutcome(relay: NormalizedRelayUrl, result: PublishResult): RelayPublishOutcome {
        val reason = safeReason(result.message)
        val type = when {
            result.accepted -> RelayPublishOutcomeType.ACCEPTED
            isAuthRequired(reason) -> RelayPublishOutcomeType.AUTHENTICATION_REQUIRED
            reason == PublishResult.NO_RESPONSE -> RelayPublishOutcomeType.TIMEOUT
            reason == PublishResult.DISCONNECTED -> RelayPublishOutcomeType.TRANSPORT_FAILURE
            reason.startsWith(PublishResult.CANNOT_CONNECT_PREFIX) -> RelayPublishOutcomeType.TRANSPORT_FAILURE
            else -> RelayPublishOutcomeType.REJECTED
        }
        return RelayPublishOutcome(relay.url, type, reason.takeIf { it.isNotBlank() })
    }

    private fun toDomainEvent(event: Event): NostrEvent? =
        runCatching { json.decodeFromString<NostrEvent>(event.toJson()) }.getOrNull()

    private fun NostrEvent.isDirectoryFor(pubkey: String): Boolean = kind == BookKinds.DIRECTORY &&
        this.pubkey.equals(pubkey, ignoreCase = true) &&
        tags.any { it.getOrNull(0) == "d" && it.getOrNull(1) == BookshelfDirectoryRules.IDENTIFIER }

    private fun NostrEvent.isProfileFor(pubkey: String): Boolean = kind == BookKinds.PROFILE_METADATA &&
        this.pubkey.equals(pubkey, ignoreCase = true)

    private fun logOutcome(eventId: String, outcome: RelayPublishOutcome) {
        val detail = outcome.reason?.let { ": $it" }.orEmpty()
        Log.i(LOG_TAG, "publish $eventId ${outcome.relayUrl} ${outcome.type}$detail")
    }

    override fun close() {
        lazyAuthenticator.close()
        client.close()
        scope.cancel()
    }

    private companion object {
        const val LOG_TAG = "BookshelfRelay"
        const val FETCH_IDLE_TIMEOUT_MILLIS = 15_000L
        const val FETCH_MAX_TOTAL_MILLIS = 30_000L
        const val PUBLISH_TIMEOUT_SECONDS = 15L
        const val MAX_REASON_LENGTH = 240
        val HEX_64 = Regex("^[a-f0-9]{64}$", RegexOption.IGNORE_CASE)

        fun parsePublicationCoordinate(raw: String): PublicationCoordinate? {
            val parts = raw.trim().split(":", limit = 3)
            if (parts.size != 3 || parts[0].toIntOrNull() != BookKinds.PUBLICATION_INDEX ||
                !HEX_64.matches(parts[1]) || parts[2].isBlank()
            ) return null
            return PublicationCoordinate(parts[1].lowercase(), parts[2])
        }

        fun isAuthRequired(reason: String) = reason.lowercase().let {
            it.contains("auth-required") || it.contains("auth required")
        }

        fun safeReason(reason: String?) = reason.orEmpty().replace(Regex("\\s+"), " ").trim().take(MAX_REASON_LENGTH)
    }

}

internal fun profileFilter(pubkey: String) =
    Filter(kinds = listOf(BookKinds.PROFILE_METADATA), authors = listOf(pubkey.lowercase()), limit = 1)

private data class PublicationCoordinate(val pubkey: String, val identifier: String)

private sealed interface AuthAttempt {
    data object Accepted : AuthAttempt
    data class Failed(val reason: String) : AuthAttempt
}

/**
 * Quartz owns the socket; this listener retains the app's lazy NIP-42 policy.
 * An unsolicited AUTH is remembered, but Amber is invoked only after an
 * auth-required EVENT rejection.
 */
private class QuartzLazyNip42Authenticator(
    private val client: NostrClient,
    private val authenticator: NostrRelayAuthenticator?,
    private val nowSeconds: () -> Long,
) : AutoCloseable {
    private val challenges = ConcurrentHashMap<NormalizedRelayUrl, String>()
    private val pendingResults = ConcurrentHashMap<String, PendingAuthResult>()
    private val listener = object : RelayConnectionListener {
        override fun onIncomingMessage(relay: IRelayClient, msgStr: String, msg: Message) {
            when (msg) {
                is AuthMessage -> challenges[relay.url] = msg.challenge
                is OkMessage -> pendingResults.remove(msg.eventId)?.result?.complete(msg.success)
            }
        }

        override fun onDisconnected(relay: IRelayClient) {
            challenges.remove(relay.url)
            pendingResults.entries.removeIf { (_, pending) ->
                if (pending.relay == relay.url) {
                    pending.result.complete(false)
                    true
                } else {
                    false
                }
            }
        }
    }

    init {
        client.addConnectionListener(listener)
    }

    suspend fun authenticate(relay: NormalizedRelayUrl): AuthAttempt {
        val auth = authenticator ?: return AuthAttempt.Failed("No Android signer is available for relay authentication.")
        val challenge = challenges[relay] ?: return AuthAttempt.Failed("The relay required authentication without a usable challenge.")
        val draft = runCatching { NostrAuthEventValidator.draft(auth.pubkey, relay.url, challenge, nowSeconds()) }
            .getOrElse { failure -> return AuthAttempt.Failed(safeAuthReason(failure.message)) }
        val signed = withTimeoutOrNull(AUTH_SIGNING_TIMEOUT_MILLIS) {
            runCatching { auth.signAuthEvent(draft) }.getOrNull()
        } ?: return AuthAttempt.Failed("The Android signer did not approve relay authentication in time.")
        val verified = NostrAuthEventValidator.verify(signed, draft, nowSeconds())
            ?: return AuthAttempt.Failed("The Android signer returned an invalid relay-authentication event.")
        val authEvent = verified.event.toQuartzRelayAuthEvent()
        val awaiting = CompletableDeferred<Boolean>()
        pendingResults[authEvent.id] = PendingAuthResult(relay, awaiting)
        client.getOrCreateRelay(relay).sendIfConnected(AuthCmd(authEvent))
        val accepted = withTimeoutOrNull(AUTH_ACK_TIMEOUT_MILLIS) { awaiting.await() } ?: false
        pendingResults.remove(authEvent.id)
        return if (accepted) AuthAttempt.Accepted else AuthAttempt.Failed("The relay rejected or did not acknowledge authentication.")
    }

    override fun close() {
        client.removeConnectionListener(listener)
        pendingResults.values.forEach { it.result.cancel() }
        pendingResults.clear()
        challenges.clear()
    }
}

private data class PendingAuthResult(
    val relay: NormalizedRelayUrl,
    val result: CompletableDeferred<Boolean>,
)

private fun NostrEvent.toQuartzRelayAuthEvent() = RelayAuthEvent(
    id = id,
    pubKey = pubkey,
    createdAt = createdAt,
    tags = tags.map { it.toTypedArray() }.toTypedArray(),
    content = content,
    sig = sig,
)

private fun safeAuthReason(reason: String?) = reason.orEmpty()
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(MAX_AUTH_REASON_LENGTH)
    .ifBlank { "Could not prepare relay authentication." }

private const val AUTH_SIGNING_TIMEOUT_MILLIS = 90_000L
private const val AUTH_ACK_TIMEOUT_MILLIS = 15_000L
private const val MAX_AUTH_REASON_LENGTH = 240
