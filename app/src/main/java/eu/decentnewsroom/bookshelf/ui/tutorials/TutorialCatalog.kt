package eu.decentnewsroom.bookshelf.ui.tutorials

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import eu.decentnewsroom.bookshelf.R

enum class TutorialDestination { Home, Search, MyBooks, Storage, ReadingProgress, Account }

val TutorialTopic.destination: TutorialDestination
    get() = when (this) {
        TutorialTopic.GettingStarted, TutorialTopic.Highlights, TutorialTopic.Reviews -> TutorialDestination.Home
        TutorialTopic.Search -> TutorialDestination.Search
        TutorialTopic.SaveAndResume -> TutorialDestination.MyBooks
        TutorialTopic.OfflineReading -> TutorialDestination.Storage
        TutorialTopic.TrackingProgress -> TutorialDestination.ReadingProgress
        TutorialTopic.ConnectAccount -> TutorialDestination.Account
    }

val TutorialTopic.actionLabelRes: Int
    @StringRes get() = when (destination) {
        TutorialDestination.Home -> R.string.tutorial_action_start_reading
        TutorialDestination.Search -> R.string.tutorial_action_search
        TutorialDestination.MyBooks -> R.string.tutorial_action_my_books
        TutorialDestination.Storage -> R.string.tutorial_action_storage
        TutorialDestination.ReadingProgress -> R.string.tutorial_action_tracking
        TutorialDestination.Account -> R.string.tutorial_action_account
    }

enum class TutorialTopic(
    val id: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val summaryRes: Int,
) {
    GettingStarted(
        id = "getting_started",
        titleRes = R.string.tutorial_topic_getting_started_title,
        summaryRes = R.string.tutorial_topic_getting_started_summary,
    ),
    Search(
        id = "search",
        titleRes = R.string.tutorial_topic_search_title,
        summaryRes = R.string.tutorial_topic_search_summary,
    ),
    SaveAndResume(
        id = "save_and_resume",
        titleRes = R.string.tutorial_topic_save_and_resume_title,
        summaryRes = R.string.tutorial_topic_save_and_resume_summary,
    ),
    OfflineReading(
        id = "offline_reading",
        titleRes = R.string.tutorial_topic_offline_reading_title,
        summaryRes = R.string.tutorial_topic_offline_reading_summary,
    ),
    Highlights(
        id = "highlights",
        titleRes = R.string.tutorial_topic_highlights_title,
        summaryRes = R.string.tutorial_topic_highlights_summary,
    ),
    Reviews(
        id = "reviews",
        titleRes = R.string.tutorial_topic_reviews_title,
        summaryRes = R.string.tutorial_topic_reviews_summary,
    ),
    TrackingProgress(
        id = "tracking_progress",
        titleRes = R.string.tutorial_topic_tracking_progress_title,
        summaryRes = R.string.tutorial_topic_tracking_progress_summary,
    ),
    ConnectAccount(
        id = "connect_account",
        titleRes = R.string.tutorial_topic_connect_account_title,
        summaryRes = R.string.tutorial_topic_connect_account_summary,
    ),
}

data class TutorialStep(
    val id: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val bodyRes: Int,
    @param:DrawableRes val illustrationRes: Int? = null,
    @param:StringRes val descriptionRes: Int? = null,
    val example: TutorialExample? = null,
) {
    init {
        require(illustrationRes == null || example == null) {
            "A tutorial step must use either a drawable or a component example."
        }
        require(illustrationRes == null || descriptionRes != null) {
            "A tutorial drawable requires an accessible description."
        }
    }
}

data class Tutorial(
    val topic: TutorialTopic,
    val steps: List<TutorialStep>,
) {
    val hasContent: Boolean get() = steps.isNotEmpty()
}

object TutorialCatalog {
    val topics: List<TutorialTopic> = TutorialTopic.entries

    // Approved copy is bundled in localized resources; topic and step IDs remain stable.
    private val tutorials: Map<TutorialTopic, Tutorial> = mapOf(
        TutorialTopic.GettingStarted to Tutorial(
            topic = TutorialTopic.GettingStarted,
            steps = listOf(
                TutorialStep(
                    id = "open_and_read",
                    titleRes = R.string.tutorial_getting_started_open_and_read_title,
                    bodyRes = R.string.tutorial_getting_started_open_and_read_body,
                    example = TutorialExample.OpenAndRead,
                ),
                TutorialStep(
                    id = "reader_controls",
                    titleRes = R.string.tutorial_getting_started_reader_controls_title,
                    bodyRes = R.string.tutorial_getting_started_reader_controls_body,
                    example = TutorialExample.ReaderControls,
                ),
                TutorialStep(
                    id = "reading_comfort",
                    titleRes = R.string.tutorial_getting_started_reading_comfort_title,
                    bodyRes = R.string.tutorial_getting_started_reading_comfort_body,
                    example = TutorialExample.ReadingComfort,
                ),
            ),
        ),
        TutorialTopic.Search to Tutorial(
            topic = TutorialTopic.Search,
            steps = listOf(
                TutorialStep(
                    id = "search_books",
                    titleRes = R.string.tutorial_search_search_books_title,
                    bodyRes = R.string.tutorial_search_search_books_body,
                    example = TutorialExample.SearchBooks,
                ),
                TutorialStep(
                    id = "search_chapters",
                    titleRes = R.string.tutorial_search_search_chapters_title,
                    bodyRes = R.string.tutorial_search_search_chapters_body,
                    example = TutorialExample.SearchChapters,
                ),
                TutorialStep(
                    id = "open_match",
                    titleRes = R.string.tutorial_search_open_match_title,
                    bodyRes = R.string.tutorial_search_open_match_body,
                    example = TutorialExample.OpenMatch,
                ),
            ),
        ),
        TutorialTopic.SaveAndResume to Tutorial(
            topic = TutorialTopic.SaveAndResume,
            steps = listOf(
                TutorialStep(
                    id = "save_book",
                    titleRes = R.string.tutorial_save_and_resume_save_book_title,
                    bodyRes = R.string.tutorial_save_and_resume_save_book_body,
                    example = TutorialExample.SaveBook,
                ),
                TutorialStep(
                    id = "resume_reading",
                    titleRes = R.string.tutorial_save_and_resume_resume_reading_title,
                    bodyRes = R.string.tutorial_save_and_resume_resume_reading_body,
                    example = TutorialExample.ResumeReading,
                ),
            ),
        ),
        TutorialTopic.OfflineReading to Tutorial(
            topic = TutorialTopic.OfflineReading,
            steps = listOf(
                TutorialStep(
                    id = "prepare_book",
                    titleRes = R.string.tutorial_offline_reading_prepare_book_title,
                    bodyRes = R.string.tutorial_offline_reading_prepare_book_body,
                    example = TutorialExample.PrepareBook,
                ),
                TutorialStep(
                    id = "check_offline",
                    titleRes = R.string.tutorial_offline_reading_check_offline_title,
                    bodyRes = R.string.tutorial_offline_reading_check_offline_body,
                    example = TutorialExample.CheckOffline,
                ),
                TutorialStep(
                    id = "restore_downloads",
                    titleRes = R.string.tutorial_offline_reading_restore_downloads_title,
                    bodyRes = R.string.tutorial_offline_reading_restore_downloads_body,
                    example = TutorialExample.RestoreDownloads,
                ),
            ),
        ),
        TutorialTopic.Highlights to Tutorial(
            topic = TutorialTopic.Highlights,
            steps = listOf(
                TutorialStep(
                    id = "create_highlight",
                    titleRes = R.string.tutorial_highlights_create_highlight_title,
                    bodyRes = R.string.tutorial_highlights_create_highlight_body,
                    example = TutorialExample.CreateHighlight,
                ),
                TutorialStep(
                    id = "revisit_highlights",
                    titleRes = R.string.tutorial_highlights_revisit_highlights_title,
                    bodyRes = R.string.tutorial_highlights_revisit_highlights_body,
                    example = TutorialExample.RevisitHighlights,
                ),
                TutorialStep(
                    id = "share_highlight",
                    titleRes = R.string.tutorial_highlights_share_highlight_title,
                    bodyRes = R.string.tutorial_highlights_share_highlight_body,
                    example = TutorialExample.ShareHighlight,
                ),
            ),
        ),
        TutorialTopic.Reviews to Tutorial(
            topic = TutorialTopic.Reviews,
            steps = listOf(
                TutorialStep(
                    id = "open_review",
                    titleRes = R.string.tutorial_reviews_open_review_title,
                    bodyRes = R.string.tutorial_reviews_open_review_body,
                    example = TutorialExample.OpenReview,
                ),
                TutorialStep(
                    id = "publish_review",
                    titleRes = R.string.tutorial_reviews_publish_review_title,
                    bodyRes = R.string.tutorial_reviews_publish_review_body,
                    example = TutorialExample.PublishReview,
                ),
                TutorialStep(
                    id = "edit_review",
                    titleRes = R.string.tutorial_reviews_edit_review_title,
                    bodyRes = R.string.tutorial_reviews_edit_review_body,
                    example = TutorialExample.EditReview,
                ),
            ),
        ),
        TutorialTopic.TrackingProgress to Tutorial(
            topic = TutorialTopic.TrackingProgress,
            steps = listOf(
                TutorialStep(
                    id = "start_tracking",
                    titleRes = R.string.tutorial_tracking_progress_start_tracking_title,
                    bodyRes = R.string.tutorial_tracking_progress_start_tracking_body,
                    example = TutorialExample.StartTracking,
                ),
                TutorialStep(
                    id = "understand_progress",
                    titleRes = R.string.tutorial_tracking_progress_understand_progress_title,
                    bodyRes = R.string.tutorial_tracking_progress_understand_progress_body,
                    example = TutorialExample.UnderstandProgress,
                ),
                TutorialStep(
                    id = "manage_tracking",
                    titleRes = R.string.tutorial_tracking_progress_manage_tracking_title,
                    bodyRes = R.string.tutorial_tracking_progress_manage_tracking_body,
                    example = TutorialExample.ManageTracking,
                ),
            ),
        ),
        TutorialTopic.ConnectAccount to Tutorial(
            topic = TutorialTopic.ConnectAccount,
            steps = listOf(
                TutorialStep(
                    id = "connect_signer",
                    titleRes = R.string.tutorial_connect_account_connect_signer_title,
                    bodyRes = R.string.tutorial_connect_account_connect_signer_body,
                    example = TutorialExample.ConnectSigner,
                ),
                TutorialStep(
                    id = "review_sharing",
                    titleRes = R.string.tutorial_connect_account_review_sharing_title,
                    bodyRes = R.string.tutorial_connect_account_review_sharing_body,
                    example = TutorialExample.ReviewSharing,
                ),
            ),
        ),
    )

    fun get(topic: TutorialTopic): Tutorial = tutorials.getValue(topic)
}
