package eu.decentnewsroom.bookshelf.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import eu.decentnewsroom.bookshelf.data.highlights.ReaderHighlight
import eu.decentnewsroom.bookshelf.data.reader.ReaderPreferences
import eu.decentnewsroom.bookshelf.domain.BookChapter
import eu.decentnewsroom.bookshelf.ui.HighlightableChapterText
import eu.decentnewsroom.bookshelf.ui.theme.ReaderColors
import eu.decentnewsroom.bookshelf.ui.reader.readerTextStyle

@Composable
internal fun ChapterSection(
    chapter: BookChapter,
    preferences: ReaderPreferences,
    colors: ReaderColors,
    onLinkClick: (String) -> Unit,
    highlights: List<ReaderHighlight>,
    onSaveHighlight: (BookChapter, String, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = chapter.title,
            style = MaterialTheme.typography.headlineSmall,
            color = colors.text,
            fontFamily = readerTextStyle(preferences).fontFamily,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(colors.accent),
        )
        chapter.summary?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        }
        val renderedHtml = chapter.renderedHtml
        if (renderedHtml != null) {
            HtmlChapterText(chapter, renderedHtml, preferences, colors, highlights, onSaveHighlight, onLinkClick)
        } else {
            HighlightableChapterText(
                chapter = chapter,
                text = AnnotatedString(chapter.content ?: "This chapter is not available on this device."),
                highlights = highlights,
                preferences = preferences,
                colors = colors,
                onSaveHighlight = onSaveHighlight,
            )
        }
    }
}

@Composable
private fun HtmlChapterText(
    chapter: BookChapter,
    html: String,
    preferences: ReaderPreferences,
    colors: ReaderColors,
    highlights: List<ReaderHighlight>,
    onSaveHighlight: (BookChapter, String, Int, Int) -> Unit,
    onLinkClick: (String) -> Unit,
) {
    val annotatedText = remember(html, colors.accent, onLinkClick) {
        AnnotatedString.fromHtml(
            htmlString = html.withReaderParagraphSpacing(),
            linkStyles = TextLinkStyles(style = SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline)),
            linkInteractionListener = { link ->
                val url = (link as? androidx.compose.ui.text.LinkAnnotation.Url)?.url
                if (url != null) onLinkClick(url)
            },
        )
    }
    HighlightableChapterText(chapter, annotatedText, highlights, preferences, colors, onSaveHighlight)
}

internal fun String.withReaderParagraphSpacing(): String =
    replace(ParagraphClosingTagRegex) { match -> "${match.value}<br>" }

private val ParagraphClosingTagRegex = Regex("</p\\s*>", RegexOption.IGNORE_CASE)
