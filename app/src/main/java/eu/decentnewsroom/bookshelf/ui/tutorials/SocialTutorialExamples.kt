package eu.decentnewsroom.bookshelf.ui.tutorials

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import eu.decentnewsroom.bookshelf.R
import eu.decentnewsroom.bookshelf.data.highlights.HighlightAnchors
import eu.decentnewsroom.bookshelf.data.highlights.ReaderHighlight
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import eu.decentnewsroom.bookshelf.ui.HighlightCard
import eu.decentnewsroom.bookshelf.ui.HighlightComposerForm
import eu.decentnewsroom.bookshelf.ui.HighlightComposerState
import eu.decentnewsroom.bookshelf.ui.HighlightedChapterText
import eu.decentnewsroom.bookshelf.ui.RatingComposerState
import eu.decentnewsroom.bookshelf.ui.books.BookActionRow
import eu.decentnewsroom.bookshelf.ui.ratings.RatingComposerForm
import eu.decentnewsroom.bookshelf.ui.theme.readerColors

/** Local presentation only. The tutorial frame suppresses child input and semantics. */
@Composable
internal fun SocialTutorialExample(example: TutorialExample) {
    when (example) {
        TutorialExample.CreateHighlight -> {
            val passage = TutorialSamples.passage()
            val quote = TutorialSamples.quote()
            val start = passage.indexOf(quote)
            val preferences = TutorialSamples.preferences()
            HighlightedChapterText(
                text = AnnotatedString(passage),
                ranges = listOf(start until start + quote.length),
                preferences = preferences,
                colors = preferences.theme.readerColors,
            )
        }
        TutorialExample.RevisitHighlights -> HighlightCard(
            highlight = sampleHighlight(),
            deliveryStatus = null,
            onOpen = {},
            onPublish = {},
            onDelete = {},
        )
        TutorialExample.ShareHighlight -> HighlightComposerForm(
            composer = HighlightComposerState(
                highlight = sampleHighlight(),
                comment = stringResource(R.string.tutorial_social_highlight_comment),
            ),
            onCommentChanged = {},
            onSubmit = {},
            readOnly = true,
        )
        TutorialExample.OpenReview -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(TutorialSamples.book().title, style = MaterialTheme.typography.titleMedium)
            BookActionRow(Icons.Outlined.Star, stringResource(R.string.tutorial_social_rate_review), {})
        }
        TutorialExample.PublishReview, TutorialExample.EditReview -> {
            val editing = example == TutorialExample.EditReview
            RatingComposerForm(
                composer = RatingComposerState(
                    book = TutorialSamples.book(),
                    selectedStars = if (editing) 5 else 4,
                    opinion = stringResource(
                        if (editing) R.string.tutorial_social_edited_review else R.string.tutorial_social_review,
                    ),
                    editingEventId = if (editing) "tutorial-review" else null,
                    hasChangedStars = editing,
                ),
                onStarsChanged = {},
                onOpinionChanged = {},
                onSubmit = {},
                onDiscard = {},
                readOnly = true,
            )
        }
        else -> Unit
    }
}

@Composable
private fun sampleHighlight(): ReaderHighlight {
    val passage = TutorialSamples.passage()
    val quote = TutorialSamples.quote()
    val chapter = TutorialSamples.chapters().first()
    val start = passage.indexOf(quote)
    val end = start + quote.length
    return ReaderHighlight(
        id = "tutorial-highlight",
        bookCoordinate = TutorialSamples.book().coordinate,
        chapterCoordinate = chapter.reference.coordinate,
        chapterTitle = chapter.title,
        chapterEvent = NostrEvent(content = passage),
        quote = quote,
        context = passage,
        startOffset = start,
        endOffset = end,
        prefix = HighlightAnchors.prefix(passage, start),
        suffix = HighlightAnchors.suffix(passage, end),
        createdAtMillis = 1_700_000_000_000L,
        displayedTextHash = HighlightAnchors.textHash(passage),
    )
}
