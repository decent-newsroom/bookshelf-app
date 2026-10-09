package eu.decentnewsroom.bookshelf.data.reading

import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Metadata and signed delivery records are durable user data, never a clearable cache. */
class ReadingStateStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun read(): ReadingDiskState {
        if (!file.exists()) return ReadingDiskState()
        // Do not silently overwrite corrupt user state with an empty list.
        return json.decodeFromString(ReadingDiskState.serializer(), file.readText(Charsets.UTF_8))
            .also { require(it.version == 1) { "Unsupported reading state version." } }
    }

    fun write(state: ReadingDiskState) {
        file.parentFile?.mkdirs()
        val temporary = File(requireNotNull(file.parentFile), "${file.name}.tmp")
        temporary.outputStream().use { stream ->
            stream.write(json.encodeToString(ReadingDiskState.serializer(), state).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        try {
            try { Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
    }
}

@Serializable
data class ReadingDiskState(val version: Int = 1, val accounts: Map<String, ReadingAccountState> = emptyMap())

@Serializable
data class ReadingAccountState(
    val preferences: ReadingPrivacy = ReadingPrivacy(),
    val tracked: List<TrackedBook> = emptyList(),
    val finished: List<FinishedBook> = emptyList(),
    val remoteSnapshot: NostrEvent? = null,
    val operations: List<ReadingOperation> = emptyList(),
    val outbox: List<ReadingDelivery> = emptyList(),
    val privateReadingRemovals: List<TrackedBook> = emptyList(),
    val automaticSigningRejected: Boolean = false,
)

@Serializable
enum class ReadingOperationType { TRACK, ADVANCE, RESET, STOP, FINISH }

@Serializable
data class ReadingOperation(
    val id: String,
    val type: ReadingOperationType,
    val coordinate: String,
    val tracked: TrackedBook? = null,
    val finished: FinishedBook? = null,
    val dependencyOperationId: String? = null,
)

@Serializable
data class ReadingDelivery(
    val event: NostrEvent,
    val operationIds: List<String>,
    val dependencies: List<String> = emptyList(),
    val acceptedRelays: Set<String> = emptySet(),
    val targets: Set<String> = emptySet(),
    val routesResolved: Boolean = false,
    val attempts: Int = 0,
    val nextRetryAtMillis: Long = 0,
    val failure: String? = null,
    val superseded: Boolean = false,
) {
    val complete: Boolean get() = superseded || (routesResolved && targets.isNotEmpty() && acceptedRelays.containsAll(targets))
}
