package eu.decentnewsroom.bookshelf.ui.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import eu.decentnewsroom.bookshelf.data.discovery.BookShareLinkEncoder
import eu.decentnewsroom.bookshelf.domain.BookSummary
import kotlinx.coroutines.launch

data class BookShareActions(
    val share: (BookSummary) -> Unit,
    val copyLink: (BookSummary) -> Unit,
)

/** Android UI effects stay outside the ViewModel and never fetch or publish a book. */
@Composable
fun rememberBookShareActions(snackbarHostState: SnackbarHostState): BookShareActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun showMessage(message: String) {
        scope.launch {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    return BookShareActions(
        share = { book ->
            val link = BookShareLinkEncoder.encode(book)
            if (link == null) {
                showMessage("This book does not have a valid publication link.")
            } else {
                try {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        // Keep titles out of the payload so Sharesheet Copy copies only the URI.
                        putExtra(Intent.EXTRA_TEXT, link)
                        putExtra(Intent.EXTRA_TITLE, book.title)
                    }
                    context.startActivity(Intent.createChooser(sendIntent, null))
                } catch (_: RuntimeException) {
                    showMessage("Could not open Android sharing. Try Copy book link.")
                }
            }
        },
        copyLink = { book ->
            val link = BookShareLinkEncoder.encode(book)
            if (link == null) {
                showMessage("This book does not have a valid publication link.")
            } else {
                try {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    if (clipboard == null) {
                        showMessage("Could not copy the book link.")
                    } else {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Book link", link))
                        // Android 13+ supplies its own clipboard confirmation.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            showMessage("Book link copied.")
                        }
                    }
                } catch (_: RuntimeException) {
                    showMessage("Could not copy the book link.")
                }
            }
        },
    )
}
