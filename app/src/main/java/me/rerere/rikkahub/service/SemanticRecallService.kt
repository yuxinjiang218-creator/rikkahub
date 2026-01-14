package me.rerere.rikkahub.service

import android.content.Context
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.rikkahub.data.db.dao.ArchiveSummaryDao
import me.rerere.rikkahub.data.db.dao.VectorIndexDao
import me.rerere.rikkahub.data.db.entity.ArchiveSummaryWithScore
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.debug.DebugLogger
import me.rerere.rikkahub.debug.model.LogLevel
import me.rerere.rikkahub.util.normalizeForSearch
import kotlin.math.sqrt
import kotlin.uuid.Uuid

/**
 * Semantic Recall 服务（语义回填服务，增强版）
 *
 * 职责：
 * 1. MultiQuery 生成（Q0, Q1, Q2，无 LLM）
 * 2. 语义检索（同 embedding_model_id 过滤）
 * 3. 融合重排（0.7 * maxCosSim + 0.3 * recencyScore）
 * 4. 硬预算调度（MAX_ITEMS=5, MAX_CHARS=1500）
 * 5. 反复回填抑制（overlap > 0.6 时跳过注入）
 */
class SemanticRecallService(
    private val context: Context,
    private val archiveSummaryDao: ArchiveSummaryDao,
    private val vectorIndexDao: VectorIndexDao,
    private val providerManager: me.rerere.ai.provider.ProviderManager
) {
    companion object {
        private const val MAX_ARCHIVE_RECALL_ITEMS = 5  // 最大归档回填条数
        private const val MAX_ARCHIVE_RECALL_CHARS = 1500  // 最大归档回填字符数
        private const val OVERLAP_THRESHOLD = 0.6f  // 反复回填抑制阈值
    }

    /**
     * 语义回填主入口
     *
     * @param settings 设置
     * @param conversationId 会话 ID
     * @param lastUserText 用户最新的输入文本
     * @param runningSummary 运行摘要（可为 null）
     * @param lastRecallIds 上一次回填的 ID 列表
     * @return ARCHIVE_RECALL 注入块，null 表示跳过注入
     */
    suspend fun recallSemantic(
        settings: Settings,
        conversationId: String,
        lastUserText: String,
        runningSummary: String?,
        lastRecallIds: List<String>
    ): String? {
        val debugLogger = DebugLogger.getInstance(context)

        // 1. 生成 MultiQuery（Q0, Q1, Q2）
        val queries = generateMultiQueries(lastUserText, runningSummary)

        debugLogger.log(
            LogLevel.DEBUG,
            "SemanticRecall",
            "MultiQuery generated",
            mapOf("count" to queries.size)
        )

        // 2. 对每个查询进行语义检索
        val allResults = mutableSetOf<ArchiveSummaryWithScore>()
        for (query in queries) {
            val results = retrieveByQuery(
                settings = settings,
                conversationId = conversationId,
                query = query,
                embeddingModelId = settings.embeddingModelId
            )
            allResults.addAll(results)
        }

        debugLogger.log(
            LogLevel.DEBUG,
            "SemanticRecall",
            "Retrieval completed",
            mapOf("count" to allResults.size)
        )

        if (allResults.isEmpty()) return null

        // 3. 融合重排
        val fusedResults = fuseAndRerank(allResults.toList())

        debugLogger.log(
            LogLevel.DEBUG,
            "SemanticRecall",
            "Rerank completed",
            mapOf("count" to fusedResults.size)
        )

        // 4. 反复回填抑制
        val suppressedResults = overlapSuppression(fusedResults, lastRecallIds)

        if (suppressedResults.isEmpty()) return null  // 本轮跳过注入

        // 5. 硬预算调度
        val selectedResults = budgetScheduler(suppressedResults)

        if (selectedResults.isEmpty()) return null

        // 6. 构建 [ARCHIVE_RECALL] 注入块
        val result = buildArchiveInjection(selectedResults)

        debugLogger.log(
            LogLevel.INFO,
            "SemanticRecall",
            "Recall completed",
            mapOf(
                "finalCount" to selectedResults.size,
                "totalChars" to selectedResults.sumOf { it.content.length }
            )
        )

        return result
    }

    /**
     * MultiQuery 生成（写死，3条，无 LLM）
     *
     * Q0 = lastUserText
     * Q1 = lastUserText + "\n\n" + runningSummary 前3行
     * Q2 = keywordLine（[a-zA-Z0-9]{2,} + normalize前32 token 去重）
     *
     * @param lastUserText 用户最新的输入文本
     * @param runningSummary 运行摘要（可为 null）
     * @return 3条查询列表
     */
    private fun generateMultiQueries(
        lastUserText: String,
        runningSummary: String?
    ): List<String> {
        val queries = mutableListOf<String>()

        // Q0 = lastUserText
        queries.add(lastUserText)

        // Q1 = lastUserText + "\n\n" + runningSummary 前3行
        if (runningSummary != null && runningSummary.isNotBlank()) {
            val summaryLines = runningSummary.lines().take(3).joinToString("\n")
            queries.add("$lastUserText\n\n$summaryLines")
        }

        // Q2 = keywordLine（[a-zA-Z0-9]{2,} + normalize前32 token 去重）
        val alphaNumeric = Regex("[a-zA-Z0-9]{2,}").findAll(lastUserText)
            .map { it.value.lowercase() }
            .toList()
        val normalized = normalizeForSearch(lastUserText)
            .split(" ").filter { it.isNotEmpty() }.distinct().take(32)
        val keywords = (alphaNumeric + normalized).distinct()
        if (keywords.isNotEmpty()) {
            queries.add(keywords.joinToString(" "))
        }

        return queries
    }

    /**
     * 根据单个查询检索归档
     *
     * @param settings 设置
     * @param conversationId 会话 ID
     * @param query 查询文本
     * @param embeddingModelId Embedding 模型 ID
     * @return 归档列表（含相似度分数）
     */
    private suspend fun retrieveByQuery(
        settings: Settings,
        conversationId: String,
        query: String,
        embeddingModelId: kotlin.uuid.Uuid?
    ): List<ArchiveSummaryWithScore> {
        // 1. 获取该会话的所有归档摘要
        val allArchives = archiveSummaryDao.getListByConversationId(conversationId)
        if (allArchives.isEmpty()) return emptyList()

        // 如果没有配置 embedding 模型，返回空列表
        if (embeddingModelId == null) return emptyList()

        // 2. 获取 embedding 模型和 provider
        // 首先从所有 provider 的 models 中查找
        val providerAndModel = settings.providers
            .mapNotNull { provider ->
                val model = provider.models.find { it.id == embeddingModelId }
                if (model != null) provider to model else null
            }
            .firstOrNull() ?: return emptyList()

        val (providerSetting, embeddingModel) = providerAndModel

        // 3. 获取对应的 Provider 实例并生成查询的 embedding
        val provider = providerManager.getProviderByType(providerSetting)

        // 4. 生成查询的 embedding
        val queryEmbedding = provider.generateEmbedding(
            providerSetting = providerSetting,
            text = query,
            params = EmbeddingGenerationParams(model = embeddingModel)
        )

        // 5. 计算相似度并过滤（同 embedding_model_id）
        val archiveWithSimilarity = allArchives.mapNotNull { archive ->
            val vectorIndex = vectorIndexDao.getByArchiveId(archive.id)
            if (vectorIndex != null && vectorIndex.embeddingModelId == embeddingModelId.toString()) {
                val similarity = cosineSimilarity(queryEmbedding, vectorIndex.embeddingVector)
                ArchiveSummaryWithScore(entity = archive, maxCosSim = similarity)
            } else {
                null
            }
        }

        // 6. 过滤低相似度结果（< 0.3）
        return archiveWithSimilarity.filter { it.maxCosSim >= 0.3f }
    }

    /**
     * 融合重排（写死）
     *
     * finalScore = 0.7 * maxCosSim + 0.3 * recencyScore
     *
     * @param results 归档列表
     * @return 重排后的归档列表
     */
    private fun fuseAndRerank(results: List<ArchiveSummaryWithScore>): List<ArchiveSummaryWithScore> {
        val minTime = results.minOfOrNull { it.createdAt } ?: 0L
        val maxTime = results.maxOfOrNull { it.createdAt } ?: 0L
        val timeRange = (maxTime - minTime).toFloat()

        return results.map { archive ->
            val recencyScore = if (timeRange > 0) {
                (archive.createdAt - minTime).toFloat() / timeRange
            } else 0.5f
            val finalScore = 0.7f * archive.maxCosSim + 0.3f * recencyScore
            archive.copy(finalScore = finalScore)
        }.sortedByDescending { it.finalScore }
    }

    /**
     * 反复回填抑制（写死）
     *
     * overlap > 0.6 时跳过注入
     *
     * @param results 当前检索结果
     * @param lastRecallIds 上一次回填的 ID 列表
     * @return 抑制后的结果（可能为空）
     */
    private fun overlapSuppression(
        results: List<ArchiveSummaryWithScore>,
        lastRecallIds: List<String>
    ): List<ArchiveSummaryWithScore> {
        val currentIds = results.map { it.id }.toSet()
        val previousIds = lastRecallIds.toSet()

        val intersection = currentIds.intersect(previousIds)
        val union = currentIds.union(previousIds)
        val overlap = if (union.isNotEmpty()) {
            intersection.size.toFloat() / union.size
        } else 0f

        return if (overlap > OVERLAP_THRESHOLD) {
            emptyList()  // 抑制：本轮跳过注入
        } else {
            results
        }
    }

    /**
     * 硬预算调度（写死）
     *
     * MAX_ITEMS=5, MAX_CHARS=1500
     * 不得截断单条 entry
     *
     * @param results 归档列表
     * @return 预算调度后的归档列表
     */
    private fun budgetScheduler(results: List<ArchiveSummaryWithScore>): List<ArchiveSummaryWithScore> {
        val selected = mutableListOf<ArchiveSummaryWithScore>()
        var totalChars = 0

        for (result in results) {
            if (selected.size >= MAX_ARCHIVE_RECALL_ITEMS) break
            val entryLen = result.content.length
            if (totalChars + entryLen > MAX_ARCHIVE_RECALL_CHARS) break
            selected.add(result)
            totalChars += entryLen
        }
        return selected
    }

    /**
     * 构建 [ARCHIVE_RECALL] 注入块
     *
     * 格式（写死）：
     * [ARCHIVE_RECALL]
     * |archive_id=<ID_1>
     * |content=<archive_content_1>
     * |archive_id=<ID_2>
     * |content=<archive_content_2>
     * ...
     * [/ARCHIVE_RECALL]
     *
     * @param results 选中的归档列表
     * @return 格式化的注入块
     */
    private fun buildArchiveInjection(results: List<ArchiveSummaryWithScore>): String {
        val content = results.joinToString("\n\n") { archive ->
            "|archive_id=${archive.id}\n|content=${archive.content}"
        }

        return "[ARCHIVE_RECALL]\n$content\n[/ARCHIVE_RECALL]"
    }

    /**
     * 计算余弦相似度
     *
     * @param vec1 向量1
     * @param vec2 向量2
     * @return 相似度分数 [0, 1]
     */
    private fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        require(vec1.size == vec2.size) { "向量维度不一致" }

        var dotProduct = 0f
        var norm1 = 0f
        var norm2 = 0f

        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
            norm1 += vec1[i] * vec1[i]
            norm2 += vec2[i] * vec2[i]
        }

        return if (norm1 == 0f || norm2 == 0f) 0f else dotProduct / (sqrt(norm1) * sqrt(norm2))
    }
}
