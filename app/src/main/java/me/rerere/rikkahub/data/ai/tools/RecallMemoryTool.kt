package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.RecallMemoryResult

fun buildRecallMemoryTool(
    json: Json,
    onRecall: suspend (query: String) -> RecallMemoryResult,
): Tool {
    return Tool(
        name = "recall_memory",
        description = """
            Retrieve highly relevant memory chunks from manually indexed historical chats.
            Call this only when it is clearly necessary:
            - user explicitly asks to recall prior chats/memories, or
            - essential context is missing and retrieval is strongly justified.
            If relevance is weak, this tool returns empty chunks.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put(
                        "query",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "What memory to retrieve")
                        }
                    )
                },
                required = listOf("query")
            )
        },
        execute = {
            val query = it.jsonObject["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val result = onRecall(query)
            val payload = buildJsonObject {
                put("query", result.query)
                put("returned_count", result.returnedCount)
                put(
                    "chunks",
                    buildJsonArray {
                        result.chunks.forEach { chunk ->
                            add(
                                buildJsonObject {
                                    put("chunk_id", chunk.chunkId)
                                    put("assistant_id", chunk.assistantId.toString())
                                    put("conversation_id", chunk.conversationId.toString())
                                    put("conversation_title", chunk.conversationTitle)
                                    put("section_key", chunk.sectionKey)
                                    put("content", chunk.content)
                                    put("bm25_score", chunk.bm25Score)
                                    put("vector_score", chunk.vectorScore)
                                    put("final_score", chunk.finalScore)
                                    put("token_estimate", chunk.tokenEstimate)
                                    put("updated_at", chunk.updatedAt.toString())
                                }
                            )
                        }
                    }
                )
            }
            listOf(UIMessagePart.Text(json.encodeToString(payload)))
        }
    )
}
