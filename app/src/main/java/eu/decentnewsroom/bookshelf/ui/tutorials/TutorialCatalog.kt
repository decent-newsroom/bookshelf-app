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
    TrackingProgress(
        id = "tracking_progress",
        titleRes = R.string.tutorial_topic_tracking_progress_title,
        summaryRes = R.string.tutorial_topic_tracking_progress_summary,
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

    // Add user-authored steps to each topic here; no placeholder lessons are shipped.
    private val tutorials: Map<TutorialTopic, Tutorial> = mapOf(
        TutorialTopic.GettingStarted to Tutorial(
            topic = TutorialTopic.GettingStarted,
            steps = emptyList(),
        ),
        TutorialTopic.TrackingProgress to Tutorial(
            topic = TutorialTopic.TrackingProgress,
            steps = emptyList(),
        ),
    )

    fun get(topic: TutorialTopic): Tutorial = tutorials.getValue(topic)
}
