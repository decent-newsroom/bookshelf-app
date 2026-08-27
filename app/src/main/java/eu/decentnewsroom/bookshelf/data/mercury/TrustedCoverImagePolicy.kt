package eu.decentnewsroom.bookshelf.data.mercury

import java.net.URI
import java.util.Locale

/** Limits automatically loaded cover art to explicitly trusted HTTPS hosts. */
internal object TrustedCoverImagePolicy {
    private val trustedHosts = setOf(
        "gutenberg.org",
        "www.gutenberg.org",
        "images.gutenberg.org",
        "aleph.gutenberg.org",
    )

    fun sanitize(url: String?): String? {
        val value = url?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.US) ?: return null
        if (uri.scheme?.lowercase(Locale.US) != "https" || uri.userInfo != null || host !in trustedHosts) return null
        return value
    }
}
