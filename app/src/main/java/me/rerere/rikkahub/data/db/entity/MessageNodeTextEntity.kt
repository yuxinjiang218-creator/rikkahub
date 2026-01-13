package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "message_node_text",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["conversation_id", "node_index"], name = "idx_mnt_conv_idx"),
        Index(value = ["conversation_id", "role"], name = "idx_mnt_conv_role")
    ]
)
data class MessageNodeTextEntity(
    @PrimaryKey
    @ColumnInfo(name = "node_id")
    val nodeId: String,

    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    @ColumnInfo(name = "node_index")
    val nodeIndex: Int,

    @ColumnInfo(name = "role")
    val role: Int,  // MessageRole.ordinal

    @ColumnInfo(name = "raw_text")
    val rawText: String,

    @ColumnInfo(name = "search_text")
    val searchText: String
)
