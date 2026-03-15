package me.rerere.rikkahub.ui.pages.scheduled

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.ScheduledTaskRun
import me.rerere.rikkahub.data.model.TaskRunStatus
import me.rerere.rikkahub.data.repository.ScheduledTaskRunRepository
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.compose.koinInject
import java.time.Instant
import kotlin.uuid.Uuid

@Composable
fun ScheduledTaskRunDetailPage(id: String) {
    val repository: ScheduledTaskRunRepository = koinInject()
    val runId = remember(id) { runCatching { Uuid.parse(id) }.getOrNull() }
    val runFlow = remember(runId) { runId?.let { repository.getRunFlow(it) } }
    val run by runFlow?.collectAsStateWithLifecycle(initialValue = null)
        ?: androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.scheduled_task_run_detail_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        when {
            runId == null -> {
                Text(
                    text = stringResource(R.string.scheduled_task_run_invalid_id),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp),
                    color = MaterialTheme.colorScheme.error
                )
            }

            run == null -> {
                Text(
                    text = stringResource(R.string.scheduled_task_run_not_found),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> {
                ScheduledTaskRunDetailContent(
                    run = run!!,
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}

@Composable
private fun ScheduledTaskRunDetailContent(
    run: ScheduledTaskRun,
    modifier: Modifier = Modifier
) {
    val settings = LocalSettings.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                ListItem(
                    headlineContent = {
                        Text(
                            text = run.taskTitleSnapshot.ifBlank { stringResource(R.string.assistant_schedule_untitled) },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    supportingContent = {
                        val started = Instant.ofEpochMilli(run.startedAt).toLocalDateTime()
                        Text("$started · ${run.status.shortLabel()}")
                    },
                    trailingContent = {
                        Text(
                            text = run.status.shortLabel(),
                            color = run.status.color(),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                )
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                ColumnListItem(stringResource(R.string.scheduled_task_run_started), Instant.ofEpochMilli(run.startedAt).toLocalDateTime())
                if (run.finishedAt > 0) {
                    ColumnListItem(stringResource(R.string.scheduled_task_run_finished), Instant.ofEpochMilli(run.finishedAt).toLocalDateTime())
                }
                if (run.durationMs > 0) {
                    ColumnListItem(
                        stringResource(R.string.scheduled_task_run_duration),
                        stringResource(R.string.scheduled_task_run_duration_ms, run.durationMs)
                    )
                }
                if (run.providerNameSnapshot.isNotBlank()) {
                    ColumnListItem(stringResource(R.string.scheduled_task_run_provider), run.providerNameSnapshot)
                }
                run.modelIdSnapshot?.let { modelId ->
                    val modelDisplayName = settings.findModelById(modelId)?.let { model ->
                        model.displayName.ifBlank { model.modelId.ifBlank { model.id.toString() } }
                    } ?: modelId.toString()
                    ColumnListItem(stringResource(R.string.setting_scheduled_tasks_model), modelDisplayName)
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.scheduled_task_run_prompt_snapshot)) },
                    supportingContent = {
                        SelectionContainer {
                            Text(
                                text = run.promptSnapshot.ifBlank { stringResource(R.string.scheduled_task_run_empty) },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                )
            }
        }

        item {
            val contentTitle = if (run.status == TaskRunStatus.SUCCESS) {
                stringResource(R.string.scheduled_task_run_result_snapshot)
            } else {
                stringResource(R.string.scheduled_task_run_error_snapshot)
            }
            val contentText = if (run.status == TaskRunStatus.SUCCESS) {
                run.resultText.ifBlank { stringResource(R.string.scheduled_task_run_empty) }
            } else {
                run.errorText.ifBlank { stringResource(R.string.scheduled_task_run_empty) }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                ListItem(
                    headlineContent = { Text(contentTitle) },
                    supportingContent = {
                        if (run.status == TaskRunStatus.SUCCESS) {
                            SelectionContainer {
                                MarkdownBlock(
                                    content = contentText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        } else {
                            SelectionContainer {
                                Text(text = contentText, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ColumnListItem(title: String, value: String) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) }
    )
}

@Composable
private fun TaskRunStatus.color() = when (this) {
    TaskRunStatus.SUCCESS -> MaterialTheme.colorScheme.primary
    TaskRunStatus.FAILED -> MaterialTheme.colorScheme.error
    TaskRunStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
    TaskRunStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun TaskRunStatus.shortLabel(): String = when (this) {
    TaskRunStatus.SUCCESS -> stringResource(R.string.assistant_schedule_status_success)
    TaskRunStatus.FAILED -> stringResource(R.string.assistant_schedule_status_failed)
    TaskRunStatus.RUNNING -> stringResource(R.string.assistant_schedule_status_running)
    TaskRunStatus.IDLE -> stringResource(R.string.assistant_schedule_status_idle)
}