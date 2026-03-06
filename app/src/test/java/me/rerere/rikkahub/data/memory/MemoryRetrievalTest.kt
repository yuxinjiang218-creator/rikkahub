package me.rerere.rikkahub.data.memory

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRetrievalTest {

    @Test
    fun `buildMemoryIndexChunks keeps cjk lines readable and section scoped`() {
        val rollingSummaryJson = JsonInstant.encodeToString(
            buildJsonObject {
                put(
                    "facts",
                    buildJsonArray {
                        add(
                            JsonPrimitive(
                                "项目代号星河，负责人林秋，交付窗口是四月中旬。".repeat(18)
                            )
                        )
                        add(
                            JsonPrimitive(
                                "生产环境数据库只允许只读账号接入，严禁写操作，并且必须记录审计日志。".repeat(16)
                            )
                        )
                    }
                )
                put(
                    "timeline",
                    buildJsonArray {
                        add(
                            JsonPrimitive(
                                "2026年3月完成压缩链路改造，随后补做记忆索引与 recall_memory 工具联调。".repeat(12)
                            )
                        )
                    }
                )
            }
        )

        val chunks = buildMemoryIndexChunks(
            rollingSummaryJson = rollingSummaryJson,
            charsPerToken = 4.0f,
            minChunkTokens = 80,
            maxChunkTokens = 120,
            overlapRatio = 0.1
        )

        assertTrue(chunks.size > 1)
        assertTrue(chunks.first().content.startsWith("[facts]"))
        assertTrue(chunks.any { it.content.contains("项目代号星河") })
        assertTrue(chunks.any { it.content.contains("生产环境数据库只允许只读账号接入") })
        assertFalse(chunks.any { it.content.contains("项 目 代 号") })
    }

    @Test
    fun `tokenizeForRetrieval includes cjk grams and technical terms`() {
        val tokens = tokenizeForRetrieval("请回忆星河项目的 prod.yaml 配置和 API key")

        assertTrue(tokens.contains("星河项目"))
        assertTrue(tokens.contains("星河"))
        assertTrue(tokens.contains("河项"))
        assertTrue(tokens.contains("prod.yaml"))
        assertTrue(tokens.contains("prod"))
        assertTrue(tokens.contains("api"))
        assertTrue(tokens.contains("key"))
    }

    @Test
    fun `rankMemoryChunks prefers the most relevant chinese chunk`() {
        val ranked = rankMemoryChunks(
            query = "回忆一下星河项目负责人是谁",
            documents = listOf(
                "[facts]\n- 星河项目负责人是林秋，发布时间是四月中旬。",
                "[facts]\n- 苍穹项目的测试机房在深圳，和当前问题无关。"
            ),
            documentEmbeddings = listOf(
                listOf(1.0f, 0.0f),
                listOf(0.0f, 1.0f)
            ),
            queryEmbedding = listOf(0.98f, 0.02f),
            bm25TopK = 50,
            vectorRerankK = 30
        )

        assertEquals(1, ranked.size)
        assertEquals(0, ranked.first().docIndex)
        assertTrue(ranked.first().finalScore > 0.8)
    }

    @Test
    fun `rankMemoryChunks returns empty when lexical and vector relevance stay low`() {
        val ranked = rankMemoryChunks(
            query = "今天天气怎么样",
            documents = listOf(
                "[facts]\n- 星河项目负责人是林秋。",
                "[facts]\n- 生产环境只允许只读数据库账号。"
            ),
            documentEmbeddings = listOf(
                listOf(0.0f, 1.0f),
                listOf(0.1f, 0.9f)
            ),
            queryEmbedding = listOf(1.0f, 0.0f),
            bm25TopK = 50,
            vectorRerankK = 30
        )

        assertTrue(ranked.isEmpty())
    }
}
