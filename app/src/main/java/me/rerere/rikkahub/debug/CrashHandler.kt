package me.rerere.rikkahub.debug

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简全局异常处理器
 *
 * 职责：
 * 1. 捕获所有未处理异常
 * 2. 生成崩溃报告（纯文本）
 * 3. 保存到 filesDir/crash/
 * 4. 重启应用后弹窗提示
 */
class CrashHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    // 崩溃日志目录
    private val crashDir: File = context.filesDir.resolve("crash").apply { mkdirs() }

    // 日期格式化
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // 1. 生成崩溃报告
        val report = generateCrashReport(thread, throwable)

        // 2. 保存到文件
        saveCrashReport(report)

        // 3. 调用默认处理器（终止应用）
        defaultHandler?.uncaughtException(thread, throwable)
    }

    /**
     * 生成崩溃报告
     */
    private fun generateCrashReport(thread: Thread, throwable: Throwable): String {
        val stackTrace = StringWriter().apply {
            PrintWriter(this).use { throwable.printStackTrace(it) }
        }.toString()

        // 获取最近 200 条调试日志
        val debugLogger = DebugLogger.getInstance(context)
        val recentLogs = debugLogger.getRecentLogs(200)

        return buildString {
            appendLine("=== RikkaHub Crash Report ===")
            appendLine()
            appendLine("Time: ${dateFormat.format(Date())}")
            appendLine("Exception: ${throwable.javaClass.simpleName}")
            appendLine("Message: ${throwable.message ?: "No message"}")
            appendLine()
            appendLine("=== Device Info ===")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine("=== Stack Trace ===")
            appendLine(stackTrace)
            appendLine()
            appendLine("=== Recent Logs (Last 200) ===")
            recentLogs.take(200).forEach {
                appendLine("[${it.level}] ${it.tag}: ${it.message}")
                it.data?.forEach { (key, value) ->
                    appendLine("  $key: $value")
                }
            }
        }
    }

    /**
     * 保存崩溃报告
     */
    private fun saveCrashReport(report: String) {
        try {
            val file = crashDir.resolve("crash_${System.currentTimeMillis()}.txt")
            file.writeText(report)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 检查是否有未处理的崩溃
     *
     * @return 崩溃报告文件列表
     */
    fun checkPendingCrashes(): List<File> {
        return crashDir.listFiles()?.filter {
            it.name.startsWith("crash_") && it.name.endsWith(".txt")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    companion object {
        /**
         * 安装全局异常处理器
         */
        fun install(context: Context) {
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            val crashHandler = CrashHandler(context, defaultHandler)
            Thread.setDefaultUncaughtExceptionHandler(crashHandler)
        }
    }
}
