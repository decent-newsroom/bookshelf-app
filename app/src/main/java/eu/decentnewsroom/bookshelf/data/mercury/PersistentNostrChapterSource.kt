package eu.decentnewsroom.bookshelf.data.mercury

import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.ChapterReference
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import eu.decentnewsroom.bookshelf.data.nostr.NostrEventContext
import eu.decentnewsroom.bookshelf.data.nostr.NostrEventVerifier
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
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface ChapterEventSource {
    suspend fun fetchChapters(references: List<ChapterReference>): List<NostrEvent>
}

class PersistentNostrChapterSource(
    private val httpClient: OkHttpClient,
    private val relayUrls: () -> List<String>,
    private val timeoutMillis: Long = 8_000,
) : ChapterEventSource, Closeable {
    private val connectionLock = Any()
    private val connections = mutableMapOf<String, RelayConnection>()

    override suspend fun fetchChapters(references: List<ChapterReference>): List<NostrEvent> =
        coroutineScope {
            val normalizedReferences = references.distinctBy(ChapterReference::coordinate)
            if (normalizedReferences.isEmpty()) {
                return@coroutineScope emptyList()
            }

            val activeConnections = connectionsFor(ChapterRelayUrls.normalize(relayUrls()))
            if (activeConnections.isEmpty()) {
                return@coroutineScope emptyList()
            }

            val attempts =
                activeConnections.map { connection ->
                    async { runCatching { connection.fetch(normalizedReferences) } }
                }.awaitAll()

            if (attempts.all { it.isFailure }) {
                throw MercuryApiException("Configured chapter relays could not be reached.")
            }

            attempts
                .flatMap { it.getOrDefault(emptyList()) }
                .groupBy(::chapterCoordinate)
                .values
                .mapNotNull { events ->
                    events.maxWithOrNull(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
                }
        }

    override fun close() {
        val closing = synchronized(connectionLock) {
            connections.values.toList().also { connections.clear() }
        }
        closing.forEach { it.close("Chapter source closed") }
    }

    private fun connectionsFor(urls: List<String>): List<RelayConnection> =
        synchronized(connectionLock) {
            val removed = connections.keys - urls.toSet()
            removed.mapNotNull(connections::remove).forEach { it.close("Chapter source removed") }
            urls.map { url -> connections.getOrPut(url) { RelayConnection(url) } }
        }

    private inner class RelayConnection(
        private val relayUrl: String,
    ) : Closeable {
        private val stateLock = Any()
        private val subscriptions = ConcurrentHashMap<String, ChapterSubscription>()
        private var webSocket: WebSocket? = null
        private var opening: CompletableDeferred<WebSocket>? = null

        private val listener =
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val waiting = synchronized(stateLock) {
                        this@RelayConnection.webSocket = webSocket
                        opening.also { opening = null }
                    }
                    waiting?.complete(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = parseRelayMessage(text) ?: return
                    val type = message.getOrNull(0)?.asString() ?: return
                    val subscriptionId = message.getOrNull(1)?.asString() ?: return
                    val subscription = subscriptions[subscriptionId] ?: return

                    when (type) {
                        "EVENT" -> {
                            val event = message.getOrNull(2)?.let { element ->
                                runCatching { RELAY_JSON.decodeFromJsonElement<NostrEvent>(element) }.getOrNull()
                            }
                            if (event != null) subscription.record(event)
                        }

                        "EOSE", "CLOSED" -> subscription.complete()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failConnection(webSocket, MercuryApiException("Chapter relay $relayUrl failed.", t))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    failConnection(webSocket, MercuryApiException("Chapter relay $relayUrl closed."))
                }
            }

        suspend fun fetch(references: List<ChapterReference>): List<NostrEvent> {
            val subscription = ChapterSubscription(references)
            subscriptions[subscription.id] = subscription

            try {
                val socket = connection()
                check(socket.send(chapterReqMessage(subscription.id, references))) {
                    "Chapter relay $relayUrl rejected a request."
                }
                return withTimeoutOrNull(timeoutMillis) { subscription.result.await() }
                    ?: subscription.snapshot()
            } finally {
                subscriptions.remove(subscription.id)
                synchronized(stateLock) { webSocket }?.send(closeSubscriptionMessage(subscription.id))
            }
        }

        override fun close() {
            close("Chapter relay closed")
        }

        fun close(reason: String) {
            val socket = synchronized(stateLock) {
                webSocket.also { webSocket = null }
            }
            socket?.close(1000, reason)
            failSubscriptions(MercuryApiException(reason))
        }

        private suspend fun connection(): WebSocket {
            val pending = synchronized(stateLock) {
                webSocket?.let { return it }
                opening ?: CompletableDeferred<WebSocket>().also { deferred ->
                    opening = deferred
                    httpClient.newWebSocket(Request.Builder().url(relayUrl).build(), listener)
                }
            }

            return withTimeoutOrNull(timeoutMillis) { pending.await() }
                ?: throw MercuryApiException("Timed out connecting to chapter relay $relayUrl.")
        }

        private fun failConnection(failedSocket: WebSocket, failure: Throwable) {
            val waiting = synchronized(stateLock) {
                if (webSocket === failedSocket) {
                    webSocket = null
                }
                opening.also { opening = null }
            }
            waiting?.completeExceptionally(failure)
            failSubscriptions(failure)
        }

        private fun failSubscriptions(failure: Throwable) {
            subscriptions.values.forEach { subscription -> subscription.fail(failure) }
        }
    }

    private class ChapterSubscription(references: List<ChapterReference>) {
        val id = "chapters-${UUID.randomUUID()}"
        val result = CompletableDeferred<List<NostrEvent>>()
        private val expectedCoordinates = references.map(ChapterReference::coordinate).toSet()
        private val idBoundCoordinates = references
            .filter { it.eventId != null }
            .associate { it.coordinate to it.eventId!!.lowercase() }
        private val events = ConcurrentHashMap<String, NostrEvent>()

        fun record(event: NostrEvent) {
            val verified = NostrEventVerifier.verify(
                event,
                context = NostrEventContext(expectedKind = BookKinds.PUBLICATION_CONTENT),
            )?.event ?: return
            val coordinate = chapterCoordinate(verified) ?: return
            if (coordinate !in expectedCoordinates) return
            if (idBoundCoordinates[coordinate] != null && idBoundCoordinates[coordinate] != verified.id) {
                return
            }
            events.compute(coordinate) { _, current ->
                if (current == null ||
                    verified.createdAt > current.createdAt ||
                    (verified.createdAt == current.createdAt && verified.id > current.id)
                ) verified else current
            }
        }

        fun snapshot(): List<NostrEvent> = events.values.toList()

        fun complete() {
            result.complete(snapshot())
        }

        fun fail(failure: Throwable) {
            result.completeExceptionally(failure)
        }
    }

    private companion object {
        val RELAY_JSON = Json { ignoreUnknownKeys = true }
    }
}

internal fun chapterReqMessage(subscriptionId: String, references: List<ChapterReference>): String {
    val filters = mutableListOf<JsonObject>()
    val ids = references.mapNotNull(ChapterReference::eventId).distinct()
    if (ids.isNotEmpty()) {
        filters += JsonObject(
            mapOf(
                "ids" to JsonArray(ids.map(::JsonPrimitive)),
                "kinds" to JsonArray(listOf(JsonPrimitive(BookKinds.PUBLICATION_CONTENT))),
                "limit" to JsonPrimitive(ids.size),
            ),
        )
    }

    references.groupBy(ChapterReference::pubkey).forEach { (pubkey, authorReferences) ->
        val identifiers = authorReferences.map(ChapterReference::identifier).distinct()
        filters += JsonObject(
            mapOf(
                "authors" to JsonArray(listOf(JsonPrimitive(pubkey))),
                "kinds" to JsonArray(listOf(JsonPrimitive(BookKinds.PUBLICATION_CONTENT))),
                "#d" to JsonArray(identifiers.map(::JsonPrimitive)),
                "limit" to JsonPrimitive(identifiers.size),
            ),
        )
    }

    return JsonArray(
        listOf(JsonPrimitive("REQ"), JsonPrimitive(subscriptionId)) + filters,
    ).toString()
}

private fun closeSubscriptionMessage(subscriptionId: String): String =
    JsonArray(listOf(JsonPrimitive("CLOSE"), JsonPrimitive(subscriptionId))).toString()

private fun parseRelayMessage(text: String): JsonArray? =
    text.takeIf { it.toByteArray(Charsets.UTF_8).size <= NostrEventVerifier.MAX_MESSAGE_BYTES }?.let {
        runCatching { Json.decodeFromString<JsonArray>(it) }.getOrNull()
    }

private fun kotlinx.serialization.json.JsonElement.asString(): String? =
    (this as? JsonPrimitive)?.content

private fun chapterCoordinate(event: NostrEvent): String? {
    val identifier = event.tags.firstNotNullOfOrNull { tag ->
        tag.getOrNull(1)?.takeIf { tag.getOrNull(0) == "d" && it.isNotBlank() }
    } ?: return null
    if (event.pubkey.isBlank()) {
        return null
    }
    return "${BookKinds.PUBLICATION_CONTENT}:${event.pubkey.lowercase()}:$identifier"
}
