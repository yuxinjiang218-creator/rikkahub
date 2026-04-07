package me.rerere.rikkahub.ui.pages.debug

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class PerformanceSnapshotFormatter {
    fun format(report: PerformanceSnapshotReport): FormattedDiagnosticsReport {
        val capturedAt = formatTimestamp(report.capturedAtMs)
        val title = when (report.mode) {
            DetectionMode.Snapshot -> "快照检测"
            DetectionMode.Deep -> "深度检测"
        }
        val text = buildString {
            appendLine("$title @ $capturedAt")
            appendLine("Page=${report.route.screenLabel} Conversation=${report.chat.conversationId ?: "none"} Foreground=${report.isForeground} Generation=${report.chat.generationState} Cost=${report.snapshotCostMs}ms")
            appendLine()
            appendLine(
                "Memory Heap=${formatBytes(report.memory.heapUsedBytes)}/${formatBytes(report.memory.heapCapBytes)} " +
                    "PSS=${formatKb(report.memory.appPssKb)} Native=${formatKb(report.memory.appNativePrivateDirtyKb)} " +
                    "PrivateDirty=${formatKb(report.memory.appPrivateDirtyKb)} SystemAvail=${formatBytes(report.memory.systemAvailMemBytes)} " +
                    "Container=${formatKb(report.memory.containerTotalRssKb.toInt())}"
            )
            appendLine(
                "HeapClass normal=${report.memory.memoryClassMb}MB large=${report.memory.largeMemoryClassMb}MB lowMemory=${report.memory.systemLowMemory}"
            )
            appendLine()
            appendLine(
                "CPU App=${formatCpu(report.cpu.appCpuPercent)} Container=${formatCpu(report.cpu.containerCpuPercent)} " +
                    "Sample=${report.cpu.sampleDurationMs}ms MainThread=${report.threads.mainThreadState} Threads=${report.threads.threadCount}"
            )
            appendLine("MainThreadSummary=${report.threads.mainThreadSummary}")
            if (report.threads.topBusyThreads.isNotEmpty()) {
                appendLine("TopThreads:")
                report.threads.topBusyThreads.forEach { thread ->
                    appendLine(" - ${thread.name} [${thread.state}] score=${thread.activityScore} frame=${thread.topFrame}")
                }
            }
            appendLine()
            appendLine(
                "Chat nodes=${report.chat.messageNodes} messages=${report.chat.currentMessages} lastParts=${report.chat.lastMessageParts} " +
                    "toolParts=${report.chat.toolPartsInLastMessage} toolChars=${report.chat.estimatedToolOutputChars} " +
                    "payload=${formatBytes(report.chat.payloadEstimateBytes.toLong())} recent=${report.chat.recentUpdateSource}"
            )
            appendLine(
                "Tasks compression=${report.tasks.compressionState?.phase ?: "idle"} ledger=${report.tasks.ledgerState?.trigger ?: "idle"} " +
                    "memoryIndex=${report.tasks.memoryIndexStatus} title=${report.tasks.titleRunning} suggestion=${report.tasks.suggestionRunning} " +
                    "generationJob=${report.tasks.generationJobRunning} jobs=${report.tasks.backgroundJobsCount}"
            )
            appendLine()
            appendLine(
                "Container running=${report.container.isRunning} processCount=${report.container.processCount} heavy=${report.container.heavyProcessHint}"
            )
            report.container.processes.forEach { process ->
                appendLine(
                    " - ${process.info.processId} pid=${process.info.pid ?: "none"} status=${process.info.status} rss=${process.rssKb?.let { formatKb(it.toInt()) } ?: "n/a"} cpu=${formatCpu(process.cpuPercent)} cmd=${process.info.command.take(80)}"
                )
            }
            appendLine()
            if (report.events.isNotEmpty()) {
                appendLine("Events:")
                report.events.forEach { event ->
                    appendLine(" - ${formatTimestamp(event.timestampMs)} ${event.category} ${event.conversationId ?: ""} ${event.detail}".trim())
                }
                appendLine()
            }
            appendLine("MainThreadStack:")
            report.threads.mainThreadStack.forEach { line ->
                appendLine(" - $line")
            }
        }
        return FormattedDiagnosticsReport(
            mode = report.mode,
            title = title,
            text = text.trimEnd(),
            capturedAtLabel = capturedAt,
        )
    }

    private fun formatTimestamp(timestampMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${kb.roundToInt()}KB"
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1fMB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2fGB", gb)
    }

    private fun formatKb(kb: Int): String = formatBytes(kb.toLong() * 1024L)

    private fun formatCpu(cpu: Double?): String {
        return cpu?.let { String.format(Locale.US, "%.1f%%", it) } ?: "n/a"
    }
}
