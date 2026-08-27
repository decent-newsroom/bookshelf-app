package eu.decentnewsroom.bookshelf.ui

import java.net.URI
import java.util.Locale

/** Policy for links originating in remote chapter content. */
internal object ChapterLinkPolicy {
    fun parse(rawUrl: String?): SafeChapterLink? {
        val value = rawUrl?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
        val host = uri.host?.trim()?.takeIf(String::isNotEmpty) ?: return null
        // Reject user-info URLs such as https://trusted.example@evil.example.
        if (scheme != "https" || uri.userInfo != null || !uri.isAbsolute) return null
        return SafeChapterLink(value, host.lowercase(Locale.US))
    }
}

internal data class SafeChapterLink(val url: String, val host: String)
