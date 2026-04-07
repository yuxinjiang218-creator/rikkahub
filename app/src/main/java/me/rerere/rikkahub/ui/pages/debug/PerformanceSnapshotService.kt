package me.rerere.rikkahub.ui.pages.debug

import android.app.ActivityManager
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.container.BackgroundProcessManager
import me.rerere.rikkahub.data.container.PRootManager
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.ConversationDerivedWorkService
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.max
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
        val sampleDurationMs = when (mode) {
            DetectionMode.Snapshot -> 400L
            DetectionMode.Deep -> 3_500L
        }
        val threadIntervalMs = when (mode) {
            DetectionMode.Snapshot -> 80L
            DetectionMode.Deep -> 250L
        }
        val currentConversationId = route.conversationId
        val backgroundProcesses = backgroundProcessManager.getAllProcesses()
        val processPids = backgroundProcesses.mapNotNull { it.pid }.distinct()
        val cpuSampleStartedAt = SystemClock.elapsedRealtime()
        val cpuStart = readCpuSnapshot(processPids)
        val threadCpuStart = readThreadCpuSnapshot()
        lateinit var frameGapStats: FrameGapStats
        lateinit var mainThreadSample: MainThreadSample

        coroutineScope {
            val mainThreadDeferred = async {
                sampleMainThread(
                    sampleDurationMs = sampleDurationMs,
                    intervalMs = threadIntervalMs,
                )
            }
            val frameGapSampleDeferred = async {
                if (mode == DetectionMode.Deep && chatService.isForeground.value) {
                    sampleFrameGaps(sampleDurationMs)
                } else {
                    FrameGapStats(
                        sampled = false,
                        frameCount = 0,
                        maxGapMs = 0.0,
                        slowFrameCount = 0,
                        thresholdMs = FRAME_GAP_THRESHOLD_MS,
                    )
                }
            }
            mainThreadSample = mainThreadDeferred.await()
            frameGapStats = frameGapSampleDeferred.await()
        }

        val actualSampleDurationMs = (SystemClock.elapsedRealtime() - cpuSampleStartedAt).coerceAtLeast(1L)
        val cpuEnd = readCpuSnapshot(processPids)
        val threadCpuEnd = readThreadCpuSnapshot()
        val cpuStats = buildCpuStats(
            start = cpuStart,
            end = cpuEnd,
            threadStart = threadCpuStart,
            threadEnd = threadCpuEnd,
            durationMs = actualSampleDurationMs,
        )
        val jvmThreads = captureJvmThreads()
        val threadStats = buildThreadStats(
            mainThreadSample = mainThreadSample,
            cpuStats = cpuStats,
            jvmThreads = jvmThreads,
            frameGapStats = frameGapStats,
        )
        val memoryStats = readMemoryStats(processPids)
        val conversation = currentConversationId?.let(chatService::getCurrentConversationSnapshotOrNull)
        val sessionState = when {
            currentConversationId == null -> "none"
            conversation != null -> "loaded"
            else -> "unloaded"
        }
        val conversationJob = currentConversationId?.let(chatService::getConversationJobSnapshot)
        val generationState = when {
            conversationJob == null -> "idle"
            conversationJob.isCancelled && !conversationJob.isCompleted -> "cancelling"
            conversationJob.isActive -> "generating"
            else -> "idle"
        }
        val windowEvents = recorder.snapshot(
            sinceMs = captureStartedAt,
            limit = if (mode == DetectionMode.Deep) 240 else 96,
        )
        val recentEvents = recorder.snapshot(
            sinceMs = captureStartedAt - 30_000L,
            limit = if (mode == DetectionMode.Deep) 200 else 80,
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
        val chunkWindowStats = summarizeChunkWindow(
            events = windowEvents,
            conversationId = currentConversationId,
            sampleDurationMs = actualSampleDurationMs,
        )
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
                appCpuGrossPercent = cpuStats.appCpuGrossPercent,
                appCpuAdjustedPercent = cpuStats.appCpuAdjustedPercent,
                diagnosticSelfCpuPercent = cpuStats.diagnosticSelfCpuPercent,
                containerCpuPercent = cpuStats.containerCpuPercent,
                sampleDurationMs = actualSampleDurationMs,
            ),
            threads = threadStats,
            chat = ChatStats(
                conversationId = currentConversationId,
                sessionState = sessionState,
                messageNodes = conversation?.messageNodes?.size ?: 0,
                currentMessages = conversation?.currentMessages?.size ?: 0,
                lastMessageParts = lastMessage?.parts?.size ?: 0,
                toolPartsInLastMessage = toolParts.size,
                estimatedToolOutputChars = estimatedToolOutputChars,
                payloadEstimateBytes = payloadEstimateBytes,
                recentUpdateSource = recentUpdateSource,
                generationState = generationState,
                chunkEvents = chunkWindowStats.chunkEvents,
                chunkRatePerSecond = chunkWindowStats.chunkRatePerSecond,
                chunkAvgCostMs = chunkWindowStats.chunkAvgCostMs,
                chunkMaxCostMs = chunkWindowStats.chunkMaxCostMs,
                lastChunkPhase = chunkWindowStats.lastChunkPhase,
                chunkSaveInterleaved = chunkWindowStats.chunkSaveInterleaved,
                chunkNotificationInterleaved = chunkWindowStats.chunkNotificationInterleaved,
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

    private suspend fun sampleMainThread(
        sampleDurationMs: Long,
        intervalMs: Long,
    ): MainThreadSample {
        val mainThread = Looper.getMainLooper().thread
        val mainStateCounter = LinkedHashMap<String, Int>()
        val mainFrameCounter = LinkedHashMap<String, Int>()
        var mainThreadStack = emptyList<String>()
        val startedAt = SystemClock.elapsedRealtime()

        while (true) {
            val state = mainThread.state.name
            mainStateCounter[state] = (mainStateCounter[state] ?: 0) + 1
            val stack = mainThread.stackTrace.take(12).map { frame ->
                "${frame.className}.${frame.methodName}:${frame.lineNumber}"
            }
            if (stack.isNotEmpty()) {
                mainThreadStack = stack
                stack.take(4).forEach { frame ->
                    val key = frame.substringBeforeLast(':')
                    mainFrameCounter[key] = (mainFrameCounter[key] ?: 0) + 1
                }
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed >= sampleDurationMs) break
            delay(minOf(intervalMs, max(1L, sampleDurationMs - elapsed)))
        }

        return MainThreadSample(
            stateCounter = mainStateCounter,
            frameCounter = mainFrameCounter,
            mainThreadStack = mainThreadStack,
        )
    }

    private suspend fun sampleFrameGaps(sampleDurationMs: Long): FrameGapStats {
        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val choreographer = Choreographer.getInstance()
                val handler = Handler(Looper.getMainLooper())
                val thresholdNs = FRAME_GAP_THRESHOLD_MS * 1_000_000L
                var completed = false
                var frameCount = 0
                var lastFrameNs: Long? = null
                var maxGapNs = 0L
                var slowFrames = 0
                lateinit var callback: Choreographer.FrameCallback

                fun finish() {
                    if (completed || !continuation.isActive) return
                    completed = true
                    choreographer.removeFrameCallback(callback)
                    handler.removeCallbacksAndMessages(FRAME_GAP_TOKEN)
                    continuation.resume(
                        FrameGapStats(
                            sampled = frameCount > 0,
                            frameCount = frameCount,
                            maxGapMs = maxGapNs / 1_000_000.0,
                            slowFrameCount = slowFrames,
                            thresholdMs = FRAME_GAP_THRESHOLD_MS,
                        )
                    )
                }

                callback = Choreographer.FrameCallback { frameTimeNanos ->
                    frameCount++
                    lastFrameNs?.let { previous ->
                        val gapNs = frameTimeNanos - previous
                        if (gapNs > maxGapNs) {
                            maxGapNs = gapNs
                        }
                        if (gapNs >= thresholdNs) {
                            slowFrames++
                        }
                    }
                    lastFrameNs = frameTimeNanos
                    if (!completed) {
                        choreographer.postFrameCallback(callback)
                    }
                }

                continuation.invokeOnCancellation {
                    completed = true
                    choreographer.removeFrameCallback(callback)
                    handler.removeCallbacksAndMessages(FRAME_GAP_TOKEN)
                }

                choreographer.postFrameCallback(callback)
                handler.postAtTime({ finish() }, FRAME_GAP_TOKEN, SystemClock.uptimeMillis() + sampleDurationMs)
            }
        }
    }

    private fun buildThreadStats(
        mainThreadSample: MainThreadSample,
        cpuStats: CpuComputationResult,
        jvmThreads: List<JvmThreadSnapshot>,
        frameGapStats: FrameGapStats,
    ): ThreadStats {
        val usedJvmThreadIndexes = mutableSetOf<Int>()
        val mainThread = Looper.getMainLooper().thread
        val mainThreadState = mainThreadSample.stateCounter.maxByOrNull { it.value }?.key ?: mainThread.state.name
        val mainThreadSummary = mainThreadSample.frameCounter.entries
            .sortedByDescending { it.value }
            .take(3)
            .joinToString(" | ") { it.key }
            .ifBlank { "<no-samples>" }
        val topCpuThreads = cpuStats.perThreadCpuPercent.entries
            .filter { it.value > 0.0 }
            .sortedByDescending { it.value }
            .mapNotNull { (tid, cpuPercent) ->
                val procThread = cpuStats.endThreadSnapshots[tid] ?: return@mapNotNull null
                if (isDiagnosticsThread(procThread.name)) return@mapNotNull null
                val matchedJvm = findMatchingJvmThread(
                    procThreadName = procThread.name,
                    jvmThreads = jvmThreads,
                    usedIndexes = usedJvmThreadIndexes,
                )
                matchedJvm?.let { usedJvmThreadIndexes += it.first }
                val jvmSnapshot = matchedJvm?.second
                ThreadHotspot(
                    tid = tid,
                    name = jvmSnapshot?.name ?: procThread.name,
                    state = jvmSnapshot?.state ?: procStateToLabel(procThread.stateCode),
                    cpuPercent = cpuPercent.takeIf { it > 0.0 },
                    topFrame = when {
                        jvmSnapshot != null -> jvmSnapshot.topFrame
                        procThread.name == mainThread.name -> mainThreadSample.mainThreadStack.firstOrNull() ?: "<empty>"
                        else -> "<unknown>"
                    },
                )
            }
            .take(5)
        return ThreadStats(
            mainThreadState = mainThreadState,
            threadCount = cpuStats.endThreadSnapshots.size,
            mainThreadSummary = mainThreadSummary,
            mainThreadStack = mainThreadSample.mainThreadStack,
            topCpuThreads = topCpuThreads,
            frameGaps = frameGapStats,
        )
    }

    private fun summarizeChunkWindow(
        events: List<DiagnosticEvent>,
        conversationId: Uuid?,
        sampleDurationMs: Long,
    ): ChunkWindowStats {
        if (conversationId == null) {
            return ChunkWindowStats()
        }
        val relevantEvents = events.filter { it.conversationId == conversationId }
        val chunkEvents = relevantEvents.filter { it.category == "chunk-received" }
        val phaseEvents = relevantEvents.filter {
            it.category.startsWith("chunk-") || it.category == "generation-finish-save"
        }
        val chunkCosts = relevantEvents.mapNotNull { event ->
            event.costMs?.takeIf {
                event.category == "chunk-updateCurrentMessages" || event.category == "chunk-runtimeUpdate"
            }
        }
        val seconds = sampleDurationMs / 1000.0
        return ChunkWindowStats(
            chunkEvents = chunkEvents.size,
            chunkRatePerSecond = chunkEvents.size.takeIf { seconds > 0.0 }?.let { it / seconds },
            chunkAvgCostMs = chunkCosts.takeIf { it.isNotEmpty() }?.average(),
            chunkMaxCostMs = chunkCosts.maxOrNull(),
            lastChunkPhase = phaseEvents.lastOrNull()?.phase
                ?: phaseEvents.lastOrNull()?.category
                ?: "none",
            chunkSaveInterleaved = relevantEvents.any { it.category == "save" || it.category == "generation-finish-save" },
            chunkNotificationInterleaved = relevantEvents.any { it.category == "chunk-liveNotification" },
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
            appCpuTimeMs = Process.getElapsedCpuTime(),
            processCpuTimesMs = processPids.associateWith { readProcessCpuTimeMs(it) },
        )
    }

    private fun readThreadCpuSnapshot(): Map<Int, ProcThreadSnapshot> {
        val taskDir = File("/proc/self/task")
        val taskEntries = taskDir.listFiles().orEmpty()
        return buildMap(taskEntries.size) {
            taskEntries.forEach { entry ->
                val tid = entry.name.toIntOrNull() ?: return@forEach
                val snapshot = readProcThreadSnapshot(entry) ?: return@forEach
                put(tid, snapshot)
            }
        }
    }

    private fun buildCpuStats(
        start: CpuSample,
        end: CpuSample,
        threadStart: Map<Int, ProcThreadSnapshot>,
        threadEnd: Map<Int, ProcThreadSnapshot>,
        durationMs: Long,
    ): CpuComputationResult {
        val windowMs = durationMs.coerceAtLeast(1L).toDouble()
        val appDelta = (end.appCpuTimeMs - start.appCpuTimeMs).coerceAtLeast(0L)
        val grossAppCpu = if (appDelta > 0L) (appDelta.toDouble() / windowMs) * 100.0 else null
        val perProcess = end.processCpuTimesMs.mapValues { (pid, endTicks) ->
            val startTicks = start.processCpuTimesMs[pid] ?: 0L
            ((endTicks ?: 0L) - startTicks).coerceAtLeast(0L).let { delta ->
                (delta.toDouble() / windowMs) * 100.0
            }
        }
        val perThread = threadEnd.mapValues { (tid, endThread) ->
            val startThread = threadStart[tid]
            val delta = (endThread.cpuTimeMs - (startThread?.cpuTimeMs ?: 0L)).coerceAtLeast(0L)
            (delta.toDouble() / windowMs) * 100.0
        }
        val diagnosticSelfCpu = threadEnd.values
            .filter { isDiagnosticsThread(it.name) }
            .sumOf { thread -> perThread[thread.tid] ?: 0.0 }
            .takeIf { it > 0.0 }
        val adjustedAppCpu = grossAppCpu?.let { gross ->
            max(0.0, gross - (diagnosticSelfCpu ?: 0.0))
        }
        return CpuComputationResult(
            appCpuGrossPercent = grossAppCpu,
            appCpuAdjustedPercent = adjustedAppCpu,
            diagnosticSelfCpuPercent = diagnosticSelfCpu,
            containerCpuPercent = perProcess.values.sum().takeIf { it > 0.0 },
            perProcessCpuPercent = perProcess,
            perThreadCpuPercent = perThread,
            endThreadSnapshots = threadEnd,
        )
    }

    private fun captureJvmThreads(): List<JvmThreadSnapshot> {
        return Thread.getAllStackTraces()
            .map { (thread, stack) ->
                JvmThreadSnapshot(
                    name = thread.name,
                    state = thread.state.name,
                    topFrame = stack.firstOrNull()?.let { frame ->
                        "${frame.className}.${frame.methodName}:${frame.lineNumber}"
                    } ?: "<empty>",
                )
            }
            .filterNot { isDiagnosticsThread(it.name) }
    }

    private fun findMatchingJvmThread(
        procThreadName: String,
        jvmThreads: List<JvmThreadSnapshot>,
        usedIndexes: Set<Int>,
    ): Pair<Int, JvmThreadSnapshot>? {
        return jvmThreads.withIndex()
            .filterNot { it.index in usedIndexes }
            .filter { namesCompatible(procThreadName, it.value.name) }
            .minByOrNull { candidate ->
                when {
                    candidate.value.name == procThreadName -> 0
                    candidate.value.name.startsWith(procThreadName) -> 1
                    procThreadName.startsWith(candidate.value.name) -> 2
                    else -> 3
                }
            }
            ?.let { it.index to it.value }
    }

    private fun namesCompatible(procThreadName: String, jvmThreadName: String): Boolean {
        return procThreadName == jvmThreadName ||
            jvmThreadName.startsWith(procThreadName) ||
            procThreadName.startsWith(jvmThreadName)
    }

    private fun isDiagnosticsThread(name: String): Boolean {
        return name.startsWith(DIAGNOSTIC_THREAD_PREFIX)
    }

    private fun readProcThreadSnapshot(taskEntry: File): ProcThreadSnapshot? {
        return runCatching {
            val content = taskEntry.resolve("stat").readText()
            val nameStart = content.indexOf('(')
            val nameEnd = content.lastIndexOf(") ")
            if (nameStart == -1 || nameEnd == -1) return@runCatching null
            val threadName = content.substring(nameStart + 1, nameEnd)
            val suffix = content.substring(nameEnd + 2)
            val fields = suffix.split(' ')
            val stateCode = fields.getOrNull(0)?.firstOrNull() ?: '?'
            val utime = fields.getOrNull(11)?.toLongOrNull() ?: return@runCatching null
            val stime = fields.getOrNull(12)?.toLongOrNull() ?: return@runCatching null
            ProcThreadSnapshot(
                tid = taskEntry.name.toInt(),
                name = threadName,
                stateCode = stateCode,
                cpuTimeMs = (utime + stime) * PROC_TICK_MS,
            )
        }.getOrNull()
    }

    private fun procStateToLabel(stateCode: Char): String = when (stateCode) {
        'R' -> "RUNNABLE"
        'S' -> "SLEEPING"
        'D' -> "WAITING_IO"
        'T' -> "STOPPED"
        't' -> "TRACING"
        'Z' -> "ZOMBIE"
        'X', 'x' -> "DEAD"
        'K' -> "WAKEKILL"
        'W' -> "WAKING"
        'P' -> "PARKED"
        else -> stateCode.toString()
    }

    private fun readProcessCpuTimeMs(pid: Int): Long? {
        return runCatching {
            val content = File("/proc/$pid/stat").readText()
            val processSuffix = content.substringAfterLast(") ")
            val fields = processSuffix.split(' ')
            val utime = fields.getOrNull(11)?.toLongOrNull() ?: return@runCatching null
            val stime = fields.getOrNull(12)?.toLongOrNull() ?: return@runCatching null
            (utime + stime) * PROC_TICK_MS
        }.getOrNull()
    }

    private fun readProcessRssKb(pid: Int): Long? {
        val lines = runCatching { File("/proc/$pid/status").readLines() }.getOrNull() ?: return null
        val rssLine = lines.firstOrNull { it.startsWith("VmRSS:") } ?: return null
        return rssLine.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull()
    }

    private data class CpuSample(
        val appCpuTimeMs: Long,
        val processCpuTimesMs: Map<Int, Long?>,
    )

    private data class ProcThreadSnapshot(
        val tid: Int,
        val name: String,
        val stateCode: Char,
        val cpuTimeMs: Long,
    )

    private data class CpuComputationResult(
        val appCpuGrossPercent: Double?,
        val appCpuAdjustedPercent: Double?,
        val diagnosticSelfCpuPercent: Double?,
        val containerCpuPercent: Double?,
        val perProcessCpuPercent: Map<Int, Double>,
        val perThreadCpuPercent: Map<Int, Double>,
        val endThreadSnapshots: Map<Int, ProcThreadSnapshot>,
    )

    private data class JvmThreadSnapshot(
        val name: String,
        val state: String,
        val topFrame: String,
    )

    private data class MainThreadSample(
        val stateCounter: Map<String, Int>,
        val frameCounter: Map<String, Int>,
        val mainThreadStack: List<String>,
    )

    private data class ChunkWindowStats(
        val chunkEvents: Int = 0,
        val chunkRatePerSecond: Double? = null,
        val chunkAvgCostMs: Double? = null,
        val chunkMaxCostMs: Long? = null,
        val lastChunkPhase: String = "none",
        val chunkSaveInterleaved: Boolean = false,
        val chunkNotificationInterleaved: Boolean = false,
    )

    companion object {
        private const val PROC_TICK_MS = 10L
        private const val DIAGNOSTIC_THREAD_PREFIX = "PerfDiag"
        private const val FRAME_GAP_THRESHOLD_MS = 32L
        private val FRAME_GAP_TOKEN = Any()
    }
}
