package me.rerere.rikkahub.debug

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.rerere.rikkahub.debug.model.DebugLogEntry
import me.rerere.rikkahub.debug.model.LogLevel
import java.io.File
import kotlin.uuid.Uuid

/**
 * 极简调试日志管理器（单例）
 *
 * 职责：
 * 1. 管理环形缓冲区
 * 2. 提供结构化日志接口
 * 3. 异步持久化关键日志
 * 4. 支持日志标记功能
 */
class DebugLogger private constructor(
    private val context: Context
) {
    // 环形缓冲区
    private val ringBuffer = RingBuffer<DebugLogEntry>(capacity = 200)

    // 问题标记列表
    private val problemMarkers = mutableListOf<ProblemMarker>()

    // IO 协程作用域
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 日志文件目录
    private val logDir: File = context.filesDir.resolve("debug_logs").apply { mkdirs() }

    companion object {
        @Volatile
        private var INSTANCE: DebugLogger? = null

        fun getInstance(context: Context): DebugLogger {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DebugLogger(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * 记录日志（核心方法）
     *
     * 性能优化：
     * - 同步写入内存缓冲区（O(1)）
     * - 异步持久化关键日志
     *
     * @param level 日志级别
     * @param tag 标签（如 "ChatService", "IntentRouter"）
     * @param message 消息
     * @param data 附加数据（可选）
     */
    fun log(
        level: LogLevel,
        tag: String,
        message: String,
        data: Map<String, Any?>? = null
    ) {
        val entry = DebugLogEntry(
            id = Uuid.random().toString(),
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            data = data
        )

        // 同步写入环形缓冲区（极快）
        ringBuffer.add(entry)

        // 异步持久化重要日志（ERROR 级别）
        if (level == LogLevel.ERROR) {
            persistLogAsync(entry)
        }
    }

    /**
     * 标记当前问题
     *
     * 用法：用户遇到问题时点击"标记问题"按钮
     *
     * @param description 问题描述
     */
    fun markProblem(description: String) {
        val marker = ProblemMarker(
            id = Uuid.random().toString(),
            timestamp = System.currentTimeMillis(),
            description = description,
            logIndex = ringBuffer.size() - 1
        )
        problemMarkers.add(marker)

        // 记录标记事件到日志
        log(
            level = LogLevel.INFO,
            tag = "DebugLogger",
            message = "Problem marked: $description",
            data = mapOf("markerId" to marker.id)
        )
    }

    /**
     * 获取标记前后的日志
     *
     * @param markerId 标记 ID
     * @param beforeCount 前置日志条数
     * @param afterCount 后置日志条数
     * @return 日志列表
     */
    fun getLogsAroundMarker(
        markerId: String,
        beforeCount: Int = 50,
        afterCount: Int = 50
    ): List<DebugLogEntry>? {
        val marker = problemMarkers.find { it.id == markerId } ?: return null
        val allLogs = ringBuffer.getAll()

        val startIndex = maxOf(0, marker.logIndex - beforeCount)
        val endIndex = minOf(allLogs.size, marker.logIndex + afterCount + 1)

        return allLogs.subList(startIndex, endIndex)
    }

    /**
     * 获取最近 N 条日志
     */
    fun getRecentLogs(count: Int = 200): List<DebugLogEntry> {
        return ringBuffer.getRecent(count)
    }

    /**
     * 获取所有问题标记
     */
    fun getAllMarkers(): List<ProblemMarker> = problemMarkers.toList()

    /**
     * 清空日志
     */
    fun clear() {
        ringBuffer.clear()
        problemMarkers.clear()
    }

    /**
     * 异步持久化日志
     */
    private fun persistLogAsync(entry: DebugLogEntry) {
        ioScope.launch {
            try {
                val file = logDir.resolve("error_${entry.timestamp}.log")
                file.writeText(entry.toString())
            } catch (e: Exception) {
                // 避免递归崩溃
                e.printStackTrace()
            }
        }
    }

    /**
     * 问题标记数据类
     */
    data class ProblemMarker(
        val id: String,
        val timestamp: Long,
        val description: String,
        val logIndex: Int
    )
}
