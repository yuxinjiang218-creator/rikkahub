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
    val conversationTitle: String = "",
    val items: List<ChatTimelineItem> = emptyList(),
    val previewMessages: List<ChatPreviewMessageItem> = emptyList(),
    val previewSearchResults: List<ConversationPreviewSearchResult> = emptyList(),
    val chatSuggestions: List<String> = emptyList(),
    val loadedStartIndex: Int = 0,
    val totalStableCount: Int = 0,
    val renderedMessageCount: Int = 0,
    val hasOlder: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val latestCompressionEventId: Long? = null,
    val lastAssistantInputTokens: Int = 0,
)

@Immutable
sealed interface ChatTimelineItem {
    val stableKey: String
    val contentType: String
}

@Immutable
data class ChatTimelineMessageItem(
    val model: ChatMessageItemModel,
) : ChatTimelineItem {
    override val stableKey: String = "message_${model.node.id}"
    override val contentType: String = "message"
}

@Immutable
data class ChatTimelineCompressionItem(
    val model: ChatCompressionBoundaryModel,
) : ChatTimelineItem {
    override val stableKey: String = "compression_${model.event.id}"
    override val contentType: String = "compression"
}

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
    windowState: ChatMessageWindowState,
    streamingTail: StreamingTailState?,
    previewSearchResults: List<ConversationPreviewSearchResult>,
): ChatTimelineUiState {
    val displayedNodes = buildDisplayedNodes(
        stableNodes = windowState.loadedStableNodes,
        streamingTail = streamingTail,
        loadedStartIndex = windowState.loadedStartIndex,
    )
    val normalizedCompressionEvents = localizeCompressionEvents(
        events = conversation.compressionEvents,
        totalStableCount = windowState.totalStableCount,
        loadedStartIndex = windowState.loadedStartIndex,
        loadedNodeCount = displayedNodes.size,
    ).sortedWith(compressionEventOrder)
    val latestCompressionEventId = normalizedCompressionEvents.lastOrNull()?.id
    val eventsByBoundary = normalizedCompressionEvents.groupBy { it.boundaryIndex }
    val assistant = settings.getAssistantById(conversation.assistantId)
    val timelineItems = buildList {
        displayedNodes.forEachIndexed { index, node ->
            eventsByBoundary[index].orEmpty().forEach { event ->
                add(
                    ChatTimelineCompressionItem(
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
                        )
                    )
                )
            }
            add(
                ChatTimelineMessageItem(
                    model = ChatMessageItemModel(
                        renderModel = ChatMessageRenderModel(
                            node = node,
                            message = node.currentMessage,
                            model = node.currentMessage.modelId?.let(settings::findModelById),
                            assistant = assistant,
                        ),
                        globalIndex = windowState.loadedStartIndex + index,
                        isLastMessage = index == displayedNodes.lastIndex,
                    )
                )
            )
        }
        eventsByBoundary[displayedNodes.size].orEmpty().forEach { event ->
            add(
                ChatTimelineCompressionItem(
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
                    )
                )
            )
        }
    }
    val previewMessages = displayedNodes.mapIndexed { index, node ->
        ChatPreviewMessageItem(
            nodeId = node.id,
            message = node.currentMessage,
            globalIndex = windowState.loadedStartIndex + index,
        )
    }
    val lastAssistantInputTokens = conversation.messageNodes
        .asReversed()
        .firstOrNull { it.currentMessage.role == MessageRole.ASSISTANT }
        ?.currentMessage
        ?.usage
        ?.promptTokens
        ?: 0
    val renderedMessageCount = windowState.totalStableCount + if (streamingTail != null && streamingTail.index >= windowState.totalStableCount) {
        1
    } else {
        0
    }

    return ChatTimelineUiState(
        conversationTitle = conversation.title,
        items = timelineItems,
        previewMessages = previewMessages,
        previewSearchResults = previewSearchResults,
        chatSuggestions = conversation.chatSuggestions,
        loadedStartIndex = windowState.loadedStartIndex,
        totalStableCount = windowState.totalStableCount,
        renderedMessageCount = renderedMessageCount,
        hasOlder = windowState.hasOlder,
        isLoadingOlder = windowState.isLoadingOlder,
        latestCompressionEventId = latestCompressionEventId,
        lastAssistantInputTokens = lastAssistantInputTokens,
    )
}

private fun buildDisplayedNodes(
    stableNodes: List<MessageNode>,
    streamingTail: StreamingTailState?,
    loadedStartIndex: Int,
): List<MessageNode> {
    if (streamingTail == null) return stableNodes

    val displayedNodes = stableNodes.toMutableList()
    val localStreamingIndex = streamingTail.index - loadedStartIndex
    when {
        localStreamingIndex in displayedNodes.indices -> displayedNodes[localStreamingIndex] = streamingTail.node
        localStreamingIndex == displayedNodes.size -> displayedNodes.add(streamingTail.node)
    }
    return displayedNodes
}
