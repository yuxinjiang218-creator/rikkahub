package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "knowledge_base_document",
    indices = [
        Index(value = ["assistant_id"]),
        Index(value = ["status"]),
        Index(value = ["relative_path"], unique = true),
        Index(value = ["assistant_id", "status"]),
        Index(value = ["status", "queued_at"]),
    ]
)
data class KnowledgeBaseDocumentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("relative_path")
    val relativePath: String,
    @ColumnInfo("display_name")
    val displayName: String,
    @ColumnInfo("mime_type")
    val mimeType: String,
    @ColumnInfo("size_bytes")
    val sizeBytes: Long,
    @ColumnInfo("status")
    val status: String,
    @ColumnInfo("chunk_count")
    val chunkCount: Int,
    @ColumnInfo("queued_at")
    val queuedAt: Long?,
    @ColumnInfo("published_generation")
    val publishedGeneration: Int,
    @ColumnInfo("building_generation")
    val buildingGeneration: Int,
    @ColumnInfo("progress_current")
    val progressCurrent: Int,
    @ColumnInfo("progress_total")
    val progressTotal: Int,
    @ColumnInfo("progress_label")
    val progressLabel: String,
    @ColumnInfo("last_indexed_at")
    val lastIndexedAt: Long?,
    @ColumnInfo("last_heartbeat_at")
    val lastHeartbeatAt: Long?,
    @ColumnInfo("last_error")
    val lastError: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
