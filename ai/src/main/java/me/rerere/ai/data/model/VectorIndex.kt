package me.rerere.ai.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid
import kotlin.time.Instant
import kotlin.time.Clock

/**
 * 向量索引 (V)
 *
 * 职责：
 * - 只允许一件事：从 A 中，找出"此刻最值得回填的那几条"
 * - 向量是索引，不是记忆
 *
 * 组成：
 * 1. Embedding 模型（一级配置项）
 *    - defaultEmbeddingModelId（全局设置）
 *    - 可选：assistant 级覆盖
 *    - embedding 模型要求：ModelType = EMBEDDING
 *
 * 2. 索引内容
 *    - archive_id：关联的归档摘要 ID
 *    - embedding_vector：向量数据
 *    - embedding_model_id：使用的模型 ID
 *    - created_at：创建时间
 *
 * 索引行为：
 * - embedding 只对 Aₖ.text 生成
 * - 不对 S、不对原始消息、不对回填块生成 embedding
 * - embedding 生成时机：Aₖ 生成后立刻生成
 * - embedding 缺失时必须可补建（容错要求）
 * - 更换 embedding 模型后：
 *   - 允许重建索引
 *   - 不允许混用不同模型向量做相似度
 */
@Serializable
data class VectorIndex(
    val id: Uuid = Uuid.random(),
    // 关联的归档摘要 ID
    val archiveId: Uuid,
    // 向量数据
    val embeddingVector: FloatArray,
    // 使用的嵌入模型 ID
    val embeddingModelId: Uuid,
    // 创建时间
    val createdAt: Instant = Clock.System.now()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VectorIndex

        if (id != other.id) return false
        if (archiveId != other.archiveId) return false
        if (!embeddingVector.contentEquals(other.embeddingVector)) return false
        if (embeddingModelId != other.embeddingModelId) return false
        if (createdAt != other.createdAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + archiveId.hashCode()
        result = 31 * result + embeddingVector.contentHashCode()
        result = 31 * result + embeddingModelId.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}
