package me.rerere.rikkahub.service.knowledge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.db.dao.KnowledgeChunkDao
import me.rerere.rikkahub.data.db.dao.KnowledgeDocumentDao
import me.rerere.rikkahub.data.db.dao.KnowledgeVectorDao
import me.rerere.rikkahub.data.db.entity.KnowledgeChunkEntity
import me.rerere.rikkahub.data.db.entity.KnowledgeDocumentEntity
import me.rerere.ai.core.tool.Tool
import me.rerere.ai.core.tool.ToolResult
import me.rerere.ai.core.tool.parameter
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * 知识库搜索 Tool
 *
 * 功能：使用关键词查询当前 assistant 的知识库
 * - 输入：query（关键词）、topK（返回数量，默认 5，最大 10）
 * - 输出：搜索结果列表（包含文件名、块索引、文本、相似度）
 * - 限制：总 token ≤ 3000，向量扫描上限 5000
 * - 隔离：按 assistantId 隔离，只查询当前 assistant 的文档
 */
class KnowledgeBaseSearchTool(
    private val providerManager: ProviderManager,
    private val documentDao: KnowledgeDocumentDao,
    private val chunkDao: KnowledgeChunkDao,
    private val vectorDao: KnowledgeVectorDao
) {
    companion object {
        private const val TAG = "KnowledgeBaseSearch"
        private const val MAX_TOKENS = 3000  // 总 token 上限
        private const val MAX_VECTOR_SCAN = 5000  // 向量扫描上限
        private const val TOP_K_MAX = 10  // topK 最大值
        private const val TOP_K_DEFAULT = 5  // topK 默认值
    }

    /**
     * 执行搜索
     */
    suspend fun search(
        assistantId: String,
        query: String,
        topK: Int = TOP_K_DEFAULT
    ): SearchResult = withContext(Dispatchers.IO) {
        val actualTopK = topK.coerceIn(1, TOP_K_MAX)

        // 1. 检查是否有 READY 状态的文档
        val readyCount = documentDao.countReadyByAssistantId(assistantId)
        if (readyCount == 0) {
            return SearchResult.Success(
                query = query,
                hits = emptyList(),
                estimatedTokens = 0
            )
        }

        // 2. 获取 embeddingModelId（从第一个 READY 文档）
        val firstReadyDoc = documentDao.getByAssistantIdSync(assistantId)
            .firstOrNull { it.status == "READY" }

        if (firstReadyDoc == null) {
            return SearchResult.Error("No indexed documents found")
        }

        val embeddingModelId = firstReadyDoc.embeddingModelId
        if (embeddingModelId == null) {
            return SearchResult.Error("Embedding model not configured")
        }

        // 3. 获取 provider 和 embedding model
        // 注意：这里需要从 settings 获取，暂时简化处理
        // TODO: 从 settings 获取 provider 和 model

        // 4. 生成查询 embedding
        // TODO: 调用 embedding API
        val queryEmbedding = generateQueryEmbedding(query, embeddingModelId)

        // 5. 拉取向量数据（限制扫描数量）
        val vectors = vectorDao.getByAssistantIdAndModel(
            assistantId = assistantId,
            embeddingModelId = embeddingModelId,
            limit = MAX_VECTOR_SCAN
        )

        if (vectors.isEmpty()) {
            return SearchResult.Success(
                query = query,
                hits = emptyList(),
                estimatedTokens = 0
            )
        }

        // 6. 计算余弦相似度并排序
        val scoredChunks = vectors.mapNotNull { vector ->
            val similarity = cosineSimilarity(queryEmbedding, vector.embeddingVector)
            if (similarity >= 0.3f) {  // 相似度阈值
                val chunk = chunkDao.getById(vector.chunkId)
                if (chunk != null) {
                    val document = documentDao.getById(chunk.documentId)
                    Pair(similarity, chunk to document)
                } else null
            } else null
        }.sortedByDescending { it.first }

        // 7. 选择 topK 并限制 token 总数
        val selectedHits = mutableListOf<SearchHit>()
        var currentTokens = 0

        for ((similarity, pair) in scoredChunks.take(actualTopK)) {
            val (chunk, document) = pair
            val estimatedTokens = ceil(chunk.text.length / 3.5).toInt()

            if (currentTokens + estimatedTokens > MAX_TOKENS) {
                // 截断当前 hit
                val remainingTokens = MAX_TOKENS - currentTokens
                if (remainingTokens >= 120) {  // 最小片段长度
                    val truncatedText = chunk.text.substring(0, (remainingTokens * 3).toInt())
                    selectedHits.add(
                        SearchHit(
                            score = similarity,
                            fileName = document.fileName,
                            chunkIndex = chunk.chunkIndex,
                            text = truncatedText
                        )
                    )
                }
                break
            }

            selectedHits.add(
                SearchHit(
                    score = similarity,
                    fileName = document.fileName,
                    chunkIndex = chunk.chunkIndex,
                    text = chunk.text
                )
            )
            currentTokens += estimatedTokens
        }

        return SearchResult.Success(
            query = query,
            hits = selectedHits,
            estimatedTokens = currentTokens
        )
    }

    /**
     * 生成查询 embedding（TODO：需要实际调用）
     */
    private suspend fun generateQueryEmbedding(
        query: String,
        embeddingModelId: String
    ): FloatArray {
        // TODO: 实际调用 embedding API
        // 这里需要获取 provider 和 model，然后调用 generateEmbedding
        throw NotImplementedError("Embedding generation not implemented")
    }

    /**
     * 计算余弦相似度
     */
    private fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        if (vec1.size != vec2.size) return 0f

        var dotProduct = 0f
        var norm1 = 0f
        var norm2 = 0f

        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
            norm1 += vec1[i] * vec1[i]
            norm2 += vec2[i] * vec2[i]
        }

        return if (norm1 == 0f || norm2 == 0f) 0f
        else dotProduct / (sqrt(norm1) * sqrt(norm2))
    }

    /**
     * 搜索结果
     */
    sealed class SearchResult {
        data class Success(
            val query: String,
            val hits: List<SearchHit>,
            val estimatedTokens: Int
        ) : SearchResult()

        data class Error(val message: String) : SearchResult()
    }

    /**
     * 搜索命中
     */
    data class SearchHit(
        val score: Float,  // 相似度 [0, 1]
        val fileName: String,
        val chunkIndex: Int,
        val text: String
    )

    /**
     * Tool 定义（供 AI 调用）
     */
    fun getTool(assistantId: String): Tool {
        return Tool(
            name = "knowledge_base_search",
            description = "Search in the current assistant's knowledge base using keywords. Returns relevant document chunks with similarity scores.",
            parameters = listOf(
                parameter<String>("query") {
                    description = "Search query or keywords"
                },
                parameter<Int>("topK") {
                    description = "Number of results to return (default: 5, max: 10)"
                    default = TOP_K_DEFAULT
                }
            )
        ) { args ->
            val query = args["query"] as? String ?: return@Tool ToolResult.error("Query is required")
            val topK = (args["topK"] as? Double ?: TOP_K_DEFAULT.toDouble()).toInt()

            try {
                val result = search(assistantId, query, topK)
                when (result) {
                    is SearchResult.Success -> {
                        val json = buildString {
                            append("{\n")
                            append("  \"query\": \"${result.query}\",\n")
                            append("  \"hits\": [\n")
                            result.hits.forEachIndexed { index, hit ->
                                append("    {\n")
                                append("      \"score\": ${"%.2f".format(hit.score)},\n")
                                append("      \"fileName\": \"${hit.fileName}\",\n")
                                append("      \"chunkIndex\": ${hit.chunkIndex},\n")
                                append("      \"text\": \"${hit.text.take(500)}${if (hit.text.length > 500) "..." else ""}\"\n")
                                append("    }${if (index < result.hits.size - 1) "," else ""}\n")
                            }
                            append("  ],\n")
                            append("  \"estimatedTokens\": ${result.estimatedTokens}\n")
                            append("}")
                        }
                        ToolResult.success(json)
                    }
                    is SearchResult.Error -> {
                        ToolResult.error(result.message)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Search failed", e)
                ToolResult.error("Search failed: ${e.message}")
            }
        }
    }
}
