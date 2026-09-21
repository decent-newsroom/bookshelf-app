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
}