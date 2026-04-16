package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.CompressionEvent
import me.rerere.rikkahub.data.model.MessageNode
import kotlin.math.max
import kotlin.math.min
import kotlin.uuid.Uuid

internal const val CHAT_INITIAL_WINDOW_SIZE = 60
internal const val CHAT_OLDER_LOAD_BATCH_SIZE = 40
internal const val CHAT_OLDER_LOAD_TRIGGER_THRESHOLD = 2
private const val CHAT_JUMP_WINDOW_SIZE = 80
private const val CHAT_JUMP_CONTEXT_BEFORE = 20

data class ChatMessageWindowState(
    val totalStableCount: Int = 0,
    val loadedStartIndex: Int = 0,
    val loadedStableNodes: List<MessageNode> = emptyList(),
    val hasOlder: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val initialized: Boolean = false,
)

data class ConversationPreviewSearchResult(
    val nodeId: Uuid,
    val messageId: Uuid,
    val globalIndex: Int,
    val message: UIMessage,
    val snippet: String,
)

internal fun createInitialChatMessageWindow(
    nodes: List<MessageNode>,
    initialWindowSize: Int = CHAT_INITIAL_WINDOW_SIZE,
): ChatMessageWindowState {
    if (nodes.isEmpty()) return ChatMessageWindowState(initialized = true)
    val startIndex = max(nodes.size - initialWindowSize, 0)
    val loadedNodes = nodes.subList(startIndex, nodes.size).toList()
    return ChatMessageWindowState(
        totalStableCount = nodes.size,
        loadedStartIndex = startIndex,
        loadedStableNodes = loadedNodes,
        hasOlder = startIndex > 0,
        initialized = true,
    )
}

internal fun syncChatMessageWindowWithNodes(
    current: ChatMessageWindowState,
    nodes: List<MessageNode>,
    initialWindowSize: Int = CHAT_INITIAL_WINDOW_SIZE,
): ChatMessageWindowState {
    if (!current.initialized) return createInitialChatMessageWindow(nodes, initialWindowSize)
    if (nodes.isEmpty()) {
        return current.copy(
            totalStableCount = 0,
            loadedStartIndex = 0,
            loadedStableNodes = emptyList(),
            hasOlder = false,
            isLoadingOlder = false,
        )
    }

    val oldTotal = current.totalStableCount
    val currentEndExclusive = current.loadedStartIndex + current.loadedStableNodes.size
    val anchoredToBottom = currentEndExclusive >= oldTotal
    val targetWindowSize = current.loadedStableNodes.size.coerceAtLeast(initialWindowSize).coerceAtMost(nodes.size)
    val nextStart = current.loadedStartIndex.coerceAtMost((nodes.size - 1).coerceAtLeast(0))
    val nextEndExclusive = when {
        anchoredToBottom -> nodes.size
        current.loadedStableNodes.isEmpty() -> min(nodes.size, nextStart + initialWindowSize)
        else -> min(nodes.size, nextStart + targetWindowSize)
    }
    val normalizedStart = if (anchoredToBottom) {
        (nodes.size - targetWindowSize).coerceAtLeast(0)
    } else {
        min(nextStart, nextEndExclusive)
    }
    val normalizedEnd = max(normalizedStart, nextEndExclusive)
    val nextNodes = nodes.subList(normalizedStart, normalizedEnd).toList()

    return current.copy(
        totalStableCount = nodes.size,
        loadedStartIndex = normalizedStart,
        loadedStableNodes = nextNodes,
        hasOlder = normalizedStart > 0,
        isLoadingOlder = false,
        initialized = true,
    )
}

internal fun computeFocusedWindowStart(
    totalCount: Int,
    targetIndex: Int,
    windowSize: Int = CHAT_JUMP_WINDOW_SIZE,
    contextBefore: Int = CHAT_JUMP_CONTEXT_BEFORE,
): Int {
    if (totalCount <= 0) return 0
    val normalizedWindowSize = min(windowSize, totalCount)
    val maxStart = (totalCount - normalizedWindowSize).coerceAtLeast(0)
    return (targetIndex - contextBefore).coerceIn(0, maxStart)
}

internal fun shouldLoadOlderMessages(
    firstVisibleMessageGlobalIndex: Int?,
    loadedStartIndex: Int,
    threshold: Int = CHAT_OLDER_LOAD_TRIGGER_THRESHOLD,
): Boolean {
    val globalIndex = firstVisibleMessageGlobalIndex ?: return false
    return globalIndex - loadedStartIndex <= threshold
}

internal fun localizeCompressionEvents(
    events: List<CompressionEvent>,
    totalStableCount: Int,
    loadedStartIndex: Int,
    loadedNodeCount: Int,
): List<CompressionEvent> {
    val loadedEndIndex = loadedStartIndex + loadedNodeCount
    return events
        .map { event ->
            event.copy(boundaryIndex = event.boundaryIndex.coerceIn(0, totalStableCount))
        }
        .filter { event -> event.boundaryIndex in loadedStartIndex..loadedEndIndex }
        .map { event -> event.copy(boundaryIndex = event.boundaryIndex - loadedStartIndex) }
}

internal fun renderedListIndexForMessage(
    localMessageIndex: Int,
    localizedEvents: List<CompressionEvent>,
): Int {
    val boundaryCount = localizedEvents.count { it.boundaryIndex <= localMessageIndex }
    return localMessageIndex + boundaryCount
}

internal fun findCompressionListIndexInWindow(
    eventId: Long,
    localizedEvents: List<CompressionEvent>,
    loadedNodeCount: Int,
): Int? {
    var listIndex = 0
    for (boundary in 0..loadedNodeCount) {
        localizedEvents.filter { it.boundaryIndex == boundary }.forEach { event ->
            if (event.id == eventId) return listIndex
            listIndex++
        }
        if (boundary < loadedNodeCount) {
            listIndex++
        }
    }
    return null
}
