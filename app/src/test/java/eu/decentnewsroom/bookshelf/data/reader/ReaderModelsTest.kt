package eu.decentnewsroom.bookshelf.data.reader

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderModelsTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun defaultsPreserveExistingReaderAppearanceAndAddReadableOptions() {
        val preferences = ReaderPreferences()
        assertEquals(ReaderFont.Serif, preferences.fontFamily)
        assertEquals(ParagraphAlignment.Left, preferences.paragraphAlignment)
        assertEquals(ReaderTheme.Sepia, preferences.theme)
    }

    @Test
    fun oldSavedPreferencesMigrateWithNewDefaults() {
        val preferences = json.decodeFromString<ReaderPreferences>(
            """{"fontSizeSp":20.0,"lineHeightMultiplier":1.7,"theme":"Paper"}""",
        )
        assertEquals(20f, preferences.fontSizeSp)
        assertEquals(1.7f, preferences.lineHeightMultiplier)
        assertEquals(ReaderTheme.Paper, preferences.theme)
        assertEquals(ReaderFont.Serif, preferences.fontFamily)
        assertEquals(ParagraphAlignment.Left, preferences.paragraphAlignment)
    }

    @Test
    fun legacyProgressDefaultsToChapterTop() {
        val progress = json.decodeFromString<ReadingProgress>(
            """{"bookCoordinate":"book","currentChapterIndex":2,"chapterCount":5,"updatedAtMillis":9}""",
        )
        assertEquals(0, progress.chapterScrollOffsetPx)
    }

    @Test
    fun initialProgressStartsAtZero() {
        assertEquals(0f, ReadingProgress.initial("book", 5).progressFraction)
        assertEquals(0.4f, ReadingProgress("book", 2, 5, 9).progressFraction)
        assertEquals(0f, ReadingProgress("book", -1, 5, 9).progressFraction)
        assertEquals(1f, ReadingProgress("book", 99, 5, 9).progressFraction)
    }
}