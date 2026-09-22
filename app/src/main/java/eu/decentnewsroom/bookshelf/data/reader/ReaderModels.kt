package eu.decentnewsroom.bookshelf.data.reader

import kotlinx.serialization.Serializable

@Serializable
enum class ReaderTheme {
    Paper,
    Sepia,
    Night,
}

@Serializable
enum class ReaderFont {
    System,
    Serif,
    SansSerif,
}

@Serializable
enum class ParagraphAlignment {
    Left,
    Justified,
}

@Serializable
data class ReaderPreferences(
    val fontSizeSp: Float = 18f,
    val lineHeightMultiplier: Float = 1.55f,
    val theme: ReaderTheme = ReaderTheme.Sepia,
    // Keep the current reader appearance when these fields are absent from saved JSON.
    val fontFamily: ReaderFont = ReaderFont.Serif,
    val paragraphAlignment: ParagraphAlignment = ParagraphAlignment.Left,
)

@Serializable
data class ReadingProgress(
    val bookCoordinate: String,
    val currentChapterIndex: Int,
    val chapterCount: Int,
    val updatedAtMillis: Long,
    /** Pixel offset within the current chapter item; defaults to the chapter top for legacy data. */
    val chapterScrollOffsetPx: Int = 0,
) {
    val currentChapterNumber: Int
        get() = if (chapterCount <= 0) 0 else (currentChapterIndex + 1).coerceIn(1, chapterCount)

    val progressFraction: Float
        get() = if (chapterCount <= 0) 0f else currentChapterIndex.coerceIn(0, chapterCount).toFloat() / chapterCount.toFloat()

    companion object {
        fun initial(bookCoordinate: String, chapterCount: Int): ReadingProgress =
            ReadingProgress(
                bookCoordinate = bookCoordinate,
                currentChapterIndex = 0,
                chapterCount = chapterCount,
                updatedAtMillis = 0,
            )
    }
}
