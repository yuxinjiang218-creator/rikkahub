package me.rerere.rikkahub.service.recall.source

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.data.db.dao.ArchiveSummaryDao
import me.rerere.rikkahub.data.db.dao.MessageNodeTextDao
import me.rerere.rikkahub.data.db.dao.VectorIndexDao
import me.rerere.rikkahub.debug.DebugLogger
import me.rerere.rikkahub.debug.model.LogLevel
import me.rerere.rikkahub.service.recall.anchor.AnchorGenerator
import me.rerere.rikkahub.service.recall.model.Candidate
import me.rerere.rikkahub.service.recall.model.CandidateKind
import me.rerere.rikkahub.service.recall.model.CandidateSource
import me.rerere.rikkahub.service.recall.model.QueryContext

/**
 * A源候选生成器（ArchiveSourceCandidateGenerator）
 *
 * Phase C: 最小闭环实现（HINT候选 + embedding检索）
 * Phase E: 优先 SNIPPET（<=800 chars），回退 HINT（<=200 chars）
 *
 * 功能：
 * 1. Gating 检查（enableArchiveRecall + embeddingModelId）
 * 2. 从 archiveSummaryDao 获取归档摘要
 * 3. 使用 embedding + vectorIndex cosineSimilarity 做检索
 * 4. 优先生成 SNIPPET 候选（<=800 chars），使用 MessageNodeTextDao 获取原始文本
 * 5. 回退生成 HINT 候选（<=200 chars），使用摘要内容
 * 6. 失败/异常 => 返回 emptyList
 *
 * 预算护栏：
 * - MAX_PER_SOURCE = 3（每来源最多3个候选）
 * - SNIPPET_MAX_CHARS = 800（Phase E: 优先 SNIPPET）
 * - HINT_MAX_CHARS = 200（回退）
 * - MIN_COS_SIM = 0.3（余弦相似度阈值）
 * - EMBEDDING_MAX_CALLS = 3（默认1次，条件满足时最多3次）
 */
class ArchiveSourceCandidateGenerator(
    private val context: Context,
    private val archiveSummaryDao: ArchiveSummaryDao,
    private val vectorIndexDao: VectorIndexDao,
    private val messageNodeTextDao: MessageNodeTextDao,
    private val providerManager: ProviderManager
) {
    companion object {
        private const val TAG = "ArchiveSourceCandidateGenerator"
        private const val MAX_PER_SOURCE = 3  // 每来源最多3个候选
        private const val SNIPPET_MAX_CHARS = 800  // Phase E: 优先 SNIPPET
        private const val HINT_MAX_CHARS = 200  // 回退 HINT
        private const val MIN_COS_SIM = 0.3f  // 余弦相似度阈值
        private const val EMBEDDING_MAX_CALLS = 3  // 最多3次 embedding 调用
        private const val MULTI_QUERY_NEED_SCORE_THRESHOLD = 0.75f  // MultiQuery 触发阈值

        // Phase G3.1: 成本护栏（写死）
        private const val MAX_NODE_TEXT_ROWS = 50  // DAO 拉取条数硬上限
        private const val MAX_WINDOW_SIZE_FOR_FULL = 200  // window > 200 时只取前 50 条

        // Phase G3.2: 质量护栏（从 RecallConstants 读取，Phase I 可调）
        // 注意：不再在此处硬编码，改用 RecallConstants 中的值
        private val EDGE_SIMILARITY_MIN get() = me.rerere.rikkahub.service.recall.RecallConstants.EDGE_SIMILARITY_MIN
        private val EDGE_SIMILARITY_MAX get() = me.rerere.rikkahub.service.recall.RecallConstants.EDGE_SIMILARITY_MAX
        private val MIN_SNIPPET_LENGTH get() = me.rerere.rikkahub.service.recall.RecallConstants.MIN_SNIPPET_LENGTH
    }

    /**
     * 生成 A源候选（最小闭环实现）
     *
     * @param queryContext 查询上下文
     * @param pSourceCandidateCount P源候选数量（用于判断是否需要 Q1/Q2）
     * @param settings 设置（用于获取 providers）
     * @return 候选列表（最多3个）
     */
    suspend fun generate(
        queryContext: QueryContext,
        pSourceCandidateCount: Int,
        settings: me.rerere.rikkahub.data.datastore.Settings? = null
    ): List<Candidate> = withContext(Dispatchers.IO) {
        val debugLogger = DebugLogger.getInstance(context)
        val candidates = mutableListOf<Candidate>()

        // 1. Gating 检查：enableArchiveRecall
        if (!queryContext.settingsSnapshot.enableArchiveRecall) {
            debugLogger.log(LogLevel.DEBUG, TAG, "Archive recall disabled")
            return@withContext emptyList()
        }

        // 2. Gating 检查：embeddingModelId
        val embeddingModelId = queryContext.settingsSnapshot.embeddingModelId
        if (embeddingModelId == null) {
            debugLogger.log(LogLevel.DEBUG, TAG, "Embedding model not configured, skipping A source")
            return@withContext emptyList()
        }

        // 3. Gating 检查：settings
        if (settings == null) {
            debugLogger.log(LogLevel.DEBUG, TAG, "Settings not provided, skipping A source")
            return@withContext emptyList()
        }

        val conversationId = queryContext.conversationId
        val lastUserText = queryContext.lastUserText

        // 4. 计算 needScore（简化：用账本大小）
        val needScore = queryContext.ledger.recent.size.toFloat()

        // 5. 决定执行多少次 embedding（Q0、Q1、Q2）
        val embeddingCalls = decideEmbeddingCalls(needScore, pSourceCandidateCount)

        debugLogger.log(
            LogLevel.DEBUG,
            TAG,
            "MultiQuery scheduling",
            mapOf(
                "embeddingCalls" to embeddingCalls,
                "needScore" to needScore,
                "pSourceCandidateCount" to pSourceCandidateCount
            )
        )

        // 6. 获取归档摘要
        val allArchives = archiveSummaryDao.getListByConversationId(conversationId)
        if (allArchives.isEmpty()) {
            debugLogger.log(LogLevel.DEBUG, TAG, "No archives found for conversation")
            return@withContext emptyList()
        }

        // 7. 获取 embedding 模型
        val providerAndModel = settings.providers
            .mapNotNull { provider ->
                val model = provider.models.find { it.id.toString() == embeddingModelId }
                if (model != null) provider to model else null
            }
            .firstOrNull()

        if (providerAndModel == null) {
            debugLogger.log(
                LogLevel.WARN,
                TAG,
                "Embedding model not found in providers",
                mapOf("embeddingModelId" to embeddingModelId)
            )
            return@withContext emptyList()
        }

        val (providerSetting, embeddingModel) = providerAndModel

        // 8. 生成查询的 embedding（只执行 Q0，简化实现）
        var embeddingCallCount = 0
        val queryEmbedding = try {
            embeddingCallCount++
            val provider = providerManager.getProviderByType(providerSetting)
            @Suppress("UNCHECKED_CAST")
            (provider as me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>).generateEmbedding(
                providerSetting = providerSetting,
                text = lastUserText,
                params = me.rerere.ai.provider.EmbeddingGenerationParams(model = embeddingModel)
            )
        } catch (e: Exception) {
            debugLogger.log(
                LogLevel.WARN,
                TAG,
                "Failed to generate query embedding",
                mapOf("error" to (e.message ?: "unknown"))
            )
            return@withContext emptyList()
        }

        // 9. 计算相似度并过滤
        val archiveWithSimilarity = allArchives.mapNotNull { archive ->
            val vectorIndex = vectorIndexDao.getByArchiveId(archive.id)
            if (vectorIndex != null && vectorIndex.embeddingModelId == embeddingModelId) {
                val similarity = cosineSimilarity(queryEmbedding, vectorIndex.embeddingVector)
                archive to similarity
            } else {
                null
            }
        }.filter { it.second >= MIN_COS_SIM }
            .sortedByDescending { it.second }
            .take(MAX_PER_SOURCE)

        debugLogger.log(
            LogLevel.INFO,
            TAG,
            "A source candidate generation completed",
            mapOf(
                "embeddingCalls" to embeddingCallCount,
                "archivesFound" to archiveWithSimilarity.size,
                "cosSimThreshold" to MIN_COS_SIM
            )
        )

        // 10. 生成候选：优先 SNIPPET（Phase G3：添加质量护栏），回退 HINT
        for ((archive, similarity) in archiveWithSimilarity) {
            if (candidates.size >= MAX_PER_SOURCE) break

            // Phase G3.2 质量护栏1：边缘相似度区间 [0.30, 0.35) 只生成 HINT
            val isInEdgeZone = similarity >= EDGE_SIMILARITY_MIN && similarity < EDGE_SIMILARITY_MAX
            if (isInEdgeZone) {
                debugLogger.log(
                    LogLevel.DEBUG,
                    TAG,
                    "Edge similarity zone, falling back to HINT",
                    mapOf(
                        "archiveId" to archive.id,
                        "similarity" to similarity,
                        "EDGE_SIMILARITY_MIN" to EDGE_SIMILARITY_MIN,
                        "EDGE_SIMILARITY_MAX" to EDGE_SIMILARITY_MAX
                    )
                )

                // 直接生成 HINT 候选
                val hintContent = archive.content.take(HINT_MAX_CHARS)
                val candidate = Candidate(
                    id = me.rerere.rikkahub.service.recall.model.CandidateBuilder.buildASourceId(
                        archiveId = archive.id,
                        kind = CandidateKind.HINT
                    ),
                    source = CandidateSource.A_ARCHIVE,
                    kind = CandidateKind.HINT,
                    content = hintContent,
                    anchors = buildAnchors(lastUserText),
                    cost = hintContent.length,
                    evidenceKey = me.rerere.rikkahub.service.recall.model.CandidateBuilder.buildASourceEvidenceKey(
                        archiveId = archive.id
                    ),
                    evidenceRaw = mapOf(
                        "archive_id" to archive.id,
                        "max_cos_sim" to similarity.toString(),
                        "created_at" to archive.createdAt.toString()
                    )
                )
                candidates.add(candidate)
                continue  // 跳过后续逻辑
            }

            // Phase E/G3.1: 尝试组装 SNIPPET（<=800 chars）
            val snippetContent = tryAssembleSnippet(
                conversationId = conversationId,
                windowStartIndex = archive.windowStartIndex,
                windowEndIndex = archive.windowEndIndex
            )

            val (kind, content, maxChars) = if (snippetContent != null) {
                // Phase G3.2 质量护栏2：SNIPPET 清理后长度 < 80 chars 退 HINT
                val cleanedSnippet = snippetContent.trim()
                if (cleanedSnippet.length < MIN_SNIPPET_LENGTH) {
                    debugLogger.log(
                        LogLevel.DEBUG,
                        TAG,
                        "Snippet too short, falling back to HINT",
                        mapOf(
                            "archiveId" to archive.id,
                            "snippetLength" to cleanedSnippet.length,
                            "MIN_SNIPPET_LENGTH" to MIN_SNIPPET_LENGTH
                        )
                    )
                    Triple(CandidateKind.HINT, archive.content, HINT_MAX_CHARS)
                } else {
                    // SNIPPET 成功
                    debugLogger.log(
                        LogLevel.DEBUG,
                        TAG,
                        "Assembled SNIPPET for archive",
                        mapOf(
                            "archiveId" to archive.id,
                            "snippetLength" to snippetContent.length,
                            "windowStartIndex" to archive.windowStartIndex,
                            "windowEndIndex" to archive.windowEndIndex
                        )
                    )
                    Triple(CandidateKind.SNIPPET, snippetContent, SNIPPET_MAX_CHARS)
                }
            } else {
                // SNIPPET 失败，回退 HINT（<=200 chars）
                debugLogger.log(
                    LogLevel.DEBUG,
                    TAG,
                    "Falling back to HINT for archive",
                    mapOf("archiveId" to archive.id)
                )
                Triple(CandidateKind.HINT, archive.content, HINT_MAX_CHARS)
            }

            // 截断内容
            val truncatedContent = content.take(maxChars)

            val candidate = Candidate(
                id = me.rerere.rikkahub.service.recall.model.CandidateBuilder.buildASourceId(
                    archiveId = archive.id,
                    kind = kind
                ),
                source = CandidateSource.A_ARCHIVE,
                kind = kind,
                content = truncatedContent,
                anchors = buildAnchors(lastUserText),  // Phase G: 使用统一的 AnchorGenerator
                cost = truncatedContent.length,
                evidenceKey = me.rerere.rikkahub.service.recall.model.CandidateBuilder.buildASourceEvidenceKey(
                    archiveId = archive.id
                ),
                evidenceRaw = mapOf(
                    "archive_id" to archive.id,
                    "max_cos_sim" to similarity.toString(),
                    "created_at" to archive.createdAt.toString()
                )
            )
            candidates.add(candidate)
        }

        return@withContext candidates
    }

    /**
     * 尝试组装 SNIPPET（Phase G3：添加成本护栏）
     *
     * 使用 MessageNodeTextDao 根据 windowStartIndex/windowEndIndex 获取原始文本，
     * 拼接后截断到 SNIPPET_MAX_CHARS（800）。
     *
     * Phase G3.1 成本护栏：
     * - MAX_NODE_TEXT_ROWS = 50（硬上限）
     * - 拼接过程中一旦达到 800 chars 立即停止
     * - 若 window > 200，只取前 50 条；不得全量拉取
     *
     * @param conversationId 会话ID
     * @param windowStartIndex 窗口起始索引
     * @param windowEndIndex 窗口结束索引
     * @return 拼接后的 SNIPPET 内容，失败返回 null
     */
    private suspend fun tryAssembleSnippet(
        conversationId: String,
        windowStartIndex: Int,
        windowEndIndex: Int
    ): String? = withContext(Dispatchers.IO) {
        try {
            val debugLogger = DebugLogger.getInstance(context)

            // 1. 生成 indices 列表 [windowStartIndex, windowEndIndex]
            val windowSize = windowEndIndex - windowStartIndex + 1
            val indices = if (windowSize > MAX_WINDOW_SIZE_FOR_FULL) {
                // Phase G3.1: window > 200 时只取前 50 条，不得全量拉取
                (windowStartIndex until (windowStartIndex + MAX_NODE_TEXT_ROWS)).toList()
            } else {
                // 正常情况：取全部，但不超过 MAX_NODE_TEXT_ROWS
                val allIndices = (windowStartIndex..windowEndIndex).toList()
                allIndices.take(MAX_NODE_TEXT_ROWS)
            }

            debugLogger.log(
                LogLevel.DEBUG,
                TAG,
                "Assembling SNIPPET with cost guardrails",
                mapOf(
                    "windowSize" to windowSize,
                    "indicesToFetch" to indices.size,
                    "MAX_NODE_TEXT_ROWS" to MAX_NODE_TEXT_ROWS
                )
            )

            // 2. 从 MessageNodeTextDao 批量获取原始文本（已限制条数）
            val nodeTexts = messageNodeTextDao.getByConversationIdAndIndices(
                conversationId = conversationId,
                indices = indices
            )

            // 3. 拼接 rawText（按 node_index 排序），一旦达到 800 chars 立即停止
            val snippetBuilder = StringBuilder()
            var totalLength = 0

            for (nodeText in nodeTexts.sortedBy { it.nodeIndex }) {
                val textLength = nodeText.rawText.length

                // Phase G3.1: 拼接过程中一旦达到 800 chars 立即停止
                if (totalLength + textLength > SNIPPET_MAX_CHARS) {
                    val remainingChars = SNIPPET_MAX_CHARS - totalLength
                    if (remainingChars > 0) {
                        snippetBuilder.append(nodeText.rawText.take(remainingChars))
                    }
                    break  // 达到 800 chars，停止拼接
                }

                snippetBuilder.append(nodeText.rawText)
                totalLength += textLength

                // 分隔符（最后一个不加）
                if (totalLength < SNIPPET_MAX_CHARS) {
                    snippetBuilder.append("\n")
                    totalLength += 1
                }
            }

            // 4. 返回拼接结果（调用方会截断到 SNIPPET_MAX_CHARS）
            snippetBuilder.toString().ifEmpty { null }
        } catch (e: Exception) {
            DebugLogger.getInstance(context).log(
                LogLevel.WARN,
                TAG,
                "Failed to assemble SNIPPET",
                mapOf(
                    "conversationId" to conversationId,
                    "windowStartIndex" to windowStartIndex,
                    "windowEndIndex" to windowEndIndex,
                    "error" to (e.message ?: "unknown")
                )
            )
            null  // 失败返回 null，触发 HINT 回退
        }
    }

    /**
     * 构建 anchors 列表（Phase G：禁止结构信息）
     *
     * 使用统一的 AnchorGenerator 生成 anchors
     * - 禁止包含 windowStartIndex/windowEndIndex/node_indices 等结构信息
     * - 只包含 query 中的高相关 token
     */
    private fun buildAnchors(query: String): List<String> {
        return AnchorGenerator.buildASourceAnchors(query)
    }

    /**
     * 决定执行多少次 embedding
     * - 默认：Q0（1次）
     * - 条件触发：needScore >= 0.75 且 P源无候选时，Q0+Q1+Q2（3次）
     */
    private fun decideEmbeddingCalls(
        needScore: Float,
        pSourceCandidateCount: Int
    ): Int {
        // needScore >= 0.75 且 P源无候选 => 执行 3 次 embedding
        if (needScore >= MULTI_QUERY_NEED_SCORE_THRESHOLD && pSourceCandidateCount == 0) {
            return EMBEDDING_MAX_CALLS
        }

        // 默认执行 1 次 embedding（Q0）
        return 1
    }

    /**
     * 计算余弦相似度
     */
    private fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        require(vec1.size == vec2.size) { "Vectors must be same length" }

        var dotProduct = 0f
        var norm1 = 0f
        var norm2 = 0f

        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
            norm1 += vec1[i] * vec1[i]
            norm2 += vec2[i] * vec2[i]
        }

        return if (norm1 == 0f || norm2 == 0f) 0f else dotProduct / (kotlin.math.sqrt(norm1) * kotlin.math.sqrt(norm2))
    }
}
