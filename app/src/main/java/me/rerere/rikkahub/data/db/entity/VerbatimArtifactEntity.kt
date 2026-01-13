package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "verbatim_artifact",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["conversation_id"], name = "idx_va_conv"),
        Index(value = ["conversation_id", "title"], name = "idx_va_title")
    ]
)
data class VerbatimArtifactEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    @ColumnInfo(name = "title")
    val title: String?,

    @ColumnInfo(name = "type")
    val type: Int,  // 1=POEM, 2=CODE, 3=LONG_TEXT

    @ColumnInfo(name = "start_node_index")
    val startNodeIndex: Int,

    @ColumnInfo(name = "end_node_index")
    val endNodeIndex: Int,

    @ColumnInfo(name = "content_sha256")
    val contentSha256: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
