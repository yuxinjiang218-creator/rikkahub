package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.KnowledgeBaseChunkReadResult

fun buildReadKnowledgeBaseChunksTool(
    json: Json,
    onRead: suspend (documentId: Long, chunkOrders: List<Int>) -> KnowledgeBaseChunkReadResult,
): Tool {
    return Tool(
        name = "read_knowledge_base_chunks",
        description = """
            Read specific chunk numbers from a chosen knowledge base document.
            Use this when a retrieved snippet is incomplete and you need adjacent chunks for continuity.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put(
                        "document_id",
                        buildJsonObject {
                            put("type", "integer")
                            put("description", "The target document id")
                        }
                    )
                    put(
                        "chunk_orders",
                        buildJsonObject {
                            put("type", "array")
                            put("description", "The chunk numbers to read, such as [12, 13]")
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", "integer")
                                }
                            )
                        }
                    )
                },
                required = listOf("document_id", "chunk_orders")
            )
        },
        execute = {
            val args = it.jsonObject
            val documentId = args["document_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            val chunkOrders = args["chunk_orders"]?.jsonArray
                ?.mapNotNull { item -> item.jsonPrimitive.contentOrNull?.toIntOrNull() }
                .orEmpty()
            val result = onRead(documentId, chunkOrders)
            val payload = buildJsonObject {
                put("document_id", result.documentId)
                put("document_name", result.documentName)
                put("mime_type", result.mimeType)
                put("returned_count", result.returnedCount)
                put(
                    "missing_chunk_orders",
                    buildJsonArray {
                        result.missingChunkOrders.forEach { add(it) }
                    }
                )
                put(
                    "chunks",
                    buildJsonArray {
                        result.chunks.forEach { chunk ->
                            add(
                                buildJsonObject {
                                    put("chunk_id", chunk.chunkId)
                                    put("document_id", chunk.documentId)
                                    put("assistant_id", chunk.assistantId.toString())
                                    put("document_name", chunk.documentName)
                                    put("mime_type", chunk.mimeType)
                                    put("chunk_order", chunk.chunkOrder)
                                    put("content", chunk.content)
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
