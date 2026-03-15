package me.rerere.rikkahub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "ScheduledPromptAlarmReceiver"

class ScheduledPromptAlarmReceiver : BroadcastReceiver(), KoinComponent {
    private val appScope: AppScope by inject()
    private val scheduledPromptManager: ScheduledPromptManager by inject()

    override fun onReceive(context: Context?, intent: Intent?) {
        val taskId = parseTaskIdFromAlarmIntent(intent) ?: return
        val pendingResult = goAsync()
        appScope.launch {
            try {
                scheduledPromptManager.onAlarmTriggered(taskId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle scheduled alarm for task: $taskId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
