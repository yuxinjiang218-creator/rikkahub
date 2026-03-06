package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "compression_event",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["conversation_id"]),
        Index(value = ["conversation_id", "created_at"])
    ]
)
data class CompressionEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("boundary_index")
    val boundaryIndex: Int,
    @ColumnInfo("summary_snapshot", defaultValue = "")
    val summarySnapshot: String = "",
    @ColumnInfo("created_at")
    val createdAt: Long,
)
