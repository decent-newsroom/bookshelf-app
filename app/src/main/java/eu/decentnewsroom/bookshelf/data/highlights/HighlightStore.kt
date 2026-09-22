package eu.decentnewsroom.bookshelf.data.highlights

import android.content.Context
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

@Serializable
data class ReaderHighlight(
    val id: String,
    val bookCoordinate: String,
    val chapterCoordinate: String,
    val chapterTitle: String,
    val chapterEvent: NostrEvent,
    val quote: String,
    val context: String,
    val startOffset: Int,
    val endOffset: Int,
    val prefix: String,
    val suffix: String,
    val comment: String = "",
    val createdAtMillis: Long,
    val publishedEventId: String? = null,
    val displayedTextHash: String = "",
)

/** Anchors use the same UTF-16 text that Compose displays, never raw HTML offsets. */
object HighlightAnchors {
    fun textHash(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun resolve(highlight: ReaderHighlight, text: String): IntRange? {
        if (highlight.quote.isEmpty()) return null
        if (highlight.displayedTextHash == textHash(text) && highlight.startOffset >= 0 &&
            highlight.endOffset <= text.length && highlight.endOffset > highlight.startOffset &&
            text.substring(highlight.startOffset, highlight.endOffset) == highlight.quote
        ) return highlight.startOffset until highlight.endOffset
        fun matches(start: Int): Boolean {
            val end = start + highlight.quote.length
            return start >= 0 && end <= text.length &&
                text.regionMatches(start, highlight.quote, 0, highlight.quote.length) &&
                start >= highlight.prefix.length && end + highlight.suffix.length <= text.length &&
                text.regionMatches(start - highlight.prefix.length, highlight.prefix, 0, highlight.prefix.length) &&
                text.regionMatches(end, highlight.suffix, 0, highlight.suffix.length)
        }
        // Even an old offset must match its surrounding text: duplicate passages may move.
        val candidates = mutableListOf<Int>()
        var from = 0
        while (from <= text.length - highlight.quote.length) {
            val start = text.indexOf(highlight.quote, from)
            if (start < 0) break
            if (matches(start)) {
                candidates += start
                if (candidates.size > 1) return null
            }
            from = start + 1
        }
        val start = candidates.singleOrNull() ?: return null
        return start until start + highlight.quote.length
    }

    fun prefix(text: String, start: Int): String = text.substring((start - 80).coerceAtLeast(0), start)
    fun suffix(text: String, end: Int): String = text.substring(end, (end + 80).coerceAtMost(text.length))
    fun context(text: String, start: Int, end: Int): String {
        val paragraphStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val paragraphEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        return text.substring(maxOf(paragraphStart.coerceAtMost(start), start - 240), minOf(paragraphEnd, end + 240))
    }
}

/** Personal reading data; intentionally independent of all clearable caches. */
class HighlightStore internal constructor(private val file: File) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "bookshelf/highlights-v1.json"))

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun all(): List<ReaderHighlight> = withContext(Dispatchers.IO) { mutex.withLock { read() } }

    suspend fun save(highlight: ReaderHighlight) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val values = read()
            write(values.filterNot { it.id == highlight.id } + highlight)
        }
    }

    suspend fun markPublished(id: String, eventId: String, comment: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            write(read().map { if (it.id == id) it.copy(publishedEventId = eventId, comment = comment) else it })
        }
    }

    /** Removes an unpublished, app-private highlight record. */
    suspend fun remove(id: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val values = read()
            val highlight = values.firstOrNull { it.id == id } ?: return@withLock false
            if (highlight.publishedEventId != null) return@withLock false
            write(values.filterNot { it.id == id })
            true
        }
    }

    private fun read(): List<ReaderHighlight> {
        if (!file.exists()) return emptyList()
        // A damaged file must surface an error, not be overwritten as an empty collection.
        return json.decodeFromString<PersistedHighlights>(file.readText(Charsets.UTF_8)).highlights
    }

    private fun write(highlights: List<ReaderHighlight>) {
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "Could not create highlight storage." }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(json.encodeToString(PersistedHighlights.serializer(), PersistedHighlights(highlights)), Charsets.UTF_8)
        try {
            try {
                Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    @Serializable
    private data class PersistedHighlights(val highlights: List<ReaderHighlight> = emptyList())
}
