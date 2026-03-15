package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.SCHEDULED_TASK_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.ScheduledPromptTask
import me.rerere.rikkahub.data.model.ScheduledTaskRun
import me.rerere.rikkahub.data.model.TaskRunStatus
import me.rerere.rikkahub.data.repository.ScheduledTaskRunRepository
import me.rerere.rikkahub.utils.sendNotification
import kotlin.uuid.Uuid

private const val TAG = "ScheduledPromptWorker"

class ScheduledPromptWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
    private val scheduledTaskRunRepository: ScheduledTaskRunRepository,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(INPUT_TASK_ID)?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: return Result.failure()

        val settings = settingsStore.settingsFlowRaw.first()
        val task = settings.scheduledTasks.firstOrNull { it.id == taskId } ?: return Result.success()
        if (!task.enabled || task.prompt.isBlank()) return Result.success()

        val runId = Uuid.random()
        val startedAt = System.currentTimeMillis()
        scheduledTaskRunRepository.insertRun(
            ScheduledTaskRun(
                id = runId,
                taskId = task.id,
                taskTitleSnapshot = taskDisplayTitle(task),
                assistantIdSnapshot = task.assistantId,
                status = TaskRunStatus.RUNNING,
                startedAt = startedAt,
                promptSnapshot = task.prompt
            )
        )
        updateTask(taskId) {
            it.copy(lastStatus = TaskRunStatus.RUNNING, lastError = "", lastRunId = runId)
        }

        val executionResult = chatService.executeScheduledTask(task)
        val finishedAt = System.currentTimeMillis()
        val duration = (finishedAt - startedAt).coerceAtLeast(0L)

        return executionResult.fold(
            onSuccess = { replyPreview ->
                updateTask(taskId) {
                    it.copy(
                        lastStatus = TaskRunStatus.SUCCESS,
                        lastRunAt = startedAt,
                        lastError = "",
                        lastRunId = runId
                    )
                }
                scheduledTaskRunRepository.updateRun(
                    ScheduledTaskRun(
                        id = runId,
                        taskId = task.id,
                        taskTitleSnapshot = taskDisplayTitle(task),
                        assistantIdSnapshot = task.assistantId,
                        status = TaskRunStatus.SUCCESS,
                        startedAt = startedAt,
                        finishedAt = finishedAt,
                        durationMs = duration,
                        promptSnapshot = task.prompt,
                        resultText = replyPreview.replyText,
                        errorText = "",
                        modelIdSnapshot = replyPreview.modelId,
                        providerNameSnapshot = replyPreview.providerName,
                    )
                )
                scheduledTaskRunRepository.pruneTaskRuns(task.id, keep = 50)
                maybeNotifySuccess(task, replyPreview.replyPreview, runId)
                Result.success()
            },
            onFailure = { error ->
                Log.e(TAG, "Scheduled task execution failed: ${task.id}", error)
                updateTask(taskId) {
                    it.copy(
                        lastStatus = TaskRunStatus.FAILED,
                        lastRunAt = startedAt,
                        lastError = error.message.orEmpty().take(200),
                        lastRunId = runId
                    )
                }
                scheduledTaskRunRepository.updateRun(
                    ScheduledTaskRun(
                        id = runId,
                        taskId = task.id,
                        taskTitleSnapshot = taskDisplayTitle(task),
                        assistantIdSnapshot = task.assistantId,
                        status = TaskRunStatus.FAILED,
                        startedAt = startedAt,
                        finishedAt = finishedAt,
                        durationMs = duration,
                        promptSnapshot = task.prompt,
                        resultText = "",
                        errorText = error.stackTraceToString().take(8_000),
                        modelIdSnapshot = null,
                        providerNameSnapshot = "",
                    )
                )
                scheduledTaskRunRepository.pruneTaskRuns(task.id, keep = 50)
                maybeNotifyFailure(task, error, runId)
                Result.retry()
            }
        )
    }

    private suspend fun updateTask(
        taskId: Uuid,
        transform: (ScheduledPromptTask) -> ScheduledPromptTask
    ) {
        settingsStore.update { settings ->
            settings.copy(
                scheduledTasks = settings.scheduledTasks.map { task ->
                    if (task.id == taskId) transform(task) else task
                }
            )
        }
    }

    private fun maybeNotifySuccess(task: ScheduledPromptTask, replyPreview: String?, runId: Uuid) {
        applicationContext.sendNotification(
            channelId = SCHEDULED_TASK_NOTIFICATION_CHANNEL_ID,
            notificationId = notificationId(task.id)
        ) {
            title = "定时任务已完成: ${taskDisplayTitle(task)}"
            content = replyPreview?.ifBlank { "任务已成功完成" } ?: "任务已成功完成"
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_REMINDER
            contentIntent = getPendingIntent(runId)
        }
    }

    private fun maybeNotifyFailure(task: ScheduledPromptTask, error: Throwable, runId: Uuid) {
        applicationContext.sendNotification(
            channelId = SCHEDULED_TASK_NOTIFICATION_CHANNEL_ID,
            notificationId = notificationId(task.id)
        ) {
            title = "定时任务失败: ${taskDisplayTitle(task)}"
            content = error.message.orEmpty().ifBlank { "任务执行失败，请查看运行记录" }
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_ERROR
            contentIntent = getPendingIntent(runId)
        }
    }

    private fun getPendingIntent(runId: Uuid): PendingIntent {
        val intent = Intent(applicationContext, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("scheduledTaskRunId", runId.toString())
        }
        return PendingIntent.getActivity(
            applicationContext,
            runId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun notificationId(taskId: Uuid): Int = taskId.hashCode() + 20_000

    private fun taskDisplayTitle(task: ScheduledPromptTask): String {
        return task.title.ifBlank {
            task.prompt.lineSequence().firstOrNull().orEmpty().take(24).ifBlank { "未命名任务" }
        }
    }
}
