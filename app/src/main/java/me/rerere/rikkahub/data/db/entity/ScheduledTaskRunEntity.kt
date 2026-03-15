package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scheduled_task_run",
    indices = [
        Index(value = ["task_id"]),
        Index(value = ["started_at"]),
        Index(value = ["status"]),
    ]
)
data class ScheduledTaskRunEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("task_id")
    val taskId: String,
    @ColumnInfo("task_title_snapshot")
    val taskTitleSnapshot: String,
    @ColumnInfo("assistant_id_snapshot")
    val assistantIdSnapshot: String,
    @ColumnInfo("status")
    val status: String,
    @ColumnInfo("started_at")
    val startedAt: Long,
    @ColumnInfo("finished_at")
    val finishedAt: Long,
    @ColumnInfo("duration_ms")
    val durationMs: Long,
    @ColumnInfo("prompt_snapshot")
    val promptSnapshot: String,
    @ColumnInfo("result_text")
    val resultText: String,
    @ColumnInfo("error_text")
    val errorText: String,
    @ColumnInfo("model_id_snapshot")
    val modelIdSnapshot: String?,
    @ColumnInfo("provider_name_snapshot")
    val providerNameSnapshot: String,
)
