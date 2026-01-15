package me.rerere.rikkahub.service.knowledge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.db.dao.KnowledgeChunkDao
import me.rerere.rikkahub.data.db.dao.KnowledgeDocumentDao
import me.rerere.rikkahub.data.db.dao.KnowledgeVectorDao
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
                            buildJsonObject {
                                put("query", result.query)
                                put("estimatedTokens", result.estimatedTokens)
                                // TODO: Add hits array (需要数组支持)
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

        // 2. 获取 embeddingModelId（从第一个 READY 文档）
        val firstReadyDoc = documentDao.getByAssistantIdSync(assistantId)
            .firstOrNull { it.status == "READY" }

        if (firstReadyDoc == null) {
            return@withContext SearchResult.Error("No indexed documents found")
        }

        val embeddingModelId = firstReadyDoc.embeddingModelId
        if (embeddingModelId == null) {
            return@withContext SearchResult.Error("Embedding model not configured")
        }

        // TODO: 实现实际的 embedding 搜索
        // 目前返回空结果，等 embedding API 集成后完善
        SearchResult.Success(
            query = query,
            hits = emptyList(),
            estimatedTokens = 0
        )
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
