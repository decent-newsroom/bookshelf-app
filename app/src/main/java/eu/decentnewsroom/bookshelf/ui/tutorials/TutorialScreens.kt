package eu.decentnewsroom.bookshelf.ui.tutorials

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.decentnewsroom.bookshelf.R
import eu.decentnewsroom.bookshelf.ui.components.BackCloseButton
import kotlinx.coroutines.flow.collect

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
fun TutorialScreen(topic: TutorialTopic, onClose: () -> Unit, onAction: (TutorialDestination) -> Unit) {
    BackHandler(onBack = onClose)
    key(topic.id) {
        TutorialPager(topic, onClose, onAction)
    }
}

@Composable
private fun TutorialPager(topic: TutorialTopic, onClose: () -> Unit, onAction: (TutorialDestination) -> Unit) {
    val tutorial = remember(topic) { TutorialCatalog.get(topic) }
    var savedStepId by rememberSaveable { mutableStateOf(tutorial.steps.firstOrNull()?.id) }
    val initialPage = tutorial.steps.indexOfFirst { it.id == savedStepId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage) { tutorial.steps.size }
    val savedStepState = rememberSaveableStateHolder()
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            savedStepId = tutorial.steps.getOrNull(page)?.id
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = stringResource(topic.titleRes),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            BackCloseButton(onClick = onClose, close = true)
        }

        if (tutorial.hasContent) {
            HorizontalPager(
                state = pagerState,
                key = { tutorial.steps[it].id },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) { page ->
                val step = tutorial.steps[page]
                savedStepState.SaveableStateProvider(step.id) {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
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
                        Text(stringResource(step.bodyRes), style = MaterialTheme.typography.bodyLarge)
                        if (page == tutorial.steps.lastIndex) {
                            Button(
                                onClick = { onAction(topic.destination) },
                                enabled = !pagerState.isScrollInProgress,
                                modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(topic.actionLabelRes))
                            }
                        }
                    }
                }
            }
            val page = pagerState.currentPage
            val stepDescription = stringResource(R.string.tutorial_step_of, page + 1, tutorial.steps.size)
            Row(
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 16.dp)
                    .semantics(mergeDescendants = true) { contentDescription = stepDescription },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(tutorial.steps.size) { index ->
                    Box(
                        Modifier.size(if (index == page) 10.dp else 8.dp).background(
                            if (index == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape,
                        ),
                    )
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}
