package me.rerere.rikkahub.service.recall.source

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.db.dao.MessageNodeTextDao
import me.rerere.rikkahub.data.db.dao.VerbatimArtifactDao
import me.rerere.rikkahub.data.db.entity.VerbatimArtifactEntity
import me.rerere.rikkahub.debug.DebugLogger
import me.rerere.rikkahub.debug.model.LogLevel
import me.rerere.rikkahub.service.IntentRouter
import me.rerere.rikkahub.service.recall.model.Candidate
import me.rerere.rikkahub.service.recall.model.CandidateKind
import me.rerere.rikkahub.service.recall.model.CandidateSource
import me.rerere.rikkahub.service.recall.model.QueryContext
import me.rerere.rikkahub.util.FtsRanker
import me.rerere.rikkahub.util.normalizeForSearch
import kotlin.math.max
import kotlin.math.min

/**
 * P源候选生成器（TextSourceCandidateGenerator）
 *
 * 功能：
 * 1. 使用 IntentRouter.extractTitles() 做 title 命中 => 候选 kind=FULL 或 SNIPPET
 * 2. 若未命中：用现有"轻量检索"找 Top 命中 node_index，扩窗 {idx-1, idx, idx+1}
 * 3. 产出：SNIPPET（<=800 chars）和 FULL（<=6000 chars，仅供 explicit）
 *
 * 预算护栏：
 * - MAX_PER_SOURCE = 3（每来源最多3个候选）
 * - SNIPPET_MAX_CHARS = 800
 * - FULL_MAX_CHARS = 6000
 */
class TextSourceCandidateGenerator(
    private val context: Context,
    private val messageNodeTextDao: MessageNodeTextDao,
    private val verbatimArtifactDao: VerbatimArtifactDao
) {
    companion object {
        private const val MAX_PER_SOURCE = 3  // 每来源最多3个候选
        private const val SNIPPET_MAX_CHARS = 800
        private const val FULL_MAX_CHARS = 6000
        private const val TAG = "TextSourceCandidateGenerator"
    }

    /**
     * 生成 P源候选
     *
     * @param queryContext 查询上下文
     * @return 候选列表（最多3个）
     */
    suspend fun generate(
        queryContext: QueryContext
    ): List<Candidate> = withContext(Dispatchers.IO) {
        val debugLogger = DebugLogger.getInstance(context)
        val candidates = mutableListOf<Candidate>()

        // 检查是否启用逐字召回
        if (!queryContext.settingsSnapshot.enableVerbatimRecall) {
            debugLogger.log(
                LogLevel.DEBUG,
                TAG,
                "Verbatim recall disabled"
            )
            return@withContext emptyList()
        }

        val conversationId = queryContext.conversationId
        val lastUserText = queryContext.lastUserText
        val isExplicit = queryContext.explicitSignal.explicit

        // 策略1：title 匹配
        val titles = IntentRouter.extractTitles(lastUserText)
        if (titles.isNotEmpty()) {
            debugLogger.log(
                LogLevel.DEBUG,
                TAG,
                "Title matching",
                mapOf("titles" to titles)
            )

            val titleCandidates = searchByTitle(conversationId, titles, isExplicit)
            candidates.addAll(titleCandidates)

            if (candidates.size >= MAX_PER_SOURCE) {
                debugLogger.log(
                    LogLevel.DEBUG,
                    TAG,
                    "Title matching produced enough candidates",
                    mapOf("count" to candidates.size)
                )
                return@withContext candidates.take(MAX_PER_SOURCE)
            }
        }

        // 策略2：FTS4 兜底检索（仅在 title 未产生足够候选时）
        if (candidates.size < MAX_PER_SOURCE) {
            debugLogger.log(LogLevel.DEBUG, TAG, "FTS4 fallback")

            val ftsCandidates = searchByFts(conversationId, lastUserText, isExplicit)
            candidates.addAll(ftsCandidates)
        }

        debugLogger.log(
            LogLevel.INFO,
            TAG,
            "Generated P source candidates",
            mapOf("count" to candidates.size)
        )

        return@withContext candidates.take(MAX_PER_SOURCE)
    }

    /**
     * 策略1：title 匹配
     */
    private suspend fun searchByTitle(
        conversationId: String,
        titles: List<String>,
        isExplicit: Boolean
    ): List<Candidate> {
        val candidates = mutableListOf<Candidate>()

        for (title in titles) {
            if (candidates.size >= MAX_PER_SOURCE) break

            val artifacts = verbatimArtifactDao.getByConversationIdAndTitle(
                conversationId = conversationId,
                title = title
            )

            if (artifacts.isNotEmpty()) {
                val artifact = artifacts.first()  // 取最新的一条
                val nodeIndices = (artifact.startNodeIndex..artifact.endNodeIndex).toList()

                // 获取消息文本
                val messageNodes = messageNodeTextDao.getByConversationIdAndIndices(
                    conversationId = conversationId,
                    indices = nodeIndices
                )

                if (messageNodes.isNotEmpty()) {
                    val fullText = messageNodes.joinToString("\n\n") { it.rawText }

                    // 显式请求：生成 FULL 候选
                    if (isExplicit && fullText.length <= FULL_MAX_CHARS) {
                        candidates.add(buildCandidate(
                            conversationId = conversationId,
                            nodeIndices = nodeIndices,
                            text = fullText,
                            kind = CandidateKind.FULL,
                            title = title
                        ))
                    }

                    // 同时生成 SNIPPET 候选（<=800 chars）
                    val snippetText = fullText.take(SNIPPET_MAX_CHARS)
                    candidates.add(buildCandidate(
                        conversationId = conversationId,
                        nodeIndices = nodeIndices,
                        text = snippetText,
                        kind = CandidateKind.SNIPPET,
                        title = title
                    ))
                }
            }
        }

        return candidates
    }

    /**
     * 策略2：FTS4 兜底检索
     */
    private suspend fun searchByFts(
        conversationId: String,
        lastUserText: String,
        isExplicit: Boolean
    ): List<Candidate> {
        val candidates = mutableListOf<Candidate>()

        // 归一化查询
        val qNorm = normalizeForSearch(lastUserText)
        val tokens = qNorm.split(" ").filter { it.isNotEmpty() }.distinct().take(16)
        if (tokens.isEmpty()) return emptyList()

        val matchQuery = tokens.joinToString(" OR ") { "\"$it\"" }

        // FTS4 查询
        val ftsQuery = SimpleSQLiteQuery(
            """
            SELECT node_id, node_index, matchinfo(message_node_fts) AS mi
            FROM message_node_fts
            WHERE message_node_fts MATCH ?
              AND conversation_id = ?
              AND role = ?
            LIMIT 50
            """.trimIndent(),
            arrayOf(matchQuery, conversationId, MessageRole.USER.ordinal)
        )

        val candidateIndices = try {
            val ftsResults = messageNodeTextDao.searchByFts(ftsQuery)
            if (ftsResults.isEmpty()) {
                // FTS4 无结果，使用倒排索引兜底
                DebugLogger.getInstance(context).log(LogLevel.DEBUG, TAG, "FTS4 empty, skipping")
                return emptyList()
            } else {
                FtsRanker.rankAndTakeTop(ftsResults).take(MAX_PER_SOURCE)
            }
        } catch (e: Exception) {
            DebugLogger.getInstance(context).log(
                LogLevel.WARN,
                TAG,
                "FTS4 failed",
                mapOf("error" to e.message)
            )
            return emptyList()
        }

        // 获取最大 node_index
        val maxIndex = messageNodeTextDao.getMaxNodeIndex(conversationId) ?: return emptyList()

        // 窗口扩展：{idx-1, idx, idx+1}
        for (idx in candidateIndices) {
            if (candidates.size >= MAX_PER_SOURCE) break

            val expandedIndices = listOf(
                max(0, idx - 1),
                idx,
                min(maxIndex, idx + 1)
            )

            val messageNodes = messageNodeTextDao.getByConversationIdAndIndices(
                conversationId = conversationId,
                indices = expandedIndices
            )

            if (messageNodes.isNotEmpty()) {
                val fullText = messageNodes.joinToString("\n\n") { it.rawText }
                val snippetText = fullText.take(SNIPPET_MAX_CHARS)

                candidates.add(buildCandidate(
                    conversationId = conversationId,
                    nodeIndices = expandedIndices,
                    text = snippetText,
                    kind = CandidateKind.SNIPPET,
                    title = null
                ))
            }
        }

        return candidates
    }

    /**
     * 构建候选对象
     */
    private fun buildCandidate(
        conversationId: String,
        nodeIndices: List<Int>,
        text: String,
        kind: CandidateKind,
        title: String?
    ): Candidate {
        val candidateId = me.rerere.rikkahub.service.recall.model.CandidateBuilder.buildPSourceId(
            conversationId = conversationId,
            kind = kind,
            nodeIndices = nodeIndices
        )

        val evidenceKey = me.rerere.rikkahub.service.recall.model.CandidateBuilder.buildPSourceEvidenceKey(
            conversationId = conversationId,
            nodeIndices = nodeIndices
        )

        val anchors = buildList {
            if (title != null) add("title:$title")
            add("node_indices:${nodeIndices.joinToString(",")}")
        }

        return Candidate(
            id = candidateId,
            source = CandidateSource.P_TEXT,
            kind = kind,
            content = text,
            anchors = anchors,
            cost = text.length,
            evidenceKey = evidenceKey,  // Phase F: 添加 evidenceKey
            evidenceRaw = mapOf(
                "node_indices" to nodeIndices.joinToString(","),
                "title" to (title ?: "")
            )
        )
    }
}
