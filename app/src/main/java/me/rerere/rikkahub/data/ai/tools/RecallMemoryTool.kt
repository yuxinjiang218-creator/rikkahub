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
    onRecall: suspend (query: String, channel: String, role: String) -> RecallMemoryResult,
): Tool {
    return Tool(
        name = "recall_memory",
        description = """
            检索当前助手下已经建立索引的历史记忆块。
            channel=current 用于找当前仍然有效的背景、偏好、约束、任务状态和最近压缩上下文。
            channel=history 用于追溯旧版本、变更历史、决策原因和之前的状态。
            role 用于限定主要查用户消息、助手消息或两者都查。
            当没有足够相关的内容时，此工具会返回空结果。
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put(
                        "query",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "要检索的历史记忆内容")
                        }
                    )
                    put(
                        "channel",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "检索当前有效记忆还是历史演化记忆")
                            put("enum", buildJsonArray {
                                add("current")
                                add("history")
                            })
                        }
                    )
                    put(
                        "role",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "优先查用户、助手还是都查")
                            put("enum", buildJsonArray {
                                add("any")
                                add("user")
                                add("assistant")
                            })
                        }
                    )
                },
                required = listOf("query", "channel", "role")
            )
        },
        execute = {
            val args = it.jsonObject
            val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val channel = args["channel"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val role = args["role"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val result = onRecall(
                query,
                channel.ifBlank { "current" },
                role.ifBlank { "any" }
            )
            val payload = buildJsonObject {
                put("query", result.query)
                put("channel", result.channel)
                put("role", result.role)
                put("returned_count", result.returnedCount)
                put(
                    "candidate_conversation_ids",
                    buildJsonArray {
                        result.candidateConversationIds.forEach { conversationId ->
                            add(conversationId.toString())
                        }
                    }
                )
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
                                    put("lane", chunk.lane)
                                    put("status", chunk.status)
                                    put("content", chunk.content)
                                    put("time_ref", chunk.timeRef)
                                    put(
                                        "tags",
                                        buildJsonArray {
                                            chunk.tags.forEach(::add)
                                        }
                                    )
                                    put(
                                        "entity_keys",
                                        buildJsonArray {
                                            chunk.entityKeys.forEach(::add)
                                        }
                                    )
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
