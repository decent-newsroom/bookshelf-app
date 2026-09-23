package eu.decentnewsroom.bookshelf.data.ratings

import android.content.Context
import eu.decentnewsroom.bookshelf.data.nostr.BookshelfRelaySync
import eu.decentnewsroom.bookshelf.data.nostr.PublishReport
import eu.decentnewsroom.bookshelf.data.nostr.RelayPublishOutcomeType
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Durable app-private queue for signed review events awaiting relay acknowledgement. */
class ReviewOutbox private constructor(private val file: File, private val now: () -> Long, private val ratingCache: BookRatingCache?) {
    constructor(context: Context) : this(File(File(context.applicationContext.filesDir, "bookshelf"), FILE_NAME), System::currentTimeMillis, BookRatingCache(context))

    private val mutex = Mutex()

    suspend fun enqueue(event: NostrEvent, publicationAuthorPubkey: String): ReviewOutboxEntry = withContext(Dispatchers.IO) {
        require(event.id.isNotBlank()) { "A signed review event ID is required." }
        mutex.withLock {
            val state = read()
            val entry = state.entries.firstOrNull { it.event.id == event.id } ?: ReviewOutboxEntry(
                event = event,
                publicationAuthorPubkey = publicationAuthorPubkey,
                queuedAtMillis = now(),
                nextRetryAtMillis = now(),
            ).also { write(state.copy(entries = state.entries + it)) }
            // This cache is distinct from the outbox. A cache write failure cannot lose the
            // already-durable signed event, which will remain eligible for later delivery.
            ratingCache?.merge(listOf(event))
            entry
        }
    }

    suspend fun pending(): List<ReviewOutboxEntry> = withContext(Dispatchers.IO) { mutex.withLock { read().entries.filterNot(ReviewOutboxEntry::isComplete) } }
    suspend fun entry(eventId: String): ReviewOutboxEntry? = withContext(Dispatchers.IO) { mutex.withLock { read().entries.firstOrNull { it.event.id == eventId } } }
    suspend fun pendingCount(): Int = pending().size

    suspend fun update(eventId: String, transform: (ReviewOutboxEntry) -> ReviewOutboxEntry): ReviewOutboxEntry? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val state = read()
            val old = state.entries.firstOrNull { it.event.id == eventId } ?: return@withLock null
            val next = transform(old)
            write(state.copy(entries = state.entries.map { if (it.event.id == eventId) next else it }))
            next
        }
    }

    private fun read(): PersistedOutbox = runCatching {
        if (!file.isFile) PersistedOutbox() else json.decodeFromString(PersistedOutbox.serializer(), file.readText(Charsets.UTF_8))
    }.getOrDefault(PersistedOutbox())

    private fun write(value: PersistedOutbox) {
        file.parentFile?.mkdirs()
        val temporary = File(requireNotNull(file.parentFile), "$FILE_NAME.tmp")
        temporary.writeText(json.encodeToString(PersistedOutbox.serializer(), value), Charsets.UTF_8)
        runCatching { Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
            .recoverCatching { Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING) }
            .getOrElse { temporary.delete(); throw IllegalStateException("Could not update review outbox.", it) }
    }

    @Serializable private data class PersistedOutbox(val entries: List<ReviewOutboxEntry> = emptyList())
    private companion object {
        const val FILE_NAME = "review-outbox-v1.json"
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}

@Serializable
enum class ReviewDeliveryState { NOT_CONFIGURED, PENDING, ACCEPTED, FAILED }

@Serializable
data class ReviewOutboxEntry(
    val event: NostrEvent,
    val publicationAuthorPubkey: String,
    val queuedAtMillis: Long,
    val citrine: ReviewDeliveryState = ReviewDeliveryState.NOT_CONFIGURED,
    val remote: Map<String, ReviewDeliveryState> = emptyMap(),
    val remoteRoutesResolved: Boolean = false,
    val attempts: Int = 0,
    val nextRetryAtMillis: Long,
    val lastFailure: String? = null,
) {
    val isComplete: Boolean
        get() = citrine !in setOf(ReviewDeliveryState.PENDING, ReviewDeliveryState.FAILED) &&
            remoteRoutesResolved && remote.values.none { it in setOf(ReviewDeliveryState.PENDING, ReviewDeliveryState.FAILED) }
}

/** Publishes a saved event to local Citrine immediately and to remote relays after validated connectivity. */
class ReviewOutboxDispatcher(
    private val outbox: ReviewOutbox,
    private val relaySync: BookshelfRelaySync,
    private val localRelayUrl: () -> String?,
    private val isOnline: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val deliveryMutex = Mutex()

    /** Saves a signed event before any relay work so the cache can show it immediately. */
    suspend fun enqueue(event: NostrEvent, publicationAuthorPubkey: String): ReviewOutboxEntry =
        outbox.enqueue(event, publicationAuthorPubkey)

    /** Delivers an already durable event without changing its signed ID. */
    suspend fun deliverSaved(entry: ReviewOutboxEntry): ReviewOutboxEntry = deliveryMutex.withLock {
        var current = outbox.entry(entry.event.id) ?: return@withLock entry
        if (!current.isComplete) {
            current = deliverCitrine(current, localRelayUrl())
            if (isOnline()) current = deliverRemote(current)
        }
        current
    }

    suspend fun enqueueAndTryCitrine(event: NostrEvent, publicationAuthorPubkey: String): ReviewOutboxEntry {
        return deliverSaved(enqueue(event, publicationAuthorPubkey))
    }

    /** Processes all due entries. Returns the number of queued reviews examined. */
    suspend fun syncPending(force: Boolean = false): Int = deliveryMutex.withLock {
        val due = outbox.pending().filter { force || it.nextRetryAtMillis <= now() }
        due.forEach { original ->
            var entry = deliverCitrine(original, localRelayUrl())
            if (isOnline()) entry = deliverRemote(entry)
        }
        due.size
    }

    private suspend fun deliverCitrine(entry: ReviewOutboxEntry, relayUrl: String?): ReviewOutboxEntry {
        if (relayUrl == null || entry.citrine == ReviewDeliveryState.ACCEPTED) return entry
        return try {
            val report = relaySync.publishToRelay(entry.event, relayUrl)
            persistCitrine(entry, report)
        } catch (failure: Exception) {
            persistFailure(entry, citrineFailure = failure.message ?: "Could not reach Citrine.")
        }
    }

    private suspend fun deliverRemote(entry: ReviewOutboxEntry): ReviewOutboxEntry {
        val routes = try { relaySync.ratingRelayUrls(entry.event, entry.publicationAuthorPubkey) } catch (failure: Exception) {
            return persistFailure(entry, remoteFailure = failure.message ?: "Could not resolve review relay routes.")
        }
        val current = outbox.update(entry.event.id) { previous ->
            previous.copy(remoteRoutesResolved = true, remote = (previous.remote + routes.associateWith { previous.remote[it] ?: ReviewDeliveryState.PENDING }))
        } ?: return entry
        val pendingRoutes = routes.filter { current.remote[it] != ReviewDeliveryState.ACCEPTED }
        if (pendingRoutes.isEmpty()) return current
        return try {
            persistRemote(current, relaySync.publishRatingToRelays(current.event, pendingRoutes))
        } catch (failure: Exception) {
            persistFailure(current, remoteFailure = failure.message ?: "Could not publish review to remote relays.")
        }
    }

    private suspend fun persistCitrine(entry: ReviewOutboxEntry, report: PublishReport): ReviewOutboxEntry =
        outbox.update(entry.event.id) { previous ->
            val accepted = report.outcomes.any { it.type == RelayPublishOutcomeType.ACCEPTED }
            val failure = report.outcomes.firstOrNull { it.type != RelayPublishOutcomeType.ACCEPTED }?.reason
            previous.withAttempt(citrine = if (accepted) ReviewDeliveryState.ACCEPTED else ReviewDeliveryState.FAILED, failure = failure)
        } ?: entry

    private suspend fun persistRemote(entry: ReviewOutboxEntry, report: PublishReport): ReviewOutboxEntry =
        outbox.update(entry.event.id) { previous ->
            val nextRemote = previous.remote.toMutableMap()
            report.outcomes.forEach { outcome -> nextRemote[outcome.relayUrl] = if (outcome.type == RelayPublishOutcomeType.ACCEPTED) ReviewDeliveryState.ACCEPTED else ReviewDeliveryState.FAILED }
            val failure = report.outcomes.firstOrNull { it.type != RelayPublishOutcomeType.ACCEPTED }?.reason
            previous.withAttempt(remote = nextRemote, failure = failure)
        } ?: entry

    private suspend fun persistFailure(entry: ReviewOutboxEntry, citrineFailure: String? = null, remoteFailure: String? = null): ReviewOutboxEntry =
        outbox.update(entry.event.id) { previous ->
            previous.withAttempt(
                citrine = if (citrineFailure != null && previous.citrine != ReviewDeliveryState.ACCEPTED) ReviewDeliveryState.FAILED else previous.citrine,
                remote = if (remoteFailure != null) previous.remote.mapValues { (_, state) -> if (state == ReviewDeliveryState.PENDING) ReviewDeliveryState.FAILED else state } else previous.remote,
                failure = citrineFailure ?: remoteFailure,
            )
        } ?: entry

    private fun ReviewOutboxEntry.withAttempt(citrine: ReviewDeliveryState = this.citrine, remote: Map<String, ReviewDeliveryState> = this.remote, failure: String?): ReviewOutboxEntry {
        val nextAttempts = attempts + 1
        return copy(citrine = citrine, remote = remote, attempts = nextAttempts, nextRetryAtMillis = now() + retryDelay(nextAttempts), lastFailure = failure)
    }

    private fun retryDelay(attempt: Int): Long = (1_000L shl (attempt - 1).coerceAtMost(10)).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
    private companion object { const val MAX_RETRY_DELAY_MILLIS = 15 * 60 * 1_000L }
}