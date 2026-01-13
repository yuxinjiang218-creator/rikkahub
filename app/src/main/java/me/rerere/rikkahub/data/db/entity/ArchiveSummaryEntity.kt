package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 归档摘要数据库实体
 *
 * 对应数据模型：ArchiveSummary
 */
@Entity(
    tableName = "archive_summary",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("conversation_id"),
        Index("created_at")
    ]
)
data class ArchiveSummaryEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo("conversation_id")
    val conversationId: String,

    @ColumnInfo("window_start_index")
    val windowStartIndex: Int,

    @ColumnInfo("window_end_index")
    val windowEndIndex: Int,

    @ColumnInfo("content")
    val content: String,

    @ColumnInfo("created_at")
    val createdAt: Long,

    @ColumnInfo("embedding_model_id")
    val embeddingModelId: String?
)
