package eu.decentnewsroom.bookshelf.ui.books

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.decentnewsroom.bookshelf.data.reader.BookReadingPresentation
import eu.decentnewsroom.bookshelf.data.reader.BookReadingProgressSource
import eu.decentnewsroom.bookshelf.data.reader.ReadingProgress
import eu.decentnewsroom.bookshelf.data.reader.resolveBookReadingPresentation
import eu.decentnewsroom.bookshelf.domain.BookSummary

/** Resolved once from bookshelf state and consumed by thumbnail surfaces. */
val LocalBookReadingPresentations = compositionLocalOf<Map<String, BookReadingPresentation>> { emptyMap() }

internal fun resolveUiReadingPresentation(
    progress: ReadingProgress,
    book: BookSummary? = null,
    sharedPresentation: BookReadingPresentation? = null,
): BookReadingPresentation = sharedPresentation
    ?: resolveBookReadingPresentation(progress = progress, tracked = null, finished = null, book = book)

internal fun chapterReadingProgressLabel(
    progress: ReadingProgress,
    presentation: BookReadingPresentation,
): String {
    if (presentation.source == BookReadingProgressSource.Tracked) return presentation.progressLabel

    val chapter = "Chapter ${progress.currentChapterNumber} of ${progress.chapterCount}"
    val status = if (presentation.isComplete && presentation.percentage == 100 && !presentation.progressLabel.contains("100%")) {
        "${presentation.progressLabel} · 100% read"
    } else {
        presentation.progressLabel
    }
    return "$chapter | $status"
}

/** Informational progress badge shared by covers and standalone book artwork. */
@Composable
fun BookProgressIndicator(
    presentation: BookReadingPresentation?,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
    backgroundColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
    progressColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
    trackColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
    checkColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
    borderColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
) {
    if (presentation == null || !presentation.hasStarted) return

    Canvas(
        modifier = modifier.size(size).semantics {
            contentDescription = presentation.accessibleLabel
            presentation.fraction?.let { fraction ->
                progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
            }
        },
    ) {
        val strokeWidth = 4.dp.toPx()
        val borderWidth = 1.5.dp.toPx()
        val radius = this.size.minDimension / 2f
        // Keep an opaque gap between the outline and progress, clear of the cover art.
        val contentRadius = radius - borderWidth - 1.dp.toPx()
        val inset = radius - contentRadius + strokeWidth / 2f
        val diameter = this.size.minDimension - inset * 2f
        val topLeft = Offset(inset, inset)
        val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)

        drawCircle(backgroundColor.copy(alpha = 1f), radius = radius)
        drawCircle(borderColor, radius = radius - borderWidth / 2f, style = Stroke(borderWidth))
        if (presentation.isComplete || (presentation.percentage ?: 0) >= 100) {
            drawCircle(progressColor, radius = contentRadius)
            if (presentation.isMarkedFinished) {
                val p = this.size.minDimension
                drawLine(checkColor, Offset(p * 0.27f, p * 0.52f), Offset(p * 0.44f, p * 0.68f), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                drawLine(checkColor, Offset(p * 0.44f, p * 0.68f), Offset(p * 0.75f, p * 0.34f), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
        } else {
            drawArc(trackColor, 0f, 360f, false, topLeft, arcSize, style = Stroke(strokeWidth, cap = StrokeCap.Round))
            presentation.fraction?.takeIf { it > 0f }?.let { fraction ->
                drawArc(progressColor, -90f, fraction.coerceIn(0f, 0.99f) * 360f, false, topLeft, arcSize, style = Stroke(strokeWidth, cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
internal fun BookProgressOverlay(
    presentation: BookReadingPresentation?,
    modifier: Modifier = Modifier,
    backgroundColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
    progressColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
    checkColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
) {
    if (presentation?.hasStarted != true) return
    Box(modifier.padding(4.dp), contentAlignment = Alignment.Center) {
        BookProgressIndicator(
            presentation = presentation,
            backgroundColor = backgroundColor,
            progressColor = progressColor,
            checkColor = checkColor,
        )
    }
}
