package me.rerere.rikkahub.data.memory

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.MemoryChunkMetadata
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRetrievalTest {

    @Test
    fun `buildMemoryIndexChunks separates current history and live tail lanes`() {
        val rollingSummaryJson = JsonInstant.encodeToString(
            buildJsonObject {
                put(
                    "meta",
                    buildJsonObject {
                        put("schema_version", JsonPrimitive(2))
                        put("summary_turn", JsonPrimitive(12))
                        put("updated_at", JsonPrimitive(1000))
                    }
                )
                put(
                    "facts",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive("fact_current"))
                                put("text", JsonPrimitive("当前数据库端口是 6432"))
                                put("status", JsonPrimitive("active"))
                                put("entity_keys", buildJsonArray { add(JsonPrimitive("6432")) })
                                put("source_roles", buildJsonArray { add(JsonPrimitive("user")) })
                            }
                        )
                    }
                )
                put(
                    "timeline",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive("timeline_old"))
                                put("text", JsonPrimitive("之前数据库端口曾经是 5432，后来切换到 6432"))
                                put("status", JsonPrimitive("historical"))
                                put(
                                    "entity_keys",
                                    buildJsonArray {
                                        add(JsonPrimitive("5432"))
                                        add(JsonPrimitive("6432"))
                                    }
                                )
                                put("source_roles", buildJsonArray { add(JsonPrimitive("assistant")) })
                            }
                        )
                    }
                )
            }
        )
        val liveTailJson = JsonInstant.encodeToString(
            buildJsonObject {
                put(
                    "timeline",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive("live_tail_1"))
                                put("text", JsonPrimitive("[USER] 刚刚又确认了一次端口还是 6432"))
                                put("status", JsonPrimitive("active"))
                                put("source_roles", buildJsonArray { add(JsonPrimitive("user")) })
                            }
                        )
                    }
                )
            }
        )

        val chunks = buildMemoryIndexChunks(
            rollingSummaryJson = rollingSummaryJson,
            charsPerToken = 4.0f,
            liveTailDigestJson = liveTailJson
        )

        assertTrue(chunks.any { it.metadata.lane == "current" && it.sectionKey == "facts" })
        assertTrue(chunks.any { it.metadata.lane == "history" && it.sectionKey == "timeline" })
        assertTrue(chunks.any { it.metadata.lane == "live_tail" && it.sectionKey == "live_tail" })
    }

    @Test
    fun `rankMemoryChunks prefers current active user chunk for current channel`() {
        val chunks = listOf(
            MemorySummaryChunk(
                sectionKey = "facts",
                chunkOrder = 0,
                content = "[facts/current]\n- 当前数据库端口是 6432",
                tokenEstimate = 12,
                metadata = MemoryChunkMetadata(
                    lane = "current",
                    status = "active",
                    sectionKey = "facts",
                    entityKeys = listOf("6432"),
                    sourceRoles = listOf("user"),
                )
            ),
            MemorySummaryChunk(
                sectionKey = "timeline",
                chunkOrder = 0,
                content = "[timeline/history]\n- 之前数据库端口曾经是 5432，后来改成 6432",
                tokenEstimate = 18,
                metadata = MemoryChunkMetadata(
                    lane = "history",
                    status = "historical",
                    sectionKey = "timeline",
                    entityKeys = listOf("5432", "6432"),
                    sourceRoles = listOf("assistant"),
                )
            ),
        )

        val ranked = rankMemoryChunks(
            query = "现在数据库端口是多少",
            chunks = chunks,
            documentEmbeddings = listOf(
                listOf(1.0f, 0.0f),
                listOf(0.6f, 0.4f)
            ),
            queryEmbedding = listOf(0.95f, 0.05f),
            channel = "current",
            role = "user",
            bm25TopK = 50,
            vectorTopK = 30
        )

        assertFalse(ranked.isEmpty())
        assertEquals(0, ranked.first().docIndex)
        assertTrue(ranked.none { it.docIndex == 1 && it.finalScore > ranked.first().finalScore })
    }

    @Test
    fun `rankMemoryChunks can surface history chunk for history channel`() {
        val chunks = listOf(
            MemorySummaryChunk(
                sectionKey = "facts",
                chunkOrder = 0,
                content = "[facts/current]\n- 当前数据库端口是 6432",
                tokenEstimate = 12,
                metadata = MemoryChunkMetadata(
                    lane = "current",
                    status = "active",
                    sectionKey = "facts",
                    entityKeys = listOf("6432"),
                    sourceRoles = listOf("user"),
                )
            ),
            MemorySummaryChunk(
                sectionKey = "timeline",
                chunkOrder = 0,
                content = "[timeline/history]\n- 之前数据库端口曾经是 5432，后来改成 6432",
                tokenEstimate = 18,
                metadata = MemoryChunkMetadata(
                    lane = "history",
                    status = "historical",
                    sectionKey = "timeline",
                    entityKeys = listOf("5432", "6432"),
                    sourceRoles = listOf("assistant"),
                )
            ),
        )

        val ranked = rankMemoryChunks(
            query = "之前数据库端口是多少",
            chunks = chunks,
            documentEmbeddings = listOf(
                listOf(0.7f, 0.3f),
                listOf(1.0f, 0.0f)
            ),
            queryEmbedding = listOf(0.98f, 0.02f),
            channel = "history",
            role = "assistant",
            bm25TopK = 50,
            vectorTopK = 30
        )

        assertFalse(ranked.isEmpty())
        assertEquals(1, ranked.first().docIndex)
    }

    @Test
    fun `buildSourcePreviewChunks keeps poem block intact`() {
        val chunks = buildSourcePreviewChunks(
            listOf(
                IndexedSourceMessage(
                    messageId = "m1",
                    role = "user",
                    text = """
                        大风车转呀转
                        风吹过旧河岸
                        我把黄昏装进口袋
                        等夜色慢慢变蓝
                    """.trimIndent()
                )
            )
        )

        assertEquals(1, chunks.size)
        assertEquals("poem", chunks.first().blockType)
        assertTrue(chunks.first().searchText.contains("大风车转呀转"))
    }

    @Test
    fun `rankSourcePreviewChunks prefers scoped conversation matches`() {
        val ranked = rankSourcePreviewChunks(
            query = "大风车诗歌",
            chunks = listOf(
                me.rerere.rikkahub.data.model.SourcePreviewChunk(
                    assistantId = kotlin.uuid.Uuid.random(),
                    conversationId = kotlin.uuid.Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                    messageId = kotlin.uuid.Uuid.random(),
                    role = "user",
                    chunkOrder = 0,
                    prefixText = "我以前写过一首大风车诗歌",
                    searchText = "我以前写过一首大风车诗歌，开头是大风车转呀转。",
                    blockType = "text",
                    updatedAt = java.time.Instant.now()
                ),
                me.rerere.rikkahub.data.model.SourcePreviewChunk(
                    assistantId = kotlin.uuid.Uuid.random(),
                    conversationId = kotlin.uuid.Uuid.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                    messageId = kotlin.uuid.Uuid.random(),
                    role = "user",
                    chunkOrder = 0,
                    prefixText = "另一个对话里提过风车",
                    searchText = "只是普通提到风车，不是那首诗。",
                    blockType = "text",
                    updatedAt = java.time.Instant.now()
                )
            ),
            role = "user",
            candidateConversationIds = setOf("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            usedFallbackScope = false
        )

        assertFalse(ranked.isEmpty())
        assertEquals(0, ranked.first().chunkIndex)
        assertTrue(ranked.first().matchedSnippet.contains("大风车"))
    }
}
