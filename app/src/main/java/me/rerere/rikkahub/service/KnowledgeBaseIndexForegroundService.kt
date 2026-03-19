package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.rerere.rikkahub.KNOWLEDGE_BASE_INDEX_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.model.KnowledgeBaseIndexState
import org.koin.android.ext.android.inject

class KnowledgeBaseIndexForegroundService : Service() {
    companion object {
        const val ACTION_START = "me.rerere.rikkahub.action.KNOWLEDGE_BASE_INDEX_START"
        const val ACTION_CANCEL_DOCUMENT = "me.rerere.rikkahub.action.KNOWLEDGE_BASE_INDEX_CANCEL_DOCUMENT"
        const val EXTRA_DOCUMENT_ID = "knowledge_base_document_id"
        const val NOTIFICATION_ID = 2003

        fun start(context: Context) {
            val intent = Intent(context, KnowledgeBaseIndexForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelDocument(context: Context, documentId: Long) {
            val intent = Intent(context, KnowledgeBaseIndexForegroundService::class.java).apply {
                action = ACTION_CANCEL_DOCUMENT
                putExtra(EXTRA_DOCUMENT_ID, documentId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val knowledgeBaseService: KnowledgeBaseService by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var processingJob: Job? = null
    private var notificationJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_DOCUMENT -> {
                val documentId = intent.getLongExtra(EXTRA_DOCUMENT_ID, -1L)
                if (documentId > 0L && knowledgeBaseService.indexState.value.currentDocumentId == documentId) {
                    processingJob?.cancel(CancellationException("Knowledge base document deleted"))
                }
                if (processingJob == null) {
                    startProcessingIfNeeded()
                }
            }

            else -> startProcessingIfNeeded()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun startProcessingIfNeeded() {
        if (processingJob != null) return
        startForegroundCompat(buildNotification(knowledgeBaseService.indexState.value))
        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            knowledgeBaseService.indexState.collectLatest { state ->
                androidx.core.app.NotificationManagerCompat.from(this@KnowledgeBaseIndexForegroundService)
                    .notify(NOTIFICATION_ID, buildNotification(state))
            }
        }
        processingJob = serviceScope.launch {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                try {
                    knowledgeBaseService.runIndexQueueLoop()
                } catch (_: CancellationException) {
                }
            }
        }.apply {
            invokeOnCompletion {
                serviceScope.launch {
                processingJob = null
                knowledgeBaseService.refreshIndexState()
                if (knowledgeBaseService.indexState.value.queuedCount > 0) {
                    startProcessingIfNeeded()
                } else {
                    notificationJob?.cancel()
                    notificationJob = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            }
        }
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(state: KnowledgeBaseIndexState): android.app.Notification {
        val openIntent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val contentText = when {
            state.isRunning && state.currentDocumentName.isNotBlank() -> {
                buildString {
                    append("正在索引：")
                    append(state.currentDocumentName)
                    if (state.progressLabel.isNotBlank()) {
                        append(" · ")
                        append(state.progressLabel)
                        append(' ')
                        append(state.progressCurrent)
                        if (state.progressTotal > 0) {
                            append('/')
                            append(state.progressTotal)
                        }
                    }
                    if (state.queuedCount > 0) {
                        append(" · 队列剩余 ")
                        append(state.queuedCount)
                    }
                }
            }

            state.queuedCount > 0 -> "知识库索引排队中，剩余 ${state.queuedCount} 个文档"
            else -> "知识库索引服务正在运行"
        }

        return NotificationCompat.Builder(this, KNOWLEDGE_BASE_INDEX_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle("知识库索引")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openPendingIntent)
            .setProgress(
                state.progressTotal.takeIf { it > 0 } ?: 0,
                state.progressCurrent.coerceAtLeast(0),
                state.isRunning && state.progressTotal <= 0
            )
            .build()
    }
}
