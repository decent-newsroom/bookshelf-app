package eu.decentnewsroom.bookshelf.data.mercury

import java.net.URI
import java.util.Locale

/** Accepts publication-provided cover art only when it is a safe HTTPS URL. */
internal object TrustedCoverImagePolicy {
    fun sanitize(url: String?): String? {
        val value = url?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.US) ?: return null
        if (uri.scheme?.lowercase(Locale.US) != "https" || uri.userInfo != null || host.isBlank()) return null
        return value
    }
}
