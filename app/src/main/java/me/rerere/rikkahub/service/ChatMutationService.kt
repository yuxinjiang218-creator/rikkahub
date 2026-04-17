package me.rerere.rikkahub.service

import android.app.Application
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.sandbox.SandboxEngine
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import kotlin.uuid.Uuid

private const val TAG = "ChatMutationService"

class ChatMutationService(
    private val context: Application,
    private val appScope: me.rerere.rikkahub.AppScope,
    private val settingsStore: SettingsStore,
    private val generationHandler: GenerationHandler,
    private val filesManager: FilesManager,
    private val runtimeService: ConversationRuntimeService,
    private val persistenceService: ConversationPersistenceService,
    private val noticeService: ChatNoticeService,
) {
    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: java.util.Locale,
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()
                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                updateTranslationField(conversationId, message.id, context.getString(R.string.translating))
                generationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { }

                saveConversation(conversationId, runtimeService.getConversationFlow(conversationId).value)
            } catch (error: Exception) {
                clearTranslationField(conversationId, message.id)
                noticeService.addError(
                    error,
                    conversationId,
                    title = context.getString(R.string.error_title_translate_message)
                )
            }
        }
    }

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>,
    ) {
        if (parts.isEmptyInputMessage()) return

        val currentConversation = runtimeService.getConversationFlow(conversationId).value
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.role,
                    parts = parts,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return
        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid,
    ): Conversation {
        val currentConversation = runtimeService.getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val forkConversationId = Uuid.random()
        val forkConversation = Conversation(
            id = forkConversationId,
            assistantId = currentConversation.assistantId,
            messageNodes = copiedNodes,
        )

        if (!SandboxEngine.copySandbox(context, conversationId.toString(), forkConversationId.toString())) {
            Log.w(TAG, "forkConversationAtMessage: failed to copy sandbox from $conversationId to $forkConversationId")
        }

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int,
    ) {
        val currentConversation = runtimeService.getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }
        if (targetNode.selectIndex == selectIndex) return

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) node.copy(selectIndex = selectIndex) else node
        }

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = runtimeService.getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(conversationId: Uuid, message: UIMessage) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = runtimeService.getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) msg.copy(translation = null) else msg
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        runtimeService.updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String,
    ) {
        val currentConversation = runtimeService.getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) msg.copy(translation = translationText) else msg
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        runtimeService.updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    private suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val startedAt = android.os.SystemClock.elapsedRealtimeNanos()
        persistenceService.saveConversation(conversationId, conversation)
        noticeService.recordConversationSave(conversationId, conversation, startedAt)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }
}
