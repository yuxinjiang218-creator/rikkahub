package me.rerere.rikkahub.data.db.entity

/**
 * ArchiveSummaryEntity with computed scores (for SemanticRecallService internal use)
 *
 * Transient properties:
 * - maxCosSim: 最大余弦相似度 (computed during retrieval)
 * - finalScore: 最终重排分数 (0.7 * maxCosSim + 0.3 * recencyScore)
 */
data class ArchiveSummaryWithScore(
    val entity: ArchiveSummaryEntity,
    val maxCosSim: Float = 0f,
    val finalScore: Float = 0f
) {
    val id: String get() = entity.id
    val conversationId: String get() = entity.conversationId
    val windowStartIndex: Int get() = entity.windowStartIndex
    val windowEndIndex: Int get() = entity.windowEndIndex
    val content: String get() = entity.content
    val createdAt: Long get() = entity.createdAt
    val embeddingModelId: String? get() = entity.embeddingModelId
}
