package me.rerere.rikkahub.service

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.db.dao.MessageNodeTextDao
import me.rerere.rikkahub.data.db.dao.VerbatimArtifactDao
import me.rerere.rikkahub.data.db.entity.VerbatimArtifactEntity
import me.rerere.rikkahub.debug.DebugLogger
import me.rerere.rikkahub.debug.model.LogLevel
import me.rerere.rikkahub.util.normalizeForSearch
import kotlin.math.max
import kotlin.math.min

/**
 * Verbatim Recall 服务（逐字回收服务）
 *
 * 职责：
 * 1. 实现两阶段检索（title 匹配 → FTS5 兜底）
 * 2. 从 message_node_text 回拼逐字原文
 * 3. 生成 [VERBATIM_RECALL] 注入块
 */
class VerbatimRecallService(
    private val context: Context,
    private val messageNodeTextDao: MessageNodeTextDao,
    private val verbatimArtifactDao: VerbatimArtifactDao
) {
    companion object {
        private const val MAX_VERBATIM_CHARS = 6000  // 逐字回收字符上限（稳定性护栏）
    }

    /**
     * 逐字召回主入口（两阶段检索）
     *
     * @param conversationId 会话 ID
     * @param lastUserText 用户最新的输入文本
     * @return VERBATIM_RECALL 注入块，null 表示未命中
     */
    suspend fun recallVerbatim(
        conversationId: String,
        lastUserText: String
    ): String? {
        val debugLogger = DebugLogger.getInstance(context)

        // 阶段一：title 匹配 + 回拼原文
        val titles = IntentRouter.extractTitles(lastUserText)

        if (titles.isNotEmpty()) {
            debugLogger.log(
                LogLevel.DEBUG,
                "VerbatimRecall",
                "Title matching",
                mapOf("titles" to titles)
            )

            val verbatimText = searchByTitleAndReconstruct(conversationId, titles)
            if (verbatimText != null) {
                debugLogger.log(
                    LogLevel.INFO,
                    "VerbatimRecall",
                    "Title matched",
                    mapOf("charCount" to verbatimText.length)
                )
                return verbatimText  // 返回已组装的 [VERBATIM_RECALL] 注入块
            }
        }

        // 阶段二：FTS5 兜底检索
        debugLogger.log(LogLevel.DEBUG, "VerbatimRecall", "FTS5 fallback")

        val qNorm = normalizeForSearch(lastUserText)
        val tokens = qNorm.split(" ").filter { it.isNotEmpty() }.distinct().take(16)
        if (tokens.isEmpty()) return null

        val matchQuery = tokens.joinToString(" OR ") { "\"$it\"" }

        // FTS 查询（使用 RawQuery 绕过 Room 验证）
        val ftsQuery = SimpleSQLiteQuery(
            """
            SELECT node_id, node_index, bm25(message_node_fts) AS score
            FROM message_node_fts
            WHERE message_node_fts MATCH ?
            AND conversation_id = ?
            AND role = ?
            ORDER BY score DESC
            LIMIT 10
            """.trimIndent(),
            arrayOf(matchQuery, conversationId, MessageRole.USER.ordinal)
        )

        val ftsResults = messageNodeTextDao.searchByFts(ftsQuery)

        if (ftsResults.isEmpty()) return null

        // 获取当前会话的真实最大 node_index（从数据库查询，而非 ftsResults.max）
        val maxIndex = messageNodeTextDao.getMaxNodeIndex(conversationId) ?: return null

        // 窗口扩展：{idx-1, idx, idx+1}（写死上下界过滤）
        val expandedIndices = expandWindow(ftsResults, maxIndex)

        // 批量获取完整记录
        val messageNodes = messageNodeTextDao.getByConversationIdAndIndices(
            conversationId = conversationId,
            indices = expandedIndices
        )

        // 拼接逐字文本
        val verbatimText = messageNodes.joinToString("\n\n") { it.rawText }

        // 应用字符数截断并构建注入块
        val result = buildVerbatimInjection(
            conversationId = conversationId,
            nodeIndices = expandedIndices,
            verbatimText = verbatimText
        )

        val isTruncated = verbatimText.length > MAX_VERBATIM_CHARS
        debugLogger.log(
            LogLevel.INFO,
            "VerbatimRecall",
            "Recall completed",
            mapOf(
                "charCount" to verbatimText.length,
                "isTruncated" to isTruncated,
                "nodeCount" to expandedIndices.size
            )
        )

        return result
    }

    /**
     * 阶段一：title 匹配 + 从 message_node_text 回拼原文（修正版）
     *
     * @param conversationId 会话 ID
     * @param titles 提取的标题列表
     * @return VERBATIM_RECALL 注入块，null 表示未命中
     */
    private suspend fun searchByTitleAndReconstruct(
        conversationId: String,
        titles: List<String>
    ): String? {
        // 1. 对每个 title 查询 verbatim_artifact
        for (title in titles) {
            val artifacts = verbatimArtifactDao.getByConversationIdAndTitle(
                conversationId = conversationId,
                title = title
            )
            if (artifacts.isNotEmpty()) {
                val artifact = artifacts.first()  // 取最新的一条

                // 2. 使用 start_node_index 和 end_node_index 从 message_node_text 回拼原文
                val nodeIndices = (artifact.startNodeIndex..artifact.endNodeIndex).toList()
                val messageNodes = messageNodeTextDao.getByConversationIdAndIndices(
                    conversationId = conversationId,
                    indices = nodeIndices
                )

                if (messageNodes.isNotEmpty()) {
                    // 3. 拼接 raw_text（而非 content_sha256）
                    val verbatimText = messageNodes.joinToString("\n\n") { it.rawText }

                    // 4. 构建注入块
                    return buildVerbatimInjection(
                        conversationId = conversationId,
                        nodeIndices = nodeIndices,
                        verbatimText = verbatimText
                    )
                }
            }
        }
        return null
    }

    /**
     * 窗口扩展：{idx-1, idx, idx+1}（写死上下界过滤）
     *
     * @param ftsResults FTS 查询结果
     * @param maxIndex 会话的最大 node_index
     * @return 扩展后的索引列表
     */
    private fun expandWindow(
        ftsResults: List<me.rerere.rikkahub.data.db.dao.FtsSearchResult>,
        maxIndex: Int
    ): List<Int> {
        val expanded = mutableSetOf<Int>()
        for (result in ftsResults) {
            val idx = result.node_index
            // 写死上下界：idx - 1 >= 0, idx + 1 <= maxIndex
            expanded.add(max(0, idx - 1))
            expanded.add(idx)
            expanded.add(min(maxIndex, idx + 1))
        }
        return expanded.sorted()
    }

    /**
     * 构建 [VERBATIM_RECALL] 注入块（含智能截断逻辑）
     *
     * @param conversationId 会话 ID
     * @param nodeIndices 消息索引列表
     * @param verbatimText 逐字文本
     * @return 格式化的注入块
     */
    private fun buildVerbatimInjection(
        conversationId: String,
        nodeIndices: List<Int>,
        verbatimText: String
    ): String {
        // 智能截断：优先在换行符处截断，保持行的完整性
        val truncatedText = if (verbatimText.length > MAX_VERBATIM_CHARS) {
            // 尝试在最近的换行符处截断
            val truncated = verbatimText.take(MAX_VERBATIM_CHARS)
            val lastNewline = truncated.lastIndexOf('\n')

            if (lastNewline > MAX_VERBATIM_CHARS * 0.8) {
                // 如果在 80% 位置之后有换行符，在那里截断
                truncated.substring(0, lastNewline) + "\n\n[...内容过长，已智能截断...]"
            } else {
                // 否则直接截断
                truncated + "\n\n[...内容已截断...]"
            }
        } else {
            verbatimText
        }

        // 组装注入块（格式固定）
        return """
            |[VERBATIM_RECALL]
            |source=message_node_text
            |conversation_id=$conversationId
            |node_indices=${nodeIndices.joinToString(",")}
            |----BEGIN_VERBATIM----
            |$truncatedText
            |----END_VERBATIM----
            |[/VERBATIM_RECALL]
        """.trimMargin()
    }
}
