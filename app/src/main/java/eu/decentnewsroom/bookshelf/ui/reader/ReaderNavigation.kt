package eu.decentnewsroom.bookshelf.ui.reader

import eu.decentnewsroom.bookshelf.domain.BookDetail
import eu.decentnewsroom.bookshelf.domain.PublicationOutlineNode
import eu.decentnewsroom.bookshelf.domain.effectiveOutline
import eu.decentnewsroom.bookshelf.data.reader.ReadingProgress

/** A single projection shared by the reader rows, Contents, resume and tracked sections. */
internal class ReaderNavigation(detail: BookDetail) {
    val entries = detail.effectiveOutline().map { node ->
        if (node.isDivision) node else {
            val chapter = node.chapterIndex?.let { detail.chapters.getOrNull(it) }
            node.copy(
                title = chapter?.title ?: node.title,
                diagnostic = node.diagnostic ?: when {
                    chapter == null -> "This section was not loaded on this device."
                    !chapter.available -> "This section is unavailable."
                    else -> null
                },
            )
        }
    }
    private val entryIndexes = entries.mapIndexed { index, node -> node.occurrenceKey to index + 1 }.toMap()
    private val leafIndexes = entries.mapIndexedNotNull { index, node ->
        node.chapterIndex?.takeIf { it in detail.chapters.indices }?.let { it to index + 1 }
    }.toMap()
    private val nodesByKey = entries.associateBy { it.occurrenceKey }

    fun listItemForChapter(chapterIndex: Int): Int = leafIndexes[chapterIndex] ?: 0
    fun initialListItem(progress: ReadingProgress, explicitChapterIndex: Int?): Int {
        if (explicitChapterIndex != null) return listItemForChapter(explicitChapterIndex)
        listItemForEntry(progress.readerEntryKey)?.let { return it }
        // A first open must show leading Book/Act headings before the first chapter.
        if (!progress.hasStarted && progress.currentChapterIndex == 0 && progress.chapterScrollOffsetPx == 0) {
            return if (entries.isEmpty()) 0 else 1
        }
        return listItemForChapter(progress.currentChapterIndex)
    }
    fun listItemForEntry(key: String?): Int? = key?.let(entryIndexes::get)
    fun entryAt(listItemIndex: Int): PublicationOutlineNode? = entries.getOrNull(listItemIndex - 1)
    fun chapterAt(listItemIndex: Int): Int? = entryAt(listItemIndex)?.chapterIndex?.takeIf(leafIndexes::containsKey)
    fun trackingChapterAt(listItemIndex: Int, reachedEnd: Boolean): Int? =
        if (reachedEnd) leafIndexes.keys.maxOrNull() else chapterAt(listItemIndex)

    fun activeKeys(entryKey: String?): Set<String> {
        val keys = mutableSetOf<String>()
        var node = entryKey?.let(nodesByKey::get)
        while (node != null && keys.add(node.occurrenceKey)) node = node.parentKey?.let(nodesByKey::get)
        return keys
    }
}
