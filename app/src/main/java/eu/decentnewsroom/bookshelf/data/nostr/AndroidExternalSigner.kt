package eu.decentnewsroom.bookshelf.data.nostr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import eu.decentnewsroom.bookshelf.domain.BookKinds
import androidx.core.net.toUri

object AndroidExternalSigner {
    fun isInstalled(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, NOSTR_SIGNER_URI.toUri())
        return context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    }

    fun loginIntent(): Intent =
        Intent(Intent.ACTION_VIEW, NOSTR_SIGNER_URI.toUri()).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            putExtra("type", "get_public_key")
            putExtra(
                "permissions",
                """[{"type":"sign_event","kind":${BookKinds.DIRECTORY}},{"type":"sign_event","kind":${NostrAuthEventDraft.KIND}}]""",
            )
        }

    fun signEventIntent(
        session: NostrSignerSession,
        unsignedEventJson: String,
        requestId: String,
    ): Intent =
        Intent(Intent.ACTION_VIEW, "nostrsigner:${Uri.encode(unsignedEventJson)}".toUri()).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            setPackage(session.packageName)
            putExtra("type", "sign_event")
            putExtra("id", requestId)
            putExtra("current_user", session.pubkey)
            putExtra("returnType", "event")
        }

    fun parseLoginResult(resultCode: Int, data: Intent?): AndroidSignerResult<NostrSignerSession> {
        val failure = parseFailure(resultCode, data)
        if (failure != null) {
            return AndroidSignerResult.Failed(failure)
        }

        val pubkey = data?.getStringExtra("result")?.trim()?.lowercase()
        val packageName = data?.getStringExtra("package")?.trim()

        if (pubkey == null || !HEX_64.matches(pubkey)) {
            return AndroidSignerResult.Failed("Signer returned an invalid public key.")
        }
        if (packageName.isNullOrBlank()) {
            return AndroidSignerResult.Failed("Signer did not identify its package.")
        }

        return AndroidSignerResult.Success(
            NostrSignerSession(
                pubkey = pubkey,
                packageName = packageName,
            ),
        )
    }

    fun parseSignEventResult(resultCode: Int, data: Intent?): AndroidSignerResult<String> {
        val failure = parseFailure(resultCode, data)
        if (failure != null) {
            return AndroidSignerResult.Failed(failure)
        }

        val eventJson = data?.getStringExtra("event")
            ?: data?.getStringExtra("result")?.takeIf { it.trimStart().startsWith("{") }

        return if (eventJson.isNullOrBlank()) {
            AndroidSignerResult.Failed("Signer returned a signature without a signed event.")
        } else {
            AndroidSignerResult.Success(eventJson)
        }
    }

    private fun parseFailure(resultCode: Int, data: Intent?): String? =
        when {
            resultCode != Activity.RESULT_OK -> "Signer did not return a result."
            data?.getBooleanExtra("rejected", false) == true -> "Signer request was rejected."
            else -> null
        }

    private const val NOSTR_SIGNER_URI = "nostrsigner:"
    private val HEX_64 = Regex("^[a-f0-9]{64}$", RegexOption.IGNORE_CASE)
}

sealed interface AndroidSignerResult<out T> {
    data class Success<T>(val value: T) : AndroidSignerResult<T>
    data class Failed(val message: String) : AndroidSignerResult<Nothing>
}
