package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_index_chunk",
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
        Index(value = ["assistant_id", "conversation_id"])
    ]
)
data class MemoryIndexChunkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("section_key")
    val sectionKey: String,
    @ColumnInfo("chunk_order")
    val chunkOrder: Int,
    @ColumnInfo("content")
    val content: String,
    @ColumnInfo("token_estimate")
    val tokenEstimate: Int,
    @ColumnInfo("embedding_json")
    val embeddingJson: String,
    @ColumnInfo("metadata_json", defaultValue = "{}")
    val metadataJson: String = "{}",
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
