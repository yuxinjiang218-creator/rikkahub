package me.rerere.rikkahub.data.analytics

/**
 * 埋点接口（无 Firebase 依赖）
 */
interface Analytics {
    fun logEvent(name: String, params: Map<String, Any?>? = null)
}

/**
 * No-Op 实现（不进行任何埋点）
 */
object NoOpAnalytics : Analytics {
    override fun logEvent(name: String, params: Map<String, Any?>?) {
        // No-op: 不进行任何埋点操作
    }
}
