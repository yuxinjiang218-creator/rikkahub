package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.runtime.Immutable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.CompressionEvent
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.WorkflowPhase
import me.rerere.rikkahub.data.model.compressionEventOrder
import me.rerere.rikkahub.service.CompressionUiState
import me.rerere.rikkahub.service.StreamingTailState
import me.rerere.rikkahub.ui.components.message.ChatMessageRenderModel
import kotlin.uuid.Uuid

@Immutable
data class ChatChromeUiState(
    val title: String = "",
    val subtitle: String? = null,
    val hasMessages: Boolean = false,
    val workflowEnabled: Boolean = false,
    val workflowActive: Boolean = false,
    val workflowPhase: WorkflowPhase? = null,
)

@Immutable
data class ChatInputUiState(
    val loading: Boolean = false,
    val messageCount: Int = 0,
    val currentChatModel: Model? = null,
    val enableWebSearch: Boolean = false,
    val workflowEnabled: Boolean = false,
    val workflowActive: Boolean = false,
    val compressionUiState: CompressionUiState? = null,
    val showLedgerGenerationDialog: Boolean = false,
)

@Immutable
data class ChatTimelineUiState(
    val conversationId: Uuid? = null,
    val conversationTitle: String = "",
    val messageItems: List<ChatMessageItemModel> = emptyList(),
    val compressionItems: List<ChatCompressionBoundaryItem> = emptyList(),
    val chatSuggestions: List<String> = emptyList(),
    val totalStableCount: Int = 0,
    val latestCompressionEventId: Long? = null,
    val lastAssistantInputTokens: Int = 0,
)

@Immutable
data class ChatPreviewUiState(
    val messages: List<ChatPreviewMessageItem> = emptyList(),
    val searchResults: List<ConversationPreviewSearchResult> = emptyList(),
)

@Immutable
data class ChatStreamingTailUiState(
    val item: ChatMessageItemModel? = null,
)

@Immutable
data class ChatMessageItemModel(
    val renderModel: ChatMessageRenderModel,
    val globalIndex: Int,
    val isLastMessage: Boolean,
) {
    val node: MessageNode
        get() = renderModel.node

    val message: UIMessage
        get() = renderModel.message

    val model: Model?
        get() = renderModel.model

    val assistant: Assistant?
        get() = renderModel.assistant
}

@Immutable
data class ChatCompressionBoundaryModel(
    val event: CompressionEvent,
    val isLatest: Boolean,
    val ledgerStatus: String?,
    val ledgerError: String?,
)

@Immutable
data class ChatCompressionBoundaryItem(
    val boundaryIndex: Int,
    val model: ChatCompressionBoundaryModel,
)

@Immutable
data class ChatPreviewMessageItem(
    val nodeId: Uuid,
    val message: UIMessage,
    val globalIndex: Int,
)

internal fun buildChatChromeUiState(
    conversation: Conversation,
    settings: Settings,
    workflowEnabled: Boolean,
    defaultAssistantLabel: String,
): ChatChromeUiState {
    val assistant = settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()
    val model = assistant.chatModelId?.let(settings::findModelById) ?: settings.getCurrentChatModel()
    val provider = model?.findProvider(providers = settings.providers, checkOverwrite = false)
    val subtitle = if (model != null && provider != null) {
        "${assistant.name.ifBlank { defaultAssistantLabel }} / ${model.displayName} (${provider.name})"
    } else {
        null
    }

    return ChatChromeUiState(
        title = conversation.title,
        subtitle = subtitle,
        hasMessages = conversation.messageNodes.isNotEmpty(),
        workflowEnabled = workflowEnabled,
        workflowActive = conversation.workflowState != null,
        workflowPhase = conversation.workflowState?.phase,
    )
}

internal fun buildChatInputUiState(
    conversation: Conversation,
    workflowEnabled: Boolean,
    currentChatModel: Model?,
    loading: Boolean,
    enableWebSearch: Boolean,
    compressionUiState: CompressionUiState?,
    showLedgerGenerationDialog: Boolean,
): ChatInputUiState {
    return ChatInputUiState(
        loading = loading,
        messageCount = conversation.messageNodes.size,
        currentChatModel = currentChatModel,
        enableWebSearch = enableWebSearch,
        workflowEnabled = workflowEnabled,
        workflowActive = conversation.workflowState != null,
        compressionUiState = compressionUiState,
        showLedgerGenerationDialog = showLedgerGenerationDialog,
    )
}

internal fun buildChatTimelineUiState(
    conversation: Conversation,
    settings: Settings,
    stableNodes: List<MessageNode>,
): ChatTimelineUiState {
    val displayedNodes = stableNodes
    val normalizedCompressionEvents = localizeCompressionEvents(
        events = conversation.compressionEvents,
        totalStableCount = displayedNodes.size,
    ).sortedWith(compressionEventOrder)
    val latestCompressionEventId = normalizedCompressionEvents.lastOrNull()?.id
    val assistant = settings.getAssistantById(conversation.assistantId)
    val lastAssistantInputTokens = conversation.messageNodes
        .asReversed()
        .firstOrNull { it.currentMessage.role == MessageRole.ASSISTANT }
        ?.currentMessage
        ?.usage
        ?.promptTokens
        ?: 0

    return ChatTimelineUiState(
        conversationId = conversation.id,
        conversationTitle = conversation.title,
        messageItems = displayedNodes.mapIndexed { index, node ->
            ChatMessageItemModel(
                renderModel = ChatMessageRenderModel(
                    node = node,
                    message = node.currentMessage,
                    model = node.currentMessage.modelId?.let(settings::findModelById),
                    assistant = assistant,
                ),
                globalIndex = index,
                isLastMessage = index == displayedNodes.lastIndex,
            )
        },
        compressionItems = normalizedCompressionEvents.map { event ->
            ChatCompressionBoundaryItem(
                boundaryIndex = event.boundaryIndex,
                model = ChatCompressionBoundaryModel(
                    event = event,
                    isLatest = event.id == latestCompressionEventId,
                    ledgerStatus = if (event.id == latestCompressionEventId) {
                        conversation.compressionState.memoryLedgerStatus
                    } else {
                        null
                    },
                    ledgerError = if (event.id == latestCompressionEventId) {
                        conversation.compressionState.memoryLedgerError
                    } else {
                        null
                    },
                ),
            )
        },
        chatSuggestions = conversation.chatSuggestions,
        totalStableCount = displayedNodes.size,
        latestCompressionEventId = latestCompressionEventId,
        lastAssistantInputTokens = lastAssistantInputTokens,
    )
}

internal fun buildChatPreviewUiState(
    stableNodes: List<MessageNode>,
    previewSearchResults: List<ConversationPreviewSearchResult>,
): ChatPreviewUiState {
    return ChatPreviewUiState(
        messages = stableNodes.mapIndexed { index, node ->
            ChatPreviewMessageItem(
                nodeId = node.id,
                message = node.currentMessage,
                globalIndex = index,
            )
        },
        searchResults = previewSearchResults,
    )
}

internal fun buildChatStreamingTailUiState(
    conversation: Conversation,
    settings: Settings,
    stableNodeCount: Int,
    streamingTail: StreamingTailState?,
): ChatStreamingTailUiState {
    if (streamingTail == null) {
        return ChatStreamingTailUiState()
    }
    if (streamingTail.index != stableNodeCount) {
        return ChatStreamingTailUiState()
    }

    val assistant = settings.getAssistantById(conversation.assistantId)
    return ChatStreamingTailUiState(
        item = ChatMessageItemModel(
            renderModel = ChatMessageRenderModel(
                node = streamingTail.node,
                message = streamingTail.node.currentMessage,
                model = streamingTail.node.currentMessage.modelId?.let(settings::findModelById),
                assistant = assistant,
            ),
            globalIndex = streamingTail.index,
            isLastMessage = true,
        )
    )
}

internal fun ChatTimelineUiState.totalListItemCount(
    includeStreamingTail: Boolean = false,
    includeLoadingIndicator: Boolean = false,
): Int {
    return messageItems.size +
        compressionItems.size +
        if (includeStreamingTail) 1 else 0 +
        if (includeLoadingIndicator) 1 else 0
}

internal fun ChatTimelineUiState.listIndexForMessage(globalIndex: Int): Int? {
    if (globalIndex !in messageItems.indices) return null
    val compressionOffset = compressionItems.count { it.boundaryIndex <= globalIndex }
    return globalIndex + compressionOffset
}

internal fun ChatTimelineUiState.listIndexForNode(nodeId: Uuid): Int? {
    val messageIndex = messageItems.indexOfFirst { it.node.id == nodeId }
    if (messageIndex < 0) return null
    return listIndexForMessage(messageItems[messageIndex].globalIndex)
}

internal fun ChatTimelineUiState.listIndexForCompressionEvent(eventId: Long): Int? {
    var listIndex = 0
    var compressionIndex = 0
    for (messageIndex in 0..messageItems.size) {
        while (compressionIndex < compressionItems.size && compressionItems[compressionIndex].boundaryIndex == messageIndex) {
            val item = compressionItems[compressionIndex]
            if (item.model.event.id == eventId) {
                return listIndex
            }
            listIndex += 1
            compressionIndex += 1
        }
        if (messageIndex < messageItems.size) {
            listIndex += 1
        }
    }
    return null
}
