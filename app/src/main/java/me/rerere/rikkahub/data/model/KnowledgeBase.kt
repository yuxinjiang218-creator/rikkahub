package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.uuid.Uuid

@Serializable
enum class KnowledgeBaseDocumentStatus {
    QUEUED,
    INDEXING,
    READY,
    FAILED,
}

data class KnowledgeBaseDocument(
    val id: Long = 0L,
    val assistantId: Uuid,
    val relativePath: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val status: KnowledgeBaseDocumentStatus = KnowledgeBaseDocumentStatus.QUEUED,
    val chunkCount: Int = 0,
    val queuedAt: Instant? = null,
    val publishedGeneration: Int = 0,
    val buildingGeneration: Int = 0,
    val progressCurrent: Int = 0,
    val progressTotal: Int = 0,
    val progressLabel: String = "",
    val lastIndexedAt: Instant? = null,
    val lastHeartbeatAt: Instant? = null,
    val lastError: String = "",
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val isSearchable: Boolean
        get() = publishedGeneration > 0

    val isRebuilding: Boolean
        get() = status == KnowledgeBaseDocumentStatus.INDEXING && publishedGeneration > 0
}

data class KnowledgeBaseChunk(
    val id: Long = 0L,
    val documentId: Long,
    val assistantId: Uuid,
    val generation: Int,
    val chunkOrder: Int,
    val content: String,
    val tokenEstimate: Int,
    val embedding: List<Float>,
    val updatedAt: Instant,
)

data class KnowledgeBaseSearchChunk(
    val chunkId: Long,
    val documentId: Long,
    val assistantId: Uuid,
    val documentName: String,
    val mimeType: String,
    val chunkOrder: Int,
    val content: String,
    val score: Double,
    val tokenEstimate: Int,
    val updatedAt: Instant,
)

enum class KnowledgeBaseResultQuality {
    GOOD,
    WEAK,
    EMPTY,
}

data class KnowledgeBaseDocumentSummary(
    val documentId: Long,
    val documentName: String,
    val mimeType: String,
    val chunkCount: Int,
)

data class KnowledgeBaseReadChunk(
    val chunkId: Long,
    val documentId: Long,
    val assistantId: Uuid,
    val documentName: String,
    val mimeType: String,
    val chunkOrder: Int,
    val content: String,
    val tokenEstimate: Int,
    val updatedAt: Instant,
)

data class KnowledgeBaseSearchResult(
    val query: String,
    val quality: KnowledgeBaseResultQuality,
    val returnedCount: Int,
    val chunks: List<KnowledgeBaseSearchChunk>,
)

data class KnowledgeBaseChunkReadResult(
    val documentId: Long,
    val documentName: String,
    val mimeType: String,
    val returnedCount: Int,
    val missingChunkOrders: List<Int>,
    val chunks: List<KnowledgeBaseReadChunk>,
)

data class KnowledgeBaseIndexState(
    val isRunning: Boolean = false,
    val currentDocumentId: Long? = null,
    val currentDocumentName: String = "",
    val queuedCount: Int = 0,
    val progressCurrent: Int = 0,
    val progressTotal: Int = 0,
    val progressLabel: String = "",
)
