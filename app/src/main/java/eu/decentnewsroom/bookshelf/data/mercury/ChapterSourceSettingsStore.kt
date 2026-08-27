package eu.decentnewsroom.bookshelf.data.mercury

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.util.Locale

class ChapterSourceSettingsStore(context: Context) {
    private val sharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _relayUrls = MutableStateFlow(loadRelayUrls())
    val relayUrls: StateFlow<List<String>> = _relayUrls.asStateFlow()

    fun setRelayUrls(rawRelayUrls: String) {
        val normalized = ChapterRelayUrls.parse(rawRelayUrls)
        _relayUrls.value = normalized
        sharedPreferences.edit().putString(KEY_RELAY_URLS, normalized.joinToString("\n")).apply()
    }

    private fun loadRelayUrls(): List<String> {
        val saved = sharedPreferences.getString(KEY_RELAY_URLS, null) ?: return ChapterRelayUrls.DEFAULTS
        return ChapterRelayUrls.parse(saved)
    }

    private companion object {
        const val PREFERENCES_NAME = "bookshelf_chapter_sources"
        const val KEY_RELAY_URLS = "relay_urls"
    }
}

object ChapterRelayUrls {
    val DEFAULTS =
        listOf(
            "wss://mercury-relay.imwald.eu",
            "wss://thecitadel.nostr1.com",
        )

    fun parse(rawRelayUrls: String): List<String> {
        require(rawRelayUrls.length <= MAX_TOTAL_LENGTH) {
            "Chapter source settings are too large."
        }
        return normalize(rawRelayUrls.lineSequence().toList())
    }

    fun normalize(relayUrls: Iterable<String>): List<String> {
        val normalized = linkedSetOf<String>()
        relayUrls.forEach { candidate ->
            val relayUrl = candidate.trim().takeIf(String::isNotEmpty)?.let(::normalizeRelayUrl)
                ?: return@forEach
            require(normalized.size < MAX_RELAY_COUNT || relayUrl in normalized) {
                "At most $MAX_RELAY_COUNT chapter relay URLs are allowed."
            }
            normalized += relayUrl
        }
        return normalized.toList()
    }

    private fun normalizeRelayUrl(candidate: String): String {
        require(candidate.length <= MAX_URL_LENGTH) {
            "Chapter relay URLs must be at most $MAX_URL_LENGTH characters."
        }
        val uri = runCatching { URI(candidate) }.getOrNull()
        require(
            uri != null &&
                uri.scheme?.lowercase(Locale.US) == "wss" &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.fragment == null,
        ) {
            "Chapter sources must be valid wss:// relay URLs."
        }

        val normalizedScheme = uri.scheme.lowercase(Locale.US)
        val normalizedHost = uri.host.lowercase(Locale.US)
        val normalized = URI(
            normalizedScheme,
            null,
            normalizedHost,
            uri.port,
            uri.path,
            uri.query,
            null,
        ).normalize().toString()

        return if (normalized.endsWith("/") && uri.path == "/" && uri.query == null) {
            normalized.dropLast(1)
        } else {
            normalized
        }
    }

    private const val MAX_RELAY_COUNT = 8
    private const val MAX_URL_LENGTH = 2_048
    private const val MAX_TOTAL_LENGTH = MAX_RELAY_COUNT * (MAX_URL_LENGTH + 1)
}
