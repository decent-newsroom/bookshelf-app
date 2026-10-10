package eu.decentnewsroom.bookshelf.ui.tutorials

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import eu.decentnewsroom.bookshelf.R
import eu.decentnewsroom.bookshelf.data.reader.ReaderPreferences
import eu.decentnewsroom.bookshelf.data.reader.ReaderTheme
import eu.decentnewsroom.bookshelf.domain.BookChapter
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.domain.ChapterReference
import eu.decentnewsroom.bookshelf.ui.theme.readerColors

/** Bundled fictional data only. Never connect tutorial illustrations to application state. */
internal object TutorialSamples {
    private const val PUBKEY = "0000000000000000000000000000000000000000000000000000000000000000"
    private val references = (1..3).map { number ->
        ChapterReference("30041:$PUBKEY:tutorial-chapter-$number", PUBKEY, "tutorial-chapter-$number", null, null)
    }

    @Composable
    fun book() = BookSummary(
        id = "tutorial-book", coordinate = "30040:$PUBKEY:tutorial-book", pubkey = PUBKEY,
        identifier = "tutorial-book", title = stringResource(R.string.tutorial_sample_book_title),
        summary = null, authors = listOf(stringResource(R.string.tutorial_sample_author)),
        coverImageUrl = null, sourceUrl = null, language = "en", releaseDate = null,
        version = null, type = "book", topics = emptyList(), relay = null, createdAt = 0,
        chapterCount = references.size, chapterRefs = references,
    )

    @Composable
    fun chapters(): List<BookChapter> {
        val titles = listOf(
            stringResource(R.string.tutorial_sample_chapter_one),
            stringResource(R.string.tutorial_sample_chapter_two),
            stringResource(R.string.tutorial_sample_chapter_three),
        )
        val passage = passage()
        return references.mapIndexed { index, reference ->
            BookChapter(reference, index, true, titles[index], null, passage, null, 0)
        }
    }

    @Composable
    fun passage() = stringResource(R.string.tutorial_sample_passage, quote())

    @Composable
    fun quote() = stringResource(R.string.tutorial_sample_quote)

    @Composable
    fun preferences(): ReaderPreferences {
        val background = MaterialTheme.colorScheme.background
        val theme = ReaderTheme.entries.firstOrNull { it.readerColors.background == background } ?: ReaderTheme.Paper
        return ReaderPreferences(theme = theme)
    }
}
