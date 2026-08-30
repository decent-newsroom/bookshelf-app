package eu.decentnewsroom.bookshelf.data.nostr

import eu.decentnewsroom.bookshelf.data.bookshelf.BookshelfDirectoryRules
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

class NostrRelayException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class NostrRelayClient(
    private val httpClient: OkHttpClient,
    val relayUrls: List<String>,
    private val timeoutMillis: Long = 5_000,
    private val authenticator: NostrRelayAuthenticator? = null,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    suspend fun fetchLatestDirectory(pubkey: String): NostrEvent? =
        fetchLatest(
            requestMessage = { id -> directoryReqMessage(id, pubkey) },
            verifyEvent = { event ->
                NostrEventVerifier.verify(
                    event,
                    context = NostrEventContext(
                        expectedKind = BookKinds.DIRECTORY,
                        expectedPubkey = pubkey,
                        expectedDTag = BookshelfDirectoryRules.IDENTIFIER,
                    ),
                )?.event?.takeIf { it.isDirectoryFor(pubkey) }
            },
        )

    suspend fun fetchLatestProfile(pubkey: String): NostrEvent? =
        fetchLatest(
            requestMessage = { id -> profileReqMessage(id, pubkey) },
            verifyEvent = { event ->
                NostrEventVerifier.verify(
                    event,
                    context = NostrEventContext(
                        expectedKind = BookKinds.PROFILE_METADATA,
                        expectedPubkey = pubkey,
                    ),
                )?.event?.takeIf { it.isProfileFor(pubkey) }
            },
        )

    private suspend fun fetchLatest(
        requestMessage: (String) -> String,
        verifyEvent: (NostrEvent) -> NostrEvent?,
    ): NostrEvent? = coroutineScope {
        val attempts = relayUrls.map { relayUrl ->
            async { runCatching { fetchLatestFromRelay(relayUrl, requestMessage, verifyEvent) } }
        }.awaitAll()
        if (attempts.isNotEmpty() && attempts.all { it.isFailure }) {
            throw NostrRelayException("Could not reach configured relays.")
        }
        attempts.mapNotNull { it.getOrNull() }
            .maxWithOrNull(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
    }

    /** Resolves exact kind 30040 coordinates from the configured known relays. */
    suspend fun fetchPublicationIndexes(coordinates: List<String>): List<NostrEvent> = coroutineScope {
        val requested = coordinates.mapNotNull(::parsePublicationCoordinate).distinct()
        val permits = Semaphore(MAX_CONCURRENT_PUBLICATION_LOOKUPS)

        requested.map { coordinate ->
            async {
                permits.withPermit {
                    fetchLatest(
                        requestMessage = { id -> publicationIndexReqMessage(id, coordinate.pubkey, coordinate.identifier) },
                        verifyEvent = { event ->
                            NostrEventVerifier.verify(
                                event,
                                context = NostrEventContext(
                                    expectedKind = BookKinds.PUBLICATION_INDEX,
                                    expectedPubkey = coordinate.pubkey,
                                    expectedDTag = coordinate.identifier,
                                ),
                            )?.event
                        },
                    )
                }
            }
        }.awaitAll().filterNotNull()
    }
    private suspend fun fetchLatestFromRelay(
        relayUrl: String,
        requestMessage: (String) -> String,
        verifyEvent: (NostrEvent) -> NostrEvent?,
    ): NostrEvent? {
        val latest = AtomicReference<NostrEvent?>(null)
        val done = CompletableDeferred<NostrEvent?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val subscriptionId = AtomicReference("bookshelf-${UUID.randomUUID()}")
        val authState = NostrRelayAuthState(authenticator != null)
        val authEventId = AtomicReference<String?>(null)
        val authChallenge = AtomicReference<String?>(null)
        val authSigningDeadlineNanos = AtomicLong(0L)
        lateinit var webSocket: WebSocket

        fun fail(message: String, cause: Throwable? = null) {
            if (!done.isCompleted) done.completeExceptionally(NostrRelayException(message, cause))
        }

        fun retryRequest(socket: WebSocket) {
            val id = "bookshelf-${UUID.randomUUID()}"
            subscriptionId.set(id)
            if (!socket.send(requestMessage(id))) fail("Relay $relayUrl rejected the authenticated request.")
        }

        fun afterAuthentication(socket: WebSocket) {
            if (authState.onAuthResult(true) == NostrRelayAuthState.Action.RETRY) retryRequest(socket)
        }

        lateinit var beginAuth: (WebSocket, String) -> Unit
        beginAuth = { socket, challenge ->
            val auth = authenticator
            if (auth != null) {
                authSigningDeadlineNanos.compareAndSet(0L, System.nanoTime() + AUTH_SIGNING_TIMEOUT_NANOS)
                scope.launch {
                val result = runCatching {
                    val draft = NostrAuthEventValidator.draft(auth.pubkey, relayUrl, challenge, nowSeconds())
                    val signed = auth.signAuthEvent(draft)
                    val verified = NostrAuthEventValidator.verify(signed, draft, nowSeconds())
                        ?: throw IllegalArgumentException("Authenticator returned an invalid NIP-42 event.")
                    authEventId.set(verified.id)
                    check(socket.send(authMessage(verified.event))) { "Relay $relayUrl rejected AUTH." }
                }
                result.exceptionOrNull()?.let { fail("Could not authenticate with relay $relayUrl.", it) }
                }
            }
        }

        webSocket = httpClient.newWebSocket(Request.Builder().url(relayUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!webSocket.send(requestMessage(subscriptionId.get()))) fail("Relay $relayUrl rejected the request.")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = parseMessage(text) ?: return
                    when (message.getOrNull(0).asString()) {
                        "AUTH" -> {
                            val challenge = message.getOrNull(1).asString()
                            if (challenge.isNullOrBlank()) fail("Relay $relayUrl sent an invalid AUTH challenge.")
                            else if (authState.onChallenge(challenge) != NostrRelayAuthState.Action.FAIL) {
                                authChallenge.set(challenge)
                            }
                        }
                        "EVENT" -> {
                            if (message.getOrNull(1).asString() != subscriptionId.get()) return
                            val event = runCatching {
                                message.getOrNull(2)?.let { json.decodeFromJsonElement<NostrEvent>(it) }
                            }.getOrNull()
                            event?.let(verifyEvent)?.also { verified ->
                                latest.updateAndGet { current ->
                                    if (current == null || verified.createdAt > current.createdAt ||
                                        (verified.createdAt == current.createdAt && verified.id > current.id)
                                    ) verified else current
                                }
                            }
                        }
                        "EOSE" -> if (message.getOrNull(1).asString() == subscriptionId.get()) done.complete(latest.get())
                        "CLOSED" -> {
                            if (message.getOrNull(1).asString() != subscriptionId.get()) return
                            val reason = message.getOrNull(2).asString().orEmpty()
                            if (isAuthRequired(reason)) when (authState.onAuthRequired()) {
                                NostrRelayAuthState.Action.RETRY -> retryRequest(webSocket)
                                NostrRelayAuthState.Action.AUTHENTICATE -> authChallenge.get()?.let { beginAuth(webSocket, it) }
                                    ?: fail("Relay $relayUrl requires authentication without a challenge.")
                                NostrRelayAuthState.Action.FAIL -> fail("Relay $relayUrl requires authentication: $reason")
                                NostrRelayAuthState.Action.NONE -> Unit
                            } else if (!done.isCompleted) done.complete(latest.get())
                        }
                        "OK" -> {
                            val expectedAuthEventId = authEventId.get()
                            if (expectedAuthEventId != null && message.getOrNull(1).asString() == expectedAuthEventId) {
                                if (message.getOrNull(2).safeBooleanOrNull() == true) afterAuthentication(webSocket)
                                else fail("Relay $relayUrl rejected NIP-42 authentication: ${message.getOrNull(3).asString().orEmpty()}")
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    fail("Relay $relayUrl failed.", t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!done.isCompleted) done.complete(latest.get())
                }
            },
        )

        try {
            return awaitWithAuthDeadline(done, timeoutMillis, authSigningDeadlineNanos) ?: latest.get()
        } finally {
            webSocket.send(closeMessage(subscriptionId.get()))
            webSocket.close(1000, "done")
            scope.cancel()
        }
    }

    suspend fun publishDirectory(event: NostrEvent): PublishReport = coroutineScope {
        if (NostrEventVerifier.verify(event) == null) return@coroutineScope PublishReport(0, relayUrls.size, null)
        val attempts = relayUrls.map { relayUrl -> async { runCatching { publishToRelay(relayUrl, event) } } }.awaitAll()
        PublishReport(
            acceptedRelays = attempts.count { it.getOrDefault(false) },
            attemptedRelays = relayUrls.size,
            eventId = event.id.takeIf(String::isNotBlank),
        )
    }

    private suspend fun publishToRelay(relayUrl: String, event: NostrEvent): Boolean {
        val done = CompletableDeferred<Boolean>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val authState = NostrRelayAuthState(authenticator != null)
        val authEventId = AtomicReference<String?>(null)
        val authChallenge = AtomicReference<String?>(null)
        val authSigningDeadlineNanos = AtomicLong(0L)
        lateinit var webSocket: WebSocket

        fun fail(message: String, cause: Throwable? = null) {
            if (!done.isCompleted) done.completeExceptionally(NostrRelayException(message, cause))
        }
        fun sendEvent(socket: WebSocket) {
            if (!socket.send(eventMessage(event))) done.complete(false)
        }
        fun retryEvent(socket: WebSocket) {
            sendEvent(socket)
        }
        fun afterAuthentication(socket: WebSocket) {
            if (authState.onAuthResult(true) == NostrRelayAuthState.Action.RETRY) retryEvent(socket)
        }

        lateinit var beginAuth: (WebSocket, String) -> Unit
        beginAuth = { socket, challenge ->
            val auth = authenticator
            if (auth != null) {
                authSigningDeadlineNanos.compareAndSet(0L, System.nanoTime() + AUTH_SIGNING_TIMEOUT_NANOS)
                scope.launch {
                val result = runCatching {
                    val draft = NostrAuthEventValidator.draft(auth.pubkey, relayUrl, challenge, nowSeconds())
                    val signed = auth.signAuthEvent(draft)
                    val verified = NostrAuthEventValidator.verify(signed, draft, nowSeconds())
                        ?: throw IllegalArgumentException("Authenticator returned an invalid NIP-42 event.")
                    authEventId.set(verified.id)
                    check(socket.send(authMessage(verified.event))) { "Relay $relayUrl rejected AUTH." }
                }
                result.exceptionOrNull()?.let { fail("Could not authenticate with relay $relayUrl.", it) }
                }
            }
        }

        webSocket = httpClient.newWebSocket(Request.Builder().url(relayUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { sendEvent(webSocket) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = parseMessage(text) ?: return
                    if (message.getOrNull(0).asString() != "AUTH" && message.getOrNull(0).asString() != "OK") return
                    if (message.getOrNull(0).asString() == "AUTH") {
                        val challenge = message.getOrNull(1).asString()
                        if (challenge.isNullOrBlank()) fail("Relay $relayUrl sent an invalid AUTH challenge.")
                        else if (authState.onChallenge(challenge) != NostrRelayAuthState.Action.FAIL) {
                            authChallenge.set(challenge)
                        }
                        return
                    }
                    val responseId = message.getOrNull(1).asString()
                    val expectedAuthEventId = authEventId.get()
                    if (expectedAuthEventId != null && responseId == expectedAuthEventId) {
                                if (message.getOrNull(2).safeBooleanOrNull() == true) afterAuthentication(webSocket)
                                else fail("Relay $relayUrl rejected NIP-42 authentication: ${message.getOrNull(3).asString().orEmpty()}")
                    } else if (responseId == event.id) {
                        if (message.getOrNull(2).safeBooleanOrNull() == true) done.complete(true)
                        else {
                            val reason = message.getOrNull(3).asString().orEmpty()
                            if (isAuthRequired(reason)) when (authState.onAuthRequired()) {
                                NostrRelayAuthState.Action.RETRY -> retryEvent(webSocket)
                                NostrRelayAuthState.Action.AUTHENTICATE -> authChallenge.get()?.let { beginAuth(webSocket, it) }
                                    ?: fail("Relay $relayUrl requires authentication without a challenge.")
                                NostrRelayAuthState.Action.FAIL -> done.complete(false)
                                NostrRelayAuthState.Action.NONE -> Unit
                            } else done.complete(false)
                        }
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (!done.isCompleted) done.complete(false) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (!done.isCompleted) done.complete(false) }
            },
        )

        try {
            return awaitWithAuthDeadline(done, timeoutMillis, authSigningDeadlineNanos) ?: false
        } finally {
            webSocket.close(1000, "done")
            scope.cancel()
        }
    }

    private fun directoryReqMessage(subscriptionId: String, pubkey: String): String = JsonArray(
        listOf(JsonPrimitive("REQ"), JsonPrimitive(subscriptionId), JsonObject(mapOf(
            "kinds" to JsonArray(listOf(JsonPrimitive(BookKinds.DIRECTORY))),
            "authors" to JsonArray(listOf(JsonPrimitive(pubkey))),
            "#d" to JsonArray(listOf(JsonPrimitive(BookshelfDirectoryRules.IDENTIFIER))),
            "limit" to JsonPrimitive(1),
        ))),
    ).toString()

    private fun publicationIndexReqMessage(subscriptionId: String, pubkey: String, identifier: String): String = JsonArray(
        listOf(JsonPrimitive("REQ"), JsonPrimitive(subscriptionId), JsonObject(mapOf(
            "kinds" to JsonArray(listOf(JsonPrimitive(BookKinds.PUBLICATION_INDEX))),
            "authors" to JsonArray(listOf(JsonPrimitive(pubkey))),
            "#d" to JsonArray(listOf(JsonPrimitive(identifier))),
            "limit" to JsonPrimitive(1),
        ))),
    ).toString()
    private fun eventMessage(event: NostrEvent): String = JsonArray(listOf(JsonPrimitive("EVENT"), json.encodeToJsonElement(event))).toString()
    private fun authMessage(event: NostrEvent): String = JsonArray(listOf(JsonPrimitive("AUTH"), json.encodeToJsonElement(event))).toString()
    private fun closeMessage(subscriptionId: String): String = JsonArray(listOf(JsonPrimitive("CLOSE"), JsonPrimitive(subscriptionId))).toString()

    private fun parseMessage(text: String): JsonArray? = text.takeIf {
        it.toByteArray(Charsets.UTF_8).size <= NostrEventVerifier.MAX_MESSAGE_BYTES
    }?.let { runCatching { json.decodeFromString<JsonArray>(it) }.getOrNull() }

    private fun NostrEvent.isDirectoryFor(pubkey: String): Boolean = kind == BookKinds.DIRECTORY &&
        this.pubkey.equals(pubkey, ignoreCase = true) &&
        tags.any { it.getOrNull(0) == "d" && it.getOrNull(1) == BookshelfDirectoryRules.IDENTIFIER }

    private fun NostrEvent.isProfileFor(pubkey: String): Boolean = kind == BookKinds.PROFILE_METADATA &&
        this.pubkey.equals(pubkey, ignoreCase = true)

    private companion object {
        const val MAX_CONCURRENT_PUBLICATION_LOOKUPS = 8
        private val HEX_64 = Regex("^[a-f0-9]{64}$", RegexOption.IGNORE_CASE)

        fun parsePublicationCoordinate(raw: String): PublicationCoordinate? {
            val parts = raw.trim().split(":", limit = 3)
            if (parts.size != 3 || parts[0].toIntOrNull() != BookKinds.PUBLICATION_INDEX ||
                !HEX_64.matches(parts[1]) || parts[2].isBlank()
            ) {
                return null
            }
            return PublicationCoordinate(parts[1].lowercase(), parts[2])
        }
        fun isAuthRequired(reason: String): Boolean {
            val normalized = reason.lowercase()
            return normalized.contains("auth-required") || normalized.contains("auth required")
        }
    }
}
private data class PublicationCoordinate(val pubkey: String, val identifier: String)

private fun kotlinx.serialization.json.JsonElement?.asString(): String? = (this as? JsonPrimitive)?.content

private fun kotlinx.serialization.json.JsonElement?.safeBooleanOrNull(): Boolean? =
    (this as? JsonPrimitive)?.booleanOrNull

private const val AUTH_SIGNING_TIMEOUT_NANOS = 90_000_000_000L

private suspend fun <T> awaitWithAuthDeadline(
    deferred: CompletableDeferred<T>,
    normalTimeoutMillis: Long,
    authDeadlineNanos: AtomicLong,
): T? {
    val normalDeadline = System.nanoTime() + normalTimeoutMillis.coerceAtLeast(1L) * 1_000_000L
    while (true) {
        val configuredDeadline = authDeadlineNanos.get()
        val deadline = if (configuredDeadline > 0L) configuredDeadline else normalDeadline
        val remainingNanos = deadline - System.nanoTime()
        if (remainingNanos <= 0L) return null
        val result = withTimeoutOrNull((remainingNanos / 1_000_000L).coerceAtLeast(1L)) {
            deferred.await()
        }
        if (deferred.isCompleted) return result
        if (authDeadlineNanos.get() == 0L) return null
    }
}

internal fun profileReqMessage(subscriptionId: String, pubkey: String): String = JsonArray(
    listOf(JsonPrimitive("REQ"), JsonPrimitive(subscriptionId), JsonObject(mapOf(
        "kinds" to JsonArray(listOf(JsonPrimitive(BookKinds.PROFILE_METADATA))),
        "authors" to JsonArray(listOf(JsonPrimitive(pubkey))),
        "limit" to JsonPrimitive(1),
    ))),
).toString()
