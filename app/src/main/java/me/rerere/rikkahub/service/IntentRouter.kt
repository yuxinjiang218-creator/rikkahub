package me.rerere.rikkahub.service

import android.content.Context
import me.rerere.rikkahub.debug.DebugLogger
import me.rerere.rikkahub.debug.model.LogLevel

/**
 * 路由枚举
 */
enum class Route {
    VERBATIM,  // 逐字回收路径
    SEMANTIC   // 语义回填路径
}

/**
 * 意图路由器（写死，无 LLM）
 *
 * 用于判定用户查询应该走 VERBATIM 还是 SEMANTIC 路径
 * 两条路径互斥，根据关键词和书名号模式匹配
 */
object IntentRouter {
    private lateinit var context: Context

    // 逐字回收关键词列表（写死）
    private val verbatimKeywords = listOf(
        "复述", "原文", "全文", "逐字", "一字不差",
        "把", "贴出来", "引用", "原诗", "原代码", "那段"
    )

    // 书名号模式（写死）
    private val titlePattern = Regex("《([^》]{1,40})》")

    /**
     * 初始化路由器
     */
    fun init(context: Context) {
        this.context = context.applicationContext
    }

    /**
     * 路由判定（写死）
     *
     * 满足任一条件即返回 VERBATIM：
     * 1. 包含任一 verbatim keyword
     * 2. 包含书名号模式《...》
     *
     * @param lastUserText 用户最新的输入文本
     * @return VERBATIM 或 SEMANTIC
     */
    fun routeIntent(lastUserText: String): Route {
        val debugLogger = DebugLogger.getInstance(context)

        if (verbatimKeywords.any { lastUserText.contains(it) }) {
            val trigger = verbatimKeywords.first { lastUserText.contains(it) }
            debugLogger.log(
                level = LogLevel.INFO,
                tag = "IntentRouter",
                message = "VERBATIM route (keyword match)",
                data = mapOf("trigger" to trigger)
            )
            return Route.VERBATIM
        }

        if (titlePattern.containsMatchIn(lastUserText)) {
            val titles = extractTitles(lastUserText)
            debugLogger.log(
                level = LogLevel.INFO,
                tag = "IntentRouter",
                message = "VERBATIM route (title match)",
                data = mapOf("titles" to titles)
            )
            return Route.VERBATIM
        }

        debugLogger.log(
            level = LogLevel.INFO,
            tag = "IntentRouter",
            message = "SEMANTIC route (default)"
        )
        return Route.SEMANTIC
    }

    /**
     * 提取书名号内容（最多3个）
     *
     * @param text 输入文本
     * @return 提取的标题列表
     */
    fun extractTitles(text: String): List<String> {
        return titlePattern.findAll(text)
            .map { it.groupValues[1] }
            .take(3)
            .toList()
    }
}
