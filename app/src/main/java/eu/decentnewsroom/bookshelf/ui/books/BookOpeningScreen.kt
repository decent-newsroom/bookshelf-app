package eu.decentnewsroom.bookshelf.ui.books

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.decentnewsroom.bookshelf.data.mercury.TrustedCoverImagePolicy
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.ui.components.LoadingInline
import java.util.Locale

/** Opening artwork is presentation only; reader loading/rendering continues in the ViewModel. */
@Composable
fun BookOpeningScreen(book: BookSummary?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (book != null) {
                val coverUrl = TrustedCoverImagePolicy.sanitize(book.coverImageUrl)
                var artworkLoaded by remember(book.id, coverUrl) { mutableStateOf(false) }
                var coverAspectRatio by remember(book.id, coverUrl) { mutableStateOf(0.68f) }
                val coverWidth = minOf(maxWidth, maxHeight * coverAspectRatio, 560.dp)
                Surface(
                    modifier = Modifier.width(coverWidth).height(coverWidth / coverAspectRatio),
                    shape = RoundedCornerShape(8.dp),
                    color = if (artworkLoaded) Color.Transparent else MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 8.dp,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        if (!artworkLoaded) {
                            Box(Modifier.fillMaxHeight().width(8.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)))
                            Column(
                                modifier = Modifier.fillMaxSize().padding(20.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f))
                                    .padding(20.dp).verticalScroll(rememberScrollState())
                                    .heightIn(min = (coverWidth / 0.68f - 80.dp).coerceAtLeast(0.dp)),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                            ) {
                                Text(book.type.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer, textAlign = TextAlign.Center)
                                Text(book.title, style = if (coverWidth < 220.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                                    fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer, textAlign = TextAlign.Center)
                                Text(book.authors.joinToString(", ").ifBlank { "Unknown author" },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer, textAlign = TextAlign.Center)
                            }
                        }
                        if (coverUrl != null) {
                            AsyncImage(
                                model = coverUrl,
                                contentDescription = "Cover of ${book.title} by ${book.authors.joinToString(", ").ifBlank { "Unknown author" }}",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                onSuccess = { state ->
                                    val size = state.painter.intrinsicSize
                                    val ratio = size.width / size.height
                                    if (ratio.isFinite() && ratio > 0f) coverAspectRatio = ratio
                                    artworkLoaded = true
                                },
                                onError = { artworkLoaded = false; coverAspectRatio = 0.68f },
                            )
                        }
                        BookProgressOverlay(
                            presentation = LocalBookReadingPresentations.current[book.coordinate],
                            modifier = Modifier.align(Alignment.BottomEnd),
                        )
                    }
                }
            }
        }
        LoadingInline("Opening book…")
    }
}
