package eu.decentnewsroom.bookshelf.data.reader

import android.content.Context
import eu.decentnewsroom.bookshelf.domain.BookDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import androidx.core.content.edit

class ReaderSettingsStore(context: Context) {
    private val sharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    private val _readerPreferences = MutableStateFlow(loadReaderPreferences())
    val readerPreferences: StateFlow<ReaderPreferences> = _readerPreferences.asStateFlow()

    private val _progress = MutableStateFlow(loadProgress())
    val progress: StateFlow<Map<String, ReadingProgress>> = _progress.asStateFlow()
    private var pendingProgressPersistence: Map<String, ReadingProgress>? = null
    private var cachedBookMetadata: BookReadingMetadata? = null

    fun setFontSizeSp(fontSizeSp: Float) {
        updateReaderPreferences { preferences ->
            preferences.copy(fontSizeSp = fontSizeSp.roundToTenth().coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP))
        }
    }

    fun setLineHeightMultiplier(lineHeightMultiplier: Float) {
        updateReaderPreferences { preferences ->
            preferences.copy(
                lineHeightMultiplier = lineHeightMultiplier.roundToTenth().coerceIn(
                    MIN_LINE_HEIGHT_MULTIPLIER,
                    MAX_LINE_HEIGHT_MULTIPLIER,
                ),
            )
        }
    }

    fun setTheme(theme: ReaderTheme) {
        updateReaderPreferences { preferences -> preferences.copy(theme = theme) }
    }

    fun setFontFamily(fontFamily: ReaderFont) {
        updateReaderPreferences { preferences -> preferences.copy(fontFamily = fontFamily) }
    }

    fun setParagraphAlignment(alignment: ParagraphAlignment) {
        updateReaderPreferences { preferences -> preferences.copy(paragraphAlignment = alignment) }
    }

    fun recordProgress(
        book: BookDetail,
        chapterIndex: Int,
        chapterScrollOffsetPx: Int = 0,
        reachedEnd: Boolean = false,
        readingActivity: Boolean = false,
        persist: Boolean = true,
    ) {
        val loadedCount = book.chapters.size
        if (loadedCount <= 0) return

        val normalizedIndex = normalizedReaderChapterIndex(chapterIndex, loadedCount)
        val normalizedOffset = normalizedReaderChapterScrollOffsetPx(chapterScrollOffsetPx)
        val coordinate = book.summary.coordinate
        val existing = _progress.value[coordinate]
        val metadata = metadataFor(book)
        val next = nextObservedReadingProgress(
            existing = existing,
            bookCoordinate = coordinate,
            chapterIndex = normalizedIndex,
            chapterCount = loadedCount,
            chapterScrollOffsetPx = normalizedOffset,
            fullChapterCount = metadata.fullChapterCount,
            completeContent = metadata.completeContent,
            chapterCoordinate = book.summary.chapterRefs.getOrNull(normalizedIndex)?.coordinate,
            publicationFingerprint = metadata.publicationFingerprint,
            contentFingerprint = metadata.contentFingerprint,
            reachedEnd = reachedEnd,
            readingActivity = readingActivity,
            nowMillis = System.currentTimeMillis(),
        )
        if (existing == next) {
            if (persist) flushProgress()
            return
        }
        val criticalChange = existing == null ||
            existing.currentChapterIndex != next.currentChapterIndex ||
            existing.chapterCoordinate != next.chapterCoordinate ||
            existing.hasStarted != next.hasStarted || existing.reachedEnd != next.reachedEnd ||
            existing.publicationFingerprint != next.publicationFingerprint ||
            existing.contentFingerprint != next.contentFingerprint ||
            existing.readingCycleStartedAtMillis != next.readingCycleStartedAtMillis
        updateProgress(coordinate, next, persist || criticalChange)
    }


    /** Records a saved book as the reader's most recently opened book. */
    fun recordBookOpened(book: BookDetail) {
        val chapterCount = book.chapters.size
        if (chapterCount <= 0) return

        val coordinate = book.summary.coordinate
        val existing = _progress.value[coordinate]
        val metadata = metadataFor(book)
        val index = resolveReaderChapterIndex(existing, book, chapterCount, metadata.publicationFingerprint)
        val offset = normalizedReaderScrollOffsetForBook(existing, book, index)
        val endpointStillValid = existing?.reachedEnd == true &&
            existing.publicationFingerprint == metadata.publicationFingerprint &&
            existing.contentFingerprint == metadata.contentFingerprint && metadata.completeContent
        val next = ReadingProgress(
            bookCoordinate = coordinate,
            currentChapterIndex = index,
            chapterCount = chapterCount,
            updatedAtMillis = System.currentTimeMillis(),
            chapterScrollOffsetPx = offset,
            hasStarted = existing?.hasStarted == true || index > 0 || (existing?.chapterScrollOffsetPx ?: 0) > 0,
            reachedEnd = endpointStillValid,
            fullChapterCount = metadata.fullChapterCount,
            completeContent = metadata.completeContent,
            chapterCoordinate = book.summary.chapterRefs.getOrNull(index)?.coordinate,
            contentFingerprint = metadata.contentFingerprint,
            publicationFingerprint = metadata.publicationFingerprint,
            lastReadingActivityMillis = existing?.lastReadingActivityMillis ?: 0,
            readingCycleStartedAtMillis = existing?.readingCycleStartedAtMillis ?: 0,
        )
        updateProgress(coordinate, next, persist = true)
    }

    /** Normalizes a saved row to the current edition without treating an open as reading activity. */
    fun normalizeBookProgress(book: BookDetail): ReadingProgress? {
        val coordinate = book.summary.coordinate
        val existing = _progress.value[coordinate] ?: return null
        val loadedCount = book.chapters.size
        val metadata = metadataFor(book)
        if (loadedCount <= 0) {
            val normalized = existing.copy(
                reachedEnd = false,
                fullChapterCount = metadata.fullChapterCount,
                completeContent = false,
                contentFingerprint = metadata.contentFingerprint,
                publicationFingerprint = metadata.publicationFingerprint,
            )
            if (normalized != existing) updateProgress(coordinate, normalized, persist = true)
            return normalized
        }
        val index = resolveReaderChapterIndex(existing, book, loadedCount, metadata.publicationFingerprint)
        val offset = normalizedReaderScrollOffsetForBook(existing, book, index)
        val samePublication = existing.publicationFingerprint == metadata.publicationFingerprint &&
            existing.contentFingerprint == metadata.contentFingerprint
        val normalized = existing.copy(
            currentChapterIndex = index,
            chapterCount = loadedCount,
            chapterScrollOffsetPx = offset,
            hasStarted = existing.hasStarted || index > 0 || existing.chapterScrollOffsetPx > 0,
            reachedEnd = existing.reachedEnd && samePublication && metadata.completeContent,
            fullChapterCount = metadata.fullChapterCount,
            completeContent = metadata.completeContent,
            chapterCoordinate = book.summary.chapterRefs.getOrNull(index)?.coordinate,
            contentFingerprint = metadata.contentFingerprint,
            publicationFingerprint = metadata.publicationFingerprint,
        )
        if (normalized != existing) updateProgress(coordinate, normalized, persist = true)
        return normalized
    }

    /** Marks an explicit Track/Reset action as a new local reading cycle without moving the bookmark. */
    fun markReadingCycle(book: BookDetail, startedAtMillis: Long = System.currentTimeMillis()) {
        val coordinate = book.summary.coordinate
        val existing = _progress.value[coordinate]
        val loadedCount = book.chapters.size
        val metadata = metadataFor(book)
        val index = if (loadedCount > 0) {
            resolveReaderChapterIndex(existing, book, loadedCount, metadata.publicationFingerprint)
        } else existing?.currentChapterIndex ?: 0
        val offset = normalizedReaderScrollOffsetForBook(existing, book, index)
        val contentFingerprint = if (loadedCount > 0) metadata.contentFingerprint else existing?.contentFingerprint
        val completeContent = if (loadedCount > 0) metadata.completeContent else existing?.completeContent == true
        val verifiedEndpoint = existing?.reachedEnd == true &&
            existing.publicationFingerprint == metadata.publicationFingerprint &&
            existing.contentFingerprint == contentFingerprint && completeContent
        val next = ReadingProgress(
            bookCoordinate = coordinate,
            currentChapterIndex = index,
            chapterCount = if (loadedCount > 0) loadedCount else existing?.chapterCount ?: 0,
            updatedAtMillis = startedAtMillis,
            chapterScrollOffsetPx = offset,
            hasStarted = true,
            reachedEnd = verifiedEndpoint,
            fullChapterCount = metadata.fullChapterCount.takeIf { it > 0 } ?: existing?.fullChapterCount ?: 0,
            completeContent = completeContent,
            chapterCoordinate = book.summary.chapterRefs.getOrNull(index)?.coordinate ?: existing?.chapterCoordinate,
            contentFingerprint = contentFingerprint,
            publicationFingerprint = metadata.publicationFingerprint,
            lastReadingActivityMillis = existing?.lastReadingActivityMillis ?: 0,
            readingCycleStartedAtMillis = startedAtMillis.coerceAtLeast(1),
        ).let { readingCycleProgress(it, startedAtMillis, verifiedEndpoint) }
        updateProgress(coordinate, next, persist = true)
    }

    /** Clears only local reread evidence after an explicit finish has been saved successfully. */
    fun completeReadingCycle(book: BookDetail) {
        val coordinate = book.summary.coordinate
        val existing = _progress.value[coordinate] ?: return
        val completed = readingCycleCompletedProgress(existing)
        if (completed != existing) updateProgress(coordinate, completed, persist = true)
    }

    /** Flushes the latest coalesced resume/presentation snapshot on reader exit. */
    fun flushProgress() {
        val pending = pendingProgressPersistence ?: return
        saveProgress(pending)
        pendingProgressPersistence = null
    }

    private fun updateProgress(coordinate: String, next: ReadingProgress, persist: Boolean) {
        if (_progress.value[coordinate] == next) return
        val progress = _progress.value.toMutableMap()
        progress[coordinate] = next
        _progress.value = progress
        if (persist) {
            saveProgress(progress)
            pendingProgressPersistence = null
        } else {
            pendingProgressPersistence = progress
        }
    }

    private fun isCompleteContent(book: BookDetail): Boolean {
        val summary = book.summary
        val total = summary.chapterCount
        return summary.sectionStreamKnown && total > 0 &&
            summary.chapterRefs.size == total && book.chapters.size == total &&
            book.chapters.all { it.available } && !book.truncated
    }

    private fun metadataFor(book: BookDetail): BookReadingMetadata {
        cachedBookMetadata?.takeIf { it.book.get() === book }?.let { return it }
        val metadata = BookReadingMetadata(
            book = java.lang.ref.WeakReference(book),
            publicationFingerprint = readingPublicationFingerprint(book.summary),
            contentFingerprint = readingContentFingerprint(book),
            completeContent = isCompleteContent(book),
            fullChapterCount = knownFullChapterCount(book),
        )
        cachedBookMetadata = metadata
        return metadata
    }

    private data class BookReadingMetadata(
        val book: java.lang.ref.WeakReference<BookDetail>,
        val publicationFingerprint: String,
        val contentFingerprint: String,
        val completeContent: Boolean,
        val fullChapterCount: Int,
    )

    private fun knownFullChapterCount(book: BookDetail): Int =
        book.summary.chapterCount.takeIf {
            it > 0 && book.summary.sectionStreamKnown && book.summary.chapterRefs.size == it
        } ?: 0

    private fun updateReaderPreferences(update: (ReaderPreferences) -> ReaderPreferences) {
        val next = update(_readerPreferences.value)
        _readerPreferences.value = next
        sharedPreferences.edit { putString(KEY_READER_PREFERENCES, json.encodeToString(next)) }
    }

    private fun loadReaderPreferences(): ReaderPreferences {
        val raw = sharedPreferences.getString(KEY_READER_PREFERENCES, null) ?: return ReaderPreferences()
        return runCatching { json.decodeFromString<ReaderPreferences>(raw) }.getOrDefault(ReaderPreferences())
    }

    private fun loadProgress(): Map<String, ReadingProgress> {
        val raw = sharedPreferences.getString(KEY_READING_PROGRESS, null) ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, ReadingProgress>>(raw) }.getOrDefault(emptyMap())
    }

    private fun saveProgress(progress: Map<String, ReadingProgress>) {
        sharedPreferences.edit { putString(KEY_READING_PROGRESS, json.encodeToString(progress)) }
    }

    private fun Float.roundToTenth(): Float = (this * 10f).roundToInt() / 10f

    private companion object {
        const val PREFERENCES_NAME = "bookshelf_reader"
        const val KEY_READER_PREFERENCES = "reader_preferences"
        const val KEY_READING_PROGRESS = "reading_progress"
        const val MIN_FONT_SIZE_SP = 14f
        const val MAX_FONT_SIZE_SP = 28f
        const val MIN_LINE_HEIGHT_MULTIPLIER = 1.2f
        const val MAX_LINE_HEIGHT_MULTIPLIER = 2.0f
    }
}

internal fun normalizedReaderChapterIndex(chapterIndex: Int, chapterCount: Int): Int =
    if (chapterCount <= 0) 0 else chapterIndex.coerceIn(0, chapterCount - 1)

internal fun normalizedReaderChapterScrollOffsetPx(offsetPx: Int): Int = offsetPx.coerceAtLeast(0)

internal fun resolveReaderChapterIndex(
    progress: ReadingProgress?,
    book: BookDetail,
    loadedChapterCount: Int,
    currentFingerprint: String,
): Int {
    if (loadedChapterCount <= 0) return 0
    val savedCoordinate = progress?.chapterCoordinate
    if (savedCoordinate != null) {
        val coordinateIndex = book.summary.chapterRefs.indexOfFirst { it.coordinate == savedCoordinate }
        if (coordinateIndex >= 0) return coordinateIndex.coerceIn(0, loadedChapterCount - 1)
        if (progress.publicationFingerprint != null && progress.publicationFingerprint != currentFingerprint) return 0
    } else if (progress?.publicationFingerprint != null && progress.publicationFingerprint != currentFingerprint) {
        return 0
    }
    return normalizedReaderChapterIndex(progress?.currentChapterIndex ?: 0, loadedChapterCount)
}

internal fun normalizedReaderScrollOffsetForBook(
    progress: ReadingProgress?,
    book: BookDetail,
    chapterIndex: Int,
): Int {
    val savedOffset = normalizedReaderChapterScrollOffsetPx(progress?.chapterScrollOffsetPx ?: 0)
    if (progress == null) return 0
    val savedCoordinate = progress.chapterCoordinate
    if (savedCoordinate != null) {
        val chapter = book.chapters.firstOrNull { it.reference.coordinate == savedCoordinate }
        return if (chapter?.available == true) savedOffset else 0
    }

    val compatibleEdition = progress.publicationFingerprint == null ||
        progress.publicationFingerprint == readingPublicationFingerprint(book.summary)
    val currentChapter = book.chapters.getOrNull(chapterIndex)
    return if (compatibleEdition && currentChapter?.available == true) savedOffset else 0
}

/** Pure observation transition so duplicate flushes cannot invent activity or rereading. */
internal fun nextObservedReadingProgress(
    existing: ReadingProgress?,
    bookCoordinate: String,
    chapterIndex: Int,
    chapterCount: Int,
    chapterScrollOffsetPx: Int,
    fullChapterCount: Int,
    completeContent: Boolean,
    chapterCoordinate: String?,
    publicationFingerprint: String,
    contentFingerprint: String,
    reachedEnd: Boolean,
    readingActivity: Boolean,
    nowMillis: Long,
): ReadingProgress {
    val count = chapterCount.coerceAtLeast(0)
    val index = normalizedReaderChapterIndex(chapterIndex, count)
    val offset = normalizedReaderChapterScrollOffsetPx(chapterScrollOffsetPx)
    val validEnd = reachedEnd && completeContent && fullChapterCount > 0
    val locationChanged = existing == null || existing.currentChapterIndex != index ||
        existing.chapterScrollOffsetPx != offset
    val stateChanged = existing == null || locationChanged || existing.reachedEnd != validEnd ||
        existing.hasStarted != (existing.hasStarted || index > 0 || offset > 0 || validEnd) ||
        existing.chapterCoordinate != chapterCoordinate || existing.chapterCount != count ||
        existing.fullChapterCount != fullChapterCount || existing.completeContent != completeContent ||
        existing.publicationFingerprint != publicationFingerprint || existing.contentFingerprint != contentFingerprint
    if (!stateChanged) return existing

    val movedFromSavedEnd = existing?.reachedEnd == true && locationChanged
    val recordActivity = readingActivity && (locationChanged || existing.reachedEnd != validEnd)
    return ReadingProgress(
        bookCoordinate = bookCoordinate,
        currentChapterIndex = index,
        chapterCount = count,
        updatedAtMillis = nowMillis,
        chapterScrollOffsetPx = offset,
        hasStarted = (existing?.hasStarted == true) || index > 0 || offset > 0 || validEnd || recordActivity,
        reachedEnd = validEnd,
        fullChapterCount = fullChapterCount.coerceAtLeast(0),
        completeContent = completeContent,
        chapterCoordinate = chapterCoordinate,
        contentFingerprint = contentFingerprint,
        publicationFingerprint = publicationFingerprint,
        lastReadingActivityMillis = if (recordActivity) nowMillis else existing?.lastReadingActivityMillis ?: 0,
        readingCycleStartedAtMillis = when {
            recordActivity && (movedFromSavedEnd || (existing?.readingCycleStartedAtMillis ?: 0) == 0L) -> nowMillis.coerceAtLeast(1)
            else -> existing?.readingCycleStartedAtMillis ?: 0
        },
    )
}

internal fun readingCycleProgress(
    progress: ReadingProgress,
    startedAtMillis: Long,
    verifiedEndpoint: Boolean,
): ReadingProgress = progress.copy(
    hasStarted = true,
    reachedEnd = verifiedEndpoint,
    readingCycleStartedAtMillis = startedAtMillis.coerceAtLeast(1),
)

internal fun readingCycleCompletedProgress(progress: ReadingProgress): ReadingProgress = progress.copy(
    lastReadingActivityMillis = 0,
    readingCycleStartedAtMillis = 0,
)

internal fun readingContentFingerprint(book: BookDetail): String {
    val identity = buildString {
        append(readingPublicationFingerprint(book.summary)).append('\u0002')
        book.chapters.sortedBy { it.position }.forEach { chapter ->
            append(chapter.position).append('\u0000')
            append(chapter.reference.coordinate).append('\u0000')
            append(chapter.reference.eventId.orEmpty()).append('\u0000')
            append(chapter.id.orEmpty()).append('\u0000')
            append(chapter.available).append('\u0001')
        }
    }
    return java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
