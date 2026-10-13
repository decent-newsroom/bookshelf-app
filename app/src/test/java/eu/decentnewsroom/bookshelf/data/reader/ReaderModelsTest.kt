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
        assertEquals(false, progress.hasStarted)
        assertEquals(false, progress.reachedEnd)
        assertEquals(0, progress.fullChapterCount)
        assertEquals(false, progress.completeContent)
        assertEquals(null, progress.chapterCoordinate)
        assertEquals(null, progress.contentFingerprint)
        assertEquals(null, progress.publicationFingerprint)
        assertEquals(0L, progress.lastReadingActivityMillis)
        assertEquals(0L, progress.readingCycleStartedAtMillis)
        assertEquals(null, progress.readerEntryKey)
        assertEquals(0, progress.readerEntryScrollOffsetPx)
    }

    @Test
    fun divisionObservationPersistsWithoutAdvancingLeafAndIsIdempotent() {
        fun observed(existing: ReadingProgress?, time: Long) = nextObservedReadingProgress(
            existing, "book", 0, 0, 0, 0, false, null, "publication", "content",
            false, true, time, readerEntryKey = "act-two", readerEntryScrollOffsetPx = 48,
        )
        val first = observed(null, 10)
        assertEquals("act-two", first.readerEntryKey)
        assertEquals(48, first.readerEntryScrollOffsetPx)
        assertEquals(0, first.currentChapterIndex)
        assertEquals(false, first.reachedEnd)
        assertEquals(first, observed(first, 20))
    }

    @Test
    fun initialProgressStartsAtZero() {
        assertEquals(0f, ReadingProgress.initial("book", 5).progressFraction)
        assertEquals(0.4f, ReadingProgress("book", 2, 5, 9).progressFraction)
        assertEquals(0f, ReadingProgress("book", -1, 5, 9).progressFraction)
        assertEquals(1f, ReadingProgress("book", 99, 5, 9).progressFraction)
    }
}
