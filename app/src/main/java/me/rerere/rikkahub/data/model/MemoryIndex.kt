package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.uuid.Uuid

data class MemoryIndexChunk(
    val id: Long = 0L,
    val assistantId: Uuid,
    val conversationId: Uuid,
    val sectionKey: String,
    val chunkOrder: Int,
    val content: String,
    val tokenEstimate: Int,
    val embedding: List<Float>,
    val metadata: MemoryChunkMetadata = MemoryChunkMetadata(),
    val updatedAt: Instant,
)

@Serializable
data class MemoryChunkMetadata(
    val lane: String = "current",
    val status: String = "active",
    val sectionKey: String = "",
    val detailKind: String? = null,
    val tags: List<String> = emptyList(),
    val entityKeys: List<String> = emptyList(),
    val salience: Double = 0.5,
    val timeRef: String? = null,
    val relatedIds: List<String> = emptyList(),
    val sourceRoles: List<String> = emptyList(),
    val sourceMessageIds: List<String> = emptyList(),
)

data class RecallMemoryChunk(
    val chunkId: Long,
    val assistantId: Uuid,
    val conversationId: Uuid,
    val conversationTitle: String,
    val sectionKey: String,
    val content: String,
    val lane: String,
    val status: String,
    val tags: List<String>,
    val entityKeys: List<String>,
    val timeRef: String?,
    val bm25Score: Double,
    val vectorScore: Double,
    val finalScore: Double,
    val tokenEstimate: Int,
    val updatedAt: Instant,
)

data class RecallMemoryResult(
    val query: String,
    val channel: String,
    val role: String,
    val returnedCount: Int,
    val candidateConversationIds: List<Uuid>,
    val chunks: List<RecallMemoryChunk>,
)

data class SourcePreviewChunk(
    val id: Long = 0L,
    val assistantId: Uuid,
    val conversationId: Uuid,
    val messageId: Uuid,
    val role: String,
    val chunkOrder: Int,
    val prefixText: String,
    val searchText: String,
    val blockType: String,
    val updatedAt: Instant,
)

data class SearchSourceCandidate(
    val sourceRef: String,
    val conversationId: Uuid,
    val messageId: Uuid,
    val role: String,
    val prefix: String,
    val hitSnippet: String,
    val score: Double,
    val usedFallbackScope: Boolean,
)

data class SearchSourceResult(
    val query: String,
    val role: String,
    val returnedCount: Int,
    val usedFallbackScope: Boolean,
    val candidates: List<SearchSourceCandidate>,
)

data class ReadSourceResult(
    val sourceRef: String,
    val conversationId: Uuid,
    val messageId: Uuid,
    val role: String,
    val createdAt: Instant,
    val content: String,
)
