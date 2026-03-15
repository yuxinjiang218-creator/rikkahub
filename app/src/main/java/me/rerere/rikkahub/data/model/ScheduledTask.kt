package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import kotlin.uuid.Uuid

@Serializable
data class ScheduledPromptTask(
    val id: Uuid = Uuid.random(),
    val enabled: Boolean = true,
    val title: String = "",
    val prompt: String = "",
    val scheduleType: ScheduleType = ScheduleType.DAILY,
    val timeMinutesOfDay: Int = 9 * 60,
    val dayOfWeek: Int? = null,
    val assistantId: Uuid,
    val overrideModelId: Uuid? = null,
    val overrideLocalTools: List<LocalToolOption>? = null,
    val overrideMcpServers: Set<Uuid>? = null,
    val overrideEnableWebSearch: Boolean? = null,
    val overrideSearchServiceIndex: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long = 0L,
    val lastStatus: TaskRunStatus = TaskRunStatus.IDLE,
    val lastError: String = "",
    val lastRunId: Uuid? = null,
)

@Serializable
enum class ScheduleType {
    DAILY,
    WEEKLY,
}

@Serializable
enum class TaskRunStatus {
    IDLE,
    RUNNING,
    SUCCESS,
    FAILED,
}

data class ScheduledTaskRun(
    val id: Uuid = Uuid.random(),
    val taskId: Uuid,
    val taskTitleSnapshot: String,
    val assistantIdSnapshot: Uuid,
    val status: TaskRunStatus,
    val startedAt: Long,
    val finishedAt: Long = 0L,
    val durationMs: Long = 0L,
    val promptSnapshot: String,
    val resultText: String = "",
    val errorText: String = "",
    val modelIdSnapshot: Uuid? = null,
    val providerNameSnapshot: String = "",
)
