@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.decentnewsroom.bookshelf.ui.ratings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.decentnewsroom.bookshelf.ui.RatingComposerState
import eu.decentnewsroom.bookshelf.ui.RatingDetailsState
import eu.decentnewsroom.bookshelf.ui.RatingDistributionUi
import eu.decentnewsroom.bookshelf.ui.RatingReviewUi
import eu.decentnewsroom.bookshelf.ui.RatingSummaryUi
import eu.decentnewsroom.bookshelf.ui.components.LoadingInline
import eu.decentnewsroom.bookshelf.ui.components.Notice
import kotlin.math.roundToInt

@Composable
fun RatingSummaryCard(summary: RatingSummaryUi, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Community rating", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(when { summary.isLoading -> "Loading ratings…"; summary.ratingCount == 0 -> "No ratings yet. Be the first to rate this book."; else -> "${summary.ratingCount} ${if (summary.ratingCount == 1) "rating" else "ratings"} · tap for reviews" }, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            if (summary.isLoading) CircularProgressIndicator(Modifier.size(24.dp)) else summary.averageStars?.let { Text("${it.formatOneDecimal()} ★", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun RatingsSheet(page: RatingDetailsState, onDismiss: () -> Unit, onAddReview: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(page.book.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Community ratings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            when { page.summary.isLoading -> LoadingInline("Loading ratings…"); page.summary.ratingCount == 0 -> Text("No community ratings yet.", color = MaterialTheme.colorScheme.onSurfaceVariant); else -> { Text("${page.summary.averageStars?.formatOneDecimal()} out of 5 · ${page.summary.ratingCount} ${if (page.summary.ratingCount == 1) "rating" else "ratings"}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); RatingDistribution(page.distribution, page.summary.ratingCount) } }
            Button(onClick = onAddReview, modifier = Modifier.fillMaxWidth()) { Text("Add a review") }
            Text("Written reviews", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            when { page.isLoadingReviews -> LoadingInline("Loading reviews…"); page.reviews.none { it.opinion.isNotBlank() } -> Text("No written reviews yet.", color = MaterialTheme.colorScheme.onSurfaceVariant); else -> page.reviews.filter { it.opinion.isNotBlank() }.forEach { RatingReviewCard(it) } }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun RatingDistribution(distribution: List<RatingDistributionUi>, total: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { (5 downTo 1).forEach { stars ->
        val count = distribution.firstOrNull { it.stars == stars }?.count ?: 0
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Text("$stars ★", Modifier.width(34.dp)); LinearProgressIndicator(progress = { if (total == 0) 0f else count.toFloat() / total }, modifier = Modifier.weight(1f).height(7.dp).clip(RoundedCornerShape(999.dp))); Text(count.toString(), Modifier.width(24.dp)) }
    } }
}

@Composable
fun RatingReviewCard(review: RatingReviewUi) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("${review.stars.formatOneDecimal()} ★", fontWeight = FontWeight.SemiBold); Text(review.opinion); Text("${review.reviewerName ?: review.reviewerPubkey.compactHex()} · ${java.text.DateFormat.getDateInstance().format(java.util.Date(review.createdAtMillis))}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}

@Composable
fun RatingComposerSheet(composer: RatingComposerState, onDismiss: () -> Unit, onStarsChanged: (Int) -> Unit, onOpinionChanged: (String) -> Unit, onSubmit: () -> Unit) {
    ModalBottomSheet(onDismissRequest = { if (!composer.isPublishing) onDismiss() }) { Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Rate ${composer.book.title}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Choose a star rating", style = MaterialTheme.typography.titleMedium)
        Row { (1..5).forEach { star -> TextButton(onClick = { onStarsChanged(star) }, enabled = !composer.isPublishing) { Text(if (star <= (composer.selectedStars ?: 0)) "★" else "☆", fontSize = 32.sp) } } }
        OutlinedTextField(value = composer.opinion, onValueChange = onOpinionChanged, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp), enabled = !composer.isPublishing, label = { Text("Your opinion (optional)") }, minLines = 4)
        if (composer.requiresSignIn) Notice("Log in with an Android signer in Settings before publishing a review.")
        composer.error?.let { Notice(it) }
        Button(onClick = onSubmit, enabled = !composer.isPublishing, modifier = Modifier.fillMaxWidth()) { if (composer.isPublishing) CircularProgressIndicator(Modifier.size(18.dp)) else Text("Publish review") }
        Spacer(Modifier.height(16.dp))
    } }
}

private fun Double.formatOneDecimal(): String = ((this * 10.0).roundToInt() / 10.0).toString()
private fun Float.formatOneDecimal(): String = ((this * 10f).roundToInt() / 10f).toString()
private fun String.compactHex(): String = if (length <= 16) this else "${take(8)}...${takeLast(8)}"
