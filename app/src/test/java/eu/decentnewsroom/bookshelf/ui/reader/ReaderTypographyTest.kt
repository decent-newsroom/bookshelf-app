package eu.decentnewsroom.bookshelf.ui.reader

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import eu.decentnewsroom.bookshelf.data.reader.ParagraphAlignment
import eu.decentnewsroom.bookshelf.data.reader.ReaderFont
import eu.decentnewsroom.bookshelf.data.reader.ReaderPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTypographyTest {
    @Test
    fun mapsFontSizeLineHeightAndAlignment() {
        val style = readerTextStyle(
            ReaderPreferences(
                fontSizeSp = 20f,
                lineHeightMultiplier = 1.6f,
                fontFamily = ReaderFont.SansSerif,
                paragraphAlignment = ParagraphAlignment.Justified,
            ),
        )
        assertEquals(FontFamily.SansSerif, style.fontFamily)
        assertEquals(20f, style.fontSize.value)
        assertEquals(32f, style.lineHeight.value)
        assertEquals(TextAlign.Justify, style.textAlign)
    }
}