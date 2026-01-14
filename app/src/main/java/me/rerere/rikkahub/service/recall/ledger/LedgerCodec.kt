package me.rerere.rikkahub.service.recall.ledger

import android.content.Context
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.debug.DebugLogger
import me.rerere.rikkahub.debug.model.LogLevel
import me.rerere.rikkahub.service.recall.model.ProbeLedgerState

/**
 * 探针账本编解码器
 *
 * 提供 JSON 序列化/反序列化功能，用于将 ProbeLedgerState 持久化到数据库。
 */
object LedgerCodec {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 从 JSON 字符串解码账本，失败时返回空账本
     *
     * @param jsonStr JSON 字符串（可为 null、空字符串、"{}"）
     * @param context Android Context（用于日志记录，可为 null）
     * @return 解码后的账本，失败时返回空账本
     */
    fun decodeOrEmpty(jsonStr: String?, context: Context?): ProbeLedgerState {
        // 空/空白/空对象 => 返回空账本
        if (jsonStr.isNullOrBlank() || jsonStr == "{}") {
            return ProbeLedgerState()
        }

        return try {
            json.decodeFromString<ProbeLedgerState>(jsonStr)
        } catch (e: SerializationException) {
            // 解析失败 => 返回空账本，记录 debug 日志
            context?.let {
                DebugLogger.getInstance(it).log(
                    LogLevel.DEBUG,
                    "LedgerCodec",
                    "Ledger decode failed, fallback to empty",
                    mapOf("error" to (e.message ?: "unknown"))
                )
            }
            ProbeLedgerState()
        } catch (e: IllegalArgumentException) {
            // JSON 格式错误 => 返回空账本
            context?.let {
                DebugLogger.getInstance(it).log(
                    LogLevel.DEBUG,
                    "LedgerCodec",
                    "Ledger JSON invalid, fallback to empty",
                    mapOf("error" to (e.message ?: "unknown"))
                )
            }
            ProbeLedgerState()
        }
    }

    /**
     * 将账本编码为 JSON 字符串
     *
     * @param ledger 账本状态
     * @return JSON 字符串
     */
    fun encodeToString(ledger: ProbeLedgerState): String {
        return json.encodeToString(ledger)
    }
}
