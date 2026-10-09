package eu.decentnewsroom.bookshelf.data.nostr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import eu.decentnewsroom.bookshelf.domain.BookKinds
import androidx.core.net.toUri
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
                """[{"type":"sign_event","kind":${BookKinds.DIRECTORY}},{"type":"sign_event","kind":${BookKinds.RATING}},{"type":"sign_event","kind":${BookKinds.READING_LIST}},{"type":"sign_event","kind":${BookKinds.FINISHED_LABEL}},{"type":"sign_event","kind":${NostrAuthEventDraft.KIND}}]""",
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

    /** NIP-55 remembered permission only; this never launches an Activity. */
    suspend fun signEventInBackground(
        context: Context,
        session: NostrSignerSession,
        unsignedEventJson: String,
    ): BackgroundSignerResult = withContext(Dispatchers.IO) {
        val draft = runCatching { SIGNER_JSON.decodeFromString<NostrEvent>(unsignedEventJson) }.getOrNull()
            ?: return@withContext BackgroundSignerResult.Unavailable
        if (draft.pubkey != session.pubkey || draft.kind !in setOf(BookKinds.READING_LIST, BookKinds.FINISHED_LABEL)) {
            return@withContext BackgroundSignerResult.Unavailable
        }
        try {
            // NIP-55 providers use the query's projection for [payload, pubkey, current_user].
            val cursor = context.contentResolver.query(
                "content://${session.packageName}.SIGN_EVENT".toUri(),
                arrayOf(unsignedEventJson, "", session.pubkey), null, null, null,
            ) ?: return@withContext BackgroundSignerResult.Unavailable
            cursor.use {
                if (it.getColumnIndex("rejected") >= 0) return@withContext BackgroundSignerResult.Rejected
                if (!it.moveToFirst()) return@withContext BackgroundSignerResult.Unavailable
                val eventColumn = it.getColumnIndex("event")
                val resultColumn = it.getColumnIndex("result")
                val returnedEvent = if (eventColumn >= 0) it.getString(eventColumn) else null
                val result = if (resultColumn >= 0) it.getString(resultColumn) else null
                val eventJson = signedEventJsonFromProvider(draft, returnedEvent, result)
                    ?: return@withContext BackgroundSignerResult.Unavailable
                BackgroundSignerResult.Signed(eventJson)
            }
        } catch (_: SecurityException) {
            BackgroundSignerResult.Unavailable
        } catch (_: IllegalArgumentException) {
            BackgroundSignerResult.Unavailable
        } catch (_: android.os.RemoteException) {
            BackgroundSignerResult.Unavailable
        }
    }

    internal fun signedEventJsonFromProvider(draft: NostrEvent, eventJson: String?, result: String?): String? {
        val fullEvent = eventJson?.takeIf { it.trimStart().startsWith("{") }
            ?: result?.takeIf { it.trimStart().startsWith("{") }
        if (fullEvent != null) return fullEvent
        val signature = result?.trim()?.lowercase()?.takeIf { HEX_128.matches(it) } ?: return null
        val id = EventHasher.hashId(draft.pubkey, draft.createdAt, draft.kind,
            draft.tags.map { it.toTypedArray() }.toTypedArray(), draft.content)
        return SIGNER_JSON.encodeToString(draft.copy(id = id, sig = signature))
    }

    private fun parseFailure(resultCode: Int, data: Intent?): String? =
        when {
            resultCode != Activity.RESULT_OK -> "Signer did not return a result."
            data?.getBooleanExtra("rejected", false) == true -> "Signer request was rejected."
            else -> null
        }

    private const val NOSTR_SIGNER_URI = "nostrsigner:"
    private val HEX_64 = Regex("^[a-f0-9]{64}$", RegexOption.IGNORE_CASE)
    private val HEX_128 = Regex("^[a-f0-9]{128}$", RegexOption.IGNORE_CASE)
    private val SIGNER_JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }
}

sealed interface BackgroundSignerResult {
    data class Signed(val eventJson: String) : BackgroundSignerResult
    data object Unavailable : BackgroundSignerResult
    data object Rejected : BackgroundSignerResult
}

sealed interface AndroidSignerResult<out T> {
    data class Success<T>(val value: T) : AndroidSignerResult<T>
    data class Failed(val message: String) : AndroidSignerResult<Nothing>
}
