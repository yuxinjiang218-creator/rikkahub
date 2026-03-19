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
import me.rerere.rikkahub.data.model.KnowledgeBaseSearchResult

fun buildSearchKnowledgeBaseTool(
    json: Json,
    onSearch: suspend (query: String, documentIds: List<Long>) -> KnowledgeBaseSearchResult,
): Tool {
    return Tool(
        name = "search_knowledge_base",
        description = """
            Search the current assistant's private knowledge base built from uploaded documents.
            Use this tool for document-backed knowledge such as manuals, notes, reports, or reference files.
            The result only includes snippets from this assistant's knowledge base and does not search memory or chat history.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put(
                        "query",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "The document knowledge to search for")
                        }
                    )
                    put(
                        "document_ids",
                        buildJsonObject {
                            put("type", "array")
                            put("description", "Optional document ids to narrow the search scope before retrieval")
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", "integer")
                                }
                            )
                        }
                    )
                },
                required = listOf("query")
            )
        },
        execute = {
            val args = it.jsonObject
            val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val documentIds = args["document_ids"]?.jsonArray
                ?.mapNotNull { item -> item.jsonPrimitive.contentOrNull?.toLongOrNull() }
                .orEmpty()
            val result = onSearch(query, documentIds)
            val payload = buildJsonObject {
                put("query", result.query)
                put("result_quality", result.quality.name.lowercase())
                put("returned_count", result.returnedCount)
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
                                    put("score", chunk.score)
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
