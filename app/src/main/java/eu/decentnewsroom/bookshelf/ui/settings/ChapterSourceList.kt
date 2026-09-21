package eu.decentnewsroom.bookshelf.ui.settings

import eu.decentnewsroom.bookshelf.data.mercury.ChapterRelayUrls

/** List operations for the Settings rows; validation remains in ChapterRelayUrls. */
internal object ChapterSourceList {
    fun add(current: List<String>, rawUrl: String): List<String> {
        require(rawUrl.isNotBlank()) { "Enter a wss:// chapter source URL." }
        val candidate = ChapterRelayUrls.normalize(listOf(rawUrl.trim())).single()
        require(candidate !in current) { "This chapter source is already configured." }
        return ChapterRelayUrls.normalize(current + candidate)
    }

    fun remove(current: List<String>, url: String): List<String> {
        require(current.size > 1) { "At least one chapter source is required." }
        require(url in current) { "This chapter source is no longer configured." }
        return current.filterNot { it == url }
    }

    fun defaults(): List<String> = ChapterRelayUrls.DEFAULTS
}
