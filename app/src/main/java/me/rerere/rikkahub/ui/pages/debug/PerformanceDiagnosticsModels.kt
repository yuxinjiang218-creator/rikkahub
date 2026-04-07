package me.rerere.rikkahub.ui.pages.debug

import me.rerere.rikkahub.data.container.BackgroundProcessInfo
import me.rerere.rikkahub.service.CompressionUiState
import me.rerere.rikkahub.service.LedgerGenerationUiState
import kotlin.uuid.Uuid

enum class DetectionMode {
    Snapshot,
    Deep,
}

data class DiagnosticRouteState(
    val screenLabel: String = "none",
    val conversationId: Uuid? = null,
)

data class DiagnosticEvent(
    val timestampMs: Long,
    val category: String,
    val detail: String,
    val conversationId: Uuid? = null,
)

data class FormattedDiagnosticsReport(
    val mode: DetectionMode,
    val title: String,
    val text: String,
    val capturedAtLabel: String,
)

data class DiagnosticsUiState(
    val overlayVisible: Boolean = false,
    val overlayExpanded: Boolean = false,
    val route: DiagnosticRouteState = DiagnosticRouteState(),
    val activeMode: DetectionMode = DetectionMode.Snapshot,
    val isCapturing: Boolean = false,
    val currentReport: FormattedDiagnosticsReport? = null,
    val lastSnapshotReport: FormattedDiagnosticsReport? = null,
    val lastDeepReport: FormattedDiagnosticsReport? = null,
    val lastError: String? = null,
)

data class MemoryStats(
    val heapUsedBytes: Long,
    val heapCapBytes: Long,
    val memoryClassMb: Int,
    val largeMemoryClassMb: Int,
    val appPssKb: Int,
    val appPrivateDirtyKb: Int,
    val appNativePrivateDirtyKb: Int,
    val systemAvailMemBytes: Long,
    val systemLowMemory: Boolean,
    val containerTotalRssKb: Long,
)

data class CpuStats(
    val appCpuPercent: Double?,
    val containerCpuPercent: Double?,
    val sampleDurationMs: Long,
)

data class ThreadHotspot(
    val name: String,
    val state: String,
    val activityScore: Int,
    val topFrame: String,
)

data class ThreadStats(
    val mainThreadState: String,
    val threadCount: Int,
    val mainThreadSummary: String,
    val mainThreadStack: List<String>,
    val topBusyThreads: List<ThreadHotspot>,
)

data class ChatStats(
    val conversationId: Uuid?,
    val messageNodes: Int,
    val currentMessages: Int,
    val lastMessageParts: Int,
    val toolPartsInLastMessage: Int,
    val estimatedToolOutputChars: Int,
    val payloadEstimateBytes: Int,
    val recentUpdateSource: String,
    val generationState: String,
)

data class TaskStats(
    val compressionState: CompressionUiState?,
    val ledgerState: LedgerGenerationUiState?,
    val memoryIndexStatus: String,
    val titleRunning: Boolean,
    val suggestionRunning: Boolean,
    val generationJobRunning: Boolean,
    val backgroundJobsCount: Int,
)

data class ContainerProcessSnapshot(
    val info: BackgroundProcessInfo,
    val rssKb: Long?,
    val cpuPercent: Double?,
)

data class ContainerStats(
    val isRunning: Boolean,
    val processCount: Int,
    val processes: List<ContainerProcessSnapshot>,
    val heavyProcessHint: String,
)

data class PerformanceSnapshotReport(
    val mode: DetectionMode,
    val capturedAtMs: Long,
    val route: DiagnosticRouteState,
    val isForeground: Boolean,
    val snapshotCostMs: Long,
    val memory: MemoryStats,
    val cpu: CpuStats,
    val threads: ThreadStats,
    val chat: ChatStats,
    val tasks: TaskStats,
    val container: ContainerStats,
    val events: List<DiagnosticEvent>,
)
