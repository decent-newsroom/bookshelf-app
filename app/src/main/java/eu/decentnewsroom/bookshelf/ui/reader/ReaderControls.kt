package eu.decentnewsroom.bookshelf.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import eu.decentnewsroom.bookshelf.data.reader.ParagraphAlignment
import eu.decentnewsroom.bookshelf.data.reader.ReaderPreferences
import eu.decentnewsroom.bookshelf.data.reader.ReaderTheme
import eu.decentnewsroom.bookshelf.domain.BookChapter
import eu.decentnewsroom.bookshelf.ui.BookshelfTab
import eu.decentnewsroom.bookshelf.ui.books.BookCover
import eu.decentnewsroom.bookshelf.ui.components.SecondaryButton
import eu.decentnewsroom.bookshelf.ui.components.BackCloseButton
import eu.decentnewsroom.bookshelf.ui.components.ReaderNotice
import eu.decentnewsroom.bookshelf.ui.onboarding.OnboardingTooltip
import eu.decentnewsroom.bookshelf.ui.theme.ReaderColors

@Composable
internal fun ReaderControlsMenu(
    isSaved: Boolean,
    colors: ReaderColors, onBack: () -> Unit, onToggleSaved: () -> Unit,
    onShowContents: () -> Unit, onShowSettings: () -> Unit, onShowHighlights: () -> Unit,
    progressControls: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(12.dp).verticalScroll(rememberScrollState())) {
        ReaderNavigationCard(colors, onBack, onShowContents, onShowSettings) {
            ReaderSaveAction(isSaved, onToggleSaved)
        }
        Spacer(Modifier.height(12.dp))
        ReaderMenuCard(colors) {
            Column {
                ReaderSocialMenuItem("Highlights", colors, onShowHighlights)
            }
        }
        Spacer(Modifier.height(24.dp))
        ReaderProgressCard(colors, progressControls)
    }
}

@Composable
private fun ReaderMenuCard(
    colors: ReaderColors,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.controls,
        contentColor = colors.text,
        shadowElevation = 1.dp,
        content = content,
    )
}

@Composable
private fun ReaderNavigationCard(
    colors: ReaderColors,
    onBack: () -> Unit,
    onShowContents: () -> Unit,
    onShowSettings: () -> Unit,
    saveAction: @Composable () -> Unit,
) {
    ReaderMenuCard(colors) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            BackCloseButton(onClick = onBack)
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SecondaryButton(onClick = onShowContents, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Contents")
                }
                SecondaryButton(
                    onClick = onShowSettings,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Reader settings" },
                ) {
                    Text("Aa", fontWeight = FontWeight.SemiBold)
                }
                saveAction()
            }
        }
    }
}

@Composable
private fun ReaderSaveAction(isSaved: Boolean, onToggleSaved: () -> Unit) {
    SecondaryButton(onClick = onToggleSaved, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(if (isSaved) "Remove" else "Save")
    }
}

@Composable
private fun ReaderSocialMenuItem(label: String, colors: ReaderColors, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = colors.text)
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = colors.muted)
    }
}

@Composable
private fun ReaderProgressCard(colors: ReaderColors, progressControls: @Composable () -> Unit) {
    ReaderMenuCard(colors) {
        Box(Modifier.padding(14.dp)) {
            progressControls()
        }
    }
}

@Composable
internal fun ReaderBottomNavigationMenu(selected: BookshelfTab, colors: ReaderColors, onSelected: (BookshelfTab) -> Unit, modifier: Modifier = Modifier) {
    NavigationBar(modifier.fillMaxWidth(), containerColor = colors.controls) {
        BookshelfTab.entries.forEach { tab ->
            NavigationBarItem(selected == tab, { onSelected(tab) }, label = { Text(tab.label) }, icon = {}, colors = NavigationBarItemDefaults.colors(selectedTextColor = colors.accent, selectedIconColor = colors.accent, indicatorColor = colors.track, unselectedTextColor = colors.muted, unselectedIconColor = colors.muted))
        }
    }
}

@Composable
internal fun ReaderHeader(
    detail: eu.decentnewsroom.bookshelf.domain.BookDetail, isSaved: Boolean,
    colors: ReaderColors,
    onBack: () -> Unit, onToggleSaved: () -> Unit, onShowContents: () -> Unit, onShowSettings: () -> Unit,
    showBookListTip: Boolean, onBookListTipDismissed: () -> Unit,
    progressControls: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            ReaderNavigationCard(colors, onBack, onShowContents, onShowSettings) {
                OnboardingTooltip(
                    visible = showBookListTip,
                    text = "Save adds this book to your personal My Books list. Remove takes it out again.",
                    onDismissed = onBookListTipDismissed,
                ) {
                    ReaderSaveAction(isSaved, onToggleSaved)
                }
            }
            ReaderProgressCard(colors, progressControls)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            BookCover(
                detail.summary,
                Modifier.size(width = 88.dp, height = 124.dp),
                colors.track,
                colors.accent,
                readingPresentation = null,
                badgeBackgroundColor = colors.controls,
                badgeProgressColor = colors.accent,
                badgeCheckColor = colors.controls,
            )
            Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = detail.summary.title, style = MaterialTheme.typography.headlineMedium, color = colors.text, fontWeight = FontWeight.Bold)
                Text("by ${detail.summary.authors.joinToString(", ").ifBlank { "Unknown author" }}", style = MaterialTheme.typography.bodyLarge, color = colors.muted)
                Text("${detail.availableChapterCount} / ${detail.summary.chapterCount} chapters available", style = MaterialTheme.typography.labelLarge, color = colors.muted)
            }
        }
        detail.summary.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.muted) }
        if (detail.truncated || detail.missingChapterCount > 0) ReaderNotice("Some referenced chapters are not available on this device.", colors)
    }
}

@Composable
internal fun ReaderSettingsSheet(preferences: ReaderPreferences, onFontSizeChanged: (Float) -> Unit, onLineHeightChanged: (Float) -> Unit, onThemeChanged: (ReaderTheme) -> Unit, onParagraphAlignmentChanged: (ParagraphAlignment) -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Reader", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            BackCloseButton(onClick = onDismiss, close = true)
        }
        SettingHeader("Font size", "${preferences.fontSizeSp.roundToInt()}sp"); Slider(preferences.fontSizeSp, onFontSizeChanged, valueRange = 14f..28f, steps = 13)
        SettingHeader("Line height", "${preferences.lineHeightMultiplier.formatOneDecimal()}x"); Slider(preferences.lineHeightMultiplier, onLineHeightChanged, valueRange = 1.2f..2.0f, steps = 7)
        Text("Theme", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { ReaderTheme.entries.forEach { theme -> FilterChip(preferences.theme == theme, { onThemeChanged(theme) }, label = { Text(theme.label) }, modifier = Modifier.heightIn(min = 48.dp), colors = readerSettingChipColors()) } }
        Text("Text alignment", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { ParagraphAlignment.entries.forEach { alignment -> FilterChip(preferences.paragraphAlignment == alignment, { onParagraphAlignmentChanged(alignment) }, label = { Text(alignment.name) }, modifier = Modifier.heightIn(min = 48.dp), colors = readerSettingChipColors()) } }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun readerSettingChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.secondaryContainer,
    labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

@Composable private fun SettingHeader(label: String, value: String) { Row(verticalAlignment = Alignment.CenterVertically) { Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.weight(1f)); Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) } }
@Composable
internal fun ReaderContentsSheet(chapters: List<BookChapter>, currentChapterIndex: Int, colors: ReaderColors, onDismiss: () -> Unit, onChapterSelected: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Contents", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            BackCloseButton(onClick = onDismiss, close = true)
        }
        Text(if (chapters.size == 1) "1 chapter" else "${chapters.size} chapters", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (chapters.isEmpty()) Text("No chapters are available on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant) else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 18.dp)) { itemsIndexed(chapters, key = { _, chapter -> chapter.reference.coordinate }) { index, chapter -> ReaderContentsItem(chapter, index == currentChapterIndex, colors) { onChapterSelected(index) } } }
    }
}
@Composable private fun ReaderContentsItem(chapter: BookChapter, selected: Boolean, colors: ReaderColors, onClick: () -> Unit) { Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (selected) colors.track else Color.Transparent).clickable(enabled = chapter.available, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(chapter.title, style = MaterialTheme.typography.titleMedium, color = if (selected) colors.accent else colors.text, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis); if (!chapter.available) Text("Not saved on this device", style = MaterialTheme.typography.labelSmall, color = colors.muted); chapter.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) } } }

private val BookshelfTab.label: String get() = when (this) { BookshelfTab.Home -> "Home"; BookshelfTab.MyBooks -> "My Books"; BookshelfTab.Settings -> "Settings" }
private fun Float.formatOneDecimal(): String = ((this * 10f).roundToInt() / 10f).toString()
private val ReaderTheme.label: String get() = when (this) { ReaderTheme.Paper -> "Paper"; ReaderTheme.Sepia -> "Sepia"; ReaderTheme.Night -> "Night" }
