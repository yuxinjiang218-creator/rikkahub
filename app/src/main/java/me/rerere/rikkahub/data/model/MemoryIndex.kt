package me.rerere.rikkahub.data.model

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
    val updatedAt: Instant,
)

data class RecallMemoryChunk(
    val chunkId: Long,
    val assistantId: Uuid,
    val conversationId: Uuid,
    val conversationTitle: String,
    val sectionKey: String,
    val content: String,
    val bm25Score: Double,
    val vectorScore: Double,
    val finalScore: Double,
    val tokenEstimate: Int,
    val updatedAt: Instant,
)

data class RecallMemoryResult(
    val query: String,
    val returnedCount: Int,
    val chunks: List<RecallMemoryChunk>,
)
