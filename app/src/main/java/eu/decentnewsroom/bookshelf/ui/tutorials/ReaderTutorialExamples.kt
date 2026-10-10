package eu.decentnewsroom.bookshelf.ui.tutorials

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.decentnewsroom.bookshelf.R
import eu.decentnewsroom.bookshelf.data.reader.ReadingProgress
import eu.decentnewsroom.bookshelf.data.reader.resolveBookReadingPresentation
import eu.decentnewsroom.bookshelf.data.reading.ReadingState
import eu.decentnewsroom.bookshelf.data.reading.TrackedBook
import eu.decentnewsroom.bookshelf.ui.ContinueReadingBook
import eu.decentnewsroom.bookshelf.ui.books.BookCard
import eu.decentnewsroom.bookshelf.ui.home.ContinueReadingCard
import eu.decentnewsroom.bookshelf.ui.reader.ReaderContentsItem
import eu.decentnewsroom.bookshelf.ui.reader.ReaderNavigationCard
import eu.decentnewsroom.bookshelf.ui.reader.ReaderProgressControls
import eu.decentnewsroom.bookshelf.ui.reader.ReaderSaveAction
import eu.decentnewsroom.bookshelf.ui.reader.ReaderSettingsControls
import eu.decentnewsroom.bookshelf.ui.reader.readerTextStyle
import eu.decentnewsroom.bookshelf.ui.reading.FinishBookCard
import eu.decentnewsroom.bookshelf.ui.theme.readerColors

/** Presentation only; the tutorial frame blocks gestures and replaces child semantics. */
@Composable
internal fun ReaderTutorialExample(example: TutorialExample) {
    val book = TutorialSamples.book()
    val preferences = TutorialSamples.preferences()
    val colors = preferences.theme.readerColors
    val progress = ReadingProgress(
        bookCoordinate = book.coordinate, currentChapterIndex = 1, chapterCount = 3,
        updatedAtMillis = 0, hasStarted = true, fullChapterCount = 3, completeContent = true,
        chapterCoordinate = book.chapterRefs[1].coordinate,
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (example) {
            TutorialExample.OpenAndRead -> {
                BookCard(book, isSaved = false, onOpen = {}, onLongPress = {})
                Text(TutorialSamples.passage(), style = readerTextStyle(preferences), color = colors.text)
            }
            TutorialExample.ReaderControls -> {
                ReaderNavigationCard(colors, {}, {}, {}) { ReaderSaveAction(false, {}) }
                TutorialContents()
            }
            TutorialExample.ReadingComfort -> {
                Text(TutorialSamples.passage(), style = readerTextStyle(preferences), color = colors.text)
                ReaderSettingsControls(preferences, {}, {}, {}, {})
            }
            TutorialExample.SaveBook ->
                ReaderNavigationCard(colors, {}, {}, {}) { ReaderSaveAction(false, {}) }
            TutorialExample.ResumeReading -> ContinueReadingCard(
                ContinueReadingBook(book, progress), onOpen = {}, onLongPress = {},
                modifier = Modifier.fillMaxWidth(),
            )
            TutorialExample.PrepareBook -> {
                BookCard(book, isSaved = true, onOpen = {}, onLongPress = {})
                Text(stringResource(R.string.tutorial_sample_offline_loaded), style = MaterialTheme.typography.bodySmall)
                TutorialContents()
            }
            TutorialExample.CheckOffline -> TutorialContents(lastUnavailable = true)
            TutorialExample.StartTracking, TutorialExample.UnderstandProgress, TutorialExample.ManageTracking -> {
                val tracked = if (example == TutorialExample.StartTracking) null else TrackedBook(
                    bookCoordinate = book.coordinate, book = book, position = 2, total = 3,
                    updatedAt = 0,
                )
                ReaderProgressControls(
                    progress = progress,
                    presentation = resolveBookReadingPresentation(progress, tracked, null, book),
                    book = book, tracked = tracked, streamKnown = true,
                    readingState = ReadingState(), isSignedIn = false, colors = colors,
                    onTrack = {}, onReset = {}, onStop = {}, onSync = {},
                )
                if (example == TutorialExample.ManageTracking) {
                    Text(stringResource(R.string.tutorial_sample_finish_location), style = MaterialTheme.typography.labelLarge)
                    FinishBookCard(finished = null, deviceOnly = true, isRereading = false, onFinish = {})
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun TutorialContents(lastUnavailable: Boolean = false) {
    val colors = TutorialSamples.preferences().theme.readerColors
    TutorialSamples.chapters().forEachIndexed { index, chapter ->
        ReaderContentsItem(
            chapter = if (lastUnavailable && index == 2) chapter.copy(available = false, content = null) else chapter,
            selected = index == 1, colors = colors, onClick = {},
        )
    }
}
