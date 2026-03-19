package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "knowledge_base_chunk",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeBaseDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["document_id"]),
        Index(value = ["assistant_id"]),
        Index(value = ["assistant_id", "document_id"]),
        Index(value = ["document_id", "generation"]),
        Index(value = ["assistant_id", "generation"]),
    ]
)
data class KnowledgeBaseChunkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo("document_id")
    val documentId: Long,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("generation")
    val generation: Int,
    @ColumnInfo("chunk_order")
    val chunkOrder: Int,
    @ColumnInfo("content")
    val content: String,
    @ColumnInfo("token_estimate")
    val tokenEstimate: Int,
    @ColumnInfo("embedding_json")
    val embeddingJson: String,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
