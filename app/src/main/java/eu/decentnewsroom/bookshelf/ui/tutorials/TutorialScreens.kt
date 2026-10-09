package eu.decentnewsroom.bookshelf.ui.tutorials

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.decentnewsroom.bookshelf.R

@Composable
fun TutorialsScreen(onOpenTopic: (TutorialTopic) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.tutorial_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        TutorialCatalog.topics.forEach { topic ->
            Card(
                onClick = { onOpenTopic(topic) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                colors = CardDefaults.cardColors(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(topic.titleRes),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            text = stringResource(topic.summaryRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                }
            }
        }
    }
}

@Composable
fun TutorialScreen(topic: TutorialTopic, onClose: () -> Unit) {
    BackHandler(onBack = onClose)

    val tutorial = remember(topic) { TutorialCatalog.get(topic) }
    var savedStepId by rememberSaveable(topic.id) {
        mutableStateOf(tutorial.steps.firstOrNull()?.id)
    }
    val stepIndex = tutorial.steps.indexOfFirst { it.id == savedStepId }
        .takeIf { it >= 0 }
        ?: 0
    val step = tutorial.steps.getOrNull(stepIndex)
    val savedStepState = rememberSaveableStateHolder()

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(topic.titleRes),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            TextButton(
                onClick = onClose,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.tutorial_close))
            }
        }

        if (step != null) {
            Column(Modifier.weight(1f).fillMaxWidth()) {
                savedStepState.SaveableStateProvider(step.id) {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(step.titleRes),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() },
                        )
                        step.illustrationRes?.let { illustration ->
                            Image(
                                painter = painterResource(illustration),
                                contentDescription = step.descriptionRes?.let { stringResource(it) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text(
                            text = stringResource(step.bodyRes),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.tutorial_step_of, stepIndex + 1, tutorial.steps.size),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp),
            )
            TutorialStepActions(
                isFirst = stepIndex == 0,
                isLast = stepIndex == tutorial.steps.lastIndex,
                onPrevious = { savedStepId = tutorial.steps[(stepIndex - 1).coerceAtLeast(0)].id },
                onNext = { savedStepId = tutorial.steps[(stepIndex + 1).coerceAtMost(tutorial.steps.lastIndex)].id },
                onDone = onClose,
            )
        } else {
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.tutorial_done))
            }
        }
    }
}

@Composable
private fun TutorialStepActions(
    isFirst: Boolean,
    isLast: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDone: () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton(
            onClick = onPrevious,
            enabled = !isFirst,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Icon(Icons.Outlined.ChevronLeft, contentDescription = null)
            Text(stringResource(R.string.tutorial_previous))
        }
        if (isLast) {
            Button(
                onClick = onDone,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.tutorial_done))
            }
        } else {
            Button(
                onClick = onNext,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.tutorial_next))
                Icon(Icons.Outlined.ChevronRight, contentDescription = null)
            }
        }
    }
}
