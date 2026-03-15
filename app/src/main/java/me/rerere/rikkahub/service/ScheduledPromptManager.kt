package me.rerere.rikkahub.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Application
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.ScheduledPromptTask
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid

private const val TAG = "ScheduledPromptManager"

class ScheduledPromptManager(
    context: Application,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(context)
    private val alarmManager = checkNotNull(context.getSystemService(AlarmManager::class.java)) {
        "AlarmManager is not available"
    }
    private val started = AtomicBoolean(false)
    private var lastTaskIds: Set<Uuid> = emptySet()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        appScope.launch {
            settingsStore.settingsFlowRaw.collectLatest { settings ->
                runCatching {
                    reconcile(settings)
                }.onFailure {
                    Log.e(TAG, "Failed to reconcile scheduled prompt tasks", it)
                }
            }
        }
    }

    suspend fun reconcileCurrentSettings() {
        reconcile(settingsStore.settingsFlowRaw.first())
    }

    suspend fun reconcile(settings: Settings) {
        val enabledTasks = settings.scheduledTasks.filter { it.enabled && it.prompt.isNotBlank() }
        val expectedTaskIds = enabledTasks.map { it.id }.toSet()

        cancelStaleSchedules(lastTaskIds - expectedTaskIds)

        val now = ZonedDateTime.now()
        enabledTasks.forEach { task ->
            cancelLegacyPeriodicWork(task.id)
            scheduleNextAlarm(task, now)
            if (ScheduledPromptTime.shouldRunCatchUp(task, now) && !hasPendingTriggeredWork(task.id)) {
                enqueueRun(
                    taskId = task.id,
                    workName = catchUpWorkName(task.id)
                )
            }
        }
        lastTaskIds = expectedTaskIds
    }

    suspend fun onAlarmTriggered(taskId: Uuid) {
        val settings = settingsStore.settingsFlowRaw.first()
        val task = settings.scheduledTasks.firstOrNull { it.id == taskId } ?: run {
            cancelTaskSchedule(taskId)
            return
        }
        if (!task.enabled || task.prompt.isBlank()) {
            cancelTaskSchedule(taskId)
            return
        }

        scheduleNextAlarm(task)
        enqueueRun(task.id, triggeredWorkName(task.id))
    }

    private fun enqueueRun(taskId: Uuid, workName: String) {
        val request = OneTimeWorkRequestBuilder<ScheduledPromptWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10L, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(scheduledPromptInputData(taskId))
            .addTag(SCHEDULED_PROMPT_WORK_TAG)
            .addTag(taskIdTag(taskId))
            .build()

        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
    }

    private fun cancelStaleSchedules(staleTaskIds: Set<Uuid>) {
        staleTaskIds.forEach(::cancelTaskSchedule)
    }

    private fun cancelTaskSchedule(taskId: Uuid) {
        alarmManager.cancel(scheduledPromptAlarmPendingIntent(appContext, taskId))
        cancelLegacyPeriodicWork(taskId)
        workManager.cancelAllWorkByTag(taskIdTag(taskId))
    }

    private fun cancelLegacyPeriodicWork(taskId: Uuid) {
        workManager.cancelUniqueWork(legacyPeriodicWorkName(taskId))
    }

    private suspend fun hasPendingTriggeredWork(taskId: Uuid): Boolean {
        return runCatching {
            withContext(Dispatchers.IO) {
                workManager.getWorkInfosForUniqueWork(triggeredWorkName(taskId)).get()
            }.any { !it.state.isFinished }
        }.getOrDefault(false)
    }

    @SuppressLint("ScheduleExactAlarm")
    private fun scheduleNextAlarm(
        task: ScheduledPromptTask,
        now: ZonedDateTime = ZonedDateTime.now()
    ) {
        val triggerAtMillis = ScheduledPromptTime.nextTriggerAt(task, now).toInstant().toEpochMilli()
        val pendingIntent = scheduledPromptAlarmPendingIntent(appContext, task.id)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.w(TAG, "Exact alarm denied for ${task.id}, fallback to inexact alarm")
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            return
        }
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    }
}
