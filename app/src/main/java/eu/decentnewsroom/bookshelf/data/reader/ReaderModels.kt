package eu.decentnewsroom.bookshelf.data.reader

import kotlinx.serialization.Serializable

@Serializable
enum class ReaderTheme {
    Paper,
    Sepia,
    Night,
}

@Serializable
data class ReaderPreferences(
    val fontSizeSp: Float = 18f,
    val lineHeightMultiplier: Float = 1.55f,
    val theme: ReaderTheme = ReaderTheme.Paper,
)

@Serializable
data class ReadingProgress(
    val bookCoordinate: String,
    val currentChapterIndex: Int,
    val chapterCount: Int,
    val updatedAtMillis: Long,
) {
    val currentChapterNumber: Int
        get() = if (chapterCount <= 0) 0 else (currentChapterIndex + 1).coerceIn(1, chapterCount)

    val progressFraction: Float
        get() = if (chapterCount <= 0) 0f else currentChapterNumber.toFloat() / chapterCount.toFloat()

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
