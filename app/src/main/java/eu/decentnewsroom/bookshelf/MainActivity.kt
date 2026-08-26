package eu.decentnewsroom.bookshelf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import eu.decentnewsroom.bookshelf.ui.BookshelfApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.initialize(applicationContext)
        enableEdgeToEdge()
        setContent {
            BookshelfApp()
        }
    }
}
