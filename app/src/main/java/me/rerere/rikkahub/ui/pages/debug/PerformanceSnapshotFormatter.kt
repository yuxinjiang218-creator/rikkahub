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
                "CPU AppGross=${formatCpu(report.cpu.appCpuGrossPercent)} AppAdjusted=${formatCpu(report.cpu.appCpuAdjustedPercent)} " +
                    "DiagSelf=${formatCpu(report.cpu.diagnosticSelfCpuPercent)} Container=${formatCpu(report.cpu.containerCpuPercent)} " +
                    "Sample=${report.cpu.sampleDurationMs}ms MainThread=${report.threads.mainThreadState} Threads=${report.threads.threadCount}"
            )
            appendLine("MainThreadSummary=${report.threads.mainThreadSummary}")
            appendLine(
                "FrameGap sampled=${report.threads.frameGaps.sampled} frames=${report.threads.frameGaps.frameCount} " +
                    "max=${formatGap(report.threads.frameGaps.maxGapMs)} slow>${report.threads.frameGaps.thresholdMs}ms=${report.threads.frameGaps.slowFrameCount}"
            )
            if (report.threads.topCpuThreads.isNotEmpty()) {
                appendLine("TopCpuThreads:")
                report.threads.topCpuThreads.forEach { thread ->
                    appendLine(
                        " - tid=${thread.tid ?: -1} ${thread.name} [${thread.state}] cpu=${formatCpu(thread.cpuPercent)} frame=${thread.topFrame}"
                    )
                }
            }
            appendLine()
            appendLine(
                "Chat session=${report.chat.sessionState} nodes=${report.chat.messageNodes} messages=${report.chat.currentMessages} lastParts=${report.chat.lastMessageParts} " +
                    "toolParts=${report.chat.toolPartsInLastMessage} toolChars=${report.chat.estimatedToolOutputChars} " +
                    "payload=${formatBytes(report.chat.payloadEstimateBytes.toLong())} recent=${report.chat.recentUpdateSource}"
            )
            appendLine(
                "Chunk events=${report.chat.chunkEvents} rate=${formatRate(report.chat.chunkRatePerSecond)} avg=${formatCost(report.chat.chunkAvgCostMs)} " +
                    "max=${formatCost(report.chat.chunkMaxCostMs?.toDouble())} last=${report.chat.lastChunkPhase} " +
                    "saveInterleaved=${report.chat.chunkSaveInterleaved} notifyInterleaved=${report.chat.chunkNotificationInterleaved}"
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
                    val extras = buildList {
                        event.phase?.let { add("phase=$it") }
                        event.costMs?.let { add("cost=${it}ms") }
                        event.sizeHint?.let { add("size=$it") }
                    }.joinToString(" ")
                    appendLine(
                        " - ${formatTimestamp(event.timestampMs)} ${event.category} ${event.conversationId ?: ""} ${event.detail} ${extras}".trim()
                    )
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

    private fun formatGap(gapMs: Double): String {
        return String.format(Locale.US, "%.1fms", gapMs)
    }

    private fun formatRate(ratePerSecond: Double?): String {
        return ratePerSecond?.let { String.format(Locale.US, "%.2f/s", it) } ?: "n/a"
    }

    private fun formatCost(costMs: Double?): String {
        return costMs?.let { String.format(Locale.US, "%.1fms", it) } ?: "n/a"
    }
}
