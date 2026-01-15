package me.rerere.rikkahub.service.recall.source

import android.content.Context
import android.util.Log
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
import me.rerere.rikkahub.service.recall.anchor.AnchorGenerator
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

        /**
         * Phase J2: 回指助手词组（写死，避免泛化）
         * 当用户明确指代助手生成的内容时，扩展搜索 ASSISTANT 角色
         */
        private val ASSISTANT_ANAPHORA_PHRASES = listOf(
            "你说的", "你刚才", "按你刚给的", "按你给的方案", "照你说的",
            "继续", "接着", "你刚发的", "你刚写的"
        )
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

        Log.i(TAG, "=== TextSourceCandidateGenerator.generate START ===")
        Log.i(TAG, "enableVerbatimRecall: ${queryContext.settingsSnapshot.enableVerbatimRecall}")

        // 检查是否启用逐字召回
        if (!queryContext.settingsSnapshot.enableVerbatimRecall) {
            Log.w(TAG, "=== Verbatim recall DISABLED, returning empty list ===")
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

        Log.i(TAG, "conversationId: $conversationId")
        Log.i(TAG, "lastUserText: $lastUserText")
        Log.i(TAG, "isExplicit: $isExplicit")

        // 策略1：title 匹配
        val titles = IntentRouter.extractTitles(lastUserText)
        Log.i(TAG, "extracted titles: $titles")

        if (titles.isNotEmpty()) {
            Log.i(TAG, "=== Title matching strategy ===")
            debugLogger.log(
                LogLevel.DEBUG,
                TAG,
                "Title matching",
                mapOf("titles" to titles)
            )

            val titleCandidates = searchByTitle(conversationId, titles, isExplicit, lastUserText)
            candidates.addAll(titleCandidates)
            Log.i(TAG, "titleCandidates count: ${titleCandidates.size}")

            if (candidates.size >= MAX_PER_SOURCE) {
                Log.i(TAG, "=== Enough candidates from title matching ===")
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
            Log.i(TAG, "=== FTS4 fallback strategy ===")
            debugLogger.log(LogLevel.DEBUG, TAG, "FTS4 fallback")

            val ftsCandidates = searchByFts(conversationId, lastUserText, isExplicit)
            candidates.addAll(ftsCandidates)
            Log.i(TAG, "ftsCandidates count: ${ftsCandidates.size}")
        }

        Log.i(TAG, "=== Total P source candidates: ${candidates.size} ===")
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
        isExplicit: Boolean,
        query: String
    ): List<Candidate> {
        val candidates = mutableListOf<Candidate>()

        Log.i(TAG, "=== searchByTitle START ===")
        Log.i(TAG, "titles: $titles")
        Log.i(TAG, "isExplicit: $isExplicit")

        for (title in titles) {
            if (candidates.size >= MAX_PER_SOURCE) break

            Log.i(TAG, "Searching for artifact with title: '$title'")

            val artifacts = verbatimArtifactDao.getByConversationIdAndTitle(
                conversationId = conversationId,
                title = title
            )

            Log.i(TAG, "Found ${artifacts.size} artifacts for title '$title'")

            if (artifacts.isNotEmpty()) {
                val artifact = artifacts.first()  // 取最新的一条
                Log.i(TAG, "artifact: startNodeIndex=${artifact.startNodeIndex}, endNodeIndex=${artifact.endNodeIndex}")

                val nodeIndices = (artifact.startNodeIndex..artifact.endNodeIndex).toList()

                // 获取消息文本
                val messageNodes = messageNodeTextDao.getByConversationIdAndIndices(
                    conversationId = conversationId,
                    indices = nodeIndices
                )

                Log.i(TAG, "Retrieved ${messageNodes.size} message nodes")

                if (messageNodes.isNotEmpty()) {
                    val fullText = messageNodes.joinToString("\n\n") { it.rawText }
                    Log.i(TAG, "fullText length: ${fullText.length}")

                    // 显式请求：生成 FULL 候选
                    if (isExplicit && fullText.length <= FULL_MAX_CHARS) {
                        Log.i(TAG, "Adding FULL candidate")
                        candidates.add(buildCandidate(
                            conversationId = conversationId,
                            nodeIndices = nodeIndices,
                            text = fullText,
                            kind = CandidateKind.FULL,
                            title = title,
                            query = query
                        ))
                    }

                    // 同时生成 SNIPPET 候选（<=800 chars）
                    val snippetText = fullText.take(SNIPPET_MAX_CHARS)
                    Log.i(TAG, "Adding SNIPPET candidate (length: ${snippetText.length})")
                    candidates.add(buildCandidate(
                        conversationId = conversationId,
                        nodeIndices = nodeIndices,
                        text = snippetText,
                        kind = CandidateKind.SNIPPET,
                        title = title,
                        query = query
                    ))
                }
            }
        }

        Log.i(TAG, "=== searchByTitle returning ${candidates.size} candidates ===")
        return candidates
    }

    /**
     * 策略2：FTS4 兜底检索
     * Phase J1: 传递 FTS 排名信息给 EvidenceScorer 使用
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

        // Phase J2: 检测回指助手词组
        val hasAssistantAnaphora = ASSISTANT_ANAPHORA_PHRASES.any { lastUserText.contains(it) }

        // Phase J2: 根据是否有回指助手词，决定搜索的角色范围
        val ftsQuery = if (hasAssistantAnaphora) {
            // 扩展搜索：USER + ASSISTANT
            SimpleSQLiteQuery(
                """
                SELECT node_id, node_index, matchinfo(message_node_fts) AS mi
                FROM message_node_fts
                WHERE message_node_fts MATCH ?
                  AND conversation_id = ?
                  AND role IN (?, ?)
                LIMIT 50
                """.trimIndent(),
                arrayOf(matchQuery, conversationId, MessageRole.USER.ordinal, MessageRole.ASSISTANT.ordinal)
            )
        } else {
            // 默认：只搜 USER
            SimpleSQLiteQuery(
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
        }

        // Phase J1: 获取排序后的 node_index 列表及对应的排名分数
        val rankedIndices = try {
            val ftsResults = messageNodeTextDao.searchByFts(ftsQuery)
            if (ftsResults.isEmpty()) {
                // FTS4 无结果，使用倒排索引兜底
                DebugLogger.getInstance(context).log(LogLevel.DEBUG, TAG, "FTS4 empty, skipping")
                return emptyList()
            } else {
                // Phase J1: rankAndTakeTop 返回的是已排序的 node_index 列表
                // 我们需要保留排名信息：top1=1.0, top2=0.7, top3=0.5
                val rankedList = FtsRanker.rankAndTakeTop(ftsResults).take(MAX_PER_SOURCE)
                rankedList.mapIndexed { index, nodeIndex ->
                    // 归一化排名分数：top1=1.0, top2=0.7, top3=0.5
                    val rankScore = when (index) {
                        0 -> 1.0f
                        1 -> 0.7f
                        2 -> 0.5f
                        else -> 0.3f
                    }
                    nodeIndex to rankScore
                }
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

        // Phase J1: 窗口扩展：{idx-1, idx, idx+1}，同时传递 fts_rank_norm
        for ((idx, ftsRankNorm) in rankedIndices) {
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

                // Phase J1: 传递 ftsRankNorm 参数
                candidates.add(buildCandidate(
                    conversationId = conversationId,
                    nodeIndices = expandedIndices,
                    text = snippetText,
                    kind = CandidateKind.SNIPPET,
                    title = null,
                    query = lastUserText,
                    ftsRankNorm = ftsRankNorm
                ))
            }
        }

        return candidates
    }

    /**
     * 构建候选对象
     * Phase J1: 添加 ftsRankNorm 参数用于 evidenceRaw
     */
    private fun buildCandidate(
        conversationId: String,
        nodeIndices: List<Int>,
        text: String,
        kind: CandidateKind,
        title: String?,
        query: String,
        ftsRankNorm: Float? = null  // Phase J1: FTS 排名归一化分数
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

        // Phase G: 使用统一的 AnchorGenerator 生成 anchors（禁止 node_indices）
        val anchors = AnchorGenerator.buildPSourceAnchors(
            query = query,
            explicitTitle = title
        )

        // Phase J1: 构建 evidenceRaw，包含 fts_rank_norm
        val evidenceRaw = mutableMapOf(
            "node_indices" to nodeIndices.joinToString(","),
            "title" to (title ?: "")
        )
        // Phase J1: 如果有 ftsRankNorm，添加到 evidenceRaw
        if (ftsRankNorm != null) {
            evidenceRaw["fts_rank_norm"] = ftsRankNorm.toString()
        }

        return Candidate(
            id = candidateId,
            source = CandidateSource.P_TEXT,
            kind = kind,
            content = text,
            anchors = anchors,
            cost = text.length,
            evidenceKey = evidenceKey,
            evidenceRaw = evidenceRaw
        )
    }
}
