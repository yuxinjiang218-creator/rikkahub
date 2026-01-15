package me.rerere.rikkahub.service.knowledge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.KnowledgeChunkDao
import me.rerere.rikkahub.data.db.dao.KnowledgeDocumentDao
import me.rerere.rikkahub.data.db.dao.KnowledgeVectorDao
import me.rerere.rikkahub.data.db.entity.KnowledgeChunkEntity
import me.rerere.rikkahub.data.db.entity.KnowledgeDocumentEntity
import me.rerere.rikkahub.data.db.entity.KnowledgeVectorEntity
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
    private val documentDao: KnowledgeDocumentDao,
    private val chunkDao: KnowledgeChunkDao,
    private val vectorDao: KnowledgeVectorDao,
    private val providerManager: ProviderManager,
    private val settingsStore: SettingsStore
) {
    companion object {
        private const val TAG = "KnowledgeBaseSearch"
        private const val MAX_TOKENS = 3000  // 总 token 上限
        private const val MAX_VECTOR_SCAN = 5000  // 向量扫描上限
        private const val TOP_K_MAX = 10  // topK 最大值
        private const val TOP_K_DEFAULT = 5  // topK 默认值
        private const val MIN_SIMILARITY_THRESHOLD = 0.57f  // 最小相似度阈值
    }

    /**
     * Tool 定义（供 AI 调用）
     */
    fun getTool(assistantId: String): Tool {
        return Tool(
            name = "knowledge_base_search",
            description = "Search in the current assistant's knowledge base using keywords. Returns relevant document chunks with similarity scores.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("query", buildJsonObject {
                            put("type", "string")
                            put("description", "Search query or keywords")
                        })
                        put("topK", buildJsonObject {
                            put("type", "number")
                            put("description", "Number of results to return (default: 5, max: 10)")
                        })
                    },
                    required = listOf("query")
                )
            },
            execute = { input ->
                try {
                    val query = input.jsonObject["query"]?.jsonPrimitive?.contentOrNull
                        ?: return@Tool buildJsonObject {
                            put("error", "Query is required")
                        }

                    val topK = input.jsonObject["topK"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                        ?: TOP_K_DEFAULT

                    val result = search(assistantId, query, topK.coerceIn(1, TOP_K_MAX))
                    when (result) {
                        is SearchResult.Success -> {
                            // 构建结果文本（单行压缩格式，节省 token）
                            // 严格控制字数：MAX_TOKENS tokens ≈ MAX_TOKENS * 2 字符（中文）
                            val maxChars = MAX_TOKENS * 2
                            val resultText = buildString {
                                appendLine("KB_SEARCH_OK")
                                var usedChars = 14  // "KB_SEARCH_OK\n" 的长度
                                var addedHits = 0

                                result.hits.forEach { hit ->
                                    // 压缩为单行：移除换行，压缩空格
                                    val snippet = hit.text
                                        .trim()
                                        .replace("\n", " ")
                                        .replace(Regex("\\s{2,}"), " ")

                                    val block = buildString {
                                        appendLine("[score=${"%.4f".format(hit.score)}] ${hit.fileName}#chunk${hit.chunkIndex}")
                                        appendLine(snippet)
                                        appendLine()
                                    }

                                    // 检查是否超过字数限制
                                    if (usedChars + block.length > maxChars) {
                                        // 尝试截断最后一个结果
                                        val remaining = maxChars - usedChars
                                        if (remaining > 120) {  // 至少保留 120 字符
                                            append(block.substring(0, remaining).trimEnd())
                                            append("...[truncated]")
                                        }
                                        Log.d(TAG, "KB search truncated at hit $addedHits/${result.hits.size}, usedChars=$usedChars, maxChars=$maxChars")
                                        return@buildString  // 停止添加更多结果
                                    }

                                    append(block)
                                    usedChars += block.length
                                    addedHits++
                                }

                                Log.d(TAG, "KB search complete: added $addedHits/${result.hits.size} hits, usedChars=$usedChars")
                            }

                            buildJsonObject {
                                put("query", result.query)
                                put("result", resultText)
                                put("estimatedTokens", result.estimatedTokens)
                            }
                        }
                        is SearchResult.Error -> {
                            buildJsonObject {
                                put("error", result.message)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Search failed", e)
                    buildJsonObject {
                        put("error", "Search failed: ${e.message}")
                    }
                }
            }
        )
    }

    /**
     * 执行搜索
     */
    private suspend fun search(
        assistantId: String,
        query: String,
        topK: Int
    ): SearchResult = withContext(Dispatchers.IO) {
        // 1. 检查是否有 READY 状态的文档
        val readyCount = documentDao.countReadyByAssistantId(assistantId)
        if (readyCount == 0) {
            return@withContext SearchResult.Success(
                query = query,
                hits = emptyList(),
                estimatedTokens = 0
            )
        }

        // 2. 获取当前配置的 embedding 模型
        val settings = settingsStore.settingsFlow.first()
        val currentEmbeddingModelId = settings.embeddingModelId
        if (currentEmbeddingModelId == null) {
            return@withContext SearchResult.Error("Embedding model not configured")
        }

        // 3. 检查文档的 embedding 模型是否匹配当前配置
        val firstReadyDoc = documentDao.getByAssistantIdSync(assistantId)
            .firstOrNull { it.status == "READY" }

        if (firstReadyDoc == null) {
            return@withContext SearchResult.Error("No indexed documents found")
        }

        val documentModelId = firstReadyDoc.embeddingModelId
        if (documentModelId != currentEmbeddingModelId.toString()) {
            return@withContext SearchResult.Error(
                "Embedding 模型已更换，请重新索引文档"
            )
        }

        // 4. 获取 provider 和 embedding model
        val providerSetting: ProviderSetting? = settings.providers.find { provider ->
            provider.models.any { it.id == currentEmbeddingModelId }
        }
        if (providerSetting == null) {
            return@withContext SearchResult.Error("Provider not found for embedding model")
        }

        val embeddingModel: Model? = providerSetting.models.find {
            it.id == currentEmbeddingModelId && it.type == ModelType.EMBEDDING
        }
        if (embeddingModel == null) {
            return@withContext SearchResult.Error("Embedding model not found")
        }

        // 5. 生成查询向量
        val queryEmbedding = try {
            val provider = providerManager.getProviderByType(providerSetting)
            provider.generateEmbedding(
                providerSetting = providerSetting,
                text = query,
                params = EmbeddingGenerationParams(model = embeddingModel)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate query embedding", e)
            return@withContext SearchResult.Error("Failed to generate query embedding: ${e.message}")
        }

        // 6. 获取所有向量并计算相似度
        val vectors = vectorDao.getByAssistantIdAndModel(
            assistantId = assistantId,
            embeddingModelId = currentEmbeddingModelId.toString()
        )
        if (vectors.isEmpty()) {
            return@withContext SearchResult.Success(
                query = query,
                hits = emptyList(),
                estimatedTokens = 0
            )
        }

        // 计算相似度
        val scored = mutableListOf<Triple<Float, KnowledgeDocumentEntity?, KnowledgeChunkEntity>>()
        for (vector in vectors) {
            val score = cosineSimilarity(queryEmbedding, vector.embeddingVector, vector.vectorNorm)
            if (score >= MIN_SIMILARITY_THRESHOLD) {  // 过滤低相似度结果
                // 获取对应的 chunk 信息
                val chunk = chunkDao.getById(vector.chunkId)
                if (chunk != null) {
                    val doc = documentDao.getById(chunk.documentId)
                    scored.add(Triple(score, doc, chunk))
                }
            }
        }

        // 7. 排序并返回 top-K
        val topResults = scored
            .sortedByDescending { it.first }
            .take(topK)

        val hits = topResults.map { (score, doc, chunk) ->
            SearchHit(
                score = score,
                fileName = doc?.fileName ?: "Unknown",
                chunkIndex = chunk.chunkIndex,
                text = chunk.text
            )
        }

        // 估算 token 数（粗略估计：中文约 2 字符 = 1 token）
        val estimatedTokens = hits.sumOf { it.text.length / 2 }

        SearchResult.Success(
            query = query,
            hits = hits,
            estimatedTokens = estimatedTokens
        )
    }

    /**
     * 计算余弦相似度
     */
    private fun cosineSimilarity(
        vec1: FloatArray,
        vec2: FloatArray,
        vec2Norm: Float
    ): Float {
        if (vec1.size != vec2.size) return 0f

        // 计算 vec1 的范数
        var norm1 = 0f
        for (v in vec1) {
            norm1 += v * v
        }
        norm1 = sqrt(norm1)

        if (norm1 == 0f || vec2Norm == 0f) return 0f

        // 计算点积
        var dotProduct = 0f
        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
        }

        return dotProduct / (norm1 * vec2Norm)
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
}
