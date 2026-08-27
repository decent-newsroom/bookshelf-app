package eu.decentnewsroom.bookshelf.data.nostr

import eu.decentnewsroom.bookshelf.data.bookshelf.BookshelfDirectoryRules
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

class NostrRelayException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class NostrRelayClient(
    private val httpClient: OkHttpClient,
    val relayUrls: List<String>,
    private val timeoutMillis: Long = 5_000,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    suspend fun fetchLatestDirectory(pubkey: String): NostrEvent? =
        coroutineScope {
            val attempts =
                relayUrls.map { relayUrl ->
                    async {
                        runCatching { fetchLatestDirectoryFromRelay(relayUrl, pubkey) }
                    }
                }.awaitAll()

            if (attempts.isNotEmpty() && attempts.all { it.isFailure }) {
                throw NostrRelayException("Could not reach configured relays.")
            }

            attempts
                .mapNotNull { it.getOrNull() }
                .maxByOrNull(NostrEvent::createdAt)
        }

    suspend fun fetchLatestProfile(pubkey: String): NostrEvent? =
        coroutineScope {
            val attempts =
                relayUrls.map { relayUrl ->
                    async {
                        runCatching { fetchLatestProfileFromRelay(relayUrl, pubkey) }
                    }
                }.awaitAll()

            if (attempts.isNotEmpty() && attempts.all { it.isFailure }) {
                throw NostrRelayException("Could not reach configured relays.")
            }

            attempts
                .mapNotNull { it.getOrNull() }
                .maxByOrNull(NostrEvent::createdAt)
        }

    suspend fun publishDirectory(event: NostrEvent): PublishReport =
        coroutineScope {
            val attempts =
                relayUrls.map { relayUrl ->
                    async {
                        runCatching { publishToRelay(relayUrl, event) }
                    }
                }.awaitAll()

            PublishReport(
                acceptedRelays = attempts.count { it.getOrDefault(false) },
                attemptedRelays = relayUrls.size,
                eventId = event.id.takeIf(String::isNotBlank),
            )
        }

    private suspend fun fetchLatestDirectoryFromRelay(relayUrl: String, pubkey: String): NostrEvent? {
        val subscriptionId = "bookshelf-${UUID.randomUUID()}"
        val latest = AtomicReference<NostrEvent?>(null)
        val done = CompletableDeferred<NostrEvent?>()
        lateinit var webSocket: WebSocket

        webSocket = httpClient.newWebSocket(
            Request.Builder().url(relayUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(directoryReqMessage(subscriptionId, pubkey))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = parseMessage(text) ?: return
                    when (message.getOrNull(0)?.jsonPrimitive?.content) {
                        "EVENT" -> {
                            val event =
                                runCatching {
                                    json.decodeFromJsonElement<NostrEvent>(message[2])
                                }.getOrNull()

                            if (event != null && event.isDirectoryFor(pubkey)) {
                                latest.updateAndGet { current ->
                                    if (current == null || event.createdAt > current.createdAt) {
                                        event
                                    } else {
                                        current
                                    }
                                }
                            }
                        }

                        "EOSE", "CLOSED" -> {
                            if (!done.isCompleted) {
                                done.complete(latest.get())
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!done.isCompleted) {
                        done.completeExceptionally(NostrRelayException("Relay $relayUrl failed.", t))
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!done.isCompleted) {
                        done.complete(latest.get())
                    }
                }
            },
        )

        val result = withTimeoutOrNull(timeoutMillis) { done.await() } ?: latest.get()
        webSocket.send(closeMessage(subscriptionId))
        webSocket.close(1000, "done")
        return result
    }

    private suspend fun fetchLatestProfileFromRelay(relayUrl: String, pubkey: String): NostrEvent? {
        val subscriptionId = "profile-${UUID.randomUUID()}"
        val latest = AtomicReference<NostrEvent?>(null)
        val done = CompletableDeferred<NostrEvent?>()
        lateinit var webSocket: WebSocket

        webSocket = httpClient.newWebSocket(
            Request.Builder().url(relayUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(profileReqMessage(subscriptionId, pubkey))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = parseMessage(text) ?: return
                    when (message.getOrNull(0)?.jsonPrimitive?.content) {
                        "EVENT" -> {
                            val event =
                                runCatching {
                                    json.decodeFromJsonElement<NostrEvent>(message[2])
                                }.getOrNull()

                            if (event != null && event.isProfileFor(pubkey)) {
                                latest.updateAndGet { current ->
                                    if (current == null || event.createdAt > current.createdAt) {
                                        event
                                    } else {
                                        current
                                    }
                                }
                            }
                        }

                        "EOSE", "CLOSED" -> {
                            if (!done.isCompleted) {
                                done.complete(latest.get())
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!done.isCompleted) {
                        done.completeExceptionally(NostrRelayException("Relay $relayUrl failed.", t))
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!done.isCompleted) {
                        done.complete(latest.get())
                    }
                }
            },
        )

        val result = withTimeoutOrNull(timeoutMillis) { done.await() } ?: latest.get()
        webSocket.send(closeMessage(subscriptionId))
        webSocket.close(1000, "done")
        return result
    }

    private suspend fun publishToRelay(relayUrl: String, event: NostrEvent): Boolean {
        val done = CompletableDeferred<Boolean>()
        lateinit var webSocket: WebSocket

        webSocket = httpClient.newWebSocket(
            Request.Builder().url(relayUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(eventMessage(event))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = parseMessage(text) ?: return
                    if (
                        message.getOrNull(0)?.jsonPrimitive?.content == "OK" &&
                        message.getOrNull(1)?.jsonPrimitive?.content == event.id
                    ) {
                        done.complete(message.getOrNull(2)?.jsonPrimitive?.booleanOrNull == true)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!done.isCompleted) {
                        done.complete(false)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!done.isCompleted) {
                        done.complete(false)
                    }
                }
            },
        )

        val accepted = withTimeoutOrNull(timeoutMillis) { done.await() } ?: false
        webSocket.close(1000, "done")
        return accepted
    }

    private fun directoryReqMessage(subscriptionId: String, pubkey: String): String =
        JsonArray(
            listOf(
                JsonPrimitive("REQ"),
                JsonPrimitive(subscriptionId),
                JsonObject(
                    mapOf(
                        "kinds" to JsonArray(listOf(JsonPrimitive(BookKinds.DIRECTORY))),
                        "authors" to JsonArray(listOf(JsonPrimitive(pubkey))),
                        "#d" to JsonArray(listOf(JsonPrimitive(BookshelfDirectoryRules.IDENTIFIER))),
                        "limit" to JsonPrimitive(1),
                    ),
                ),
            ),
        ).toString()

    private fun eventMessage(event: NostrEvent): String =
        JsonArray(
            listOf(
                JsonPrimitive("EVENT"),
                json.encodeToJsonElement(event),
            ),
        ).toString()

    private fun closeMessage(subscriptionId: String): String =
        JsonArray(
            listOf(
                JsonPrimitive("CLOSE"),
                JsonPrimitive(subscriptionId),
            ),
        ).toString()

    private fun parseMessage(text: String): JsonArray? =
        runCatching { json.decodeFromString<JsonArray>(text) }.getOrNull()

    private fun NostrEvent.isDirectoryFor(pubkey: String): Boolean =
        kind == BookKinds.DIRECTORY &&
            this.pubkey.equals(pubkey, ignoreCase = true) &&
            tags.any { it.getOrNull(0) == "d" && it.getOrNull(1) == BookshelfDirectoryRules.IDENTIFIER }

    private fun NostrEvent.isProfileFor(pubkey: String): Boolean =
        kind == BookKinds.PROFILE_METADATA && this.pubkey.equals(pubkey, ignoreCase = true)
}

internal fun profileReqMessage(subscriptionId: String, pubkey: String): String =
    JsonArray(
        listOf(
            JsonPrimitive("REQ"),
            JsonPrimitive(subscriptionId),
            JsonObject(
                mapOf(
                    "kinds" to JsonArray(listOf(JsonPrimitive(BookKinds.PROFILE_METADATA))),
                    "authors" to JsonArray(listOf(JsonPrimitive(pubkey))),
                    "limit" to JsonPrimitive(1),
                ),
            ),
        ),
    ).toString()
