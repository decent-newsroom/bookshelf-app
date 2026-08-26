package eu.decentnewsroom.bookshelf.data.nostr

import android.content.Context

class NostrSignerSessionStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("nostr_signer_session", Context.MODE_PRIVATE)

    fun load(): NostrSignerSession? {
        val pubkey = preferences.getString(KEY_PUBKEY, null)?.trim()?.lowercase()
        val packageName = preferences.getString(KEY_PACKAGE_NAME, null)?.trim()

        if (pubkey == null || packageName.isNullOrBlank() || !HEX_64.matches(pubkey)) {
            return null
        }

        return NostrSignerSession(
            pubkey = pubkey,
            packageName = packageName,
        )
    }

    fun save(session: NostrSignerSession) {
        require(HEX_64.matches(session.pubkey)) { "Signer pubkey is invalid." }
        require(session.packageName.isNotBlank()) { "Signer package is invalid." }

        preferences
            .edit()
            .putString(KEY_PUBKEY, session.pubkey.lowercase())
            .putString(KEY_PACKAGE_NAME, session.packageName)
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val KEY_PUBKEY = "pubkey"
        const val KEY_PACKAGE_NAME = "package_name"
        val HEX_64 = Regex("^[a-f0-9]{64}$", RegexOption.IGNORE_CASE)
    }
}