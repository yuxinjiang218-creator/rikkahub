package me.rerere.rikkahub.service.recall

import me.rerere.rikkahub.debug.DebugLogger
import me.rerere.rikkahub.debug.model.LogLevel
import kotlin.system.measureTimeMillis

/**
 * 召回性能监控器
 *
 * 用于监控召回流程的各个阶段性能，确保总响应时间 < 600ms
 */
object RecallPerformanceMonitor {
    private const val TAG = "RecallPerformance"

    // 性能阈值（单位：毫秒）
    private const val TARGET_TOTAL_TIME = 600L  // 总目标时间
    private const val TARGET_NEED_GATE = 10L     // NeedGate 阶段
    private const val TARGET_P_SOURCE = 200L     // P源候选生成
    private const val TARGET_A_SOURCE = 300L     // A源候选生成（含 embedding）
    private const val TARGET_SCORING = 50L       // 评分阶段
    private const val TARGET_DECISION = 10L      // 决策阶段
    private const val TARGET_INJECTION = 10L     // 注入块构建

    /**
     * 性能度量数据
     */
    data class PerformanceMetrics(
        val needGateTime: Long = 0,
        val pSourceTime: Long = 0,
        val aSourceTime: Long = 0,
        val scoringTime: Long = 0,
        val decisionTime: Long = 0,
        val injectionTime: Long = 0,
        val totalTime: Long = 0
    ) {
        fun isWithinTarget(): Boolean {
            return totalTime <= TARGET_TOTAL_TIME
        }

        fun getBottleneck(): String {
            val times = mapOf(
                "NeedGate" to needGateTime,
                "P源候选" to pSourceTime,
                "A源候选" to aSourceTime,
                "评分" to scoringTime,
                "决策" to decisionTime,
                "注入" to injectionTime
            )
            return times.maxByOrNull { it.value }?.key ?: "未知"
        }
    }

    /**
     * 测量并记录函数执行时间
     */
    suspend fun <T> measureStage(
        stageName: String,
        targetTime: Long,
        context: android.content.Context,
        block: suspend () -> T
    ): T {
        var result: T? = null
        val elapsedTime = measureTimeMillis {
            result = block()
        }

        // 如果超过目标时间，记录警告
        if (elapsedTime > targetTime) {
            DebugLogger.getInstance(context).log(
                LogLevel.WARN,
                TAG,
                "Stage exceeded target time",
                mapOf(
                    "stage" to stageName,
                    "actual" to "${elapsedTime}ms",
                    "target" to "${targetTime}ms",
                    "exceeded" to "${elapsedTime - targetTime}ms"
                )
            )
        } else {
            DebugLogger.getInstance(context).log(
                LogLevel.DEBUG,
                TAG,
                "Stage completed",
                mapOf(
                    "stage" to stageName,
                    "time" to "${elapsedTime}ms"
                )
            )
        }

        return result!!
    }

    /**
     * 测量函数执行时间（返回结果和时间）
     */
    fun <T> measureAndTime(block: () -> T): Pair<T, Long> {
        var result: T? = null
        val time = measureTimeMillis {
            result = block()
        }
        return result!! to time
    }

    /**
     * 记录总性能指标
     */
    fun logTotalMetrics(
        context: android.content.Context,
        metrics: PerformanceMetrics
    ) {
        val debugLogger = DebugLogger.getInstance(context)

        val level = if (metrics.isWithinTarget()) {
            LogLevel.INFO
        } else {
            LogLevel.WARN
        }

        debugLogger.log(
            level,
            TAG,
            "Recall performance summary",
            mapOf(
                "total" to "${metrics.totalTime}ms",
                "target" to "${TARGET_TOTAL_TIME}ms",
                "withinTarget" to metrics.isWithinTarget(),
                "needGate" to "${metrics.needGateTime}ms",
                "pSource" to "${metrics.pSourceTime}ms",
                "aSource" to "${metrics.aSourceTime}ms",
                "scoring" to "${metrics.scoringTime}ms",
                "decision" to "${metrics.decisionTime}ms",
                "injection" to "${metrics.injectionTime}ms",
                "bottleneck" to metrics.getBottleneck()
            )
        )
    }

    /**
     * 获取性能建议
     */
    fun getOptimizationSuggestions(metrics: PerformanceMetrics): List<String> {
        val suggestions = mutableListOf<String>()

        if (metrics.needGateTime > TARGET_NEED_GATE) {
            suggestions.add("NeedGate 耗时过长，考虑简化启发式规则")
        }

        if (metrics.pSourceTime > TARGET_P_SOURCE) {
            suggestions.add("P源候选生成耗时过长，考虑优化 FTS4 查询或减少窗口大小")
        }

        if (metrics.aSourceTime > TARGET_A_SOURCE) {
            suggestions.add("A源候选生成耗时过长，考虑减少 embedding 次数或使用缓存")
        }

        if (metrics.scoringTime > TARGET_SCORING) {
            suggestions.add("评分耗时过长，考虑简化评分公式或缓存结果")
        }

        if (metrics.totalTime > TARGET_TOTAL_TIME) {
            suggestions.add("总耗时超过 ${TARGET_TOTAL_TIME}ms，当前为 ${metrics.totalTime}ms")
        }

        if (suggestions.isEmpty()) {
            suggestions.add("性能表现良好，所有阶段都在目标时间内")
        }

        return suggestions
    }
}
