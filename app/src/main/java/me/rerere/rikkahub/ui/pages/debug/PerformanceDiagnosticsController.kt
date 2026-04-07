package me.rerere.rikkahub.ui.pages.debug

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class PerformanceDiagnosticsController(
    private val snapshotService: PerformanceSnapshotService,
    private val formatter: PerformanceSnapshotFormatter,
    private val recorder: PerformanceDiagnosticsRecorder,
) {
    private val diagnosticsDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PerfDiagWorker").apply {
            isDaemon = true
        }
    }.asCoroutineDispatcher()
    private val diagnosticsScope = CoroutineScope(
        SupervisorJob() + diagnosticsDispatcher + CoroutineName("PerfDiagWorkerScope")
    )

    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    fun showOverlay() {
        recorder.setEnabled(true)
        _uiState.update { it.copy(overlayVisible = true, overlayExpanded = false, lastError = null) }
    }

    fun hideOverlay() {
        recorder.setEnabled(false)
        _uiState.update {
            it.copy(
                overlayVisible = false,
                overlayExpanded = false,
                isCapturing = false,
                lastError = null,
            )
        }
    }

    fun setOverlayExpanded(expanded: Boolean) {
        _uiState.update { it.copy(overlayExpanded = expanded) }
    }

    fun updateRoute(route: DiagnosticRouteState) {
        _uiState.update { current ->
            if (current.route == route) current else current.copy(route = route)
        }
    }

    fun runDetection(mode: DetectionMode) {
        if (_uiState.value.isCapturing) return
        recorder.setEnabled(true)
        _uiState.update { it.copy(isCapturing = true, activeMode = mode, lastError = null) }
        val route = _uiState.value.route
        diagnosticsScope.launch {
            try {
                val report = snapshotService.capture(route, mode)
                val formatted = formatter.format(report)
                withContext(Dispatchers.Main.immediate) {
                    _uiState.update { state ->
                        state.copy(
                            isCapturing = false,
                            activeMode = mode,
                            currentReport = formatted,
                            lastSnapshotReport = if (mode == DetectionMode.Snapshot) formatted else state.lastSnapshotReport,
                            lastDeepReport = if (mode == DetectionMode.Deep) formatted else state.lastDeepReport,
                        )
                    }
                    if (!_uiState.value.overlayVisible) {
                        recorder.setEnabled(false)
                    }
                }
            } catch (_: CancellationException) {
                withContext(Dispatchers.Main.immediate) {
                    _uiState.update { it.copy(isCapturing = false) }
                    if (!_uiState.value.overlayVisible) {
                        recorder.setEnabled(false)
                    }
                }
            } catch (error: Throwable) {
                withContext(Dispatchers.Main.immediate) {
                    _uiState.update {
                        it.copy(
                            isCapturing = false,
                            lastError = error.message ?: error::class.simpleName ?: "unknown error",
                        )
                    }
                    if (!_uiState.value.overlayVisible) {
                        recorder.setEnabled(false)
                    }
                }
            }
        }
    }
}
