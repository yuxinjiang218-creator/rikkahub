package me.rerere.rikkahub.service.recall.planner

/**
 * Planner 日志接口（Phase L 收尾：解决单测环境 Log 依赖问题）
 *
 * 提供极简的日志接口，避免直接依赖 android.util.Log
 */
interface PlannerLogger {
    fun warn(message: String, throwable: Throwable? = null)
}

/**
 * 默认实现：使用 android.util.Log
 */
class DefaultPlannerLogger(private val tag: String) : PlannerLogger {
    override fun warn(message: String, throwable: Throwable?) {
        if (throwable != null) {
            android.util.Log.w(tag, message, throwable)
        } else {
            android.util.Log.w(tag, message)
        }
    }
}

/**
 * 单测用：空操作 Logger（不输出任何日志）
 */
class NoOpPlannerLogger : PlannerLogger {
    override fun warn(message: String, throwable: Throwable?) {
        // 空操作，避免单测环境 Log 崩溃
    }
}
