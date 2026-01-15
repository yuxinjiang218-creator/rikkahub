package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 知识库文档实体
 * 每个 assistant 有独立的知识库，通过 assistantId 隔离
 */
@Entity(
    tableName = "knowledge_document",
    indices = [
        Index(value = ["assistantId"]),
        Index(value = ["contentHash", "assistantId"], unique = true),  // 同一 assistant 下内容哈希唯一
    ]
)
data class KnowledgeDocumentEntity(
    @PrimaryKey
    val id: String,

    val assistantId: String,  // 所属 assistant ID

    val fileName: String,

    val mime: String,  // MIME type，例如 "application/pdf"

    val localPath: String,  // 拷贝到私有目录后的本地路径

    val sizeBytes: Long,

    val contentHash: String,  // SHA-256 hash，用于去重

    val createdAt: Long,  // 创建时间戳（毫秒）

    val status: String,  // DocumentStatus: PENDING, INDEXING, READY, FAILED

    val errorMessage: String? = null,  // 失败时的错误信息

    val embeddingModelId: String? = null,  // 使用的 embedding model ID（READY 时写入）
)

/**
 * 文档索引状态
 */
enum class DocumentStatus {
    PENDING,    // 等待索引
    INDEXING,   // 正在索引
    READY,      // 索引完成
    FAILED      // 索引失败
}
