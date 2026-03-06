package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("assistant_id", defaultValue = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
    val assistantId: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("nodes")
    val nodes: String,
    @ColumnInfo("create_at")
    val createAt: Long,
    @ColumnInfo("update_at")
    val updateAt: Long,
    @ColumnInfo("suggestions", defaultValue = "[]")
    val chatSuggestions: String,
    @ColumnInfo("is_pinned", defaultValue = "0")
    val isPinned: Boolean,
    @ColumnInfo("workflow_state", defaultValue = "")
    val workflowState: String = "",
    @ColumnInfo("rolling_summary_json", defaultValue = "")
    val rollingSummaryJson: String = "",
    @ColumnInfo("rolling_summary_token_estimate", defaultValue = "0")
    val rollingSummaryTokenEstimate: Int = 0,
    @ColumnInfo("last_compressed_message_index", defaultValue = "-1")
    val lastCompressedMessageIndex: Int = -1,
    @ColumnInfo("last_compressed_at", defaultValue = "0")
    val lastCompressedAt: Long = 0L,
)
