package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.Job
import me.rerere.rikkahub.data.model.CompressionEvent
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationCompressionPayload
import me.rerere.rikkahub.data.model.compressionEventOrder
import me.rerere.rikkahub.data.model.withCompressionPayload
import me.rerere.rikkahub.data.repository.ConversationRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ConversationArtifactSvc"

class ConversationArtifactService(
    private val conversationRepo: ConversationRepository,
    private val runtimeService: ConversationRuntimeService,
) {
    private val compressionArtifactWarmJobs = ConcurrentHashMap<Uuid, Job>()

    fun cleanup() {
        compressionArtifactWarmJobs.values.forEach { it.cancel() }
        compressionArtifactWarmJobs.clear()
    }

    fun mergeMissingCompressionArtifacts(
        base: Conversation,
        fallback: Conversation,
    ): Conversation {
        if (base.id != fallback.id) return base

        val mergedCompressionState = base.compressionState.copy(
            dialogueSummaryText = base.compressionState.dialogueSummaryText.ifBlank {
                fallback.compressionState.dialogueSummaryText
            },
            rollingSummaryJson = base.compressionState.rollingSummaryJson.ifBlank {
                fallback.compressionState.rollingSummaryJson
            },
        )
        val mergedEvents = if (base.compressionEvents.isNotEmpty() || fallback.compressionEvents.isEmpty()) {
            base.compressionEvents
        } else {
            fallback.compressionEvents
        }

        if (mergedCompressionState == base.compressionState && mergedEvents === base.compressionEvents) {
            return base
        }

        return base.copy(
            compressionState = mergedCompressionState,
            compressionEvents = mergedEvents,
        )
    }

    fun warmCompressionArtifactsAsync(conversationId: Uuid, conversation: Conversation) {
        if (!needsCompressionArtifactWarmup(conversation)) return
        val existingJob = compressionArtifactWarmJobs[conversationId]
        if (existingJob?.isActive == true) return

        val job = runtimeService.launchWithConversationReference(conversationId) {
            runCatching {
                val current = runtimeService.getCurrentConversation(conversationId)
                if (!needsCompressionArtifactWarmup(current)) return@runCatching

                val payload = if (current.compressionState.hasSummary) {
                    null
                } else {
                    conversationRepo.getCompressionPayload(conversationId)
                }
                val events = if (current.compressionEvents.isEmpty()) {
                    conversationRepo.getCompressionEvents(conversationId)
                } else {
                    emptyList()
                }
                if (payload == null && events.isEmpty()) return@runCatching

                val latest = runtimeService.getCurrentConversation(conversationId)
                runtimeService.updateConversation(
                    conversationId,
                    applyCompressionArtifacts(
                        conversation = latest,
                        payload = payload,
                        events = events,
                    ),
                )
            }.onFailure { error ->
                Log.w(TAG, "warmCompressionArtifactsAsync failed for $conversationId", error)
            }
        }
        compressionArtifactWarmJobs[conversationId] = job
        job.invokeOnCompletion {
            compressionArtifactWarmJobs.remove(conversationId, job)
        }
    }

    suspend fun hydrateCompressionPayload(
        conversationId: Uuid,
        conversation: Conversation,
    ): Conversation {
        val mergedConversation = runtimeService.getCurrentConversationOrNull(conversationId)
            ?.let { mergeMissingCompressionArtifacts(conversation, it) }
            ?: conversation
        if (!needsCompressionArtifactWarmup(mergedConversation)) {
            return mergedConversation
        }

        val payload = if (mergedConversation.compressionState.hasSummary) {
            null
        } else {
            conversationRepo.getCompressionPayload(conversationId)
        }
        val events = if (mergedConversation.compressionEvents.isEmpty()) {
            conversationRepo.getCompressionEvents(conversationId)
        } else {
            emptyList()
        }
        if (payload == null && events.isEmpty()) {
            return mergedConversation
        }

        val hydratedConversation = applyCompressionArtifacts(
            conversation = mergedConversation,
            payload = payload,
            events = events,
        )
        runtimeService.updateConversation(conversationId, hydratedConversation)
        return hydratedConversation
    }

    suspend fun getConversationWithCompressionArtifacts(conversationId: Uuid): Conversation? {
        val conversation = conversationRepo.getConversationWithCompressionPayload(conversationId) ?: return null
        return conversation.copy(
            compressionEvents = conversationRepo.getCompressionEvents(conversationId)
                .sortedWith(compressionEventOrder),
        )
    }

    private fun needsCompressionArtifactWarmup(conversation: Conversation): Boolean {
        val likelyHasPersistedArtifacts =
            conversation.compressionState.lastCompressedMessageIndex >= 0 ||
                conversation.compressionState.dialogueSummaryTokenEstimate > 0 ||
                conversation.compressionState.rollingSummaryTokenEstimate > 0 ||
                conversation.compressionState.memoryLedgerStatus != "idle"
        if (!likelyHasPersistedArtifacts) return false

        return !conversation.compressionState.hasSummary || conversation.compressionEvents.isEmpty()
    }

    private fun applyCompressionArtifacts(
        conversation: Conversation,
        payload: ConversationCompressionPayload?,
        events: List<CompressionEvent>,
    ): Conversation {
        val withPayload = if (payload != null) {
            conversation.withCompressionPayload(payload)
        } else {
            conversation
        }
        return if (events.isEmpty()) {
            withPayload
        } else {
            withPayload.copy(compressionEvents = events.sortedWith(compressionEventOrder))
        }
    }
}
