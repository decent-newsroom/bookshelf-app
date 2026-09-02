package eu.decentnewsroom.bookshelf.data.nostr

import android.content.Context
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Stores the optional relay running on this device, for example Citrine. */
class LocalRelaySettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _relayUrl = MutableStateFlow(loadRelayUrl())
    val relayUrl: StateFlow<String?> = _relayUrl.asStateFlow()

    fun setRelayUrl(rawRelayUrl: String) {
        val relayUrl = rawRelayUrl.trim().takeIf(String::isNotEmpty)?.let(::normalizeRelayUrl)
        _relayUrl.value = relayUrl
        preferences.edit().putString(KEY_RELAY_URL, relayUrl).apply()
    }

    private fun loadRelayUrl(): String? = preferences.getString(KEY_RELAY_URL, null)?.let { saved ->
        runCatching { normalizeRelayUrl(saved) }.getOrNull()
    }

    private fun normalizeRelayUrl(candidate: String): String {
        require(candidate.length <= MAX_URL_LENGTH) { "Local relay URL is too long." }
        val normalized = RelayUrlNormalizer.normalizeOrNull(candidate)
        require(normalized != null && normalized.url.startsWith("ws")) {
            "Local relay must be a valid ws:// or wss:// relay URL."
        }
        return normalized.url
    }

    private companion object {
        const val PREFERENCES_NAME = "bookshelf_local_relay"
        const val KEY_RELAY_URL = "relay_url"
        const val MAX_URL_LENGTH = 2_048
    }
}
