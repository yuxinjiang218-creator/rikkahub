package me.rerere.rikkahub.service.recall.source

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.data.db.dao.ArchiveSummaryDao
import me.rerere.rikkahub.data.db.dao.VectorIndexDao
import me.rerere.rikkahub.debug.DebugLogger
import me.rerere.rikkahub.debug.model.LogLevel
import me.rerere.rikkahub.service.recall.model.Candidate
import me.rerere.rikkahub.service.recall.model.CandidateKind
import me.rerere.rikkahub.service.recall.model.CandidateSource
import me.rerere.rikkahub.service.recall.model.QueryContext

/**
 * A源候选生成器（ArchiveSourceCandidateGenerator）
 *
 * Phase C: 最小闭环实现（HINT候选 + embedding检索）
 *
 * 功能：
 * 1. Gating 检查（enableArchiveRecall + embeddingModelId）
 * 2. 从 archiveSummaryDao 获取归档摘要
 * 3. 使用 embedding + vectorIndex cosineSimilarity 做检索
 * 4. 生成 HINT 候选（<=200 chars）
 * 5. 失败/异常 => 返回 emptyList
 *
 * 预算护栏：
 * - MAX_PER_SOURCE = 3（每来源最多3个候选）
 * - HINT_MAX_CHARS = 200
 * - MIN_COS_SIM = 0.3（余弦相似度阈值）
 * - EMBEDDING_MAX_CALLS = 3（默认1次，条件满足时最多3次）
 */
class ArchiveSourceCandidateGenerator(
    private val context: Context,
    private val archiveSummaryDao: ArchiveSummaryDao,
    private val vectorIndexDao: VectorIndexDao,
    private val providerManager: ProviderManager
) {
    companion object {
        private const val TAG = "ArchiveSourceCandidateGenerator"
        private const val MAX_PER_SOURCE = 3  // 每来源最多3个候选
        private const val HINT_MAX_CHARS = 200
        private const val MIN_COS_SIM = 0.3f  // 余弦相似度阈值
        private const val EMBEDDING_MAX_CALLS = 3  // 最多3次 embedding 调用
        private const val MULTI_QUERY_NEED_SCORE_THRESHOLD = 0.75f  // MultiQuery 触发阈值
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

        // 10. 生成 HINT 候选
        for ((archive, similarity) in archiveWithSimilarity) {
            if (candidates.size >= MAX_PER_SOURCE) break

            // 截断内容为 HINT（<=200 chars）
            val hintContent = archive.content.take(HINT_MAX_CHARS)

            val candidate = Candidate(
                id = me.rerere.rikkahub.service.recall.model.CandidateBuilder.buildASourceId(
                    archiveId = archive.id,
                    kind = CandidateKind.HINT
                ),
                source = CandidateSource.A_ARCHIVE,
                kind = CandidateKind.HINT,
                content = hintContent,
                anchors = listOf("archive_id:${archive.id}"),
                cost = hintContent.length,
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
