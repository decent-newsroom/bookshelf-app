package eu.decentnewsroom.bookshelf.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BookshelfColors =
    lightColorScheme(
        primary = Color(0xFF24564B),
        onPrimary = Color.White,
        secondary = Color(0xFF705B25),
        onSecondary = Color.White,
        tertiary = Color(0xFF5B5172),
        background = Color(0xFFFBFCFA),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFE5ECE8),
        outline = Color(0xFF6F7975),
    )

@Composable
fun BookshelfTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BookshelfColors,
        content = content,
    )
}
