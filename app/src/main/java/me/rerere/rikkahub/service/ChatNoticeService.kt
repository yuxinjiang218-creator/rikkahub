package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.pages.debug.PerformanceDiagnosticsRecorder
import me.rerere.rikkahub.utils.cancelNotification
import me.rerere.rikkahub.utils.sendNotification
import kotlin.uuid.Uuid

class ChatNoticeService(
    private val context: Application,
    private val runtimeService: ConversationRuntimeService,
    private val diagnosticsRecorder: PerformanceDiagnosticsRecorder,
) {
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(error: Throwable, conversationId: Uuid? = null, title: String? = null) {
        if (error is CancellationException) return
        _errors.update { it + ChatError(title = title, error = error, conversationId = conversationId) }
    }

    fun addSuccessNotice(message: String, conversationId: Uuid? = null, title: String? = null) {
        _errors.update {
            it + ChatError(
                title = title,
                error = IllegalStateException(message),
                conversationId = conversationId,
                kind = ChatNoticeKind.SUCCESS,
            )
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    fun recordUiDiagnostic(
        category: String,
        conversationId: Uuid,
        detail: String,
        phase: String? = null,
    ) {
        diagnosticsRecorder.record(
            category = category,
            conversationId = conversationId,
            detail = detail,
            phase = phase,
        )
    }

    fun <T> traceDiagnostic(
        category: String,
        detail: String,
        conversationId: Uuid,
        phase: String? = null,
        sizeHint: String? = null,
        block: () -> T,
    ): T {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        return block().also {
            diagnosticsRecorder.record(
                category = category,
                detail = detail,
                conversationId = conversationId,
                costMs = elapsedMillisSince(startedAt),
                phase = phase,
                sizeHint = sizeHint,
            )
        }
    }

    fun recordConversationSave(conversationId: Uuid, conversation: Conversation, startedAtNs: Long) {
        diagnosticsRecorder.record(
            category = "save",
            detail = "messageNodes=${conversation.messageNodes.size}",
            conversationId = conversationId,
            costMs = elapsedMillisSince(startedAtNs),
            sizeHint = buildConversationSizeHint(conversation),
        )
    }

    fun recordConversationMetadataSave(conversationId: Uuid, conversation: Conversation, startedAtNs: Long) {
        diagnosticsRecorder.record(
            category = "metadata-save",
            detail = "messageNodes=${conversation.messageNodes.size}",
            conversationId = conversationId,
            costMs = elapsedMillisSince(startedAtNs),
            sizeHint = buildConversationSizeHint(conversation),
        )
    }

    fun buildConversationSizeHint(conversation: Conversation): String {
        val lastMessage = conversation.currentMessages.lastOrNull()
        return "messages=${conversation.currentMessages.size} lastParts=${lastMessage?.parts?.size ?: 0} textChars=${estimateMessageChars(lastMessage)}"
    }

    fun buildChunkSizeHint(messages: List<UIMessage>, startIndex: Int): String {
        val lastMessage = messages.lastOrNull()
        return "messages=${messages.size} startIndex=$startIndex lastParts=${lastMessage?.parts?.size ?: 0} textChars=${estimateMessageChars(lastMessage)}"
    }

    fun sendGenerationDoneNotification(conversationId: Uuid, senderName: String) {
        cancelLiveUpdateNotification(conversationId)

        val conversation = runtimeService.getConversationFlow(conversationId).value
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 1
        ) {
            title = senderName
            content = conversation.currentMessages.lastOrNull()?.toText()?.take(50)?.trim() ?: ""
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    fun sendLiveUpdateNotification(
        conversationId: Uuid,
        messages: List<UIMessage>,
        senderName: String,
    ) {
        val lastMessage = messages.lastOrNull() ?: return
        val parts = lastMessage.parts
        val (chipText, statusText, contentText) = determineNotificationContent(parts)

        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationId = getLiveUpdateNotificationId(conversationId)
        ) {
            title = senderName
            content = contentText
            subText = statusText
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            useBigTextStyle = true
            contentIntent = getPendingIntent(context, conversationId)
            requestPromotedOngoing = true
            shortCriticalText = chipText
        }
    }

    fun cancelLiveUpdateNotification(conversationId: Uuid) {
        context.cancelNotification(getLiveUpdateNotificationId(conversationId))
    }

    private fun getLiveUpdateNotificationId(conversationId: Uuid): Int {
        return conversationId.hashCode() + 10000
    }

    private fun determineNotificationContent(parts: List<UIMessagePart>): Triple<String, String, String> {
        val lastReasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
        val lastTool = parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
        val lastText = parts.filterIsInstance<UIMessagePart.Text>().lastOrNull()

        return when {
            lastTool != null && !lastTool.isExecuted -> {
                val toolName = lastTool.toolName.removePrefix("mcp__")
                Triple(
                    context.getString(R.string.notification_live_update_chip_tool),
                    context.getString(R.string.notification_live_update_tool, toolName),
                    lastTool.input.take(100)
                )
            }

            lastReasoning != null && lastReasoning.finishedAt == null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_thinking),
                    context.getString(R.string.notification_live_update_thinking),
                    lastReasoning.reasoning.takeLast(200)
                )
            }

            lastText != null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_writing),
                    lastText.text.takeLast(200)
                )
            }

            else -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_title),
                    ""
                )
            }
        }
    }

    private fun getPendingIntent(context: Context, conversationId: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun estimateMessageChars(message: UIMessage?): Int {
        return message?.parts?.sumOf(::estimatePartChars) ?: 0
    }

    private fun estimatePartChars(part: UIMessagePart): Int = when (part) {
        is UIMessagePart.Text -> part.text.length
        is UIMessagePart.Document -> part.fileName.length + part.url.length
        is UIMessagePart.Image -> part.url.length
        is UIMessagePart.Tool -> part.output.sumOf(::estimatePartChars)
        else -> part.toString().length
    }

    private fun elapsedMillisSince(startedAtNs: Long): Long {
        return ((SystemClock.elapsedRealtimeNanos() - startedAtNs) / 1_000_000L).coerceAtLeast(0L)
    }
}
