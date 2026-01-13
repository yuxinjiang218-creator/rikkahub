package me.rerere.rikkahub.debug.model

/**
 * 极简日志条目
 *
 * 设计要点：
 * - 结构化数据（便于过滤和分析）
 * - 轻量级（避免大量内存占用）
 */
data class DebugLogEntry(
    val id: String,
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val data: Map<String, Any?>? = null
) {
    override fun toString(): String {
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))

        val dataStr = data?.map { "${it.key}=${it.value}" }?.joinToString(", ") ?: ""

        return "[$time] [$level] [$tag] $message ${if (dataStr.isNotEmpty()) "[$dataStr]" else ""}"
    }
}

/**
 * 日志级别
 */
enum class LogLevel {
    VERBOSE,  // 详细信息
    DEBUG,    // 调试信息
    INFO,     // 一般信息
    WARN,     // 警告
    ERROR     // 错误
}
