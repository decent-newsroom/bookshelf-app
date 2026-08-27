package eu.decentnewsroom.bookshelf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import eu.decentnewsroom.bookshelf.ui.BookshelfApp
import eu.decentnewsroom.bookshelf.ui.theme.applyBookshelfEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.initialize(applicationContext)
        applyBookshelfEdgeToEdge(AppGraph.readerSettings.readerPreferences.value.theme)
        setContent {
            BookshelfApp()
        }
    }
}
