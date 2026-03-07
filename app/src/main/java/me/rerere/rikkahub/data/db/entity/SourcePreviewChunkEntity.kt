package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "source_preview_chunk",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["assistant_id"]),
        Index(value = ["conversation_id"]),
        Index(value = ["message_id"]),
        Index(value = ["assistant_id", "conversation_id"]),
    ]
)
data class SourcePreviewChunkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("message_id")
    val messageId: String,
    @ColumnInfo("role")
    val role: String,
    @ColumnInfo("chunk_order")
    val chunkOrder: Int,
    @ColumnInfo("prefix_text")
    val prefixText: String,
    @ColumnInfo("search_text")
    val searchText: String,
    @ColumnInfo("block_type")
    val blockType: String,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
