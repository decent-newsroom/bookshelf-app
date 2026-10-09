package eu.decentnewsroom.bookshelf.data.nostr

import android.util.Log
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.PublishResult
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetchAll
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.publishAndCollectResults
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
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
import eu.decentnewsroom.bookshelf.data.reading.ReadingEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

class NostrRelayException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Quartz-backed directory/profile relay transport with NIP-65 role-aware routing. */
class NostrRelayClient(
    httpClient: OkHttpClient,
    relayUrls: List<String>,
    private val timeoutMillis: Long = FETCH_IDLE_TIMEOUT_MILLIS,
    authenticator: NostrRelayAuthenticator? = null,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var baseRelays = relayUrls.mapNotNull(RelayUrlNormalizer::normalizeOrNull).toCollection(LinkedHashSet())
    private val profileLookupRelay = requireNotNull(RelayUrlNormalizer.normalizeOrNull(PROFILE_LOOKUP_RELAY_URL))
    private val relayListLock = Any()
    private var relayListOwner: String? = null
    private var discoveredRelays = UserRelayList()
    private var readingRoutesVerifiedOwner: String? = null
    private val publicationAuthorRelayLists = LinkedHashMap<String, UserRelayList>()
    private val client = NostrClient(BasicOkHttpWebSocket.Builder { httpClient }, scope)
    private val lazyAuthenticator = QuartzLazyNip42Authenticator(client, authenticator, nowSeconds)

    /** Current publish targets: configured relays followed by verified NIP-65 write relays. */
    val relayUrls: List<String>
        get() = publishRelayUrls

    val publishRelayUrls: List<String>
        get() = relaysFor(RelayAccess.WRITE).map(NormalizedRelayUrl::url)

    val readRelayUrls: List<String>
        get() = relaysFor(RelayAccess.READ).map(NormalizedRelayUrl::url)

    val configuredRelayUrls: List<String>
        get() = synchronized(relayListLock) { baseRelays.map(NormalizedRelayUrl::url) }

    val userReadRelayUrls: List<String>
        get() = synchronized(relayListLock) { discoveredRelays.read }

    val userWriteRelayUrls: List<String>
        get() = synchronized(relayListLock) { discoveredRelays.write }

    fun setConfiguredRelayUrls(relayUrls: List<String>) {
        val normalized = relayUrls.mapNotNull(RelayUrlNormalizer::normalizeOrNull).toCollection(LinkedHashSet())
        require(normalized.isNotEmpty()) { "At least one bootstrap relay is required." }
        synchronized(relayListLock) { baseRelays = normalized; readingRoutesVerifiedOwner = null }
    }

    suspend fun fetchLatestDirectory(pubkey: String): NostrEvent? {
        ensureUserRelayList(pubkey)
        return fetchLatest(
            filter = Filter(
                kinds = listOf(BookKinds.DIRECTORY),
                authors = listOf(pubkey.lowercase()),
                tags = mapOf("d" to listOf(BookshelfDirectoryRules.IDENTIFIER)),
                limit = 1,
            ),
            relaySet = readRelays(),
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
    }

    /** Null means an EOSE-confirmed empty list, never an unreachable relay set. */
    suspend fun fetchReadingSnapshot(pubkey: String): NostrEvent? {
        ensureUserRelayList(pubkey)
        val snapshot = ReadingEvents.newestSnapshot(fetchReadingEvents(
            pubkey, BookKinds.READING_LIST,
            Filter(kinds = listOf(BookKinds.READING_LIST), authors = listOf(pubkey.lowercase()), limit = 1),
        ))
        if (snapshot != null && snapshot.content.isNotEmpty()) {
            throw NostrRelayException("The public reading snapshot uses an unsupported content format; it has been preserved.")
        }
        return snapshot
    }

    suspend fun fetchFinishedLabels(pubkey: String): List<NostrEvent> {
        ensureUserRelayList(pubkey)
        // Page each relay independently: one fast relay's empty response cannot conceal another's history.
        val results = coroutineScope {
            readRelays().map { relay -> async {
                try {
                    Result.success(fetchFinishedHistoryFromRelay(pubkey, relay))
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: NostrRelayException) {
                    Result.failure<List<NostrEvent>>(failure)
                }
            } }.awaitAll()
        }
        val complete = results.mapNotNull { it.getOrNull() }
        if (complete.isEmpty()) {
            throw (results.firstNotNullOfOrNull { it.exceptionOrNull() }
                ?: NostrRelayException("No finished-history read relays are configured."))
        }
        return complete.flatten().distinctBy { it.id }.filter { ReadingEvents.parseFinished(it).isNotEmpty() }
    }

    private suspend fun fetchFinishedHistoryFromRelay(pubkey: String, relay: NormalizedRelayUrl): List<NostrEvent> {
        val labels = LinkedHashMap<String, NostrEvent>()
        var until: Long? = null
        while (true) {
            val page = fetchReadingEvents(pubkey, BookKinds.FINISHED_LABEL,
                finishedLabelsFilter(pubkey, until = until), linkedSetOf(relay))
            if (page.isEmpty()) break
            page.forEach { labels[it.id] = it }
            val oldest = page.minOf { it.createdAt }
            // Include every event at the cut boundary before stepping past that second.
            val boundary = fetchReadingEvents(pubkey, BookKinds.FINISHED_LABEL,
                finishedLabelsFilter(pubkey, until = oldest, since = oldest,
                    limit = FINISHED_LABEL_PAGE_SIZE + 1), linkedSetOf(relay))
            if (boundary.size > FINISHED_LABEL_PAGE_SIZE) {
                throw NostrRelayException("Too many finished labels share one timestamp to read the complete history safely.")
            }
            boundary.forEach { labels[it.id] = it }
            if (oldest == 0L) break
            until = oldest - 1
        }
        return labels.values.toList()
    }

    /** Reading snapshots must distinguish confirmed absence from transport failures. */
    private suspend fun fetchReadingEvents(
        pubkey: String,
        kind: Int,
        filter: Filter,
        relays: Set<NormalizedRelayUrl> = readRelays(),
    ): List<NostrEvent> {
        if (relays.isEmpty()) throw NostrRelayException("No reading-state read relays are configured.")
        val requestId = "reading-${java.util.UUID.randomUUID()}"
        val lock = Any()
        val terminal = mutableSetOf<NormalizedRelayUrl>()
        val completed = mutableSetOf<NormalizedRelayUrl>()
        val events = LinkedHashMap<String, NostrEvent>()
        val done = CompletableDeferred<Unit>()
        fun ended(relay: NormalizedRelayUrl, success: Boolean) = synchronized(lock) {
            if (success) completed.add(relay)
            terminal.add(relay)
            if (terminal.containsAll(relays)) done.complete(Unit)
        }
        val listener = object : SubscriptionListener {
            override fun onEvent(event: Event, isLive: Boolean, relay: NormalizedRelayUrl, forFilters: List<Filter>?) {
                if (!filter.match(event)) return
                val candidate = toDomainEvent(event) ?: return
                val verified = NostrEventVerifier.verify(candidate,
                    context = NostrEventContext(expectedKind = kind, expectedPubkey = pubkey))?.event ?: return
                synchronized(lock) { events[verified.id] = verified }
            }

            override fun onEose(relay: NormalizedRelayUrl, forFilters: List<Filter>?) { ended(relay, true) }
            override fun onClosed(message: String, relay: NormalizedRelayUrl, forFilters: List<Filter>?) { ended(relay, false) }
            override fun onCannotConnect(relay: NormalizedRelayUrl, message: String, forFilters: List<Filter>?) { ended(relay, false) }
        }
        try {
            client.subscribe(requestId, relays.associateWith { listOf(filter) }, listener)
            withTimeoutOrNull(timeoutMillis) { done.await() }
            return synchronized(lock) {
                if (completed.isEmpty()) throw NostrRelayException("Reading state could not be refreshed; no relay completed the query.")
                events.values.toList()
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: NostrRelayException) {
            throw failure
        } catch (failure: Throwable) {
            throw NostrRelayException("Could not refresh reading state.", failure)
        } finally {
            client.unsubscribe(requestId)
        }
    }

    /** Reading events use configured routes and the active author's NIP-65 write routes only. */
    suspend fun readingRelayUrls(pubkey: String, excludedRelayUrl: String? = null): List<String> {
        val normalizedPubkey = pubkey.lowercase()
        val owner = synchronized(relayListLock) { readingRoutesVerifiedOwner }
        if (owner != normalizedPubkey) {
            val event = fetchReadingEvents(normalizedPubkey, BookKinds.USER_RELAY_LIST,
                userRelayListFilter(normalizedPubkey), configuredRelays())
                .maxWithOrNull(compareBy<NostrEvent> { it.createdAt }.thenByDescending { it.id })
            synchronized(relayListLock) {
                discoveredRelays = event?.let(::relayListFromVerifiedEvent) ?: UserRelayList()
                relayListOwner = normalizedPubkey
                readingRoutesVerifiedOwner = normalizedPubkey
            }
        }
        val excluded = excludedRelayUrl?.let(RelayUrlNormalizer::normalizeOrNull)?.url
        return publishRelayUrls.filter { it != excluded }
    }

    /** Background reading work must never trigger foreground NIP-42 signer requests. */
    suspend fun publishReadingEventToRelays(event: NostrEvent, relayUrls: Collection<String>): PublishReport {
        val targets = relayUrls.mapNotNull(RelayUrlNormalizer::normalizeOrNull).toCollection(LinkedHashSet())
        return publishEvent(event, targets, allowRelayAuthentication = false)
    }

    suspend fun fetchLatestProfile(pubkey: String): NostrEvent? {
        // Profiles are public metadata. Looking up a reviewer must not replace the
        // active signer's NIP-65 routing state.
        val profileRelays = readRelays().apply { add(profileLookupRelay) }
        return fetchLatest(profileFilter(pubkey), profileRelays) { event ->
            NostrEventVerifier.verify(
                event,
                context = NostrEventContext(expectedKind = BookKinds.PROFILE_METADATA, expectedPubkey = pubkey),
            )?.event?.takeIf { it.isProfileFor(pubkey) }
        }
    }

    /** Resolves exact kind 30040 coordinates from read relays and optional secure NIP-19 hints. */
    suspend fun fetchPublicationIndexes(
        coordinates: List<String>,
        relayHints: List<String> = emptyList(),
    ): List<NostrEvent> {
        val relaySet = readRelays().apply {
            relayHints.mapNotNull(::normalizeSecureRelayUrl).mapNotNull(RelayUrlNormalizer::normalizeOrNull).forEach(::add)
        }.take(MAX_PUBLICATION_LOOKUP_RELAYS).toCollection(LinkedHashSet())
        return coordinates.mapNotNull(::parsePublicationCoordinate).distinct().mapNotNull { coordinate ->
            fetchLatest(
                filter = Filter(
                    kinds = listOf(BookKinds.PUBLICATION_INDEX),
                    authors = listOf(coordinate.pubkey),
                    tags = mapOf("d" to listOf(coordinate.identifier)),
                    limit = 1,
                ),
                relaySet = relaySet,
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
    }

    /** Bounded global rating query for discovery; callers apply recency/product policy locally. */
    suspend fun fetchRecentRatings(limit: Int = 1_000): List<NostrEvent> = fetchAll(
        filter = Filter(kinds = listOf(BookKinds.RATING), limit = limit.coerceIn(1, 1_000)),
        relaySet = readRelays(),
    ) { event -> NostrEventVerifier.verify(event, context = NostrEventContext(expectedKind = BookKinds.RATING))?.event }
    /** Fetches verified ratings only through interoperable a/A publication-address references. */
    suspend fun fetchRatings(publicationCoordinates: List<String>): List<NostrEvent> {
        val coordinates = publicationCoordinates.map(String::trim).filter(String::isNotBlank).distinct()
        val ratingRelays = readRelays()
        val filters = buildList {
            if (coordinates.isNotEmpty()) {
                add(Filter(kinds = listOf(BookKinds.RATING), tags = mapOf("a" to coordinates)))
                add(Filter(kinds = listOf(BookKinds.RATING), tags = mapOf("A" to coordinates)))
            }
        }
        return filters.flatMap { filter ->
            fetchAll(filter = filter, relaySet = ratingRelays) { event ->
                NostrEventVerifier.verify(event, context = NostrEventContext(expectedKind = BookKinds.RATING))?.event
            }
        }.distinctBy(NostrEvent::id)
    }
    /** Fetches and applies the active account's verified NIP-65 relay list using bootstrap relays. */
    suspend fun refreshUserRelayList(pubkey: String): UserRelayList? {
        val normalizedPubkey = pubkey.lowercase()
        val relayEvent = fetchLatest(
            filter = userRelayListFilter(normalizedPubkey),
            relaySet = configuredRelays(),
        ) { event ->
            NostrEventVerifier.verify(
                event,
                context = NostrEventContext(
                    expectedKind = BookKinds.USER_RELAY_LIST,
                    expectedPubkey = normalizedPubkey,
                ),
            )?.event
        }
        val parsed = relayEvent?.let(::relayListFromVerifiedEvent)
        synchronized(relayListLock) {
            relayListOwner = normalizedPubkey
            discoveredRelays = parsed ?: UserRelayList()
        }
        return parsed
    }

    /** Resolves a publication author's NIP-65 list without replacing the active user's routes. */
    suspend fun refreshPublicationAuthorRelayList(pubkey: String): UserRelayList? {
        val normalizedPubkey = pubkey.lowercase()
        val relayEvent = fetchLatest(userRelayListFilter(normalizedPubkey), configuredRelays()) { event ->
            NostrEventVerifier.verify(
                event,
                context = NostrEventContext(expectedKind = BookKinds.USER_RELAY_LIST, expectedPubkey = normalizedPubkey),
            )?.event
        }
        val parsed = relayEvent?.let(::relayListFromVerifiedEvent)
        if (parsed != null) synchronized(relayListLock) {
            publicationAuthorRelayLists[normalizedPubkey] = parsed
            while (publicationAuthorRelayLists.size > MAX_PUBLICATION_AUTHOR_RELAY_LISTS) {
                publicationAuthorRelayLists.remove(publicationAuthorRelayLists.entries.iterator().next().key)
            }
        }
        return parsed
    }
    fun clearUserRelayList() {
        synchronized(relayListLock) {
            relayListOwner = null
            readingRoutesVerifiedOwner = null
            discoveredRelays = UserRelayList()
            publicationAuthorRelayLists.clear()
        }
    }

    private suspend fun ensureUserRelayList(pubkey: String) {
        val normalizedPubkey = pubkey.lowercase()
        if (synchronized(relayListLock) { relayListOwner == normalizedPubkey }) return
        try {
            refreshUserRelayList(normalizedPubkey)
        } catch (failure: NostrRelayException) {
            Log.w(LOG_TAG, "Could not refresh NIP-65 relays for $normalizedPubkey", failure)
        }
    }

    private fun configuredRelays(): LinkedHashSet<NormalizedRelayUrl> = synchronized(relayListLock) {
        LinkedHashSet(baseRelays)
    }

    private fun readRelays() = relaysFor(RelayAccess.READ)

    private fun writeRelays() = relaysFor(RelayAccess.WRITE)

    private fun publicationAuthorReadRelays(pubkey: String): List<NormalizedRelayUrl> = synchronized(relayListLock) {
        publicationAuthorRelayLists[pubkey.lowercase()]?.read
            ?.mapNotNull(RelayUrlNormalizer::normalizeOrNull)
            .orEmpty()
    }

    private fun highlightAuthorWriteRelays(pubkey: String): List<NormalizedRelayUrl> = synchronized(relayListLock) {
        if (relayListOwner == pubkey.lowercase()) {
            discoveredRelays.write.mapNotNull(RelayUrlNormalizer::normalizeOrNull)
        } else {
            publicationAuthorRelayLists[pubkey.lowercase()]?.write
                ?.mapNotNull(RelayUrlNormalizer::normalizeOrNull)
                .orEmpty()
        }
    }

    private fun relaysFor(access: RelayAccess): LinkedHashSet<NormalizedRelayUrl> = synchronized(relayListLock) {
        LinkedHashSet(baseRelays).apply {
            val discovered = if (access == RelayAccess.READ) discoveredRelays.read else discoveredRelays.write
            discovered.mapNotNull(RelayUrlNormalizer::normalizeOrNull).forEach(::add)
        }
    }

    private suspend fun fetchLatest(
        filter: Filter,
        relaySet: Set<NormalizedRelayUrl>,
        verifyEvent: (NostrEvent) -> NostrEvent?,
    ): NostrEvent? {
        if (relaySet.isEmpty()) return null
        val filters = relaySet.associateWith { listOf(filter) }
        val events = try {
            client.fetchAll(filters = filters, timeoutMs = timeoutMillis, maxTotalMs = FETCH_MAX_TOTAL_MILLIS)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            throw NostrRelayException("Could not reach configured relays.", failure)
        }
        return events.mapNotNull(::toDomainEvent).mapNotNull(verifyEvent)
            .maxWithOrNull(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
    }

    private suspend fun fetchAll(
        filter: Filter,
        relaySet: Set<NormalizedRelayUrl>,
        verifyEvent: (NostrEvent) -> NostrEvent?,
    ): List<NostrEvent> {
        if (relaySet.isEmpty()) return emptyList()
        val events = try {
            client.fetchAll(
                filters = relaySet.associateWith { listOf(filter) },
                timeoutMs = timeoutMillis,
                maxTotalMs = FETCH_MAX_TOTAL_MILLIS,
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            throw NostrRelayException("Could not reach configured relays.", failure)
        }
        return events.mapNotNull(::toDomainEvent).mapNotNull(verifyEvent)
    }
    /** Publishes through default, active-user, and publication-author read routes. */
    suspend fun publishRating(event: NostrEvent, publicationAuthorPubkey: String): PublishReport =
        publishToRelays(event, ratingRelayUrls(event, publicationAuthorPubkey))

    /** Resolves the current default, active-user-write and publication-author routes for one rating. */
    suspend fun ratingRelayUrls(event: NostrEvent, publicationAuthorPubkey: String, excludedRelayUrl: String? = null): List<String> {
        ensureUserRelayList(event.pubkey)
        runCatching { refreshPublicationAuthorRelayList(publicationAuthorPubkey) }
            .onFailure { Log.w(LOG_TAG, "Could not refresh publication-author NIP-65 relay list.", it) }
        val excluded = excludedRelayUrl?.let(RelayUrlNormalizer::normalizeOrNull)?.url
        return configuredRelays().apply {
            writeRelays().forEach(::add)
            publicationAuthorReadRelays(publicationAuthorPubkey).forEach(::add)
        }.map(NormalizedRelayUrl::url).filter { it != excluded }
    }

    /**
     * Resolves NIP-84 targets without changing the active signer's NIP-65 state. A discovery
     * failure is propagated so a durable caller can retry before treating bootstrap-only routing
     * as complete delivery.
     */
    suspend fun highlightRelayUrls(
        event: NostrEvent,
        chapterPublisherPubkey: String,
        excludedRelayUrl: String? = null,
    ): List<String> {
        refreshRelayListForHighlightAuthor(event.pubkey)
        refreshPublicationAuthorRelayList(chapterPublisherPubkey)
        val excluded = excludedRelayUrl?.let(RelayUrlNormalizer::normalizeOrNull)?.url
        return configuredRelays().apply {
            highlightAuthorWriteRelays(event.pubkey).forEach(::add)
            publicationAuthorReadRelays(chapterPublisherPubkey).forEach(::add)
        }.map(NormalizedRelayUrl::url).filter { it != excluded }
    }

    private suspend fun refreshRelayListForHighlightAuthor(pubkey: String) {
        val normalizedPubkey = pubkey.lowercase()
        if (synchronized(relayListLock) { relayListOwner == normalizedPubkey }) return
        val relayEvent = fetchLatest(userRelayListFilter(normalizedPubkey), configuredRelays()) { candidate ->
            NostrEventVerifier.verify(
                candidate,
                context = NostrEventContext(
                    expectedKind = BookKinds.USER_RELAY_LIST,
                    expectedPubkey = normalizedPubkey,
                ),
            )?.event
        }
        val parsed = relayEvent?.let(::relayListFromVerifiedEvent) ?: UserRelayList()
        synchronized(relayListLock) {
            publicationAuthorRelayLists[normalizedPubkey] = parsed
            while (publicationAuthorRelayLists.size > MAX_PUBLICATION_AUTHOR_RELAY_LISTS) {
                publicationAuthorRelayLists.remove(publicationAuthorRelayLists.entries.iterator().next().key)
            }
        }
    }

    suspend fun publishDirectory(event: NostrEvent): PublishReport {
        ensureUserRelayList(event.pubkey)
        return publishEvent(event, writeRelays())
    }

    suspend fun publishToRelay(event: NostrEvent, relayUrl: String): PublishReport {
        val relay = RelayUrlNormalizer.normalizeOrNull(relayUrl)
            ?: throw NostrRelayException("Local relay URL is invalid.")
        return publishEvent(event, linkedSetOf(relay))
    }

    suspend fun publishToRelays(event: NostrEvent, relayUrls: Collection<String>): PublishReport {
        val targets = relayUrls.mapNotNull(RelayUrlNormalizer::normalizeOrNull).toCollection(LinkedHashSet())
        return publishEvent(event, targets)
    }

    /** Sends immutable highlight-related events but only starts NIP-42 as their signer. */
    suspend fun publishHighlightEventToRelays(
        event: NostrEvent,
        relayUrls: Collection<String>,
        allowRelayAuthentication: Boolean,
    ): PublishReport {
        val targets = relayUrls.mapNotNull(RelayUrlNormalizer::normalizeOrNull).toCollection(LinkedHashSet())
        return publishEvent(event, targets, allowRelayAuthentication)
    }

    private suspend fun publishEvent(
        event: NostrEvent,
        targets: Set<NormalizedRelayUrl>,
        allowRelayAuthentication: Boolean = true,
    ): PublishReport {
        if (NostrEventVerifier.verify(event) == null) {
            return failedPublishReport(event, targets, RelayPublishOutcomeType.PROTOCOL_FAILURE, "The signed event failed local verification.")
        }
        val quartzEvent = runCatching { Event.fromJson(json.encodeToString(event)) }.getOrElse { failure ->
            return failedPublishReport(event, targets, RelayPublishOutcomeType.PROTOCOL_FAILURE, safeReason(failure.message))
        }
        val initial = runCatching { client.publishAndCollectResults(quartzEvent, targets, PUBLISH_TIMEOUT_SECONDS) }
            .getOrElse { failure ->
                return failedPublishReport(event, targets, RelayPublishOutcomeType.TRANSPORT_FAILURE, safeReason(failure.message))
            }
        val outcomes = initial.map { (relay, result) -> publishOutcome(relay, result) }.toMutableList()
        if (allowRelayAuthentication) retryAuthenticationRequiredPublishes(quartzEvent, targets, outcomes)
        outcomes.forEach { logOutcome(event.id, it) }
        val targetUrls = targets.map(NormalizedRelayUrl::url)
        return PublishReport(
            acceptedRelays = outcomes.count { it.type == RelayPublishOutcomeType.ACCEPTED },
            attemptedRelays = outcomes.size,
            eventId = event.id.takeIf(String::isNotBlank),
            outcomes = outcomes.sortedBy { targetUrls.indexOf(it.relayUrl).let { index -> if (index < 0) Int.MAX_VALUE else index } },
        )
    }

    private fun failedPublishReport(
        event: NostrEvent,
        targets: Set<NormalizedRelayUrl>,
        type: RelayPublishOutcomeType,
        reason: String,
    ) = PublishReport(
        acceptedRelays = 0,
        attemptedRelays = targets.size,
        eventId = event.id.takeIf(String::isNotBlank),
        outcomes = targets.map { RelayPublishOutcome(it.url, type, reason) },
    )

    private suspend fun retryAuthenticationRequiredPublishes(
        event: Event,
        targets: Set<NormalizedRelayUrl>,
        outcomes: MutableList<RelayPublishOutcome>,
    ) {
        outcomes.indices.forEach { index ->
            val current = outcomes[index]
            if (current.type != RelayPublishOutcomeType.AUTHENTICATION_REQUIRED) return@forEach
            val relay = targets.firstOrNull { it.url == current.relayUrl } ?: return@forEach
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
        const val MAX_PUBLICATION_AUTHOR_RELAY_LISTS = 64
        const val MAX_PUBLICATION_LOOKUP_RELAYS = 8
        val HEX_64 = Regex("^[a-f0-9]{64}$", RegexOption.IGNORE_CASE)

        fun parsePublicationCoordinate(raw: String): PublicationCoordinate? {
            val parts = raw.split(":", limit = 3)
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

internal fun userRelayListFilter(pubkey: String) =
    Filter(kinds = listOf(BookKinds.USER_RELAY_LIST), authors = listOf(pubkey.lowercase()), limit = 1)

internal fun finishedLabelsFilter(pubkey: String, until: Long? = null, since: Long? = null, limit: Int = FINISHED_LABEL_PAGE_SIZE) =
    Filter(kinds = listOf(BookKinds.FINISHED_LABEL), authors = listOf(pubkey.lowercase()),
        tags = mapOf("l" to listOf("read")), until = until, since = since, limit = limit)

data class UserRelayList(
    val read: List<String> = emptyList(),
    val write: List<String> = emptyList(),
)

internal fun relayListFromVerifiedEvent(event: NostrEvent): UserRelayList {
    val read = LinkedHashSet<String>()
    val write = LinkedHashSet<String>()
    event.tags.forEach { tag ->
        if (tag.getOrNull(0) != "r") return@forEach
        val relay = normalizeSecureRelayUrl(tag.getOrNull(1)) ?: return@forEach
        when (tag.getOrNull(2)) {
            null, "" -> {
                read.addWithinLimit(relay)
                write.addWithinLimit(relay)
            }
            "read" -> read.addWithinLimit(relay)
            "write" -> write.addWithinLimit(relay)
        }
    }
    return UserRelayList(read.toList(), write.toList())
}

/** Avoid handing cleartext relay URLs to Quartz, which deliberately rejects them. */
private fun normalizeSecureRelayUrl(rawUrl: String?): String? {
    val trimmed = rawUrl?.trim().orEmpty()
    if (!trimmed.startsWith("wss://", ignoreCase = true)) return null
    val canonicalScheme = "wss://" + trimmed.substring("wss://".length)
    return RelayUrlNormalizer.normalizeOrNull(canonicalScheme)?.url
}
private fun LinkedHashSet<String>.addWithinLimit(relay: String) {
    if (relay in this || size < MAX_USER_RELAY_COUNT) add(relay)
}

private enum class RelayAccess { READ, WRITE }

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
        val signed = withTimeoutOrNull(AUTH_SIGNING_TIMEOUT_MILLIS.milliseconds) {
            runCatching { auth.signAuthEvent(draft) }.getOrNull()
        } ?: return AuthAttempt.Failed("The Android signer did not approve relay authentication in time.")
        val verified = NostrAuthEventValidator.verify(signed, draft, nowSeconds())
            ?: return AuthAttempt.Failed("The Android signer returned an invalid relay-authentication event.")
        val authEvent = verified.event.toQuartzRelayAuthEvent()
        val awaiting = CompletableDeferred<Boolean>()
        pendingResults[authEvent.id] = PendingAuthResult(relay, awaiting)
        client.getOrCreateRelay(relay).sendIfConnected(AuthCmd(authEvent))
        val accepted = withTimeoutOrNull(AUTH_ACK_TIMEOUT_MILLIS.milliseconds) { awaiting.await() } ?: false
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

private const val PROFILE_LOOKUP_RELAY_URL = "wss://profiles.nostr1.com"
private const val AUTH_SIGNING_TIMEOUT_MILLIS = 90_000L
private const val AUTH_ACK_TIMEOUT_MILLIS = 15_000L
private const val MAX_AUTH_REASON_LENGTH = 240
private const val MAX_USER_RELAY_COUNT = 12
private const val FINISHED_LABEL_PAGE_SIZE = 500
