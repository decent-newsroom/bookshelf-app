package eu.decentnewsroom.bookshelf.data.reading

import eu.decentnewsroom.bookshelf.data.nostr.BackgroundSignerResult
import eu.decentnewsroom.bookshelf.data.nostr.NostrSignerSession
import eu.decentnewsroom.bookshelf.data.nostr.PublishReport
import eu.decentnewsroom.bookshelf.data.nostr.RelayPublishOutcomeType
import eu.decentnewsroom.bookshelf.domain.BookDetail
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

interface ReadingTransport {
    suspend fun snapshot(pubkey: String): NostrEvent?
    suspend fun finished(pubkey: String): List<NostrEvent>
    suspend fun resolve(coordinates: List<String>): List<BookSummary>
    suspend fun writeRelays(pubkey: String): List<String>
    suspend fun publish(event: NostrEvent, relay: String): PublishReport
}

data class ReadingSignRequest(
    val id: String,
    val session: NostrSignerSession,
    val draft: NostrEvent,
    val operationIds: List<String>,
    val dependencies: List<String> = emptyList(),
    val generation: Long = 0,
)

/** Durable reading metadata, independent from both the saved shelf and physical resume position. */
class ReadingStateRepository(
    private val store: ReadingStateStore,
    private val transport: ReadingTransport,
    private val online: () -> Boolean,
    private val localRelay: () -> String?,
    private val backgroundSign: suspend (NostrSignerSession, String) -> BackgroundSignerResult,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
    initialSession: NostrSignerSession? = null,
    private val publicationWindow: suspend () -> Unit = { delay(3_000) },
) {
    private val mutex = Mutex()
    private val syncMutex = Mutex()
    private var startupFailure: String? = null
    private var lastNotice: String? = null
    private var disk = try { store.read() } catch (failure: Exception) {
        startupFailure = "Could not read saved reading lists. Original data has been preserved."
        ReadingDiskState()
    }
    @Volatile private var session: NostrSignerSession? = initialSession
    @Volatile private var foreground = false
    private var burstJob: Job? = null
    private var retryJob: Job? = null
    @Volatile private var historyRefreshRequested = true
    private var generation = 0L
    private val _state = MutableStateFlow(ReadingState(error = startupFailure))
    val state: StateFlow<ReadingState> = _state.asStateFlow()
    private val _signRequest = MutableStateFlow<ReadingSignRequest?>(null)
    val signRequest: StateFlow<ReadingSignRequest?> = _signRequest.asStateFlow()
    val activeAccountPubkey: String? get() = session?.pubkey
    init { emit() }
    private fun key(): String = session?.pubkey?.lowercase() ?: GUEST
    private fun account(owner: String = key()): ReadingAccountState = disk.accounts[owner] ?: ReadingAccountState()

    suspend fun setSession(value: NostrSignerSession?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (session == value) return@withLock
            session = value
            historyRefreshRequested = true
            generation++
            _signRequest.value = null
            emit(error = null, syncing = false)
        }
        scheduleSync(immediate = true)
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (value) {
            historyRefreshRequested = true
            scheduleSync(immediate = true)
            if (retryJob?.isActive != true) retryJob = scope.launch {
                while (foreground) {
                    delay(30_000)
                    if (state.value.pendingCount > 0) sync()
                }
            }
        } else {
            retryJob?.cancel()
            retryJob = null
        }
    }

    fun connectivityChanged() {
        historyRefreshRequested = true
        scheduleSync(immediate = true)
    }

    suspend fun track(book: BookDetail, chapterIndex: Int = 0) {
        require(book.summary.sectionStreamKnown && book.summary.chapterCount > 0) { "The section list is still loading." }
        mutate { current ->
            if (current.tracked.any { it.bookCoordinate == book.summary.coordinate }) return@mutate current
            val public = session != null && !current.preferences.readingDeviceOnly
            val entry = trackedEntry(book, chapterIndex, public)
            current.copy(
                tracked = current.tracked + entry,
                privateReadingRemovals = current.privateReadingRemovals.filterNot { it.bookCoordinate == entry.bookCoordinate },
                operations = current.operations + if (public) listOf(operation(ReadingOperationType.TRACK, entry)) else emptyList(),
            )
        }
        scheduleSync()
    }

    suspend fun advance(book: BookDetail, chapterIndex: Int) {
        mutate { current ->
            val old = current.tracked.firstOrNull { it.bookCoordinate == book.summary.coordinate } ?: return@mutate current
            if (!book.summary.sectionStreamKnown || chapterIndex <= old.position) return@mutate current
            val public = old.isPublic && !current.preferences.readingDeviceOnly && session != null
            val entry = trackedEntry(book, chapterIndex, public)
            val next = if (public) current else privatizeReading(current, entry.bookCoordinate)
            next.copy(
                tracked = next.tracked.map { if (it.bookCoordinate == entry.bookCoordinate) entry else it },
                operations = if (public) appendAdvance(next.operations, operation(ReadingOperationType.ADVANCE, entry), next.outbox) else next.operations,
            )
        }
        scheduleSync()
    }

    suspend fun reset(coordinate: String) {
        mutate { current ->
            val old = current.tracked.firstOrNull { it.bookCoordinate == coordinate } ?: return@mutate current
            val public = old.isPublic && !current.preferences.readingDeviceOnly && session != null
            val entry = old.copy(position = 0, sectionId = old.book?.chapterRefs?.firstOrNull()?.eventId, updatedAt = actionTime(current), isPublic = public)
            val next = if (public) current else privatizeReading(current, coordinate)
            next.copy(tracked = next.tracked.map { if (it.bookCoordinate == coordinate) entry else it },
                operations = next.operations + if (public) listOf(operation(ReadingOperationType.RESET, entry)) else emptyList())
        }
        scheduleSync()
    }

    suspend fun stop(coordinate: String) {
        mutate { removeTracking(it, coordinate) }
        scheduleSync()
    }

    suspend fun finish(book: BookSummary) {
        mutate { current ->
            val alreadyFinished = current.finished.firstOrNull { it.bookCoordinate == book.coordinate }
            val public = session != null && !current.preferences.finishedDeviceOnly
            val pendingLabel = current.operations.firstOrNull { it.type == ReadingOperationType.FINISH && it.coordinate == book.coordinate }
            val savedLabel = current.outbox.lastOrNull { it.event.kind == BookKinds.FINISHED_LABEL &&
                it.event.tags.any { tag -> tag.getOrNull(0) == "a" && tag.getOrNull(1) == book.coordinate } }
            // Completion history is one entry per edition. Rereading never erases it.
            val entry = if (public && alreadyFinished != null && !alreadyFinished.isPublic) alreadyFinished.copy(book = book, isPublic = true)
                else if (public && alreadyFinished == null) FinishedBook(book.coordinate, book, actionTime(current), true)
                else alreadyFinished ?: FinishedBook(book.coordinate, book, actionTime(current), false)
            val label = if (public && pendingLabel == null && (alreadyFinished?.isPublic != true || savedLabel == null)) ReadingOperation(
                UUID.randomUUID().toString(), ReadingOperationType.FINISH, book.coordinate, finished = entry,
            ) else null
            val withLabel = current.copy(
                finished = current.finished.filterNot { it.bookCoordinate == book.coordinate } + entry,
                operations = current.operations + listOfNotNull(label),
            )
            removeTracking(withLabel, book.coordinate, if (public) label?.id ?: pendingLabel?.id ?: savedLabel?.operationIds?.firstOrNull() else null)
        }
        scheduleSync(immediate = true)
    }

    suspend fun setPrivacy(readingDeviceOnly: Boolean? = null, finishedDeviceOnly: Boolean? = null, shareCoordinates: Set<String> = emptySet()) {
        mutate { current ->
            val preferences = current.preferences.copy(
                readingDeviceOnly = readingDeviceOnly ?: current.preferences.readingDeviceOnly,
                finishedDeviceOnly = finishedDeviceOnly ?: current.preferences.finishedDeviceOnly,
            )
            require(session != null || (preferences.readingDeviceOnly && preferences.finishedDeviceOnly)) { "Sign in before sharing reading lists." }
            generation++
            historyRefreshRequested = true
            _signRequest.value = null
            var next = current.copy(preferences = preferences)
            if (readingDeviceOnly == false) {
                val selected = next.tracked.filter { !it.isPublic && it.bookCoordinate in shareCoordinates }
                    .map { it.copy(isPublic = true, updatedAt = actionTime(next)) }
                val removals = next.privateReadingRemovals.filter { it.bookCoordinate in shareCoordinates }
                next = next.copy(
                    tracked = next.tracked.map { old -> selected.firstOrNull { it.bookCoordinate == old.bookCoordinate } ?: old },
                    operations = next.operations + selected.map { operation(ReadingOperationType.TRACK, it) } +
                        removals.map { ReadingOperation(UUID.randomUUID().toString(), ReadingOperationType.STOP, it.bookCoordinate) },
                    privateReadingRemovals = next.privateReadingRemovals.filterNot { it.bookCoordinate in shareCoordinates },
                )
            }
            if (finishedDeviceOnly == false) {
                val selected = next.finished.filter { !it.isPublic && it.bookCoordinate in shareCoordinates }.map { it.copy(isPublic = true) }
                next = next.copy(
                    finished = next.finished.map { old -> selected.firstOrNull { it.bookCoordinate == old.bookCoordinate } ?: old },
                    operations = next.operations + selected.map { ReadingOperation(UUID.randomUUID().toString(), ReadingOperationType.FINISH, it.bookCoordinate, finished = it) },
                )
            }
            // Sharing a locally finished book's queued removal must retain the same relay ordering
            // when its finished label was already shared independently of tracking.
            if (readingDeviceOnly == false && !preferences.finishedDeviceOnly) {
                val removals = current.privateReadingRemovals.filter { it.bookCoordinate in shareCoordinates }
                removals.forEach { removal ->
                    val completion = next.finished.firstOrNull { it.bookCoordinate == removal.bookCoordinate && it.isPublic }
                    if (completion != null) {
                        var dependency = next.operations.firstOrNull { it.coordinate == removal.bookCoordinate && it.type == ReadingOperationType.FINISH }?.id
                            ?: next.outbox.lastOrNull { it.event.kind == BookKinds.FINISHED_LABEL && it.event.tags.any { tag ->
                                tag.getOrNull(0) == "a" && tag.getOrNull(1) == removal.bookCoordinate
                            } }?.operationIds?.firstOrNull()
                        if (dependency == null) {
                            val label = ReadingOperation(UUID.randomUUID().toString(), ReadingOperationType.FINISH, removal.bookCoordinate, finished = completion)
                            dependency = label.id
                            next = next.copy(operations = next.operations + label)
                        }
                        next = next.copy(operations = next.operations.map { op ->
                            if (op.type == ReadingOperationType.STOP && op.coordinate == removal.bookCoordinate && op.dependencyOperationId == null)
                                op.copy(dependencyOperationId = dependency) else op
                        })
                    }
                }
            }
            next
        }
        scheduleSync(immediate = true)
    }

    private fun trackedEntry(book: BookDetail, index: Int, public: Boolean) = TrackedBook(
        bookCoordinate = book.summary.coordinate, book = book.summary,
        position = index.coerceIn(0, book.summary.chapterCount - 1), total = book.summary.chapterCount,
        sectionId = book.chapters.getOrNull(index)?.id ?: book.summary.chapterRefs.getOrNull(index)?.eventId,
        updatedAt = actionTime(account()), isPublic = public,
    )

    private fun actionTime(current: ReadingAccountState): Long = maxOf(now() / 1000,
        (current.finished.maxOfOrNull { it.finishedAt } ?: 0) + 1)

    private fun removeTracking(current: ReadingAccountState, coordinate: String, dependency: String? = null): ReadingAccountState {
        val old = current.tracked.firstOrNull { it.bookCoordinate == coordinate } ?: return current
        val public = old.isPublic && !current.preferences.readingDeviceOnly && session != null
        val wasPublic = old.isPublic || ReadingEvents.parseSnapshot(current.remoteSnapshot ?: NostrEvent()).any { it.bookCoordinate == coordinate }
        val next = if (public) current else privatizeReading(current, coordinate)
        return next.copy(
            tracked = next.tracked.filterNot { it.bookCoordinate == coordinate },
            operations = next.operations + if (public) listOf(ReadingOperation(
                UUID.randomUUID().toString(), ReadingOperationType.STOP, coordinate, dependencyOperationId = dependency,
            )) else emptyList(),
            privateReadingRemovals = if (!public && wasPublic) next.privateReadingRemovals.filterNot { it.bookCoordinate == coordinate } +
                old.copy(isPublic = false, status = "Stopped on this device; public removal not shared") else next.privateReadingRemovals,
        )
    }

    private fun privatizeReading(current: ReadingAccountState, coordinate: String) = current.copy(
        operations = current.operations.filterNot { it.coordinate == coordinate && it.type != ReadingOperationType.FINISH },
        outbox = current.outbox.map { delivery ->
            if (!delivery.complete && delivery.event.kind == BookKinds.READING_LIST && delivery.event.tags.any { it.getOrNull(0) == "a" && it.getOrNull(1) == coordinate })
                delivery.copy(superseded = true) else delivery
        },
    )

    private suspend fun mutate(transform: (ReadingAccountState) -> ReadingAccountState) {
        val active = session
        val owner = key()
        withContext(Dispatchers.IO) {
            mutex.withLock {
                check(startupFailure == null) { startupFailure.orEmpty() }
                check(session == active && key() == owner) { "Account changed. Try the reading action again." }
                val old = account(owner)
                val next = transform(old)
                if (next != old) persist(owner, next)
            }
        }
    }

    private fun persist(owner: String, next: ReadingAccountState) {
        val dependencies = next.outbox.filterNot { it.complete }.flatMap { it.dependencies }.toSet()
        val latestLabels = next.outbox.filter { it.event.kind == BookKinds.FINISHED_LABEL && !it.superseded }
            .groupBy { delivery -> delivery.event.tags.firstOrNull { it.getOrNull(0) == "a" }?.getOrNull(1) }
            .values.mapNotNull { it.maxByOrNull { delivery -> delivery.event.createdAt } }.map { it.event.id }.toSet()
        // Completed progress snapshots can be frequent. Keep pending events and prerequisite labels,
        // with the newest public snapshot already retained separately as the sync baseline.
        val compacted = next.copy(outbox = next.outbox.filter { !it.complete || it.event.id in dependencies || it.event.id in latestLabels })
        val updated = disk.copy(accounts = disk.accounts + (owner to compacted))
        store.write(updated)
        disk = updated
        emit()
    }

    private fun emit(error: String? = lastNotice, syncing: Boolean = _state.value.isSyncing) {
        lastNotice = error
        val current = account()
        val assigned = current.outbox.filterNot { it.superseded }.flatMap { it.operationIds }.toSet()
        val pending = current.operations.count { it.id !in assigned } + current.outbox.count { !it.complete }
        fun label(coordinate: String, public: Boolean, kind: Int): String {
            if (!public) return "On this device"
            if (!enabled(current, kind)) return "Public sync paused"
            if (current.outbox.any { !it.complete && it.event.kind == kind && it.failure != null && it.event.tags.any { tag ->
                tag.getOrNull(0) == "a" && tag.getOrNull(1) == coordinate
            } }) return "Public delivery needs retry"
            if (current.operations.any { it.coordinate == coordinate && operationKind(it) == kind } ||
                current.outbox.any { !it.complete && it.event.kind == kind && it.event.tags.any { tag -> tag.getOrNull(0) == "a" && tag.getOrNull(1) == coordinate } }) return "Public sync pending"
            return "Public"
        }
        _state.value = ReadingState(
            preferences = current.preferences,
            tracked = current.tracked.sortedByDescending { it.updatedAt }.map { it.copy(status = label(it.bookCoordinate, it.isPublic, BookKinds.READING_LIST)) },
            finished = current.finished.sortedByDescending { it.finishedAt }.map { it.copy(status = label(it.bookCoordinate, it.isPublic, BookKinds.FINISHED_LABEL)) },
            privateReadingRemovals = current.privateReadingRemovals,
            pendingCount = pending, isSyncing = syncing, error = startupFailure ?: error ?: current.outbox.firstOrNull {
                !it.complete && enabled(current, it.event.kind) && it.failure != null
            }?.failure,
        )
    }

    private fun scheduleSync(immediate: Boolean = false) {
        if (!foreground || session == null) return
        synchronized(this) {
            if (burstJob?.isActive == true) return
            burstJob = scope.launch {
                if (!immediate) publicationWindow()
                // Clear before sync: advances arriving during I/O start the next fixed window.
                synchronized(this@ReadingStateRepository) { burstJob = null }
                sync()
            }
        }
    }

    suspend fun sync(manual: Boolean = false) = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val active = session ?: return@withLock
            val owner = active.pubkey.lowercase()
            val token = mutex.withLock token@{
                if (startupFailure != null || (!foreground && !manual)) return@token null
                emit(error = null, syncing = true)
                generation
            } ?: return@withLock
            try {
                var baselineReady = false
                val preferences = mutex.withLock { account(owner).preferences }
                if (online() && !preferences.readingDeviceOnly) {
                    try {
                        val remote = transport.snapshot(owner) // failure MUST NOT mean an empty list
                        ensureCurrent(active, token)
                        mutex.withLock { mergeRemoteSnapshot(owner, remote) }
                        baselineReady = true
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { mutex.withLock { if (session == active && generation == token) emit(error = failure.message ?: "Reading list refresh needs retry.") } }
                }
                if (online() && !preferences.finishedDeviceOnly && (manual || historyRefreshRequested)) {
                    try {
                    val labels = transport.finished(owner).flatMap(ReadingEvents::parseFinished)
                    ensureCurrent(active, token)
                    mutex.withLock {
                        val current = account(owner)
                        val privateEntries = current.finished.filterNot { it.isPublic }
                        val privateCoordinates = privateEntries.map { it.bookCoordinate }.toSet()
                        val merged = privateEntries + (current.finished.filter { it.isPublic } + labels.filterNot { it.bookCoordinate in privateCoordinates }.map { label ->
                            label.copy(book = current.finished.firstOrNull { it.bookCoordinate == label.bookCoordinate }?.book)
                        }).groupBy { it.bookCoordinate }.values.map { entries ->
                            entries.maxBy { it.finishedAt }.copy(isPublic = entries.any { it.isPublic }, book = entries.firstNotNullOfOrNull { it.book })
                        }
                        persist(owner, current.copy(finished = merged, tracked = current.tracked.filterNot { tracked ->
                            tracked.isPublic && merged.any { it.bookCoordinate == tracked.bookCoordinate && it.finishedAt >= tracked.updatedAt }
                        }))
                        historyRefreshRequested = false
                    }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { mutex.withLock { if (session == active && generation == token) emit(error = failure.message ?: "Finished history refresh needs retry.") } }
                }
                if (online() && (!preferences.readingDeviceOnly || !preferences.finishedDeviceOnly)) resolveMissing(owner, active, token)
                // Labels are signed first. One snapshot includes all eligible list operations.
                while (_signRequest.value == null && (manual || !mutex.withLock { account(owner).automaticSigningRejected })) {
                    ensureCurrent(active, token)
                    val request = mutex.withLock { nextRequest(active, baselineReady) } ?: break
                    val signed = try { backgroundSign(active, ReadingEvents.unsignedJson(request.draft)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        mutex.withLock { if (session == active) emit(error = failure.message ?: "Background signing is unavailable. Reading changes remain saved.") }
                        break
                    }
                    when (signed) {
                        is BackgroundSignerResult.Signed -> {
                            try { saveSignature(request, signed.eventJson) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                mutex.withLock { if (session == active) emit(error = failure.message ?: "Could not save the signed reading event.") }
                                break
                            }
                        }
                        BackgroundSignerResult.Unavailable -> {
                            mutex.withLock {
                                ensureCurrent(active, token)
                                if (manual) _signRequest.value = request
                                emit(error = "Reading changes saved. Tap Sync to approve them in your signer.")
                            }
                            break
                        }
                        BackgroundSignerResult.Rejected -> {
                            mutex.withLock {
                                ensureCurrent(active, token)
                                persist(owner, account(owner).copy(automaticSigningRejected = true))
                                emit(error = "Your signer rejected reading-event signing. Change its permission before retrying.")
                            }
                            break
                        }
                    }
                }
                deliver(owner, active, token, force = manual)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutex.withLock { if (session == active) emit(error = failure.message ?: "Reading sync needs retry.") }
            } finally {
                mutex.withLock { emit(syncing = false) }
            }
        }
    }

    private fun ensureCurrent(active: NostrSignerSession, token: Long) {
        check(session == active && generation == token) { "Account or reading privacy changed. Sync again." }
    }

    private fun mergeRemoteSnapshot(owner: String, fetched: NostrEvent?) {
        val current = account(owner)
        val remote = ReadingEvents.newestSnapshot(listOfNotNull(current.remoteSnapshot, fetched))
        val privateEntries = current.tracked.filterNot { it.isPublic }
        val privateCoordinates = (privateEntries + current.privateReadingRemovals).map { it.bookCoordinate }.toSet()
        val known = (current.tracked + current.privateReadingRemovals).associateBy { it.bookCoordinate }
        val projected = ReadingEvents.parseSnapshot(remote ?: NostrEvent()).map {
            it.copy(book = known[it.bookCoordinate]?.book)
        }.filterNot { it.bookCoordinate in privateCoordinates }
        val outbox = current.outbox.map { delivery ->
            if (delivery.event.kind == BookKinds.READING_LIST && !delivery.complete && remote != null &&
                ReadingEvents.newestSnapshot(listOf(delivery.event, remote))?.id != delivery.event.id)
                delivery.copy(superseded = true) else delivery
        }
        val rebased = applyReadingOperations(projected, current.operations)
        persist(owner, current.copy(remoteSnapshot = remote, tracked = privateEntries + rebased, outbox = outbox))
    }

    private fun nextRequest(active: NostrSignerSession, baselineReady: Boolean): ReadingSignRequest? {
        val current = account(active.pubkey.lowercase())
        val assigned = current.outbox.filterNot { it.superseded }.flatMap { it.operationIds }.toSet()
        val pending = current.operations.filterNot { it.id in assigned }
        val label = pending.firstOrNull { it.type == ReadingOperationType.FINISH && !current.preferences.finishedDeviceOnly }
        if (label != null) return ReadingSignRequest(UUID.randomUUID().toString(), active,
            ReadingEvents.finishedDraft(active.pubkey, label.coordinate, label.finished?.finishedAt ?: now() / 1000), listOf(label.id), generation = generation)
        if (!baselineReady || current.preferences.readingDeviceOnly) return null
        val reading = pending.filter { it.type != ReadingOperationType.FINISH }
        if (reading.isEmpty()) return null
        val applied = current.operations.filter { it.type != ReadingOperationType.FINISH }
        val dependencies = applied.mapNotNull { operation -> operation.dependencyOperationId?.let { dependency ->
            current.outbox.firstOrNull { dependency in it.operationIds }?.event?.id
        } }.distinct()
        if (applied.any { it.dependencyOperationId != null && current.outbox.none { d -> it.dependencyOperationId in d.operationIds } }) return null
        val base = ReadingEvents.parseSnapshot(current.remoteSnapshot ?: NostrEvent())
        val books = applyReadingOperations(base, applied)
        return ReadingSignRequest(UUID.randomUUID().toString(), active,
            ReadingEvents.snapshotDraft(active.pubkey, books,
                maxOf(now() / 1000, (current.remoteSnapshot?.createdAt ?: 0) + 1),
                ReadingEvents.preservedSnapshotTags(current.remoteSnapshot ?: NostrEvent())), reading.map { it.id }, dependencies, generation)
    }

    suspend fun completeSignature(requestId: String?, eventJson: String) = withContext(Dispatchers.IO) {
        val request = _signRequest.value ?: return@withContext
        if (request.id != requestId) return@withContext
        try { saveSignature(request, eventJson) }
        catch (failure: Exception) {
            mutex.withLock { _signRequest.value = null; emit(error = failure.message ?: "Could not save signed reading event.") }
        }
        // Do not request another foreground signature automatically; the next Sync remains explicit.
        sync()
    }

    suspend fun failSignature(requestId: String?, message: String) {
        mutex.withLock {
            if (_signRequest.value?.id != requestId) return@withLock
            _signRequest.value = null
            emit(error = message)
        }
    }

    private suspend fun saveSignature(request: ReadingSignRequest, eventJson: String) {
        val event = ReadingEvents.decodeSigned(eventJson, request.draft)
        mutex.withLock {
            check(session == request.session) { "Account changed. Reading changes remain saved for the original account." }
            check(generation == request.generation) { "Reading privacy changed. Sync again." }
            val owner = request.session.pubkey.lowercase()
            val current = account(owner)
            check(enabled(current, event.kind)) { "Public sharing was paused. Reading changes remain saved." }
            check(request.operationIds.all { id -> current.operations.any { it.id == id } }) { "Reading changes were superseded. Sync again." }
            val local = localRelay()
            val existing = current.outbox.firstOrNull { it.event.id == event.id }
            val newest = if (event.kind == BookKinds.READING_LIST) ReadingEvents.newestSnapshot(listOfNotNull(current.remoteSnapshot, event)) else current.remoteSnapshot
            val staleSnapshot = event.kind == BookKinds.READING_LIST && newest?.id != event.id
            val delivery = if (existing == null) ReadingDelivery(event, request.operationIds, request.dependencies, targets = setOfNotNull(local), superseded = staleSnapshot)
                else existing.copy(operationIds = (existing.operationIds + request.operationIds).distinct(),
                    dependencies = (existing.dependencies + request.dependencies).distinct(), routesResolved = false,
                    targets = existing.targets + listOfNotNull(local), superseded = staleSnapshot, nextRetryAtMillis = 0)
            persist(owner, current.copy(outbox = current.outbox.filterNot { it.event.id == event.id } + delivery, automaticSigningRejected = false,
                remoteSnapshot = newest))
            if (_signRequest.value?.id == request.id) _signRequest.value = null
        }
    }

    private suspend fun deliver(owner: String, active: NostrSignerSession, token: Long, force: Boolean) {
        val ids = mutex.withLock { account(owner).outbox.filterNot { it.complete }.map { it.event.id } }
        val attempted = mutableSetOf<Pair<String, String>>()
        for (id in ids) {
            ensureCurrent(active, token)
            var entry = mutex.withLock { account(owner).outbox.first { it.event.id == id } }
            if (entry.complete || (!force && entry.nextRetryAtMillis > now())) continue
            val allowed = mutex.withLock { enabled(account(owner), entry.event.kind) }
            if (!allowed) continue
            var routesResolved = false
            val remoteRoutes = if (online()) {
                try {
                    transport.writeRelays(owner).filter { it != localRelay() }.also { routesResolved = it.isNotEmpty() }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    mutex.withLock { emit(error = failure.message ?: "Reading relay routing needs retry; local delivery remains available.") }
                    emptyList()
                }
            } else emptyList()
            ensureCurrent(active, token)
            val targets = if (routesResolved) listOfNotNull(localRelay()).toSet() + remoteRoutes else entry.targets + listOfNotNull(localRelay())
            entry = entry.copy(targets = targets, routesResolved = if (online()) routesResolved else entry.routesResolved)
            saveDelivery(owner, entry)
            for (relay in targets - entry.acceptedRelays) {
                ensureCurrent(active, token)
                if (relay != localRelay() && !online()) continue
                var dependenciesReady = true
                for (dependency in entry.dependencies) {
                    var label = mutex.withLock { account(owner).outbox.firstOrNull { it.event.id == dependency } }
                    if (label == null) { dependenciesReady = false; break }
                    if (relay !in label.acceptedRelays) {
                        if ((label.event.id to relay) in attempted || (!force && label.failure != null && label.nextRetryAtMillis > now())) {
                            dependenciesReady = false
                            break
                        }
                        if (!mutex.withLock { enabled(account(owner), label.event.kind) }) { dependenciesReady = false; break }
                        // A new destination also needs the label, even if its original routes completed.
                        label = label.copy(targets = label.targets + relay)
                        saveDelivery(owner, label)
                        attempted += label.event.id to relay
                        label = publishSaved(owner, label, relay, active, token)
                    }
                    if (relay !in label.acceptedRelays) { dependenciesReady = false; break }
                }
                if (!dependenciesReady) continue
                attempted += entry.event.id to relay
                entry = publishSaved(owner, entry, relay, active, token)
            }
        }
    }

    private suspend fun publishSaved(owner: String, entry: ReadingDelivery, relay: String, active: NostrSignerSession, token: Long): ReadingDelivery {
        val report = try { transport.publish(entry.event, relay) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            ensureCurrent(active, token)
            val failed = entry.copy(attempts = entry.attempts + 1, nextRetryAtMillis = now() + 30_000, failure = failure.message ?: "Relay delivery failed.")
            saveDelivery(owner, failed)
            return failed
        }
        ensureCurrent(active, token)
        val accepted = report.outcomes.any { outcome ->
            // ReadingTransport.publish has exactly one destination; its reported URL may be
            // normalized (for example a trailing slash) without changing that destination.
            (outcome.type == RelayPublishOutcomeType.ACCEPTED ||
                (outcome.type == RelayPublishOutcomeType.REJECTED && outcome.reason?.trim()?.lowercase()?.let {
                    it.startsWith("duplicate") || it.startsWith("already have") || it.startsWith("already exists")
                } == true))
        }
        val attempts = entry.attempts + 1
        val delivered = entry.copy(
            acceptedRelays = if (accepted) entry.acceptedRelays + relay else entry.acceptedRelays,
            attempts = attempts,
            nextRetryAtMillis = now() + (1_000L shl (attempts - 1).coerceAtMost(10)).coerceAtMost(900_000),
            failure = if (accepted) null else report.failureMessage(),
        )
        saveDelivery(owner, delivered)
        return delivered
    }

    private suspend fun saveDelivery(owner: String, delivery: ReadingDelivery) = mutex.withLock {
        val current = account(owner)
        persist(owner, current.copy(
            outbox = current.outbox.map { if (it.event.id == delivery.event.id) delivery else it },
            operations = if (delivery.complete) current.operations.filterNot { it.id in delivery.operationIds } else current.operations,
        ))
    }

    suspend fun resolveBook(coordinate: String): BookSummary? = withContext(Dispatchers.IO) {
        val active = session
        val owner = key()
        val cached = mutex.withLock { account(owner).let { a ->
            a.tracked.firstOrNull { it.bookCoordinate == coordinate }?.book ?: a.finished.firstOrNull { it.bookCoordinate == coordinate }?.book
        } }
        if (cached != null || !online()) return@withContext cached
        val resolved = transport.resolve(listOf(coordinate)).firstOrNull { it.coordinate == coordinate } ?: return@withContext null
        mutex.withLock {
            if (session == active) updateBooks(owner, listOf(resolved))
        }
        resolved
    }

    private suspend fun resolveMissing(owner: String, active: NostrSignerSession, token: Long) {
        val coordinates = mutex.withLock { account(owner).let { a ->
            (a.tracked.filter { it.book == null }.map { it.bookCoordinate } + a.finished.filter { it.book == null }.map { it.bookCoordinate }).distinct().take(40)
        } }
        if (coordinates.isEmpty()) return
        try {
            val books = transport.resolve(coordinates)
            mutex.withLock { ensureCurrent(active, token); updateBooks(owner, books) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Unresolved entries remain visible and can be retried individually. */ }
    }

    private fun updateBooks(owner: String, books: List<BookSummary>) {
        val current = account(owner)
        val known = books.associateBy { it.coordinate }
        persist(owner, current.copy(
            tracked = current.tracked.map { it.copy(book = known[it.bookCoordinate] ?: it.book) },
            finished = current.finished.map { it.copy(book = known[it.bookCoordinate] ?: it.book) },
        ))
    }

    private fun operation(type: ReadingOperationType, entry: TrackedBook) = ReadingOperation(UUID.randomUUID().toString(), type, entry.bookCoordinate, tracked = entry)
    private fun operationKind(op: ReadingOperation) = if (op.type == ReadingOperationType.FINISH) BookKinds.FINISHED_LABEL else BookKinds.READING_LIST
    private fun enabled(account: ReadingAccountState, kind: Int) = when (kind) {
        BookKinds.READING_LIST -> !account.preferences.readingDeviceOnly
        BookKinds.FINISHED_LABEL -> !account.preferences.finishedDeviceOnly
        else -> false
    }
    private companion object { const val GUEST = "device-guest" }
}

/** Apply intent, not a union of old snapshots: removals/reset must survive a remote refresh. */
internal fun applyReadingOperations(base: List<TrackedBook>, operations: List<ReadingOperation>): List<TrackedBook> {
    val books = base.associateBy { it.bookCoordinate }.toMutableMap()
    operations.forEach { operation ->
        when (operation.type) {
            ReadingOperationType.STOP -> books.remove(operation.coordinate)
            ReadingOperationType.TRACK, ReadingOperationType.RESET -> operation.tracked?.let { books[operation.coordinate] = it }
            ReadingOperationType.ADVANCE -> operation.tracked?.let { entry ->
                val old = books[entry.bookCoordinate]
                books[entry.bookCoordinate] = if (old != null && old.position > entry.position) old.copy(book = entry.book ?: old.book) else entry
            }
            ReadingOperationType.FINISH -> Unit
        }
    }
    return books.values.toList()
}

private fun appendAdvance(operations: List<ReadingOperation>, next: ReadingOperation, outbox: List<ReadingDelivery>): List<ReadingOperation> {
    val assigned = outbox.filterNot { it.complete }.flatMap { it.operationIds }.toSet()
    val previous = operations.lastOrNull()
    return if (previous?.type == ReadingOperationType.ADVANCE && previous.coordinate == next.coordinate && previous.id !in assigned)
        operations.dropLast(1) + next else operations + next
}
