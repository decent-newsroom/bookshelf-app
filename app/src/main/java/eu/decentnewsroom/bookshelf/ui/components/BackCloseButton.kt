package eu.decentnewsroom.bookshelf.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.decentnewsroom.bookshelf.R

/** Shared themed navigation control; the owner supplies the same action as system Back. */
@Composable
fun BackCloseButton(onClick: () -> Unit, modifier: Modifier = Modifier, close: Boolean = false) {
    FilledTonalIconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        Icon(
            imageVector = if (close) Icons.Outlined.Close else Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = stringResource(if (close) R.string.navigation_close else R.string.navigation_back),
        )
    }
}
