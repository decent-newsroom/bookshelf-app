package eu.decentnewsroom.bookshelf.data.highlights

import android.content.Context
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import eu.decentnewsroom.bookshelf.data.nostr.BookshelfRelaySync
import eu.decentnewsroom.bookshelf.data.nostr.NostrEventContext
import eu.decentnewsroom.bookshelf.data.nostr.NostrEventVerifier
import eu.decentnewsroom.bookshelf.data.nostr.PublishReport
import eu.decentnewsroom.bookshelf.data.nostr.RelayPublishOutcomeType
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.CancellationException
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

/**
 * Durable app-private queue for a signed NIP-84 highlight and the exact chapter event it cites.
 *
 * The pair is written before any relay work. Per-relay acknowledgement is retained separately for
 * each event, so retrying never changes either signed event ID and cannot mistake a partial pair
 * delivery for success.
 */
class HighlightOutbox internal constructor(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(File(File(context.applicationContext.filesDir, "bookshelf"), FILE_NAME))

    private val mutex = Mutex()

    suspend fun enqueue(
        highlight: NostrEvent,
        chapter: NostrEvent,
        localHighlightId: String = "",
    ): HighlightOutboxEntry = withContext(Dispatchers.IO) {
        validatePair(highlight, chapter)
        mutex.withLock {
            val state = read()
            val existing = state.entries.firstOrNull { it.event.id == highlight.id }
            when {
                existing == null -> HighlightOutboxEntry(
                    event = highlight,
                    chapterEvent = chapter,
                    localHighlightId = localHighlightId,
                    queuedAtMillis = now(),
                    nextRetryAtMillis = now(),
                ).also { write(state.copy(entries = state.entries + it)) }
                existing.chapterEvent.id != chapter.id ->
                    throw IllegalArgumentException("A highlight event ID cannot be paired with a different chapter event.")
                existing.localHighlightId.isBlank() && localHighlightId.isNotBlank() ->
                    existing.copy(localHighlightId = localHighlightId).also { replacement ->
                        write(state.copy(entries = state.entries.map { if (it.event.id == highlight.id) replacement else it }))
                    }
                existing.localHighlightId.isNotBlank() && localHighlightId.isNotBlank() && existing.localHighlightId != localHighlightId ->
                    throw IllegalArgumentException("A highlight event ID is already linked to another local highlight.")
                else -> existing
            }
        }
    }

    suspend fun pending(): List<HighlightOutboxEntry> = withContext(Dispatchers.IO) {
        mutex.withLock { read().entries.filterNot(HighlightOutboxEntry::isComplete) }
    }

    /** Includes completed entries so private highlight records can recover their delivery state. */
    suspend fun entries(): List<HighlightOutboxEntry> = withContext(Dispatchers.IO) {
        mutex.withLock { read().entries }
    }

    internal suspend fun update(eventId: String, transform: (HighlightOutboxEntry) -> HighlightOutboxEntry): HighlightOutboxEntry? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val state = read()
                val old = state.entries.firstOrNull { it.event.id == eventId } ?: return@withLock null
                val next = transform(old)
                write(state.copy(entries = state.entries.map { if (it.event.id == eventId) next else it }))
                next
            }
        }

    internal fun validate(entry: HighlightOutboxEntry) = validatePair(entry.event, entry.chapterEvent)

    private fun validatePair(highlight: NostrEvent, chapter: NostrEvent) {
        NostrEventVerifier.requireVerified(highlight, context = NostrEventContext(expectedKind = HIGHLIGHT_KIND))
        NostrEventVerifier.requireVerified(chapter, context = NostrEventContext(expectedKind = BookKinds.PUBLICATION_CONTENT))
        val chapterIdentifier = chapter.tags.firstOrNull { it.getOrNull(0) == "d" }?.getOrNull(1)
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("The cited chapter must have a d tag.")
        val coordinate = "${BookKinds.PUBLICATION_CONTENT}:${chapter.pubkey.lowercase()}:$chapterIdentifier"
        require(highlight.tags.any { it.getOrNull(0) == "e" && it.getOrNull(1) == chapter.id }) {
            "The highlight must cite its exact chapter event with an e tag."
        }
        require(highlight.tags.any { it.getOrNull(0) == "a" && it.getOrNull(1) == coordinate }) {
            "The highlight must cite its chapter coordinate with an a tag."
        }
    }

    private fun read(): PersistedOutbox {
        if (!file.exists()) return PersistedOutbox()
        if (!file.isFile) throw IllegalStateException("Highlight outbox path is not a file.")
        return try {
            json.decodeFromString(PersistedOutbox.serializer(), file.readText(Charsets.UTF_8))
        } catch (failure: Exception) {
            throw IllegalStateException("Could not read highlight outbox; the queued signed events were left untouched.", failure)
        }
    }

    private fun write(value: PersistedOutbox) {
        file.parentFile?.mkdirs()
        val temporary = File(requireNotNull(file.parentFile), "$FILE_NAME.tmp")
        temporary.writeText(json.encodeToString(PersistedOutbox.serializer(), value), Charsets.UTF_8)
        runCatching { Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
            .recoverCatching { Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING) }
            .getOrElse { failure -> temporary.delete(); throw IllegalStateException("Could not update highlight outbox.", failure) }
    }

    @Serializable private data class PersistedOutbox(val entries: List<HighlightOutboxEntry> = emptyList())

    private companion object {
        const val FILE_NAME = "highlight-outbox-v1.json"
        const val HIGHLIGHT_KIND = 9802
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}

@Serializable
enum class HighlightDeliveryState { PENDING, ACCEPTED, FAILED }

@Serializable
data class HighlightPairDelivery(
    val highlight: HighlightDeliveryState = HighlightDeliveryState.PENDING,
    val chapter: HighlightDeliveryState = HighlightDeliveryState.PENDING,
) {
    val isComplete: Boolean get() = highlight == HighlightDeliveryState.ACCEPTED && chapter == HighlightDeliveryState.ACCEPTED
}

@Serializable
data class HighlightOutboxEntry(
    val event: NostrEvent,
    val chapterEvent: NostrEvent,
    val localHighlightId: String = "",
    val queuedAtMillis: Long,
    /** Acknowledgements for the current configured local Citrine URL(s). */
    val local: Map<String, HighlightPairDelivery> = emptyMap(),
    /** Acknowledgements for the immutable remote route set resolved on the first online attempt. */
    val remote: Map<String, HighlightPairDelivery> = emptyMap(),
    val remoteRoutesResolved: Boolean = false,
    val attempts: Int = 0,
    val nextRetryAtMillis: Long,
    val lastFailure: String? = null,
) {
    val isComplete: Boolean
        get() = remoteRoutesResolved && remote.isNotEmpty() &&
            (local.values + remote.values).all(HighlightPairDelivery::isComplete)

    val deliveryLabel: String
        get() = when {
            isComplete -> "Published"
            lastFailure != null -> "Retrying"
            else -> "Pending"
        }
}

/**
 * Delivers pairs highlight-first to every destination. Local delivery works while offline; remote
 * route discovery and publication are strictly gated by validated internet connectivity.
 */
class HighlightOutboxDispatcher(
    private val outbox: HighlightOutbox,
    private val relaySync: BookshelfRelaySync,
    private val localRelayUrl: () -> String?,
    private val isOnline: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val resolveRemoteRoutes: suspend (NostrEvent, String) -> List<String> = { event, publisher ->
        relaySync.highlightRelayUrls(event, publisher)
    },
    private val publishToRoutes: suspend (NostrEvent, Collection<String>) -> PublishReport = { event, routes ->
        relaySync.publishHighlightEventToRelays(event, routes)
    },
) {
    private val dispatchMutex = Mutex()

    suspend fun enqueueAndTryDeliver(
        highlight: NostrEvent,
        chapter: NostrEvent,
        localHighlightId: String = "",
    ): HighlightOutboxEntry = dispatchMutex.withLock {
        deliver(outbox.enqueue(highlight, chapter, localHighlightId))
    }

    /** Processes due, incomplete pairs. Returns the number of entries examined. */
    suspend fun syncPending(force: Boolean = false): Int = dispatchMutex.withLock {
        val due = outbox.pending().filter { force || it.nextRetryAtMillis <= now() }
        for (entry in due) {
            deliver(entry)
        }
        due.size
    }

    private suspend fun deliver(entry: HighlightOutboxEntry): HighlightOutboxEntry {
        outbox.validate(entry)
        var current = deliverLocal(entry, normalizedRelay(localRelayUrl()))
        if (isOnline()) current = deliverRemote(current)
        return current
    }

    private suspend fun deliverLocal(entry: HighlightOutboxEntry, relayUrl: String?): HighlightOutboxEntry {
        val current = outbox.update(entry.event.id) { previous ->
            // Local relay configuration is mutable. Retire acknowledgements for old endpoints so
            // they neither conceal a new endpoint nor keep the pair pending forever.
            val delivery = relayUrl?.let { previous.local[it] ?: previous.remote[it] ?: HighlightPairDelivery() }
            previous.copy(
                local = relayUrl?.let { mapOf(it to requireNotNull(delivery)) }.orEmpty(),
                remote = if (relayUrl == null) previous.remote else previous.remote - relayUrl,
            )
        } ?: return entry
        if (relayUrl == null) return current
        return deliverToRoutes(current, setOf(relayUrl), local = true)
    }

    private suspend fun deliverRemote(entry: HighlightOutboxEntry): HighlightOutboxEntry {
        val current = if (entry.remoteRoutesResolved) entry else {
            val routes = try {
                resolveRemoteRoutes(entry.event, entry.chapterEvent.pubkey)
                    .mapNotNull(::normalizedRelay)
                    .filter { it != normalizedRelay(localRelayUrl()) }
                    .toCollection(LinkedHashSet())
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                return persistFailure(entry, remoteFailure = failure.message ?: "Could not resolve highlight relay routes.")
            }
            if (routes.isEmpty()) {
                return persistFailure(entry, remoteFailure = "No remote highlight relay routes were resolved.")
            }
            outbox.update(entry.event.id) { previous ->
                previous.copy(
                    remoteRoutesResolved = true,
                    remote = previous.remote + routes.associateWith { previous.remote[it] ?: HighlightPairDelivery() },
                )
            } ?: return entry
        }
        if (!isOnline()) return current
        return deliverToRoutes(current, current.remote.keys, local = false)
    }

    private suspend fun deliverToRoutes(entry: HighlightOutboxEntry, routes: Collection<String>, local: Boolean): HighlightOutboxEntry {
        val state = if (local) entry.local else entry.remote
        val highlightRoutes = routes.filter { state[it]?.highlight != HighlightDeliveryState.ACCEPTED }
        var current = entry
        if (highlightRoutes.isNotEmpty()) {
            current = try {
                persistReport(current, local, publishToRoutes(current.event, highlightRoutes), highlight = true)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                persistFailure(current, localFailure = local.thenFailure(failure), remoteFailure = (!local).thenFailure(failure))
            }
        }
        val updatedState = if (local) current.local else current.remote
        val chapterRoutes = routes.filter { updatedState[it]?.chapter != HighlightDeliveryState.ACCEPTED }
        if (chapterRoutes.isEmpty()) return current
        return try {
            persistReport(current, local, publishToRoutes(current.chapterEvent, chapterRoutes), highlight = false)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            persistFailure(current, localFailure = local.thenFailure(failure), remoteFailure = (!local).thenFailure(failure))
        }
    }

    private suspend fun persistReport(entry: HighlightOutboxEntry, local: Boolean, report: PublishReport, highlight: Boolean): HighlightOutboxEntry =
        outbox.update(entry.event.id) { previous ->
            val deliveries = (if (local) previous.local else previous.remote).toMutableMap()
            report.outcomes.forEach { outcome ->
                val prior = deliveries[outcome.relayUrl] ?: HighlightPairDelivery()
                val nextState = if (outcome.isDurablyAccepted()) HighlightDeliveryState.ACCEPTED else HighlightDeliveryState.FAILED
                deliveries[outcome.relayUrl] = if (highlight) prior.copy(highlight = nextState) else prior.copy(chapter = nextState)
            }
            val failure = report.outcomes.firstOrNull { !it.isDurablyAccepted() }?.reason
            previous.withAttempt(local = local, deliveries = deliveries, failure = failure)
        } ?: entry

    private suspend fun persistFailure(entry: HighlightOutboxEntry, localFailure: String? = null, remoteFailure: String? = null): HighlightOutboxEntry =
        outbox.update(entry.event.id) { previous ->
            val isLocal = localFailure != null
            val original = if (isLocal) previous.local else previous.remote
            val deliveries = original.mapValues { (_, pair) ->
                pair.copy(
                    highlight = if (pair.highlight == HighlightDeliveryState.PENDING) HighlightDeliveryState.FAILED else pair.highlight,
                    chapter = if (pair.chapter == HighlightDeliveryState.PENDING) HighlightDeliveryState.FAILED else pair.chapter,
                )
            }
            previous.withAttempt(local = isLocal, deliveries = deliveries, failure = localFailure ?: remoteFailure)
        } ?: entry

    private fun HighlightOutboxEntry.withAttempt(local: Boolean, deliveries: Map<String, HighlightPairDelivery>, failure: String?): HighlightOutboxEntry {
        val attempts = attempts + 1
        return if (local) {
            copy(local = deliveries, attempts = attempts, nextRetryAtMillis = now() + retryDelay(attempts), lastFailure = failure)
        } else {
            copy(remote = deliveries, attempts = attempts, nextRetryAtMillis = now() + retryDelay(attempts), lastFailure = failure)
        }
    }

    private fun Boolean.thenFailure(failure: Exception): String? = takeIf { it }?.let { failure.message ?: "Could not publish highlight event." }

    private fun normalizedRelay(rawUrl: String?): String? = rawUrl?.let(RelayUrlNormalizer::normalizeOrNull)?.url

    private fun retryDelay(attempt: Int): Long = (1_000L shl (attempt - 1).coerceAtMost(10)).coerceAtMost(MAX_RETRY_DELAY_MILLIS)

    private companion object { const val MAX_RETRY_DELAY_MILLIS = 15 * 60 * 1_000L }
}

/** A duplicate reply confirms the relay already durably stores the immutable event. */
internal fun eu.decentnewsroom.bookshelf.data.nostr.RelayPublishOutcome.isDurablyAccepted(): Boolean =
    type == RelayPublishOutcomeType.ACCEPTED ||
        (type == RelayPublishOutcomeType.REJECTED && reason?.trim()?.lowercase()?.let {
            it.startsWith("duplicate") || it.startsWith("already have") || it.startsWith("already exists")
        } == true)
