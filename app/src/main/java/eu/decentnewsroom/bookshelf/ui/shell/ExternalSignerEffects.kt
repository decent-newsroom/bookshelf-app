package eu.decentnewsroom.bookshelf.ui.shell

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.decentnewsroom.bookshelf.data.nostr.AndroidExternalSigner
import eu.decentnewsroom.bookshelf.data.nostr.AndroidSignerResult
import eu.decentnewsroom.bookshelf.ui.BookshelfUiState
import eu.decentnewsroom.bookshelf.ui.BookshelfViewModel

data class ExternalSignerActions(
    val signerAvailable: Boolean,
    val startLogin: () -> Unit,
)

/**
 * Bridges pending signer work from the app state to Android Activity Result launchers.
 *
 * Signer intents must remain in Compose because they require an activity-owned launcher;
 * business decisions and result handling continue to live in [BookshelfViewModel].
 */
@Composable
fun rememberExternalSignerActions(
    state: BookshelfUiState,
    viewModel: BookshelfViewModel,
): ExternalSignerActions {
    val context = LocalContext.current
    val signerAvailable = remember(context) { AndroidExternalSigner.isInstalled(context) }
    var launchedDirectoryRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var launchedAuthRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var launchedRatingRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var launchedHighlightRequestId by rememberSaveable { mutableStateOf<String?>(null) }

    val loginLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        when (val parsed = AndroidExternalSigner.parseLoginResult(result.resultCode, result.data)) {
            is AndroidSignerResult.Success -> viewModel.completeExternalSignerLogin(parsed.value)
            is AndroidSignerResult.Failed -> viewModel.reportExternalSignerFailure(parsed.message)
        }
    }
    val directorySignLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val requestId = result.data?.getStringExtra("id") ?: launchedDirectoryRequestId
        when (val parsed = AndroidExternalSigner.parseSignEventResult(result.resultCode, result.data)) {
            is AndroidSignerResult.Success -> viewModel.completeDirectorySignature(requestId, parsed.value)
            is AndroidSignerResult.Failed -> viewModel.failPendingDirectorySignature(parsed.message)
        }
        launchedDirectoryRequestId = null
    }
    val authSignLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val requestId = result.data?.getStringExtra("id") ?: launchedAuthRequestId
        when (val parsed = AndroidExternalSigner.parseSignEventResult(result.resultCode, result.data)) {
            is AndroidSignerResult.Success -> viewModel.completeNostrAuthSignature(requestId, parsed.value)
            is AndroidSignerResult.Failed -> viewModel.failPendingNostrAuthSignature(requestId, parsed.message)
        }
        launchedAuthRequestId = null
    }
    val ratingSignLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val requestId = result.data?.getStringExtra("id") ?: launchedRatingRequestId
        when (val parsed = AndroidExternalSigner.parseSignEventResult(result.resultCode, result.data)) {
            is AndroidSignerResult.Success -> viewModel.completeRatingSignature(requestId, parsed.value)
            is AndroidSignerResult.Failed -> viewModel.failPendingRatingSignature(parsed.message)
        }
        launchedRatingRequestId = null
    }
    val highlightSignLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val requestId = result.data?.getStringExtra("id") ?: launchedHighlightRequestId
        when (val parsed = AndroidExternalSigner.parseSignEventResult(result.resultCode, result.data)) {
            is AndroidSignerResult.Success -> viewModel.completeHighlightSignature(requestId, parsed.value)
            is AndroidSignerResult.Failed -> viewModel.failPendingHighlightSignature(parsed.message)
        }
        launchedHighlightRequestId = null
    }

    LaunchedEffect(state.pendingDirectorySignRequest?.id) {
        val request = state.pendingDirectorySignRequest ?: return@LaunchedEffect
        launchedDirectoryRequestId = request.id
        runCatching {
            directorySignLauncher.launch(
                AndroidExternalSigner.signEventIntent(request.session, request.unsignedEventJson, request.id),
            )
        }.onFailure { failure ->
            launchedDirectoryRequestId = null
            viewModel.failPendingDirectorySignature(failure.message ?: "Could not open Android signer.")
        }
    }
    LaunchedEffect(state.pendingNostrAuthSignRequest?.id) {
        val request = state.pendingNostrAuthSignRequest ?: return@LaunchedEffect
        launchedAuthRequestId = request.id
        runCatching {
            authSignLauncher.launch(
                AndroidExternalSigner.signEventIntent(request.session, request.unsignedEventJson, request.id),
            )
        }.onFailure { failure ->
            launchedAuthRequestId = null
            viewModel.failPendingNostrAuthSignature(request.id, failure.message ?: "Could not open Android signer.")
        }
    }
    LaunchedEffect(state.pendingRatingSignRequest?.id) {
        val request = state.pendingRatingSignRequest ?: return@LaunchedEffect
        launchedRatingRequestId = request.id
        runCatching {
            ratingSignLauncher.launch(
                AndroidExternalSigner.signEventIntent(request.session, request.unsignedEventJson, request.id),
            )
        }.onFailure { failure ->
            launchedRatingRequestId = null
            viewModel.failPendingRatingSignature(failure.message ?: "Could not open Android signer.")
        }
    }
    LaunchedEffect(state.pendingHighlightSignRequest?.id) {
        val request = state.pendingHighlightSignRequest ?: return@LaunchedEffect
        launchedHighlightRequestId = request.id
        runCatching {
            highlightSignLauncher.launch(
                AndroidExternalSigner.signEventIntent(request.session, request.unsignedEventJson, request.id),
            )
        }.onFailure { failure ->
            launchedHighlightRequestId = null
            viewModel.failPendingHighlightSignature(failure.message ?: "Could not open Android signer.")
        }
    }

    return ExternalSignerActions(
        signerAvailable = signerAvailable,
        startLogin = {
            if (!signerAvailable) {
                viewModel.reportExternalSignerFailure("No Android Nostr signer found.")
            } else {
                runCatching { loginLauncher.launch(AndroidExternalSigner.loginIntent()) }
                    .onFailure { failure ->
                        viewModel.reportExternalSignerFailure(failure.message ?: "Could not open Android signer.")
                    }
            }
        },
    )
}
