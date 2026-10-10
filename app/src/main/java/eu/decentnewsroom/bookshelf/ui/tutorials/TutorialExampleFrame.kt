package eu.decentnewsroom.bookshelf.ui.tutorials

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import eu.decentnewsroom.bookshelf.R
import eu.decentnewsroom.bookshelf.ui.books.LocalBookReadingPresentations

/** Read-only illustration. Parent scrolling/paging remains the gesture owner. */
@Composable
internal fun TutorialExampleFrame(example: TutorialExample) {
    val description = stringResource(
        R.string.tutorial_example_accessibility,
        stringResource(example.descriptionRes),
    )
    Surface(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = description
        },
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.tutorial_example_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Box(
                Modifier.fillMaxWidth()
                    .focusProperties { canFocus = false },
            ) {
                CompositionLocalProvider(LocalBookReadingPresentations provides emptyMap()) {
                    TutorialExampleContent(example)
                }
                // This topmost sibling owns the hit path instead of the pictured controls.
                // Do not consume events: the ancestor pager and page scroll still need them.
                Box(Modifier.matchParentSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent()
                    }
                })
            }
        }
    }
}

@Composable
private fun TutorialExampleContent(example: TutorialExample) {
    when (example) {
        TutorialExample.OpenAndRead,
        TutorialExample.ReaderControls,
        TutorialExample.ReadingComfort,
        TutorialExample.SaveBook,
        TutorialExample.ResumeReading,
        TutorialExample.PrepareBook,
        TutorialExample.CheckOffline,
        TutorialExample.StartTracking,
        TutorialExample.UnderstandProgress,
        TutorialExample.ManageTracking -> ReaderTutorialExample(example)

        TutorialExample.CreateHighlight,
        TutorialExample.RevisitHighlights,
        TutorialExample.ShareHighlight,
        TutorialExample.OpenReview,
        TutorialExample.PublishReview,
        TutorialExample.EditReview -> SocialTutorialExample(example)

        TutorialExample.SearchBooks,
        TutorialExample.SearchChapters,
        TutorialExample.OpenMatch,
        TutorialExample.RestoreDownloads,
        TutorialExample.ConnectSigner,
        TutorialExample.ReviewSharing -> SearchSettingsTutorialExample(example)
    }
}
