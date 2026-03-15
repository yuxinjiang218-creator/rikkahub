package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.model.ScheduleType
import me.rerere.rikkahub.data.model.ScheduledPromptTask
import me.rerere.rikkahub.data.model.TaskRunStatus
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

internal object ScheduledPromptTime {
    fun nextTriggerAt(
        task: ScheduledPromptTask,
        now: ZonedDateTime = ZonedDateTime.now()
    ): ZonedDateTime {
        val atTimeToday = now.toLocalDate()
            .atStartOfDay(now.zone)
            .plusMinutes(task.timeMinutesOfDay.coerceIn(0, 1439).toLong())

        return when (task.scheduleType) {
            ScheduleType.DAILY -> if (atTimeToday.isAfter(now)) atTimeToday else atTimeToday.plusDays(1)
            ScheduleType.WEEKLY -> {
                val dayOfWeek = DayOfWeek.of(task.dayOfWeek.coerceValidDayOfWeek())
                val next = now.with(TemporalAdjusters.nextOrSame(dayOfWeek))
                    .toLocalDate()
                    .atStartOfDay(now.zone)
                    .plusMinutes(task.timeMinutesOfDay.coerceIn(0, 1439).toLong())
                if (next.isAfter(now)) next else next.plusWeeks(1)
            }
        }
    }

    fun latestDueAt(
        task: ScheduledPromptTask,
        now: ZonedDateTime = ZonedDateTime.now()
    ): ZonedDateTime {
        val todayAtTaskTime = now.toLocalDate()
            .atStartOfDay(now.zone)
            .plusMinutes(task.timeMinutesOfDay.coerceIn(0, 1439).toLong())

        return when (task.scheduleType) {
            ScheduleType.DAILY -> if (todayAtTaskTime.isAfter(now)) todayAtTaskTime.minusDays(1) else todayAtTaskTime
            ScheduleType.WEEKLY -> {
                val dayOfWeek = DayOfWeek.of(task.dayOfWeek.coerceValidDayOfWeek())
                val thisWeekTarget = now.with(TemporalAdjusters.previousOrSame(dayOfWeek))
                    .toLocalDate()
                    .atStartOfDay(now.zone)
                    .plusMinutes(task.timeMinutesOfDay.coerceIn(0, 1439).toLong())
                if (thisWeekTarget.isAfter(now)) thisWeekTarget.minusWeeks(1) else thisWeekTarget
            }
        }
    }

    fun shouldRunCatchUp(
        task: ScheduledPromptTask,
        now: ZonedDateTime = ZonedDateTime.now()
    ): Boolean {
        if (task.lastStatus == TaskRunStatus.RUNNING) return false
        val latestDue = latestDueAt(task, now).toInstant().toEpochMilli()
        val marker = if (task.lastRunAt > 0L) task.lastRunAt else task.createdAt
        return latestDue > marker
    }

    fun initialDelayMillis(task: ScheduledPromptTask, zoneId: ZoneId = ZoneId.systemDefault()): Long {
        val now = ZonedDateTime.now(zoneId)
        val target = nextTriggerAt(task, now)
        return Duration.between(now, target).toMillis().coerceAtLeast(60_000L)
    }
}

private fun Int?.coerceValidDayOfWeek(): Int = when (this) {
    null -> DayOfWeek.MONDAY.value
    in DayOfWeek.MONDAY.value..DayOfWeek.SUNDAY.value -> this
    else -> DayOfWeek.MONDAY.value
}
