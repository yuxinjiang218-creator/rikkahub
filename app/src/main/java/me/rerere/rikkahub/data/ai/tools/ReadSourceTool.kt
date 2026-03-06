package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.ReadSourceResult

fun buildReadSourceTool(
    json: Json,
    onRead: suspend (sourceRef: String) -> ReadSourceResult,
): Tool {
    return Tool(
        name = "read_source",
        description = """
            读取 search_source 选中的那条完整原始消息全文。
            仅接受 search_source 返回的 source_ref，一次只读取一条完整用户或助手消息。
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put(
                        "source_ref",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "search_source 返回的 source_ref")
                        }
                    )
                },
                required = listOf("source_ref")
            )
        },
        execute = {
            val sourceRef = it.jsonObject["source_ref"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val result = onRead(sourceRef)
            val payload = buildJsonObject {
                put("source_ref", result.sourceRef)
                put("conversation_id", result.conversationId.toString())
                put("message_id", result.messageId.toString())
                put("role", result.role)
                put("created_at", result.createdAt.toString())
                put("content", result.content)
            }
            listOf(UIMessagePart.Text(json.encodeToString(payload)))
        }
    )
}
