package eu.decentnewsroom.bookshelf.ui.theme

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import eu.decentnewsroom.bookshelf.data.reader.ReaderTheme

private val PaperColorScheme =
    lightColorScheme(
        primary = Color(0xFF24564B),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD2E8DF),
        onPrimaryContainer = Color(0xFF0B3028),
        secondary = Color(0xFF705B25),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFF4E2A7),
        onSecondaryContainer = Color(0xFF2B2100),
        tertiary = Color(0xFF5B5172),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFE7DEFF),
        onTertiaryContainer = Color(0xFF261B3C),
        background = Color(0xFFFAFAF7),
        onBackground = Color(0xFF1F2623),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1F2623),
        surfaceVariant = Color(0xFFE5ECE8),
        onSurfaceVariant = Color(0xFF66746E),
        outline = Color(0xFF6F7975),
        inverseSurface = Color(0xFF29312E),
        inverseOnSurface = Color(0xFFF0F2EF),
        inversePrimary = Color(0xFF86D6C1),
    )

private val SepiaColorScheme =
    lightColorScheme(
        primary = Color(0xFF7A5534),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFF3D7B8),
        onPrimaryContainer = Color(0xFF321A07),
        secondary = Color(0xFF715E4B),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFE8DCC4),
        onSecondaryContainer = Color(0xFF2B2118),
        tertiary = Color(0xFF655F3D),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFECE4B5),
        onTertiaryContainer = Color(0xFF211F08),
        background = Color(0xFFF4ECD8),
        onBackground = Color(0xFF2B2118),
        surface = Color(0xFFFFF7E6),
        onSurface = Color(0xFF2B2118),
        surfaceVariant = Color(0xFFE4D5B7),
        onSurfaceVariant = Color(0xFF715E4B),
        outline = Color(0xFF887664),
        inverseSurface = Color(0xFF392F25),
        inverseOnSurface = Color(0xFFFFEEDD),
        inversePrimary = Color(0xFFE7BD94),
    )

private val NightColorScheme =
    darkColorScheme(
        primary = Color(0xFF86D6C1),
        onPrimary = Color(0xFF00382D),
        primaryContainer = Color(0xFF24564B),
        onPrimaryContainer = Color(0xFFC0F0E3),
        secondary = Color(0xFFD8BD77),
        onSecondary = Color(0xFF3B2F00),
        secondaryContainer = Color(0xFF554619),
        onSecondaryContainer = Color(0xFFF4E2A7),
        tertiary = Color(0xFFCFC2E8),
        onTertiary = Color(0xFF352C49),
        tertiaryContainer = Color(0xFF4D4361),
        onTertiaryContainer = Color(0xFFEBDDFF),
        background = Color(0xFF111816),
        onBackground = Color(0xFFE8EFEA),
        surface = Color(0xFF19211E),
        onSurface = Color(0xFFE8EFEA),
        surfaceVariant = Color(0xFF27332F),
        onSurfaceVariant = Color(0xFFAAB7B0),
        outline = Color(0xFF83918A),
        inverseSurface = Color(0xFFE8EFEA),
        inverseOnSurface = Color(0xFF29312E),
        inversePrimary = Color(0xFF24564B),
    )

internal data class ReaderColors(
    val background: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val track: Color,
    val notice: Color,
    val controls: Color,
)

internal val ReaderTheme.readerColors: ReaderColors
    get() =
        when (this) {
            ReaderTheme.Paper -> ReaderColors(
                background = Color(0xFFFAFAF7),
                text = Color(0xFF1F2623),
                muted = Color(0xFF66746E),
                accent = Color(0xFF24564B),
                track = Color(0xFFE2E9E4),
                notice = Color(0xFFECEFE9),
                controls = Color(0xFFFFFFFF),
            )

            ReaderTheme.Sepia -> ReaderColors(
                background = Color(0xFFF4ECD8),
                text = Color(0xFF2B2118),
                muted = Color(0xFF715E4B),
                accent = Color(0xFF7A5534),
                track = Color(0xFFE4D5B7),
                notice = Color(0xFFE8DCC4),
                controls = Color(0xFFFFF7E6),
            )

            ReaderTheme.Night -> ReaderColors(
                background = Color(0xFF111816),
                text = Color(0xFFE8EFEA),
                muted = Color(0xFFAAB7B0),
                accent = Color(0xFF86D6C1),
                track = Color(0xFF27332F),
                notice = Color(0xFF1C2522),
                controls = Color(0xFF19211E),
            )
        }

private val ReaderTheme.colorScheme: ColorScheme
    get() =
        when (this) {
            ReaderTheme.Paper -> PaperColorScheme
            ReaderTheme.Sepia -> SepiaColorScheme
            ReaderTheme.Night -> NightColorScheme
        }

internal fun ComponentActivity.applyBookshelfEdgeToEdge(theme: ReaderTheme) {
    val colors = theme.colorScheme
    val statusBarColor = colors.background.toArgb()
    val navigationBarColor = colors.surface.toArgb()
    val statusBarStyle = theme.systemBarStyle(statusBarColor)
    val navigationBarStyle = theme.systemBarStyle(navigationBarColor)
    enableEdgeToEdge(
        statusBarStyle = statusBarStyle,
        navigationBarStyle = navigationBarStyle,
    )
}

private fun ReaderTheme.systemBarStyle(scrim: Int): SystemBarStyle =
    if (this == ReaderTheme.Night) {
        SystemBarStyle.dark(scrim)
    } else {
        SystemBarStyle.light(scrim, scrim)
    }

@Composable
fun BookshelfTheme(
    theme: ReaderTheme,
    content: @Composable () -> Unit,
) {
    val activity = LocalContext.current as? ComponentActivity
    DisposableEffect(activity, theme) {
        activity?.applyBookshelfEdgeToEdge(theme)
        onDispose { }
    }
    MaterialTheme(
        colorScheme = theme.colorScheme,
        content = content,
    )
}
