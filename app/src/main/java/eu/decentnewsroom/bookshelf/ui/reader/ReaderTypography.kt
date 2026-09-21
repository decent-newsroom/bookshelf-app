package eu.decentnewsroom.bookshelf.ui.reader

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import eu.decentnewsroom.bookshelf.data.reader.ParagraphAlignment
import eu.decentnewsroom.bookshelf.data.reader.ReaderFont
import eu.decentnewsroom.bookshelf.data.reader.ReaderPreferences

/** Maps persisted reader preferences to the one text style shared by chapter content and previews. */
fun readerTextStyle(preferences: ReaderPreferences): TextStyle =
    TextStyle(
        fontFamily = preferences.fontFamily.composeFontFamily,
        fontSize = preferences.fontSizeSp.sp,
        lineHeight = (preferences.fontSizeSp * preferences.lineHeightMultiplier).sp,
        textAlign = preferences.paragraphAlignment.textAlign,
    )

private val ReaderFont.composeFontFamily: FontFamily
    get() = when (this) {
        ReaderFont.System -> FontFamily.Default
        ReaderFont.Serif -> FontFamily.Serif
        ReaderFont.SansSerif -> FontFamily.SansSerif
    }

private val ParagraphAlignment.textAlign: TextAlign
    get() = when (this) {
        ParagraphAlignment.Left -> TextAlign.Left
        ParagraphAlignment.Justified -> TextAlign.Justify
    }