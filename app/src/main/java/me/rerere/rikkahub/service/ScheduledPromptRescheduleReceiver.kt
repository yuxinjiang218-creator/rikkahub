package me.rerere.rikkahub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "ScheduledPromptRescheduleReceiver"
private val RESCHEDULE_ACTIONS = setOf(
    Intent.ACTION_BOOT_COMPLETED,
    Intent.ACTION_MY_PACKAGE_REPLACED,
    Intent.ACTION_TIME_CHANGED,
    Intent.ACTION_TIMEZONE_CHANGED,
)

class ScheduledPromptRescheduleReceiver : BroadcastReceiver(), KoinComponent {
    private val appScope: AppScope by inject()
    private val scheduledPromptManager: ScheduledPromptManager by inject()

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in RESCHEDULE_ACTIONS) return

        val pendingResult = goAsync()
        appScope.launch {
            try {
                scheduledPromptManager.reconcileCurrentSettings()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reconcile after broadcast: $action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
