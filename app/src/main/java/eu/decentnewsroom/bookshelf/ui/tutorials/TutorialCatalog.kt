package eu.decentnewsroom.bookshelf.ui.tutorials

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import eu.decentnewsroom.bookshelf.R

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
)

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
                ),
                TutorialStep(
                    id = "reader_controls",
                    titleRes = R.string.tutorial_getting_started_reader_controls_title,
                    bodyRes = R.string.tutorial_getting_started_reader_controls_body,
                ),
                TutorialStep(
                    id = "reading_comfort",
                    titleRes = R.string.tutorial_getting_started_reading_comfort_title,
                    bodyRes = R.string.tutorial_getting_started_reading_comfort_body,
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
                ),
                TutorialStep(
                    id = "search_chapters",
                    titleRes = R.string.tutorial_search_search_chapters_title,
                    bodyRes = R.string.tutorial_search_search_chapters_body,
                ),
                TutorialStep(
                    id = "open_match",
                    titleRes = R.string.tutorial_search_open_match_title,
                    bodyRes = R.string.tutorial_search_open_match_body,
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
                ),
                TutorialStep(
                    id = "resume_reading",
                    titleRes = R.string.tutorial_save_and_resume_resume_reading_title,
                    bodyRes = R.string.tutorial_save_and_resume_resume_reading_body,
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
                ),
                TutorialStep(
                    id = "check_offline",
                    titleRes = R.string.tutorial_offline_reading_check_offline_title,
                    bodyRes = R.string.tutorial_offline_reading_check_offline_body,
                ),
                TutorialStep(
                    id = "restore_downloads",
                    titleRes = R.string.tutorial_offline_reading_restore_downloads_title,
                    bodyRes = R.string.tutorial_offline_reading_restore_downloads_body,
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
                ),
                TutorialStep(
                    id = "revisit_highlights",
                    titleRes = R.string.tutorial_highlights_revisit_highlights_title,
                    bodyRes = R.string.tutorial_highlights_revisit_highlights_body,
                ),
                TutorialStep(
                    id = "share_highlight",
                    titleRes = R.string.tutorial_highlights_share_highlight_title,
                    bodyRes = R.string.tutorial_highlights_share_highlight_body,
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
                ),
                TutorialStep(
                    id = "publish_review",
                    titleRes = R.string.tutorial_reviews_publish_review_title,
                    bodyRes = R.string.tutorial_reviews_publish_review_body,
                ),
                TutorialStep(
                    id = "edit_review",
                    titleRes = R.string.tutorial_reviews_edit_review_title,
                    bodyRes = R.string.tutorial_reviews_edit_review_body,
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
                ),
                TutorialStep(
                    id = "understand_progress",
                    titleRes = R.string.tutorial_tracking_progress_understand_progress_title,
                    bodyRes = R.string.tutorial_tracking_progress_understand_progress_body,
                ),
                TutorialStep(
                    id = "manage_tracking",
                    titleRes = R.string.tutorial_tracking_progress_manage_tracking_title,
                    bodyRes = R.string.tutorial_tracking_progress_manage_tracking_body,
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
                ),
                TutorialStep(
                    id = "review_sharing",
                    titleRes = R.string.tutorial_connect_account_review_sharing_title,
                    bodyRes = R.string.tutorial_connect_account_review_sharing_body,
                ),
            ),
        ),
    )

    fun get(topic: TutorialTopic): Tutorial = tutorials.getValue(topic)
}
