package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.compressionEventOrder
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

class ConversationPersistenceService(
    private val conversationRepo: ConversationRepository,
    private val runtimeService: ConversationRuntimeService,
) {
    fun normalizeCompressionState(conversation: Conversation): Conversation {
        val maxIndex = conversation.messageNodes.lastIndex
        val normalizedCompressedIndex = conversation.compressionState.lastCompressedMessageIndex
            .coerceAtLeast(-1)
            .coerceAtMost(maxIndex)
        val normalizedEvents = conversation.compressionEvents
            .map { event ->
                event.copy(boundaryIndex = event.boundaryIndex.coerceIn(0, conversation.messageNodes.size))
            }
            .sortedWith(compressionEventOrder)
        return conversation.copy(
            compressionState = conversation.compressionState.copy(
                lastCompressedMessageIndex = normalizedCompressedIndex,
            ),
            compressionEvents = normalizedEvents,
        )
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation): Conversation {
        val normalizedConversation = normalizeCompressionState(conversation.copy())
        val exists = conversationRepo.existsConversationById(normalizedConversation.id)
        if (!exists && normalizedConversation.title.isBlank() && normalizedConversation.messageNodes.isEmpty()) {
            return normalizedConversation
        }

        runtimeService.updateConversation(conversationId, normalizedConversation)
        if (!exists) {
            conversationRepo.insertConversation(normalizedConversation)
        } else {
            conversationRepo.updateConversation(normalizedConversation)
        }
        return normalizedConversation
    }
}
