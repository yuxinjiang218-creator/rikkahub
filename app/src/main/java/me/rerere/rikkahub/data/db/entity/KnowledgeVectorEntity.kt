package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 知识库向量实体
 * 存储 chunk 的 embedding 向量，用于语义检索
 */
@Entity(
    tableName = "knowledge_vector",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["chunkId"],
            onDelete = ForeignKey.CASCADE  // chunk 删除时级联删除向量
        )
    ],
    indices = [
        Index(value = ["chunkId"]),
        Index(value = ["assistantId"]),
        Index(value = ["embeddingModelId"]),
    ]
)
data class KnowledgeVectorEntity(
    @PrimaryKey
    val id: String,

    val chunkId: String,  // 所属 chunk ID

    val assistantId: String,  // 所属 assistant ID

    val embeddingModelId: String,  // embedding 模型 ID

    val embeddingVector: FloatArray,  // embedding 向量

    val vectorNorm: Float  // 向量范数（用于余弦相似度计算）
)
