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
import me.rerere.rikkahub.data.model.SearchSourceResult

fun buildSearchSourceTool(
    json: Json,
    onSearch: suspend (query: String, role: String, candidateConversationIds: List<String>) -> SearchSourceResult,
): Tool {
    return Tool(
        name = "search_source",
        description = """
            在历史原始消息中搜索更接近原文的候选消息。
            这个工具先优先搜索 recall_memory 返回的候选对话范围，再在必要时受控回退到当前助手的其他历史对话。
            它只返回候选预览，不返回完整消息全文。拿到合适的 source_ref 后，再调用 read_source。
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put(
                        "query",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "要找的原文内容、诗歌、代码、原话等")
                        }
                    )
                    put(
                        "role",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "优先搜索用户消息、助手消息或两者都查")
                            put("enum", buildJsonArray {
                                add("any")
                                add("user")
                                add("assistant")
                            })
                        }
                    )
                    put(
                        "candidate_conversation_ids",
                        buildJsonObject {
                            put("type", "array")
                            put("description", "recall_memory 返回的候选对话 ID 列表，可为空数组")
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", "string")
                                }
                            )
                        }
                    )
                },
                required = listOf("query", "role", "candidate_conversation_ids")
            )
        },
        execute = {
            val args = it.jsonObject
            val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val role = args["role"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { "any" }
            val candidateConversationIds = args["candidate_conversation_ids"]?.jsonArray
                ?.mapNotNull { item -> item.jsonPrimitive.contentOrNull?.trim()?.takeIf { value -> value.isNotBlank() } }
                .orEmpty()
            val result = onSearch(query, role, candidateConversationIds)
            val payload = buildJsonObject {
                put("query", result.query)
                put("role", result.role)
                put("returned_count", result.returnedCount)
                put("used_fallback_scope", result.usedFallbackScope)
                put(
                    "candidates",
                    buildJsonArray {
                        result.candidates.forEach { candidate ->
                            add(
                                buildJsonObject {
                                    put("source_ref", candidate.sourceRef)
                                    put("conversation_id", candidate.conversationId.toString())
                                    put("message_id", candidate.messageId.toString())
                                    put("role", candidate.role)
                                    put("prefix", candidate.prefix)
                                    put("hit_snippet", candidate.hitSnippet)
                                    put("score", candidate.score)
                                    put("used_fallback_scope", candidate.usedFallbackScope)
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
