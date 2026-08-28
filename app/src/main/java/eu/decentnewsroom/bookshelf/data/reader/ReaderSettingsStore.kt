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

    fun recordProgress(book: BookDetail, chapterIndex: Int) {
        val chapterCount = book.chapters.size
        if (chapterCount <= 0) {
            return
        }

        val normalizedChapterIndex = normalizedReaderChapterIndex(chapterIndex, chapterCount)
        val coordinate = book.summary.coordinate
        val existing = _progress.value[coordinate]
        if (existing?.currentChapterIndex == normalizedChapterIndex && existing.chapterCount == chapterCount) {
            return
        }

        val next = ReadingProgress(
            bookCoordinate = coordinate,
            currentChapterIndex = normalizedChapterIndex,
            chapterCount = chapterCount,
            updatedAtMillis = System.currentTimeMillis(),
        )
        val progress = _progress.value.toMutableMap()
        progress[coordinate] = next
        _progress.value = progress
        saveProgress(progress)
    }

    private fun updateReaderPreferences(update: (ReaderPreferences) -> ReaderPreferences) {
        val next = update(_readerPreferences.value)
        _readerPreferences.value = next
        sharedPreferences.edit().putString(KEY_READER_PREFERENCES, json.encodeToString(next)).apply()
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
        sharedPreferences.edit().putString(KEY_READING_PROGRESS, json.encodeToString(progress)).apply()
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
