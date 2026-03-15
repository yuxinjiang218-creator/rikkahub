package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Data
import kotlin.uuid.Uuid

internal const val SCHEDULED_PROMPT_WORK_TAG = "scheduled_prompt"
internal const val SCHEDULED_PROMPT_ALARM_ACTION = "me.rerere.rikkahub.action.SCHEDULED_PROMPT_ALARM"
private const val TASK_ID_TAG_PREFIX = "scheduled_prompt_task_id:"
private const val EXTRA_ALARM_TASK_ID = "scheduled_prompt_alarm_task_id"
internal const val INPUT_TASK_ID = "task_id"

internal fun catchUpWorkName(taskId: Uuid): String = "scheduled_prompt_catchup_$taskId"

internal fun triggeredWorkName(taskId: Uuid): String = "scheduled_prompt_triggered_$taskId"

internal fun legacyPeriodicWorkName(taskId: Uuid): String = "scheduled_prompt_periodic_$taskId"

internal fun taskIdTag(taskId: Uuid): String = "$TASK_ID_TAG_PREFIX$taskId"

internal fun scheduledPromptAlarmPendingIntent(
    context: Context,
    taskId: Uuid
): PendingIntent {
    val intent = Intent(context, ScheduledPromptAlarmReceiver::class.java).apply {
        action = SCHEDULED_PROMPT_ALARM_ACTION
        putExtra(EXTRA_ALARM_TASK_ID, taskId.toString())
    }
    return PendingIntent.getBroadcast(
        context,
        taskId.hashCode(),
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

internal fun parseTaskIdFromAlarmIntent(intent: Intent?): Uuid? {
    if (intent?.action != SCHEDULED_PROMPT_ALARM_ACTION) return null
    val rawTaskId = intent.getStringExtra(EXTRA_ALARM_TASK_ID) ?: return null
    return runCatching { Uuid.parse(rawTaskId) }.getOrNull()
}

internal fun scheduledPromptInputData(taskId: Uuid): Data {
    return Data.Builder()
        .putString(INPUT_TASK_ID, taskId.toString())
        .build()
}
