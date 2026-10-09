package eu.decentnewsroom.bookshelf.data.reading

import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReadingStateStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owner = "a".repeat(64)
    private val coordinate = "30040:$owner:edition"

    @Test
    fun accountPartitionsOperationsAndImmutableDeliverySurviveRestart() {
        val file = File(temporary.root, "bookshelf/reading-state-v1.json")
        val entry = TrackedBook(coordinate, position = 3, total = 7, updatedAt = 1_700_000_000, isPublic = true)
        val label = NostrEvent(id = "f".repeat(64), pubkey = owner, kind = BookKinds.FINISHED_LABEL,
            createdAt = 1_700_000_001, tags = listOf(listOf("a", coordinate)), sig = "b".repeat(128))
        val operation = ReadingOperation("stop", ReadingOperationType.STOP, coordinate, dependencyOperationId = "finish")
        val delivery = ReadingDelivery(label, listOf("finish"), targets = setOf("wss://one", "ws://127.0.0.1:4869"),
            acceptedRelays = setOf("ws://127.0.0.1:4869"), routesResolved = true, attempts = 2,
            nextRetryAtMillis = 7_000, failure = "Remote unavailable")
        val state = ReadingDiskState(accounts = mapOf(
            "device-guest" to ReadingAccountState(tracked = listOf(entry.copy(isPublic = false))),
            owner to ReadingAccountState(preferences = ReadingPrivacy(false, false), tracked = listOf(entry),
                operations = listOf(operation), outbox = listOf(delivery), privateReadingRemovals = listOf(entry.copy(isPublic = false))),
        ))
        ReadingStateStore(file).write(state)
        assertEquals(state, ReadingStateStore(file).read())
        val restored = ReadingStateStore(file).read().accounts.getValue(owner).outbox.single()
        assertEquals(label.id, restored.event.id)
        assertEquals(label.sig, restored.event.sig)
        assertFalse(restored.complete)
        assertFalse(File(file.parentFile, "${file.name}.tmp").exists())
    }

    @Test
    fun atomicReplacementRetainsLatestStateWithoutTemporaryFile() {
        val file = temporary.newFile("reading.json")
        val store = ReadingStateStore(file)
        store.write(ReadingDiskState(accounts = mapOf(owner to ReadingAccountState())))
        val latest = ReadingDiskState(accounts = mapOf(owner to ReadingAccountState(
            finished = listOf(FinishedBook(coordinate, finishedAt = 1_700_000_001)),
        )))
        store.write(latest)
        assertEquals(latest, ReadingStateStore(file).read())
        assertFalse(File(file.parentFile, "${file.name}.tmp").exists())
    }

    @Test
    fun corruptAndFutureVersionFilesArePreservedAndNeverReturnedAsEmptyState() {
        val file = temporary.newFile("reading.json")
        listOf("{broken", "{\"version\":2,\"accounts\":{}}").forEach { text ->
            file.writeText(text)
            assertThrows(Exception::class.java) { ReadingStateStore(file).read() }
            assertEquals(text, file.readText())
        }
        assertEquals(ReadingDiskState(), ReadingStateStore(File(temporary.root, "absent.json")).read())
    }

    @Test
    fun labelDeliveryNeedsEveryTargetAndResolvedRemoteRoutes() {
        val event = NostrEvent(id = "e".repeat(64), kind = BookKinds.FINISHED_LABEL)
        val initial = ReadingDelivery(event, listOf("label"), targets = setOf("wss://one", "wss://two"))
        assertFalse(initial.copy(acceptedRelays = initial.targets).complete)
        assertFalse(initial.copy(routesResolved = true, acceptedRelays = setOf("wss://one")).complete)
        assertTrue(initial.copy(routesResolved = true, acceptedRelays = initial.targets).complete)
        assertTrue(initial.copy(superseded = true).complete)
    }
}
