package eu.decentnewsroom.bookshelf.data.reading

import eu.decentnewsroom.bookshelf.data.nostr.BackgroundSignerResult
import eu.decentnewsroom.bookshelf.data.nostr.NostrSignerSession
import eu.decentnewsroom.bookshelf.data.nostr.PublishReport
import eu.decentnewsroom.bookshelf.data.nostr.RelayPublishOutcome
import eu.decentnewsroom.bookshelf.data.nostr.RelayPublishOutcomeType
import eu.decentnewsroom.bookshelf.domain.BookDetail
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.domain.ChapterReference
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReadingStateRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owner = "a".repeat(64)
    private val session = NostrSignerSession(owner, "test.signer")

    @Test
    fun guestProgressIsMonotonicResettableAndNeverStartsRelayOrSignerWork() = runBlocking {
        val fixture = fixture()
        try {
            val book = book("guest")
            fixture.repository.track(book)
            fixture.repository.advance(book, 4)
            fixture.repository.advance(book, 2)
            assertEquals(4, fixture.repository.state.value.tracked.single().position)
            fixture.repository.reset(book.summary.coordinate)
            assertEquals(0, fixture.repository.state.value.tracked.single().position)
            fixture.repository.advance(book, 99)
            assertEquals(6, fixture.repository.state.value.tracked.single().position)
            assertTrue(fixture.repository.state.value.finished.isEmpty())
            fixture.repository.sync(manual = true)
            assertEquals(0, fixture.transport.calls)
            assertEquals(0, fixture.signCalls)
            assertTrue(fixture.store.read().accounts.getValue("device-guest").operations.isEmpty())
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun explicitFinishRemovesTrackingDeduplicatesHistoryAndRereadingPreservesIt() = runBlocking {
        val fixture = fixture()
        try {
            val book = book("finished")
            fixture.repository.track(book)
            fixture.repository.advance(book, 6)
            assertTrue(fixture.repository.state.value.finished.isEmpty())
            fixture.repository.finish(book.summary)
            val completedAt = fixture.repository.state.value.finished.single().finishedAt
            assertTrue(fixture.repository.state.value.tracked.isEmpty())
            fixture.repository.finish(book.summary)
            assertEquals(1, fixture.repository.state.value.finished.size)
            fixture.repository.track(book, 2)
            assertEquals(completedAt, fixture.repository.state.value.finished.single().finishedAt)
            assertEquals(2, fixture.repository.state.value.tracked.single().position)
            fixture.repository.stop(book.summary.coordinate)
            assertTrue(fixture.repository.state.value.tracked.isEmpty())
            assertEquals(completedAt, fixture.repository.state.value.finished.single().finishedAt)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun persistedGuestStateIsVisibleImmediatelyAfterRestart() = runBlocking {
        val fixture = fixture()
        try {
            val book = book("restart")
            fixture.repository.track(book, 3)
            val restarted = fixture.repository()
            assertEquals(book.summary.coordinate, restarted.state.value.tracked.single().bookCoordinate)
            assertEquals(3, restarted.state.value.tracked.single().position)
            restarted.setSession(null)
            assertEquals(3, restarted.state.value.tracked.single().position)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun guestAndEachSignerAccountKeepIndependentDataAndPrivacy() = runBlocking {
        val fixture = fixture()
        try {
            val guest = book("guest")
            val accountBook = book("account")
            fixture.repository.track(guest)
            fixture.repository.setSession(session)
            assertTrue(fixture.repository.state.value.tracked.isEmpty())
            fixture.repository.track(accountBook)
            fixture.repository.setPrivacy(readingDeviceOnly = false, shareCoordinates = setOf(accountBook.summary.coordinate))
            assertFalse(fixture.repository.state.value.preferences.readingDeviceOnly)
            assertTrue(fixture.repository.state.value.preferences.finishedDeviceOnly)
            assertTrue(fixture.repository.state.value.tracked.single().isPublic)
            fixture.repository.setSession(NostrSignerSession("b".repeat(64), "test.signer"))
            assertTrue(fixture.repository.state.value.tracked.isEmpty())
            assertTrue(fixture.repository.state.value.preferences.readingDeviceOnly)
            fixture.repository.setSession(null)
            assertEquals(guest.summary.coordinate, fixture.repository.state.value.tracked.single().bookCoordinate)
            assertFalse(fixture.repository.state.value.tracked.single().isPublic)
            fixture.repository.setSession(session)
            assertEquals(accountBook.summary.coordinate, fixture.repository.state.value.tracked.single().bookCoordinate)
            assertFalse(fixture.repository.state.value.preferences.readingDeviceOnly)
            assertEquals(0, fixture.transport.calls)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun privacyPreviewOnlySharesSelectedEntriesAndPreferencesRemainIndependent() = runBlocking {
        val fixture = fixture()
        try {
            fixture.repository.setSession(session)
            val selected = book("selected")
            val privateBook = book("private")
            fixture.repository.track(selected)
            fixture.repository.track(privateBook)
            fixture.repository.finish(book("finished-private").summary)
            fixture.repository.setPrivacy(readingDeviceOnly = false, shareCoordinates = setOf(selected.summary.coordinate))
            val entries = fixture.repository.state.value.tracked.associateBy { it.bookCoordinate }
            assertTrue(entries.getValue(selected.summary.coordinate).isPublic)
            assertFalse(entries.getValue(privateBook.summary.coordinate).isPublic)
            assertFalse(fixture.repository.state.value.finished.single().isPublic)
            assertTrue(fixture.repository.state.value.preferences.finishedDeviceOnly)
            fixture.repository.advance(privateBook, 4)
            assertFalse(fixture.repository.state.value.tracked.first { it.bookCoordinate == privateBook.summary.coordinate }.isPublic)
            fixture.repository.setPrivacy(readingDeviceOnly = true)
            assertTrue(fixture.repository.state.value.preferences.readingDeviceOnly)
            assertEquals(0, fixture.transport.calls)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun localChangesWhilePausedRemainPrivateWhenReenabledWithoutSelection() = runBlocking {
        val fixture = fixture()
        try {
            fixture.repository.setSession(session)
            fixture.repository.setPrivacy(readingDeviceOnly = false)
            val book = book("paused")
            fixture.repository.track(book)
            fixture.repository.setPrivacy(readingDeviceOnly = true)
            fixture.repository.advance(book, 3)
            fixture.repository.setPrivacy(readingDeviceOnly = false, shareCoordinates = emptySet())
            assertFalse(fixture.repository.state.value.tracked.single().isPublic)
            assertTrue(fixture.store.read().accounts.getValue(owner).operations.none { it.coordinate == book.summary.coordinate })
            fixture.repository.sync(manual = true)
            assertNull(fixture.repository.signRequest.value)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun guestCannotEnablePublicSharingAndUnknownSectionStreamCannotBeTracked() = runBlocking {
        val fixture = fixture()
        try {
            try {
                fixture.repository.setPrivacy(readingDeviceOnly = false)
                fail("Guest sharing should be rejected")
            } catch (_: IllegalArgumentException) { }
            val unknown = book("unknown").let { it.copy(summary = it.summary.copy(sectionStreamKnown = false)) }
            try {
                fixture.repository.track(unknown)
                fail("Unknown stream should not be tracked")
            } catch (_: IllegalArgumentException) { }
            assertTrue(fixture.repository.state.value.preferences.readingDeviceOnly)
            assertTrue(fixture.repository.state.value.tracked.isEmpty())
            assertEquals(0, fixture.transport.calls)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun failedRemoteReadRetainsPendingIntentAndCannotRequestDestructiveSnapshot() = runBlocking {
        val fixture = fixture()
        try {
            fixture.repository.setSession(session)
            fixture.repository.setPrivacy(readingDeviceOnly = false)
            fixture.repository.track(book("pending"), 2)
            fixture.transport.snapshotFailure = IllegalStateException("Relay read failed")
            fixture.repository.sync(manual = true)
            assertNotNull(fixture.repository.state.value.error)
            assertNull(fixture.repository.signRequest.value)
            assertEquals(0, fixture.signCalls)
            assertEquals(1, fixture.store.read().accounts.getValue(owner).operations.size)
            assertEquals(2, fixture.repository.state.value.tracked.single().position)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun unavailableBackgroundPermissionLeavesDurableIntentForExplicitSignerRequest() = runBlocking {
        val fixture = fixture()
        try {
            fixture.repository.setSession(session)
            fixture.repository.setPrivacy(readingDeviceOnly = false)
            val book = book("signer")
            fixture.repository.track(book, 3)
            assertNull(fixture.repository.signRequest.value)
            fixture.repository.sync(manual = true)
            val request = requireNotNull(fixture.repository.signRequest.value)
            assertEquals(session, request.session)
            assertEquals(3, ReadingEvents.parseSnapshot(request.draft).single().position)
            assertTrue(fixture.store.read().accounts.getValue(owner).outbox.isEmpty())
            assertEquals(1, fixture.store.read().accounts.getValue(owner).operations.size)
            fixture.repository.failSignature(request.id, "Cancelled by reader")
            assertNull(fixture.repository.signRequest.value)
            assertEquals(1, fixture.store.read().accounts.getValue(owner).operations.size)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun explicitBackgroundRejectionDoesNotFallThroughToForegroundSigner() = runBlocking {
        val fixture = fixture()
        try {
            fixture.repository.setSession(session)
            fixture.repository.setPrivacy(readingDeviceOnly = false)
            fixture.repository.track(book("rejected"))
            fixture.backgroundResult = BackgroundSignerResult.Rejected
            fixture.repository.sync(manual = true)
            assertNull(fixture.repository.signRequest.value)
            assertTrue(fixture.repository.state.value.error.orEmpty().contains("rejected"))
            assertEquals(1, fixture.store.read().accounts.getValue(owner).operations.size)
            assertTrue(fixture.store.read().accounts.getValue(owner).outbox.isEmpty())
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun publicFinishQueuesLabelBeforeRemovalWhileMixedPrivacyQueuesOnlyApplicableIntent() = runBlocking {
        val fixture = fixture()
        try {
            fixture.repository.setSession(session)
            fixture.repository.setPrivacy(readingDeviceOnly = false, finishedDeviceOnly = false)
            val publicBook = book("both-public")
            fixture.repository.track(publicBook)
            fixture.repository.finish(publicBook.summary)
            val operations = fixture.store.read().accounts.getValue(owner).operations
            val label = operations.single { it.type == ReadingOperationType.FINISH }
            val stop = operations.single { it.type == ReadingOperationType.STOP }
            assertEquals(label.id, stop.dependencyOperationId)
            assertTrue(operations.indexOf(label) < operations.indexOf(stop))
            fixture.repository.setPrivacy(finishedDeviceOnly = true)
            val mixedBook = book("history-private")
            fixture.repository.track(mixedBook)
            fixture.repository.finish(mixedBook.summary)
            val mixed = fixture.store.read().accounts.getValue(owner).operations.filter { it.coordinate == mixedBook.summary.coordinate }
            assertTrue(mixed.any { it.type == ReadingOperationType.STOP })
            assertTrue(mixed.none { it.type == ReadingOperationType.FINISH })
            assertFalse(fixture.repository.state.value.finished.first { it.bookCoordinate == mixedBook.summary.coordinate }.isPublic)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun rereadCompletionStillDependsOnEarlierUndeliveredLabel() = runBlocking {
        val fixture = fixture()
        try {
            fixture.repository.setSession(session)
            fixture.repository.setPrivacy(readingDeviceOnly = false, finishedDeviceOnly = false)
            val book = book("pending-finished-reread")
            fixture.repository.track(book)
            fixture.repository.finish(book.summary)
            val label = fixture.store.read().accounts.getValue(owner).operations.single { it.type == ReadingOperationType.FINISH }
            fixture.repository.track(book)
            fixture.repository.finish(book.summary)
            val current = fixture.store.read().accounts.getValue(owner).operations
            assertEquals(1, current.count { it.type == ReadingOperationType.FINISH })
            assertEquals(label.id, current.last { it.type == ReadingOperationType.STOP }.dependencyOperationId)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun stoppedPublicEntryRequiresSelectionBeforeItsRemovalIsShared() = runBlocking {
        val fixture = fixture()
        try {
            fixture.repository.setSession(session)
            fixture.repository.setPrivacy(readingDeviceOnly = false)
            val book = book("private-removal")
            fixture.repository.track(book)
            fixture.repository.setPrivacy(readingDeviceOnly = true)
            fixture.repository.stop(book.summary.coordinate)
            assertTrue(fixture.repository.state.value.tracked.isEmpty())
            assertEquals(book.summary.coordinate, fixture.repository.state.value.privateReadingRemovals.single().bookCoordinate)
            fixture.repository.setPrivacy(readingDeviceOnly = false)
            assertTrue(fixture.store.read().accounts.getValue(owner).operations.isEmpty())
            assertEquals(1, fixture.repository.state.value.privateReadingRemovals.size)
            fixture.repository.setPrivacy(readingDeviceOnly = false, shareCoordinates = setOf(book.summary.coordinate))
            assertTrue(fixture.repository.state.value.privateReadingRemovals.isEmpty())
            assertEquals(ReadingOperationType.STOP, fixture.store.read().accounts.getValue(owner).operations.single().type)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun failedLabelBlocksOnlyItsRelayAndRetriesKeepOriginalSignedEventIds() = runBlocking {
        val fixture = fixture()
        try {
            val relayOne = "wss://one.example"
            val relayTwo = "wss://two.example"
            fixture.transport.routes = listOf(relayOne, relayTwo)
            val (label, removal) = seedFinishDeliveries(fixture, setOf(relayOne, relayTwo))
            fixture.transport.rejected = setOf(label.id to relayTwo)
            val repository = fixture.repository()
            repository.setSession(session)
            repository.sync(manual = true)
            assertTrue(fixture.transport.published.contains(label.id to relayOne))
            assertTrue(fixture.transport.published.contains(removal.id to relayOne))
            assertTrue(fixture.transport.published.contains(label.id to relayTwo))
            assertFalse(fixture.transport.published.contains(removal.id to relayTwo))
            fixture.transport.rejected = emptySet()
            val restarted = fixture.repository()
            restarted.setSession(session)
            restarted.sync(manual = true)
            assertTrue(fixture.transport.published.contains(removal.id to relayTwo))
            assertEquals(setOf(label.id, removal.id), fixture.transport.published.map { it.first }.toSet())
            assertEquals(1, fixture.transport.published.count { it == (removal.id to relayOne) })
            assertTrue(fixture.store.read().accounts.getValue(owner).outbox.all { it.complete })
            assertTrue(fixture.store.read().accounts.getValue(owner).operations.isEmpty())
            assertEquals(0, fixture.signCalls)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun offlineCitrineReceivesOriginalLabelBeforeRemovalWhileRemoteDeliveryWaits() = runBlocking {
        val fixture = fixture()
        try {
            fixture.online = false
            fixture.localRelay = "ws://127.0.0.1:4869"
            val remote = "wss://remote.example"
            val (label, removal) = seedFinishDeliveries(fixture, setOf(requireNotNull(fixture.localRelay), remote))
            val repository = fixture.repository()
            repository.setSession(session)
            repository.sync(manual = true)
            assertEquals(listOf(label.id to fixture.localRelay, removal.id to fixture.localRelay), fixture.transport.published)
            assertEquals(2, fixture.transport.calls)
            val durable = fixture.store.read().accounts.getValue(owner).outbox
            assertTrue(durable.all { !it.complete })
            assertTrue(durable.all { requireNotNull(fixture.localRelay) in it.acceptedRelays })
            assertEquals(0, fixture.signCalls)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun deviceOnlyPausesSeededSignedWorkIncludingOfflineLocalDelivery() = runBlocking {
        val fixture = fixture()
        try {
            fixture.online = false
            fixture.localRelay = "ws://127.0.0.1:4869"
            seedFinishDeliveries(fixture, setOf(requireNotNull(fixture.localRelay)))
            val repository = fixture.repository()
            repository.setSession(session)
            repository.setPrivacy(readingDeviceOnly = true, finishedDeviceOnly = true)
            repository.sync(manual = true)
            assertEquals(0, fixture.transport.calls)
            assertTrue(fixture.transport.published.isEmpty())
            assertTrue(fixture.store.read().accounts.getValue(owner).outbox.all { !it.complete })
            assertEquals(0, fixture.signCalls)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun newlyAddedDestinationReceivesPreviouslyCompletedLabelBeforePendingRemoval() = runBlocking {
        val fixture = fixture()
        try {
            val oldRelay = "wss://old.example"
            val addedRelay = "wss://added.example"
            val (label, removal) = seedFinishDeliveries(fixture, setOf(oldRelay))
            val disk = fixture.store.read()
            val account = disk.accounts.getValue(owner)
            fixture.store.write(disk.copy(accounts = mapOf(owner to account.copy(outbox = account.outbox.map {
                if (it.event.id == label.id) it.copy(routesResolved = true, acceptedRelays = setOf(oldRelay)) else it
            }))))
            fixture.transport.routes = listOf(oldRelay, addedRelay)
            val repository = fixture.repository()
            repository.setSession(session)
            repository.sync(manual = true)
            val addedPublishes = fixture.transport.published.filter { it.second == addedRelay }
            assertEquals(listOf(label.id to addedRelay, removal.id to addedRelay), addedPublishes)
            assertFalse(fixture.transport.published.contains(label.id to oldRelay))
            assertTrue(fixture.store.read().accounts.getValue(owner).outbox.all { it.complete })
            assertEquals(0, fixture.signCalls)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun unselectedPrivateFinishedEntryIsNotPromotedByRemoteLabelForSameEdition() = runBlocking {
        val fixture = fixture()
        try {
            val book = book("private-finished-overlay")
            val privateFinish = FinishedBook(book.summary.coordinate, book.summary, 1_700_000_010, isPublic = false)
            fixture.store.write(ReadingDiskState(accounts = mapOf(owner to ReadingAccountState(
                preferences = ReadingPrivacy(readingDeviceOnly = true, finishedDeviceOnly = false),
                finished = listOf(privateFinish),
            ))))
            fixture.transport.finishedEvents = listOf(ReadingEvents.finishedDraft(owner, book.summary.coordinate, 1_700_000_000))
            val repository = fixture.repository()
            repository.setSession(session)
            repository.sync(manual = true)
            val visible = repository.state.value.finished.single()
            assertFalse(visible.isPublic)
            assertEquals(privateFinish.finishedAt, visible.finishedAt)
            assertEquals("On this device", visible.status)
            assertEquals(0, fixture.signCalls)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun continuousAdvancesUseOneFixedWindowAndNextBurstStartsAnother() = runBlocking {
        val fixture = fixture()
        try {
            fixture.online = false
            val firstWindow = CompletableDeferred<Unit>()
            val secondWindow = CompletableDeferred<Unit>()
            var windows = 0
            fixture.publicationWindow = {
                windows++
                if (windows == 1) firstWindow.await() else secondWindow.await()
            }
            fixture.repository.setSession(session)
            fixture.repository.setPrivacy(readingDeviceOnly = false)
            val book = book("burst")
            fixture.repository.track(book)
            fixture.repository.setForeground(true)
            awaitInitialForegroundSync(fixture)
            assertEquals(0, fixture.signCalls)
            fixture.repository.advance(book, 1)
            val firstJob = fixture.scope.coroutineContext.get(Job)!!.children.last()
            assertEquals(1, windows)
            (2..5).forEach { fixture.repository.advance(book, it) }
            assertEquals(1, windows)
            assertEquals(5, fixture.repository.state.value.tracked.single().position)
            firstWindow.complete(Unit)
            withTimeout(5_000) { firstJob.join() }
            fixture.repository.advance(book, 6)
            assertEquals(2, windows)
            assertEquals(6, fixture.repository.state.value.tracked.single().position)
            assertEquals(0, fixture.signCalls)
            assertEquals(0, fixture.transport.calls)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun persistedSignerRejectionSuppressesAutomaticRetryUntilExplicitSync() = runBlocking {
        val fixture = fixture()
        try {
            fixture.repository.setSession(session)
            fixture.repository.setPrivacy(readingDeviceOnly = false)
            fixture.repository.track(book("automatic-rejection"))
            fixture.backgroundResult = BackgroundSignerResult.Rejected
            fixture.repository.sync(manual = true)
            assertEquals(1, fixture.signCalls)
            assertTrue(fixture.store.read().accounts.getValue(owner).automaticSigningRejected)
            val restarted = fixture.repository()
            restarted.setSession(session)
            restarted.setForeground(true)
            awaitInitialForegroundSync(fixture)
            assertEquals(1, fixture.signCalls)
            assertNull(restarted.signRequest.value)
            fixture.backgroundResult = BackgroundSignerResult.Unavailable
            restarted.sync(manual = true)
            assertEquals(2, fixture.signCalls)
            assertNotNull(restarted.signRequest.value)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun failedRemoteRoutingStillAllowsLocalCitrineDelivery() = runBlocking {
        val fixture = fixture()
        try {
            fixture.localRelay = "ws://127.0.0.1:4869"
            val local = requireNotNull(fixture.localRelay)
            val (label, removal) = seedFinishDeliveries(fixture, setOf(local))
            fixture.transport.routingFailure = IllegalStateException("Remote routing unavailable")
            val repository = fixture.repository()
            repository.setSession(session)
            repository.sync(manual = true)
            assertEquals(listOf(label.id to local, removal.id to local), fixture.transport.published)
            val durable = fixture.store.read().accounts.getValue(owner).outbox
            assertTrue(durable.all { local in it.acceptedRelays && !it.complete })
            assertTrue(repository.state.value.error.orEmpty().contains("routing"))
            assertEquals(0, fixture.signCalls)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun completedProgressSnapshotsCompactWhileNewestBaselineAndRequiredLabelsSurvive() = runBlocking {
        val fixture = fixture()
        try {
            val (label, removal) = seedFinishDeliveries(fixture, setOf("wss://relay.example"))
            val relay = "wss://relay.example"
            val olderSnapshot = removal.copy(id = "3".repeat(64), createdAt = removal.createdAt - 1)
            val latestSnapshot = removal.copy(id = "4".repeat(64), createdAt = removal.createdAt + 1)
            fun complete(event: NostrEvent) = ReadingDelivery(event, emptyList(), targets = setOf(relay),
                acceptedRelays = setOf(relay), routesResolved = true)
            fixture.store.write(ReadingDiskState(accounts = mapOf(owner to ReadingAccountState(
                preferences = ReadingPrivacy(), remoteSnapshot = latestSnapshot,
                outbox = listOf(complete(olderSnapshot), complete(latestSnapshot), complete(label)),
            ))))
            val repository = fixture.repository()
            repository.setSession(session)
            repository.finish(book("trigger-compaction").summary)
            val account = fixture.store.read().accounts.getValue(owner)
            assertEquals(latestSnapshot, account.remoteSnapshot)
            assertTrue(account.outbox.none { it.event.kind == BookKinds.READING_LIST })
            assertEquals(label.id, account.outbox.single().event.id)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun corruptStoreBlocksMutationsAndPreservesOriginalBytes() = runBlocking {
        val fixture = fixture()
        try {
            val broken = "{not valid reading data"
            fixture.file.writeText(broken)
            val restarted = fixture.repository()
            assertNotNull(restarted.state.value.error)
            try {
                restarted.track(book("must-not-overwrite"))
                fail("Corrupt durable data should block writes")
            } catch (_: IllegalStateException) { }
            assertEquals(broken, fixture.file.readText())
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun pendingOperationsRebaseWithoutResurrectingStoppedBooksOrLosingRemoteProgress() {
        val first = tracked("first", 5)
        val unrelated = tracked("unrelated", 2)
        val stopped = tracked("stopped", 4)
        val reset = tracked("reset", 6)
        val operations = listOf(
            operation("advance", ReadingOperationType.ADVANCE, first.copy(position = 3)),
            operation("stop", ReadingOperationType.STOP, stopped),
            operation("reset", ReadingOperationType.RESET, reset.copy(position = 0)),
        )
        val rebased = applyReadingOperations(listOf(first, unrelated, stopped, reset), operations).associateBy { it.bookCoordinate }
        assertEquals(5, rebased.getValue(first.bookCoordinate).position)
        assertEquals(unrelated, rebased.getValue(unrelated.bookCoordinate))
        assertFalse(rebased.containsKey(stopped.bookCoordinate))
        assertEquals(0, rebased.getValue(reset.bookCoordinate).position)
    }

    @Test
    fun newerTrackAfterStopAndAdvanceAfterResetFollowLocalActionOrder() {
        val entry = tracked("ordered", 6)
        val operations = listOf(
            operation("stop", ReadingOperationType.STOP, entry),
            operation("track", ReadingOperationType.TRACK, entry.copy(position = 2)),
            operation("reset", ReadingOperationType.RESET, entry.copy(position = 0)),
            operation("advance", ReadingOperationType.ADVANCE, entry.copy(position = 3)),
            ReadingOperation("finish", ReadingOperationType.FINISH, entry.bookCoordinate),
        )
        assertEquals(3, applyReadingOperations(listOf(entry), operations).single().position)
    }

    private fun tracked(identifier: String, position: Int) = TrackedBook("30040:$owner:$identifier", position = position, total = 7, updatedAt = 1_700_000_000)
    private fun operation(id: String, type: ReadingOperationType, entry: TrackedBook) = ReadingOperation(id, type, entry.bookCoordinate, tracked = entry)

    private fun seedFinishDeliveries(fixture: Fixture, targets: Set<String>): Pair<NostrEvent, NostrEvent> {
        val coordinate = "30040:$owner:seeded"
        val label = ReadingEvents.finishedDraft(owner, coordinate, 1_700_000_000).copy(id = "1".repeat(64), sig = "a".repeat(128))
        val removal = ReadingEvents.snapshotDraft(owner, emptyList(), 1_700_000_001).copy(id = "2".repeat(64), sig = "b".repeat(128))
        // The store is an already trusted signed-data boundary; these tests exercise delivery,
        // not signer validation (which ReadingEventsTest covers with real signatures).
        fixture.store.write(ReadingDiskState(accounts = mapOf(owner to ReadingAccountState(
            preferences = ReadingPrivacy(false, false),
            operations = listOf(
                ReadingOperation("finish", ReadingOperationType.FINISH, coordinate),
                ReadingOperation("stop", ReadingOperationType.STOP, coordinate, dependencyOperationId = "finish"),
            ),
            outbox = listOf(
                ReadingDelivery(label, listOf("finish"), targets = targets),
                ReadingDelivery(removal, listOf("stop"), dependencies = listOf(label.id), targets = targets),
            ),
        ))))
        return label to removal
    }

    private fun book(identifier: String): BookDetail {
        val references = (0..6).map { ChapterReference("30041:$owner:$identifier-$it", owner, "$identifier-$it", null, null) }
        val summary = BookSummary(id = "c".repeat(64), coordinate = "30040:$owner:$identifier", pubkey = owner,
            identifier = identifier, title = identifier, summary = null, authors = emptyList(), coverImageUrl = null,
            sourceUrl = null, language = null, releaseDate = null, version = null, type = "book", topics = emptyList(),
            relay = null, createdAt = 1_700_000_000, chapterCount = 7, chapterRefs = references)
        // Unavailable chapter bodies still retain the complete section metadata used for tracking.
        return BookDetail(summary, emptyList(), availableChapterCount = 0, missingChapterCount = 7, truncated = false)
    }

    private fun fixture(): Fixture = Fixture(File(temporary.root, "reading-${System.nanoTime()}.json"))

    private suspend fun awaitInitialForegroundSync(fixture: Fixture) {
        // Foreground entry launches an immediate sync, then its long-lived retry loop.
        // The retry loop is always the last child; joining all earlier children also
        // works when the immediate offline sync has already completed.
        val initialJobs = fixture.scope.coroutineContext.get(Job)!!.children.toList().dropLast(1)
        withTimeout(5_000) { initialJobs.joinAll() }
    }

    private class Fixture(val file: File) {
        val store = ReadingStateStore(file)
        val transport = FakeTransport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var signCalls = 0
        var online = true
        var localRelay: String? = null
        var backgroundResult: BackgroundSignerResult = BackgroundSignerResult.Unavailable
        var publicationWindow: suspend () -> Unit = { delay(3_000) }
        fun repository() = ReadingStateRepository(store, transport, online = { online }, localRelay = { localRelay },
            backgroundSign = { _, _ -> signCalls++; backgroundResult }, scope = scope, now = { 1_700_000_000_000L },
            publicationWindow = { publicationWindow() })
        val repository = repository()
    }

    private class FakeTransport : ReadingTransport {
        var calls = 0
        var snapshotFailure: Exception? = null
        var routingFailure: Exception? = null
        var routes = emptyList<String>()
        var finishedEvents = emptyList<NostrEvent>()
        var rejected = emptySet<Pair<String, String>>()
        val published = mutableListOf<Pair<String, String>>()
        override suspend fun snapshot(pubkey: String): NostrEvent? { calls++; snapshotFailure?.let { throw it }; return null }
        override suspend fun finished(pubkey: String): List<NostrEvent> { calls++; return finishedEvents }
        override suspend fun resolve(coordinates: List<String>): List<BookSummary> { calls++; return emptyList() }
        override suspend fun writeRelays(pubkey: String): List<String> { calls++; routingFailure?.let { throw it }; return routes }
        override suspend fun publish(event: NostrEvent, relay: String): PublishReport {
            calls++
            published += event.id to relay
            val accepted = (event.id to relay) !in rejected
            return PublishReport(if (accepted) 1 else 0, 1, event.id, listOf(
                RelayPublishOutcome(relay, if (accepted) RelayPublishOutcomeType.ACCEPTED else RelayPublishOutcomeType.REJECTED),
            ))
        }
    }
}
