package me.rerere.rikkahub.ui.pages.debug

import android.app.ActivityManager
import android.app.Application
import android.os.Looper
import android.os.Process
import kotlinx.coroutines.delay
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.container.BackgroundProcessManager
import me.rerere.rikkahub.data.container.PRootManager
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.ConversationDerivedWorkService
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

class PerformanceSnapshotService(
    private val context: Application,
    private val chatService: ChatService,
    private val derivedWorkService: ConversationDerivedWorkService,
    private val backgroundProcessManager: BackgroundProcessManager,
    private val prootManager: PRootManager,
    private val recorder: PerformanceDiagnosticsRecorder,
) {
    suspend fun capture(
        route: DiagnosticRouteState,
        mode: DetectionMode,
    ): PerformanceSnapshotReport {
        val captureStartedAt = System.currentTimeMillis()
        val cpuSampleDurationMs = when (mode) {
            DetectionMode.Snapshot -> 400L
            DetectionMode.Deep -> 3_500L
        }
        val threadIntervalMs = when (mode) {
            DetectionMode.Snapshot -> 80L
            DetectionMode.Deep -> 250L
        }
        val threadSampleCount = when (mode) {
            DetectionMode.Snapshot -> 4
            DetectionMode.Deep -> 14
        }
        val currentConversationId = route.conversationId
        val backgroundProcesses = backgroundProcessManager.getAllProcesses()
        val processPids = backgroundProcesses.mapNotNull { it.pid }.distinct()
        val cpuStart = readCpuSnapshot(processPids)
        val threadStats = sampleThreads(
            sampleCount = threadSampleCount,
            intervalMs = threadIntervalMs,
        )
        val cpuEnd = readCpuSnapshot(processPids)
        val cpuStats = buildCpuStats(cpuStart, cpuEnd, cpuSampleDurationMs)
        val memoryStats = readMemoryStats(processPids)
        val conversation = currentConversationId?.let { chatService.getConversationFlow(it).value }
        val conversationJob = currentConversationId?.let { chatService.getConversationJobSnapshot(it) }
        val generationState = when {
            conversationJob == null -> "idle"
            conversationJob.isCancelled && !conversationJob.isCompleted -> "cancelling"
            conversationJob.isActive -> "generating"
            else -> "idle"
        }
        val recentEvents = recorder.snapshot(
            sinceMs = if (mode == DetectionMode.Deep) captureStartedAt else captureStartedAt - 30_000L,
            limit = if (mode == DetectionMode.Deep) 120 else 40,
        )
        val recentUpdateSource = recentEvents
            .lastOrNull { it.conversationId == currentConversationId }
            ?.category
            ?: "unknown"
        val payloadEstimateBytes = conversation?.let {
            runCatching { JsonInstant.encodeToString(it.messageNodes).length }.getOrDefault(0)
        } ?: 0
        val lastMessage = conversation?.currentMessages?.lastOrNull()
        val toolParts = lastMessage?.parts?.filterIsInstance<UIMessagePart.Tool>().orEmpty()
        val estimatedToolOutputChars = toolParts.sumOf { tool ->
            tool.output.sumOf { part ->
                when (part) {
                    is UIMessagePart.Text -> part.text.length
                    is UIMessagePart.Document -> (part.fileName + part.url).length
                    is UIMessagePart.Image -> part.url.length
                    else -> part.toString().length
                }
            }
        }
        val compressionStates = chatService.getCompressionUiStatesSnapshot()
        val ledgerStates = chatService.getLedgerGenerationUiStatesSnapshot()
        val backgroundJobsCount = chatService.getConversationJobsSnapshot().size +
            chatService.getCompressionWorkerJobsSnapshot().count { it.value?.isActive == true } +
            derivedWorkService.getTrackedJobsCount()
        val processSnapshots = backgroundProcesses.map { process ->
            ContainerProcessSnapshot(
                info = process,
                rssKb = process.pid?.let(::readProcessRssKb),
                cpuPercent = process.pid?.let { cpuStats.perProcessCpuPercent[it] },
            )
        }
        val heavyProcessHint = processSnapshots.maxByOrNull {
            (it.cpuPercent ?: 0.0) + ((it.rssKb ?: 0L) / 1024.0)
        }?.let {
            "${it.info.processId} rss=${it.rssKb ?: 0}KB cpu=${it.cpuPercent?.roundToInt() ?: 0}%"
        } ?: "none"

        return PerformanceSnapshotReport(
            mode = mode,
            capturedAtMs = System.currentTimeMillis(),
            route = route,
            isForeground = chatService.isForeground.value,
            snapshotCostMs = System.currentTimeMillis() - captureStartedAt,
            memory = memoryStats,
            cpu = CpuStats(
                appCpuPercent = cpuStats.appCpuPercent,
                containerCpuPercent = cpuStats.containerCpuPercent,
                sampleDurationMs = cpuSampleDurationMs,
            ),
            threads = threadStats,
            chat = ChatStats(
                conversationId = currentConversationId,
                messageNodes = conversation?.messageNodes?.size ?: 0,
                currentMessages = conversation?.currentMessages?.size ?: 0,
                lastMessageParts = lastMessage?.parts?.size ?: 0,
                toolPartsInLastMessage = toolParts.size,
                estimatedToolOutputChars = estimatedToolOutputChars,
                payloadEstimateBytes = payloadEstimateBytes,
                recentUpdateSource = recentUpdateSource,
                generationState = generationState,
            ),
            tasks = TaskStats(
                compressionState = currentConversationId?.let(compressionStates::get),
                ledgerState = currentConversationId?.let(ledgerStates::get),
                memoryIndexStatus = conversation?.memoryIndexState?.lastIndexStatus?.ifBlank { "idle" } ?: "idle",
                titleRunning = currentConversationId?.let(derivedWorkService::hasTitleJob) == true,
                suggestionRunning = currentConversationId?.let(derivedWorkService::hasSuggestionJob) == true,
                generationJobRunning = conversationJob?.isActive == true,
                backgroundJobsCount = backgroundJobsCount,
            ),
            container = ContainerStats(
                isRunning = prootManager.isRunning,
                processCount = processSnapshots.size,
                processes = processSnapshots.sortedByDescending { (it.cpuPercent ?: 0.0) + ((it.rssKb ?: 0L) / 1024.0) },
                heavyProcessHint = heavyProcessHint,
            ),
            events = recentEvents,
        )
    }

    private suspend fun sampleThreads(
        sampleCount: Int,
        intervalMs: Long,
    ): ThreadStats {
        val mainThread = Looper.getMainLooper().thread
        val mainStateCounter = LinkedHashMap<String, Int>()
        val mainFrameCounter = LinkedHashMap<String, Int>()
        val topThreads = LinkedHashMap<String, ThreadAccumulator>()
        var mainThreadStack = emptyList<String>()

        repeat(sampleCount) { index ->
            val stackTraces = Thread.getAllStackTraces()
            stackTraces.forEach { (thread, stack) ->
                if (!thread.isAlive) return@forEach
                val state = thread.state.name
                if (thread === mainThread) {
                    mainStateCounter[state] = (mainStateCounter[state] ?: 0) + 1
                    mainThreadStack = stack.take(12).map { frame ->
                        "${frame.className}.${frame.methodName}:${frame.lineNumber}"
                    }
                    stack.take(4).forEach { frame ->
                        val key = "${frame.className}.${frame.methodName}"
                        mainFrameCounter[key] = (mainFrameCounter[key] ?: 0) + 1
                    }
                }

                val activityScore = when (thread.state) {
                    Thread.State.RUNNABLE -> 3
                    Thread.State.BLOCKED -> 2
                    Thread.State.WAITING,
                    Thread.State.TIMED_WAITING -> 1
                    else -> 0
                }
                if (activityScore == 0) return@forEach
                val topFrame = stack.firstOrNull()?.let { frame ->
                    "${frame.className}.${frame.methodName}:${frame.lineNumber}"
                } ?: "<empty>"
                val accumulator = topThreads.getOrPut(thread.name) {
                    ThreadAccumulator(name = thread.name)
                }
                accumulator.state = state
                accumulator.score += activityScore
                accumulator.frames[topFrame] = (accumulator.frames[topFrame] ?: 0) + 1
            }
            if (index != sampleCount - 1) {
                delay(intervalMs)
            }
        }

        val mainThreadState = mainStateCounter.maxByOrNull { it.value }?.key ?: mainThread.state.name
        val mainThreadSummary = mainFrameCounter.entries
            .sortedByDescending { it.value }
            .take(3)
            .joinToString(" | ") { it.key }
            .ifBlank { "<no-samples>" }
        val topBusyThreads = topThreads.values
            .filter { it.name != mainThread.name }
            .sortedByDescending { it.score }
            .take(5)
            .map { accumulator ->
                ThreadHotspot(
                    name = accumulator.name,
                    state = accumulator.state,
                    activityScore = accumulator.score,
                    topFrame = accumulator.frames.maxByOrNull { it.value }?.key ?: "<unknown>",
                )
            }
        return ThreadStats(
            mainThreadState = mainThreadState,
            threadCount = Thread.getAllStackTraces().size,
            mainThreadSummary = mainThreadSummary,
            mainThreadStack = mainThreadStack,
            topBusyThreads = topBusyThreads,
        )
    }

    private fun readMemoryStats(processPids: List<Int>): MemoryStats {
        val runtime = Runtime.getRuntime()
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val processMem = activityManager.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()
        val systemMem = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val containerTotalRssKb = processPids.sumOf { pid -> readProcessRssKb(pid) ?: 0L }
        return MemoryStats(
            heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            heapCapBytes = runtime.maxMemory(),
            memoryClassMb = activityManager.memoryClass,
            largeMemoryClassMb = activityManager.largeMemoryClass,
            appPssKb = processMem?.totalPss ?: 0,
            appPrivateDirtyKb = processMem?.totalPrivateDirty ?: 0,
            appNativePrivateDirtyKb = processMem?.nativePrivateDirty ?: 0,
            systemAvailMemBytes = systemMem.availMem,
            systemLowMemory = systemMem.lowMemory,
            containerTotalRssKb = containerTotalRssKb,
        )
    }

    private fun readCpuSnapshot(processPids: List<Int>): CpuSample {
        return CpuSample(
            totalTicks = readTotalCpuTicks(),
            appTicks = readProcessCpuTicks(Process.myPid()),
            processTicks = processPids.associateWith { readProcessCpuTicks(it) },
        )
    }

    private fun buildCpuStats(
        start: CpuSample,
        end: CpuSample,
        durationMs: Long,
    ): CpuComputationResult {
        val totalDelta = (end.totalTicks - start.totalTicks).coerceAtLeast(1L)
        val cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val appDelta = ((end.appTicks ?: 0L) - (start.appTicks ?: 0L)).coerceAtLeast(0L)
        val appCpu = (appDelta.toDouble() / totalDelta.toDouble()) * 100.0 * cpuCores
        val perProcess = end.processTicks.mapValues { (pid, endTicks) ->
            val startTicks = start.processTicks[pid] ?: 0L
            ((endTicks ?: 0L) - startTicks).coerceAtLeast(0L).let { delta ->
                (delta.toDouble() / totalDelta.toDouble()) * 100.0 * cpuCores
            }
        }
        return CpuComputationResult(
            appCpuPercent = if (appDelta > 0L) appCpu else null,
            containerCpuPercent = perProcess.values.sum().takeIf { it > 0.0 },
            perProcessCpuPercent = perProcess,
            sampleDurationMs = durationMs,
        )
    }

    private fun readTotalCpuTicks(): Long {
        val line = File("/proc/stat").useLines { lines -> lines.firstOrNull() }.orEmpty()
        val fields = line.split(Regex("\\s+")).drop(1)
        return fields.sumOf { it.toLongOrNull() ?: 0L }
    }

    private fun readProcessCpuTicks(pid: Int): Long? {
        val content = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return null
        val processSuffix = content.substringAfterLast(") ")
        val fields = processSuffix.split(' ')
        val utime = fields.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = fields.getOrNull(12)?.toLongOrNull() ?: return null
        return utime + stime
    }

    private fun readProcessRssKb(pid: Int): Long? {
        val lines = runCatching { File("/proc/$pid/status").readLines() }.getOrNull() ?: return null
        val rssLine = lines.firstOrNull { it.startsWith("VmRSS:") } ?: return null
        return rssLine.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull()
    }

    private data class ThreadAccumulator(
        val name: String,
        var state: String = "UNKNOWN",
        var score: Int = 0,
        val frames: MutableMap<String, Int> = LinkedHashMap(),
    )

    private data class CpuSample(
        val totalTicks: Long,
        val appTicks: Long?,
        val processTicks: Map<Int, Long?>,
    )

    private data class CpuComputationResult(
        val appCpuPercent: Double?,
        val containerCpuPercent: Double?,
        val perProcessCpuPercent: Map<Int, Double>,
        val sampleDurationMs: Long,
    )
}
