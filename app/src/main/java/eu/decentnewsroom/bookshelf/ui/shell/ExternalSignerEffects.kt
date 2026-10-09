package eu.decentnewsroom.bookshelf.ui.shell

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.decentnewsroom.bookshelf.data.nostr.AndroidExternalSigner
import eu.decentnewsroom.bookshelf.data.nostr.AndroidSignerResult
import eu.decentnewsroom.bookshelf.data.reading.ReadingEvents
import eu.decentnewsroom.bookshelf.ui.BookshelfUiState
import eu.decentnewsroom.bookshelf.ui.BookshelfViewModel

data class ExternalSignerActions(
    val signerAvailable: Boolean,
    val startLogin: () -> Unit,
)

internal enum class ForegroundSignerType { AUTH, DIRECTORY, RATING, HIGHLIGHT, READING, LOGIN }

internal data class ForegroundSignerRequest(val type: ForegroundSignerType, val id: String) {
    val key: String get() = "${type.name}:$id"
}

/** An already returned request stays consumed until its asynchronous state handler clears it. */
internal fun nextForegroundSignerRequest(
    pending: List<ForegroundSignerRequest>,
    consumed: Set<String>,
    busyKey: String?,
): ForegroundSignerRequest? =
    if (busyKey != null) null else pending.firstOrNull { it.key !in consumed }

/** One activity-owned launcher serializes login and every foreground signing permission request. */
@Composable
fun rememberExternalSignerActions(
    state: BookshelfUiState,
    viewModel: BookshelfViewModel,
): ExternalSignerActions {
    val context = LocalContext.current
    val signerAvailable = remember(context) { AndroidExternalSigner.isInstalled(context) }
    val latestState by rememberUpdatedState(state)
    var activeRequestKey by rememberSaveable { mutableStateOf<String?>(null) }
    var consumedRequestKeys by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var loginRequested by rememberSaveable { mutableStateOf(false) }
    var loginAwaitingPubkey by rememberSaveable { mutableStateOf<String?>(null) }
    var loginAwaitingPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var loginAwaitingError by rememberSaveable { mutableStateOf<String?>(null) }

    fun failRequest(request: ForegroundSignerRequest, message: String) {
        val current = latestState
        when (request.type) {
            ForegroundSignerType.AUTH -> viewModel.failPendingNostrAuthSignature(request.id, message)
            ForegroundSignerType.DIRECTORY -> if (current.pendingDirectorySignRequest?.id == request.id) viewModel.failPendingDirectorySignature(message)
            ForegroundSignerType.RATING -> if (current.pendingRatingSignRequest?.id == request.id) viewModel.failPendingRatingSignature(message)
            ForegroundSignerType.HIGHLIGHT -> if (current.pendingHighlightSignRequest?.id == request.id) viewModel.failPendingHighlightSignature(message)
            ForegroundSignerType.READING -> viewModel.failReadingSignature(request.id, message)
            ForegroundSignerType.LOGIN -> viewModel.reportExternalSignerFailure(message)
        }
    }

    val signerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // Saveable identity also associates an Activity Result delivered after configuration recreation.
        val key = activeRequestKey
        val type = key?.substringBefore(':')?.let { name -> ForegroundSignerType.entries.firstOrNull { it.name == name } }
        if (key != null && type != null) {
            val request = ForegroundSignerRequest(type, key.substringAfter(':'))
            if (type == ForegroundSignerType.LOGIN) {
                when (val parsed = AndroidExternalSigner.parseLoginResult(result.resultCode, result.data)) {
                    is AndroidSignerResult.Success -> {
                        // Wait for the new account before considering any queued requests from the old account.
                        loginAwaitingPubkey = parsed.value.pubkey
                        loginAwaitingPackage = parsed.value.packageName
                        loginAwaitingError = latestState.error
                        viewModel.completeExternalSignerLogin(parsed.value)
                    }
                    is AndroidSignerResult.Failed -> viewModel.reportExternalSignerFailure(parsed.message)
                }
            } else {
                val returnedId = result.data?.getStringExtra("id")
                if (returnedId != null && returnedId != request.id) {
                    failRequest(request, "Signer returned a different request ID.")
                } else when (val parsed = AndroidExternalSigner.parseSignEventResult(result.resultCode, result.data)) {
                    is AndroidSignerResult.Success -> when (type) {
                        ForegroundSignerType.AUTH -> viewModel.completeNostrAuthSignature(request.id, parsed.value)
                        ForegroundSignerType.DIRECTORY -> viewModel.completeDirectorySignature(request.id, parsed.value)
                        ForegroundSignerType.RATING -> viewModel.completeRatingSignature(request.id, parsed.value)
                        ForegroundSignerType.HIGHLIGHT -> viewModel.completeHighlightSignature(request.id, parsed.value)
                        ForegroundSignerType.READING -> viewModel.completeReadingSignature(request.id, parsed.value)
                        ForegroundSignerType.LOGIN -> Unit
                    }
                    is AndroidSignerResult.Failed -> failRequest(request, parsed.message)
                }
            }
        }
        activeRequestKey = null
    }

    val pending = listOfNotNull(
        state.pendingNostrAuthSignRequest?.takeIf { it.session == state.signerSession }?.let { ForegroundSignerRequest(ForegroundSignerType.AUTH, it.id) },
        state.pendingDirectorySignRequest?.takeIf { it.session == state.signerSession }?.let { ForegroundSignerRequest(ForegroundSignerType.DIRECTORY, it.id) },
        state.pendingRatingSignRequest?.takeIf { it.session == state.signerSession }?.let { ForegroundSignerRequest(ForegroundSignerType.RATING, it.id) },
        state.pendingHighlightSignRequest?.takeIf { it.session == state.signerSession }?.let { ForegroundSignerRequest(ForegroundSignerType.HIGHLIGHT, it.id) },
        state.pendingReadingSignRequest?.takeIf { it.session == state.signerSession }?.let { ForegroundSignerRequest(ForegroundSignerType.READING, it.id) },
    )
    LaunchedEffect(pending, activeRequestKey, loginRequested, loginAwaitingPubkey, loginAwaitingPackage, state.signerSession, state.error) {
        val pendingKeys = pending.map { it.key }.toSet()
        consumedRequestKeys = ArrayList(consumedRequestKeys.filter { it in pendingKeys || it == activeRequestKey })
        if (activeRequestKey != null) return@LaunchedEffect
        if (loginAwaitingPubkey != null) {
            val sessionChanged = state.signerSession?.pubkey == loginAwaitingPubkey && state.signerSession?.packageName == loginAwaitingPackage
            if (!sessionChanged && state.error == loginAwaitingError) return@LaunchedEffect
            loginAwaitingPubkey = null
            loginAwaitingPackage = null
            loginAwaitingError = null
        }
        val request = if (loginRequested) ForegroundSignerRequest(ForegroundSignerType.LOGIN, "login")
            else nextForegroundSignerRequest(pending, consumedRequestKeys.toSet(), activeRequestKey)
        if (request == null) return@LaunchedEffect
        activeRequestKey = request.key
        if (request.type == ForegroundSignerType.LOGIN) loginRequested = false
        else consumedRequestKeys = ArrayList(consumedRequestKeys + request.key)
        runCatching {
            val intent = when (request.type) {
                ForegroundSignerType.LOGIN -> AndroidExternalSigner.loginIntent()
                ForegroundSignerType.AUTH -> state.pendingNostrAuthSignRequest!!.let { AndroidExternalSigner.signEventIntent(it.session, it.unsignedEventJson, it.id) }
                ForegroundSignerType.DIRECTORY -> state.pendingDirectorySignRequest!!.let { AndroidExternalSigner.signEventIntent(it.session, it.unsignedEventJson, it.id) }
                ForegroundSignerType.RATING -> state.pendingRatingSignRequest!!.let { AndroidExternalSigner.signEventIntent(it.session, it.unsignedEventJson, it.id) }
                ForegroundSignerType.HIGHLIGHT -> state.pendingHighlightSignRequest!!.let { AndroidExternalSigner.signEventIntent(it.session, it.unsignedEventJson, it.id) }
                ForegroundSignerType.READING -> state.pendingReadingSignRequest!!.let { AndroidExternalSigner.signEventIntent(it.session, ReadingEvents.unsignedJson(it.draft), it.id) }
            }
            signerLauncher.launch(intent)
        }.onFailure { failure ->
            failRequest(request, failure.message ?: "Could not open Android signer.")
            activeRequestKey = null
        }
    }

    return ExternalSignerActions(
        signerAvailable = signerAvailable,
        startLogin = {
            if (!signerAvailable) viewModel.reportExternalSignerFailure("No Android Nostr signer found.")
            else if (activeRequestKey != "LOGIN:login") {
                loginAwaitingPubkey = null
                loginAwaitingPackage = null
                loginAwaitingError = null
                loginRequested = true
            }
        },
    )
}
