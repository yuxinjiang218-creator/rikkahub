package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 知识库文本块实体
 * 将文档切分成固定大小的块，便于 embedding 检索
 */
@Entity(
    tableName = "knowledge_chunk",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE  // 文档删除时级联删除 chunk
        )
    ],
    indices = [
        Index(value = ["documentId"]),
        Index(value = ["assistantId"]),
    ]
)
data class KnowledgeChunkEntity(
    @PrimaryKey
    val id: String,

    val documentId: String,  // 所属文档 ID

    val assistantId: String,  // 所属 assistant ID

    val chunkIndex: Int,  // 块索引（从 0 开始）

    val text: String,  // 块文本内容

    val charCount: Int  // 字符数
)
