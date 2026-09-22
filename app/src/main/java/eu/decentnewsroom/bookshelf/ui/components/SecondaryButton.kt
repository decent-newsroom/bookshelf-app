package eu.decentnewsroom.bookshelf.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A lower-emphasis action with a visible surface, so its touch target aligns visually with
 * neighboring controls.
 */
@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = TextButtonDefaults.textButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        content = content,
    )
}
