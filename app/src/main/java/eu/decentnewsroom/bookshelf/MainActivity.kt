package eu.decentnewsroom.bookshelf

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import eu.decentnewsroom.bookshelf.ui.BookshelfApp
import eu.decentnewsroom.bookshelf.ui.BookshelfViewModel
import eu.decentnewsroom.bookshelf.ui.theme.applyBookshelfEdgeToEdge

class MainActivity : ComponentActivity() {
    private val bookshelfViewModel by lazy {
        ViewModelProvider(this)[BookshelfViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.initialize(applicationContext)
        applyBookshelfEdgeToEdge(AppGraph.readerSettings.readerPreferences.value.theme)
        if (intent.action == Intent.ACTION_VIEW) {
            bookshelfViewModel.openInitialBookLink(intent.dataString)
        }
        setContent {
            BookshelfApp(viewModel = bookshelfViewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) {
            bookshelfViewModel.openBookLink(intent.dataString)
        }
    }
}
