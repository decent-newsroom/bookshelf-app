@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.decentnewsroom.bookshelf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import eu.decentnewsroom.bookshelf.data.highlights.HighlightAnchors
import eu.decentnewsroom.bookshelf.data.highlights.ReaderHighlight
import eu.decentnewsroom.bookshelf.data.reader.ReaderPreferences
import eu.decentnewsroom.bookshelf.domain.BookChapter
import eu.decentnewsroom.bookshelf.ui.reader.readerTextStyle
import eu.decentnewsroom.bookshelf.ui.theme.ReaderColors

/**
 * SelectionState publicly exposes selected text, not the drag offsets. Only a unique text match
 * is saved, ensuring a highlight always has an exact durable UTF-16 anchor.
 */
internal fun uniquelySelectedRange(displayedText: String, selectedText: String): IntRange? {
    if (selectedText.isEmpty()) return null
    val start = displayedText.indexOf(selectedText)
    if (start < 0 || displayedText.indexOf(selectedText, start + 1) >= 0) return null
    return start until (start + selectedText.length)
}

@Composable
internal fun HighlightableChapterText(
    chapter: BookChapter,
    text: AnnotatedString,
    highlights: List<ReaderHighlight>,
    preferences: ReaderPreferences,
    colors: ReaderColors,
    onSaveHighlight: (chapter: BookChapter, displayedText: String, start: Int, end: Int) -> Unit,
) {
    val selectionState = rememberSelectionState()
    val displayedText = text.text
    val ranges = remember(highlights, displayedText, chapter.reference.coordinate) {
        highlights
            .asSequence()
            .filter { it.chapterCoordinate == chapter.reference.coordinate }
            .mapNotNull { HighlightAnchors.resolve(it, displayedText) }
            .toList()
    }
    val annotatedText = remember(text, ranges, colors.accent) {
        text.withHighlightRanges(ranges, colors.accent.copy(alpha = 0.24f))
    }
    val selectedText = selectionState.selectedTexts.singleOrNull()?.text
    val selectedRange = selectedText?.let { uniquelySelectedRange(displayedText, it) }
    val textLayoutResult = remember { mutableStateOf<TextLayoutResult?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            SelectionContainer(state = selectionState) {
                Text(
                    text = annotatedText,
                    modifier = Modifier.fillMaxWidth(),
                    onTextLayout = { textLayoutResult.value = it },
                    style = MaterialTheme.typography.bodyLarge
                        .merge(readerTextStyle(preferences))
                        .copy(color = colors.text),
                )
            }
            if (selectedRange != null) {
                textLayoutResult.value?.let { layout ->
                    val bounds = layout.getBoundingBox(selectedRange.last.coerceAtMost(displayedText.lastIndex))
                    Popup(
                        alignment = Alignment.TopStart,
                        offset = IntOffset(bounds.left.toInt(), bounds.bottom.toInt()),
                    ) {
                        Surface(shape = MaterialTheme.shapes.small, shadowElevation = 6.dp) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Button(onClick = {
                                    onSaveHighlight(chapter, displayedText, selectedRange.first, selectedRange.last + 1)
                                    selectionState.clear()
                                }) { Text("Create highlight") }
                                TextButton(onClick = selectionState::clear) { Text("Cancel") }
                            }
                        }
                    }
                }
            }
        }
        if (selectedText != null && selectedRange == null) {
            Text(
                "This passage appears more than once in the chapter. Refine the selection to save an exact highlight.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
    }
}

private fun AnnotatedString.withHighlightRanges(ranges: List<IntRange>, color: Color): AnnotatedString =
    buildAnnotatedString {
        append(this@withHighlightRanges)
        ranges.forEach { range ->
            if (!range.isEmpty() && range.first >= 0 && range.last < length) {
                addStyle(SpanStyle(background = color), range.first, range.last + 1)
            }
        }
    }

@Composable
internal fun BookHighlightsSheet(
    highlights: List<ReaderHighlight>,
    delivery: Map<String, String>,
    onDismiss: () -> Unit,
    onOpen: (ReaderHighlight) -> Unit,
    onPublish: (ReaderHighlight) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Highlights", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (highlights.isEmpty()) {
                Text("No highlights yet. Long-press and drag across a passage in a chapter, then tap Create highlight in the floating selection control.")
            } else {
                highlights.sortedByDescending(ReaderHighlight::createdAtMillis).forEach { highlight ->
                    HighlightCard(
                        highlight = highlight,
                        deliveryStatus = delivery[highlight.id],
                        onOpen = { onOpen(highlight) },
                        onPublish = { onPublish(highlight) },
                    )
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun HighlightCard(
    highlight: ReaderHighlight,
    deliveryStatus: String?,
    onOpen: () -> Unit,
    onPublish: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(highlight.chapterTitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (highlight.comment.isNotBlank()) Text(highlight.comment, style = MaterialTheme.typography.bodyMedium)
        Text("“${highlight.quote}”", style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Serif)
        Text(
            text = deliveryStatus ?: if (highlight.publishedEventId != null) "Queued for delivery" else "Saved privately",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onOpen) { Text("Open passage") }
            if (highlight.publishedEventId == null) TextButton(onClick = onPublish) { Text("Publish") }
        }
    }
}

@Composable
internal fun HighlightComposerSheet(
    composer: HighlightComposerState,
    onDismiss: () -> Unit,
    onCommentChanged: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = { if (!composer.isPublishing) onDismiss() }) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Publish highlight", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("“${composer.highlight.quote}”", fontFamily = FontFamily.Serif, style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = composer.comment,
                onValueChange = onCommentChanged,
                modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp),
                enabled = !composer.isPublishing,
                label = { Text("Comment (optional)") },
                minLines = 3,
            )
            if (composer.requiresSignIn) Text("Log in with an Android signer in Settings before publishing this highlight.")
            composer.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onSubmit, enabled = !composer.isPublishing, modifier = Modifier.fillMaxWidth()) {
                if (composer.isPublishing) CircularProgressIndicator(Modifier.size(18.dp)) else Text("Publish highlight")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
