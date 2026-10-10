@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.decentnewsroom.bookshelf.ui.onboarding

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import eu.decentnewsroom.bookshelf.ui.components.SecondaryButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private const val OnboardingTooltipDurationMillis = 10_000L

@Composable
fun OnboardingTooltip(visible: Boolean, text: String, onDismissed: () -> Unit, content: @Composable () -> Unit) {
    if (!visible) {
        content()
        return
    }
    val state = rememberTooltipState(isPersistent = true)
    val coroutineScope = rememberCoroutineScope()
    val latestOnDismissed by rememberUpdatedState(onDismissed)
    LaunchedEffect(visible) {
        if (visible) {
            val autoDismiss = launch {
                delay(OnboardingTooltipDurationMillis.milliseconds)
                latestOnDismissed()
                state.dismiss()
            }
            try {
                state.show()
            } finally {
                autoDismiss.cancel()
                // Persistent TooltipState.show() can remain suspended after dismiss().
                // Also consume the tip if its anchor leaves composition early.
                latestOnDismissed()
            }
        }
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(positioning = TooltipAnchorPosition.Above, spacingBetweenTooltipAndAnchor = 16.dp),
        tooltip = {
            PlainTooltip {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(text)
                    SecondaryButton(
                        onClick = {
                            latestOnDismissed()
                            coroutineScope.launch { state.dismiss() }
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Got it")
                    }
                }
            }
        },
        state = state, focusable = false, enableUserInput = false, content = content,
    )
}
