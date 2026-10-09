package eu.decentnewsroom.bookshelf.ui.reader

/** One positioned reader observation shared by local progress and tracked-section projections. */
internal data class ReaderObservation(
    val chapterIndex: Int,
    val scrollOffsetPx: Int,
    val reachedEnd: Boolean,
    val readingActivity: Boolean,
    /** Null until a real chapter position or verified endpoint can be attributed. */
    val trackingChapterIndex: Int? = null,
)

internal data class ReaderLayoutSnapshot(
    val listItemIndex: Int,
    val scrollOffsetPx: Int,
    val canScrollForward: Boolean,
    val hasVisibleItems: Boolean,
    val viewportSizePx: Int,
    val isScrollInProgress: Boolean = false,
    val explicitNavigationGeneration: Int = 0,
)

private const val ReaderHeaderItemCount = 1

internal fun readerListItemIndexForChapter(chapterIndex: Int, chapterCount: Int): Int =
    if (chapterCount <= 0) {
        0
    } else {
        coerceReaderChapterIndex(chapterIndex, chapterCount) + ReaderHeaderItemCount
    }

internal fun chapterIndexForReaderListItem(listItemIndex: Int, chapterCount: Int): Int =
    if (chapterCount <= 0) {
        0
    } else {
        (listItemIndex - ReaderHeaderItemCount).coerceIn(0, chapterCount - 1)
    }

internal fun coerceReaderChapterIndex(chapterIndex: Int, chapterCount: Int): Int =
    if (chapterCount <= 0) {
        0
    } else {
        chapterIndex.coerceIn(0, chapterCount - 1)
    }

internal fun readerScrollOffsetForListItem(listItemIndex: Int, scrollOffsetPx: Int): Int =
    if (listItemIndex < ReaderHeaderItemCount) 0 else scrollOffsetPx.coerceAtLeast(0)

/** Header has no chapter offset; terminal cards must never overwrite the last chapter's offset. */
internal fun readerResumePositionForListItem(listItemIndex: Int, scrollOffsetPx: Int, chapterCount: Int): Pair<Int, Int>? =
    if (chapterCount <= 0 || listItemIndex > chapterCount || listItemIndex < 0) null
    else chapterIndexForReaderListItem(listItemIndex, chapterCount) to readerScrollOffsetForListItem(listItemIndex, scrollOffsetPx)

/** Only a chapter item or a verified endpoint can attribute a tracked section. */
internal fun readerTrackingChapterIndexForListItem(
    listItemIndex: Int,
    chapterCount: Int,
    reachedEnd: Boolean,
): Int? = when {
    chapterCount <= 0 -> null
    reachedEnd -> chapterCount - 1
    listItemIndex in 1..chapterCount -> listItemIndex - 1
    else -> null
}

internal fun readerObservationHasActivity(
    previous: ReaderObservation,
    chapterIndex: Int,
    scrollOffsetPx: Int,
    reachedEnd: Boolean,
    isScrollInProgress: Boolean,
    explicitNavigation: Boolean,
): Boolean {
    val locationChanged = previous.chapterIndex != chapterIndex || previous.scrollOffsetPx != scrollOffsetPx
    val endpointJustReached = !previous.reachedEnd && reachedEnd
    return endpointJustReached || (locationChanged && (explicitNavigation || isScrollInProgress))
}

/** Publish chapter/start/end/activity changes promptly, but keep pixel-only work coalesced. */
internal fun readerShouldPublishPosition(
    previous: ReaderObservation?,
    current: ReaderObservation,
    activityNeedsPublication: Boolean,
): Boolean = previous == null || previous.chapterIndex != current.chapterIndex ||
    previous.reachedEnd != current.reachedEnd ||
    (previous.scrollOffsetPx == 0 && current.scrollOffsetPx > 0) || activityNeedsPublication

internal fun readerHasTerminalActions(sectionStreamKnown: Boolean, total: Int, loadedSectionCount: Int, truncated: Boolean): Boolean =
    sectionStreamKnown && total > 0 && loadedSectionCount == total && !truncated

internal fun readerHasCompleteContent(
    sectionStreamKnown: Boolean,
    total: Int,
    loadedSectionCount: Int,
    allChaptersAvailable: Boolean,
    truncated: Boolean,
): Boolean = readerHasTerminalActions(sectionStreamKnown, total, loadedSectionCount, truncated) && allChaptersAvailable

/** `canScrollForward` is meaningful only after Compose has laid out visible content and a viewport. */
internal fun readerIsAtVerifiedEnd(
    completeContent: Boolean,
    canScrollForward: Boolean,
    hasVisibleItems: Boolean,
    viewportSizePx: Int,
): Boolean = completeContent && hasVisibleItems && viewportSizePx > 0 && !canScrollForward

/** Retains the most recent chapter position while end cards are in view. */
internal class ReaderResumePositionTracker(
    private val chapterCount: Int,
    initialPosition: Pair<Int, Int>? = null,
) {
    private var lastChapterPosition: Pair<Int, Int>? = initialPosition

    fun observe(listItemIndex: Int, scrollOffsetPx: Int): Pair<Int, Int>? =
        readerResumePositionForListItem(listItemIndex, scrollOffsetPx, chapterCount)
            ?.also { lastChapterPosition = it }

    fun positionOnExit(listItemIndex: Int, scrollOffsetPx: Int): Pair<Int, Int>? =
        observe(listItemIndex, scrollOffsetPx) ?: lastChapterPosition
}
