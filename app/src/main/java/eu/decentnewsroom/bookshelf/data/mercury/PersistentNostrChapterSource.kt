package eu.decentnewsroom.bookshelf.data.mercury

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import eu.decentnewsroom.bookshelf.data.nostr.NostrEventContext
import eu.decentnewsroom.bookshelf.data.nostr.NostrEventVerifier
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.ChapterReference
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.Closeable
import java.util.UUID

interface ChapterEventSource {
    suspend fun fetchChapters(references: List<ChapterReference>): List<NostrEvent>
}

/** Quartz-backed chapter relay source with one shared relay pool for active subscriptions. */
class PersistentNostrChapterSource(
    httpClient: OkHttpClient,
    private val relayUrls: () -> List<String>,
    private val timeoutMillis: Long = 8_000,
) : ChapterEventSource, Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = NostrClient(BasicOkHttpWebSocket.Builder { httpClient }, scope)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override suspend fun fetchChapters(references: List<ChapterReference>): List<NostrEvent> {
        val normalizedReferences = references.distinctBy(ChapterReference::coordinate)
        if (normalizedReferences.isEmpty()) return emptyList()

        val relays = ChapterRelayUrls.mergeWithHints(
            defaultRelayUrls = relayUrls(),
            relayHints = normalizedReferences.map(ChapterReference::relay),
        ).mapNotNull(RelayUrlNormalizer::normalizeOrNull).toCollection(LinkedHashSet())
        if (relays.isEmpty()) return emptyList()

        val subscription = ChapterSubscription(normalizedReferences, relays)
        val filters = chapterFilters(normalizedReferences)
        if (filters.isEmpty()) return emptyList()

        client.subscribe(
            subscription.id,
            relays.associateWith { filters },
            object : SubscriptionListener {
                override fun onEvent(
                    event: Event,
                    isLive: Boolean,
                    relay: NormalizedRelayUrl,
                    forFilters: List<Filter>?,
                ) {
                    subscription.record(toDomainEvent(event))
                }

                override fun onEose(relay: NormalizedRelayUrl, forFilters: List<Filter>?) {
                    subscription.complete(relay)
                }

                override fun onClosed(
                    message: String,
                    relay: NormalizedRelayUrl,
                    forFilters: List<Filter>?,
                ) {
                    subscription.failRelay(relay)
                }

                override fun onCannotConnect(
                    relay: NormalizedRelayUrl,
                    message: String,
                    forFilters: List<Filter>?,
                ) {
                    subscription.failRelay(relay)
                }
            },
        )

        return try {
            withTimeoutOrNull(timeoutMillis) { subscription.result.await() } ?: subscription.snapshot()
        } finally {
            client.unsubscribe(subscription.id)
        }
    }

    override fun close() {
        client.close()
        scope.cancel()
    }

    private fun toDomainEvent(event: Event): NostrEvent? =
        runCatching { json.decodeFromString<NostrEvent>(event.toJson()) }.getOrNull()
}

internal fun chapterFilters(references: List<ChapterReference>): List<Filter> {
    val filters = mutableListOf<Filter>()
    val ids = references.mapNotNull(ChapterReference::eventId).distinct()
    if (ids.isNotEmpty()) {
        filters += Filter(
            ids = ids,
            kinds = listOf(BookKinds.PUBLICATION_CONTENT),
            limit = ids.size,
        )
    }

    references.groupBy { it.pubkey.lowercase() }.forEach { (pubkey, authorReferences) ->
        val identifiers = authorReferences.map(ChapterReference::identifier).distinct()
        if (identifiers.isNotEmpty()) {
            filters += Filter(
                authors = listOf(pubkey),
                kinds = listOf(BookKinds.PUBLICATION_CONTENT),
                tags = mapOf("d" to identifiers),
                limit = identifiers.size,
            )
        }
    }
    return filters
}

private class ChapterSubscription(
    references: List<ChapterReference>,
    private val relays: Set<NormalizedRelayUrl>,
) {
    val id = "chapters-${UUID.randomUUID()}"
    val result = CompletableDeferred<List<NostrEvent>>()
    private val expectedCoordinates = references.map(ChapterReference::coordinate).toSet()
    private val idBoundCoordinates = references
        .filter { it.eventId != null }
        .associate { it.coordinate to it.eventId!!.lowercase() }
    private val events = mutableMapOf<String, NostrEvent>()
    private val remainingRelays = relays.toMutableSet()
    private val failedRelays = mutableSetOf<NormalizedRelayUrl>()

    fun record(event: NostrEvent?) {
        val verified = event?.let {
            NostrEventVerifier.verify(
                it,
                context = NostrEventContext(expectedKind = BookKinds.PUBLICATION_CONTENT),
            )?.event
        } ?: return
        val coordinate = chapterCoordinate(verified) ?: return
        if (coordinate !in expectedCoordinates) return
        if (idBoundCoordinates[coordinate] != null && idBoundCoordinates[coordinate] != verified.id) return

        synchronized(this) {
            val current = events[coordinate]
            if (current == null || verified.createdAt > current.createdAt ||
                (verified.createdAt == current.createdAt && verified.id > current.id)
            ) {
                events[coordinate] = verified
            }
        }
    }

    fun complete(relay: NormalizedRelayUrl) = finishRelay(relay, failed = false)

    fun failRelay(relay: NormalizedRelayUrl) = finishRelay(relay, failed = true)

    fun snapshot(): List<NostrEvent> = synchronized(this) { events.values.toList() }

    private fun finishRelay(relay: NormalizedRelayUrl, failed: Boolean) {
        synchronized(this) {
            if (!remainingRelays.remove(relay)) return
            if (failed) failedRelays += relay
            if (remainingRelays.isNotEmpty() || result.isCompleted) return
            if (failedRelays.size == relays.size && events.isEmpty()) {
                result.completeExceptionally(MercuryApiException("Configured chapter relays could not be reached."))
            } else {
                result.complete(events.values.toList())
            }
        }
    }
}

private fun chapterCoordinate(event: NostrEvent): String? {
    val identifier = event.tags.firstNotNullOfOrNull { tag ->
        tag.getOrNull(1)?.takeIf { tag.getOrNull(0) == "d" && it.isNotBlank() }
    } ?: return null
    if (event.pubkey.isBlank()) return null
    return "${BookKinds.PUBLICATION_CONTENT}:${event.pubkey.lowercase()}:$identifier"
}