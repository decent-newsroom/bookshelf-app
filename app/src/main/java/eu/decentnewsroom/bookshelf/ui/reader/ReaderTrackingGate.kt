package eu.decentnewsroom.bookshelf.ui.reader

/**
 * Separates real section transitions from pixel persistence and invalidates automatic
 * advances queued before an explicit tracking action or account change.
 * Used on the ViewModel's main thread; disk/relay operations retain their own guards.
 */
internal class ReaderTrackingGate {
    private var lastSection: Pair<String, Int>? = null
    private var generation = 0L

    fun sectionChanged(readerSessionId: String, sectionIndex: Int?): Long? {
        if (sectionIndex == null) return null
        val section = readerSessionId to sectionIndex
        if (lastSection == section) return null
        lastSection = section
        return generation
    }

    fun invalidatePendingAdvances() {
        generation++
        // Retain the observed section: Reset must not replay an unchanged viewport.
    }

    fun accepts(ticket: Long): Boolean = generation == ticket
}

/** Reading event timestamps may be logical seconds slightly ahead of wall time. */
internal fun localReadingCycleTime(nowMillis: Long, finishedAtSeconds: Long): Long {
    val afterFinished = when {
        finishedAtSeconds < 0L -> 1L
        finishedAtSeconds >= Long.MAX_VALUE / 1_000L -> Long.MAX_VALUE
        // Local resolver compares whole seconds to avoid misclassifying activity
        // just before a same-second remote finish. Explicit intent crosses that boundary.
        else -> (finishedAtSeconds + 1L) * 1_000L
    }
    return maxOf(nowMillis, afterFinished)
}
