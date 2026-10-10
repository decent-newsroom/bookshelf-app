package eu.decentnewsroom.bookshelf.ui.tutorials

import androidx.annotation.StringRes
import eu.decentnewsroom.bookshelf.R

/** Bundled presentation only: an example never resolves live app state or services. */
enum class TutorialExample(@param:StringRes val descriptionRes: Int) {
    OpenAndRead(R.string.tutorial_example_open_and_read),
    ReaderControls(R.string.tutorial_example_reader_controls),
    ReadingComfort(R.string.tutorial_example_reading_comfort),
    SearchBooks(R.string.tutorial_example_search_books),
    SearchChapters(R.string.tutorial_example_search_chapters),
    OpenMatch(R.string.tutorial_example_open_match),
    SaveBook(R.string.tutorial_example_save_book),
    ResumeReading(R.string.tutorial_example_resume_reading),
    PrepareBook(R.string.tutorial_example_prepare_book),
    CheckOffline(R.string.tutorial_example_check_offline),
    RestoreDownloads(R.string.tutorial_example_restore_downloads),
    CreateHighlight(R.string.tutorial_example_create_highlight),
    RevisitHighlights(R.string.tutorial_example_revisit_highlights),
    ShareHighlight(R.string.tutorial_example_share_highlight),
    OpenReview(R.string.tutorial_example_open_review),
    PublishReview(R.string.tutorial_example_publish_review),
    EditReview(R.string.tutorial_example_edit_review),
    StartTracking(R.string.tutorial_example_start_tracking),
    UnderstandProgress(R.string.tutorial_example_understand_progress),
    ManageTracking(R.string.tutorial_example_manage_tracking),
    ConnectSigner(R.string.tutorial_example_connect_signer),
    ReviewSharing(R.string.tutorial_example_review_sharing),
}
